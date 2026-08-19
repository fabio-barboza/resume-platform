"""Modelos declarativos das três tabelas, um arquivo por tabela.

    base.py        -> Base, a classe declarativa
    candidate.py   -> tabela candidates
    document.py    -> tabela documents
    chunk.py       -> tabela chunks

ATENÇÃO, e aqui é diferente de `api/schemas`, que não re-exporta nada:

Uma tabela só entra em `Base.metadata` no momento em que a classe do modelo é
importada — é o import que registra a tabela no catálogo. Como o Alembic lê
`Base.metadata` para comparar com o banco, se alguém importasse só `Base` sem
importar os modelos, o catálogo chegaria vazio e o
`alembic revision --autogenerate` geraria um DROP de todas as tabelas.

Por isso os imports abaixo existem: eles garantem que importar qualquer coisa
deste pacote já registra as três tabelas. Não é conveniência, é o que mantém
as migrações corretas — não remova.
"""

from resume_agent.db.models.base import Base
from resume_agent.db.models.candidate import Candidate
from resume_agent.db.models.chunk import Chunk
from resume_agent.db.models.document import Document

__all__ = ["Base", "Candidate", "Chunk", "Document"]
