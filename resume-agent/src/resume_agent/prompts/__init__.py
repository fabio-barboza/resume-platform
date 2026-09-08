"""Prompts em arquivo, fora do código que os usa.

Prompt é conteúdo, não lógica: em `.md` o diff de uma mudança de regra fica
legível e o `agent.py` não carrega 116 linhas de string.

As variáveis usam a sintaxe `$nome` do `string.Template`, não a do `str.format`:
o prompt tem um JSON de exemplo, e as chaves dele colidiriam com o formato.
"""

from importlib.resources import files
from string import Template


def render(filename: str, **variables: object) -> str:
    """Carrega o prompt e substitui as variáveis `$nome`.

    Estoura se faltar variável — prompt com `$algo` não substituído chega ao
    modelo como texto literal, e o defeito só apareceria na resposta.
    """
    text = files(__package__).joinpath(filename).read_text(encoding="utf-8")
    return Template(text).substitute(**variables)
