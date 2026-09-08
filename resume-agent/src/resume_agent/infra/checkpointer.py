"""Checkpointer do LangGraph em Postgres: histórico de conversa fora do processo.

Estado de conversa em memória do processo só funciona com uma réplica. Com N
pods atrás de um balanceador, o turno seguinte cai em outro processo, que não
conhece a sessão — o agente responde sem contexto, com a mesma confiança de
sempre. O checkpointer move o estado para o Postgres, indexado pelo
`thread_id` (o `session_id` gerado pelo cliente), e qualquer réplica retoma a
conversa de onde parou.

O `PostgresSaver` não fala SQLAlchemy: o pool aqui é do psycopg3 e é
independente do `QueuePool` da `db/engine.py`. São dois pools contra o mesmo
banco — `DB_POOL_MAX_SIZE + CHECKPOINTER_POOL_MAX_SIZE`, multiplicado pelo
número de réplicas, precisa caber no `max_connections` do Postgres.

As tabelas (`checkpoints`, `checkpoint_blobs`, `checkpoint_writes` e o controle
`checkpoint_migrations`) são criadas pela migração `0003`. Nada de DDL em
runtime: `setup()` não é chamado daqui.
"""

import os

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from resume_agent.db.engine import psycopg_url


class _LazyConnectionPool(ConnectionPool):
    """Pool que só abre no primeiro uso.

    O `PostgresSaver` é construído quando `agent.py` é importado, o que na
    suíte de testes acontece antes de o banco descartável existir. Abrir ali
    faria o pool entrar em backoff de reconexão contra um banco que ainda não
    foi criado. `get_connection` da lib faz `isinstance(conn, ConnectionPool)`,
    então precisa ser subclasse — proxy não passaria.
    """

    def connection(self, timeout: float | None = None):
        if self.closed:
            self.open()
        return super().connection(timeout)


_pool: ConnectionPool | None = None
_checkpointer = None


def _get_pool() -> ConnectionPool:
    global _pool
    if _pool is None:
        _pool = _LazyConnectionPool(
            psycopg_url(),
            min_size=int(os.getenv("CHECKPOINTER_POOL_MIN_SIZE", "1")),
            max_size=int(os.getenv("CHECKPOINTER_POOL_MAX_SIZE", "5")),
            kwargs={
                # `autocommit` porque o PostgresSaver gerencia a própria
                # transação; `dict_row` porque ele lê as colunas por nome;
                # `prepare_threshold=0` porque com pgbouncer em transaction
                # mode um prepared statement não sobrevive ao próximo turno.
                "autocommit": True,
                "prepare_threshold": 0,
                "row_factory": dict_row,
            },
            open=False,
        )
    return _pool


def get_checkpointer():
    """`PostgresSaver` do processo, criado no primeiro uso e reaproveitado."""
    global _checkpointer
    if _checkpointer is None:
        from langgraph.checkpoint.postgres import PostgresSaver

        _checkpointer = PostgresSaver(_get_pool())
    return _checkpointer


def close_checkpointer() -> None:
    """Fecha o pool. Chamado no shutdown da API e pelos testes."""
    global _pool, _checkpointer
    if _pool is not None:
        _pool.close()
    _pool = None
    _checkpointer = None
