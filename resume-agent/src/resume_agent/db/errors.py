"""Erros da camada de dados, sem vazar exceção de driver nem de ORM.

A camada de serviço captura estes tipos; ela não importa SQLAlchemy nem
psycopg.
"""


class DuplicateKeyError(Exception):
    """Violação de unique constraint (SQLSTATE 23505)."""
