"""Schema do corpo de erro da API, usado por todos os routers.

Não confundir com `api/errors.py`, o vizinho: lá ficam os *handlers*, que
capturam o erro de domínio e escolhem o status HTTP. Aqui fica só o formato
do JSON que eles devolvem.
"""

from pydantic import BaseModel


# Corpo de qualquer resposta de erro da API. O `detail` é preenchido pelos
# handlers em `api/errors.py`, que traduzem os erros de domínio
# (`services/errors.py`) em status HTTP.
#
# Comentário, não docstring: o Pydantic publica a docstring como `description`
# do schema, e isso apareceria no Swagger.
class ErrorResponse(BaseModel):
    detail: str
