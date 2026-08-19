"""A classe base declarativa, sozinha no seu arquivo.

Fica separada dos modelos de propósito: `candidate.py`, `document.py` e
`chunk.py` importam daqui, e se `Base` morasse junto de um deles esse arquivo
viraria o "primeiro entre iguais" sem motivo.

`Base.metadata` é o catálogo de tabelas que o Alembic compara com o banco em
`alembic revision --autogenerate`. Uma tabela só entra nele quando a classe do
modelo é *importada* — veja o comentário em `__init__.py`.
"""

from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass
