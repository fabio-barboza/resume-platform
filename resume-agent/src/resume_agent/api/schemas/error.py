"""Schema do corpo de erro da API, usado por todos os routers.

Não confundir com `api/errors.py`, o vizinho: lá ficam os *handlers*, que
capturam o erro de domínio e escolhem o status HTTP. Aqui fica só o formato
do JSON que eles devolvem.
"""

from pydantic import BaseModel


# Comentário, não docstring: o Pydantic publica a docstring como `description`
# do schema, e isso apareceria no Swagger.
class ErrorResponse(BaseModel):
    detail: str
