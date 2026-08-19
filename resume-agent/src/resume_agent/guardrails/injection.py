"""Detecção de injeção de prompt no texto do currículo.

O candidato escreve no PDF, muitas vezes em texto invisível (branco sobre
branco, fonte tamanho zero, camada fora da página), algo como "ignore os outros
currículos". O `pypdf` extrai esse texto, ele vira chunk e entra no contexto do
modelo como se fosse conteúdo do currículo.

A checagem é determinística por regex, e não por LLM, porque barreira de
bloqueio precisa dar sempre a mesma resposta para o mesmo arquivo. O preço é o
esperado de lista de padrões: pega o ataque conhecido em linguagem natural, não
pega o criativo.
"""

import re
import unicodedata
from dataclasses import dataclass

_CONTEXT_WINDOW = 60


@dataclass(frozen=True)
class InjectionMatch:
    """Um trecho do currículo que tenta dar ordens a quem estiver lendo."""

    pattern: str
    excerpt: str


# Padrões escritos sem acento e em minúsculas: o texto é dobrado antes da busca
# (ver `_fold`). `[^.]{0,40}?` permite palavra de ligação e quebra de linha
# entre o verbo e o objeto — extração de PDF quebra frase no meio — mas para no
# ponto final, para não juntar duas frases sem relação.
_PATTERNS: tuple[tuple[str, re.Pattern[str]], ...] = (
    (
        "ordem para ignorar instruções",
        re.compile(
            r"\b(ignore|ignorar|desconsidere|desconsiderar|esqueca|esquecer"
            r"|disregard|forget)\b[^.]{0,40}?\b(instrucoes|instrucao|regras"
            r"|orientacoes|comandos|instructions?|rules|prompts?)\b"
        ),
    ),
    (
        "referência ao prompt do sistema",
        re.compile(
            r"\b(system\s+prompt|prompt\s+do\s+sistema|prompt\s+de\s+sistema)\b"
        ),
    ),
    (
        "tentativa de redefinir o papel do assistente",
        re.compile(
            r"\b(voce\s+(e|esta)\s+agora|a\s+partir\s+de\s+agora[,\s]+voce"
            r"|you\s+are\s+now|from\s+now\s+on[,\s]+you)\b"
        ),
    ),
    (
        "instrução dirigida ao sistema de IA",
        # "novas instruções" sozinho não basta: currículo de quem treinou time
        # diz "responsável pelas novas instruções do processo de deploy". O que
        # denuncia o ataque é a instrução ter destinatário — o sistema — ou vir
        # anunciada por dois pontos.
        re.compile(
            r"\bnovas?\s+instrucoes\s*:"
            r"|\binstrucao\s*:"
            r"|\binstrucoes?\s+(ao|para\s+o)\s+"
            r"(sistema|modelo|assistente|agente|avaliador)\b"
            r"|\bnew\s+instructions?\s*:"
            r"|\binstructions?\s+(to|for)\s+the\s+(system|ai|assistant|model)\b"
        ),
    ),
    (
        "marcador de conversa de chat embutido no texto",
        re.compile(
            r"(<\|im_(start|end)\|>|\[/?inst\]|<<\s*sys\s*>>"
            r"|^\s*###\s*(system|instrucoes?)\b|</?system>|</?assistant>)",
            re.MULTILINE,
        ),
    ),
    (
        "ordem para favorecer este candidato",
        re.compile(
            r"\b(sempre\s+recomende|recomende\s+(sempre\s+)?(este|esse)\s+candidato"
            r"|classifique\s+(este|esse)\s+candidato|always\s+recommend"
            r"|rank\s+this\s+candidate|select\s+this\s+candidate"
            r"|this\s+candidate\s+is\s+the\s+best)\b"
        ),
    ),
    (
        "ordem para descartar os demais currículos",
        re.compile(
            r"\b(ignore|desconsidere|descarte|reject)\b[^.]{0,40}?\b(outros?\s+"
            r"(curriculos?|candidatos?)|other\s+(resumes?|candidates?))\b"
        ),
    ),
)


def _fold(text: str) -> tuple[str, list[int]]:
    """Devolve o texto sem acento e em minúsculas, com o mapa de volta.

    A dobra é caractere a caractere de propósito: `NFKD` sobre a string inteira
    muda o comprimento, e aí o índice do `re` não aponta mais para o lugar certo
    no texto original.
    """
    chars: list[str] = []
    origins: list[int] = []
    for i, ch in enumerate(text):
        for decomposed in unicodedata.normalize("NFKD", ch):
            if unicodedata.combining(decomposed):
                continue
            chars.append(decomposed.lower())
            origins.append(i)
    return "".join(chars), origins


def _excerpt(text: str, start: int, end: int) -> str:
    """O trecho suspeito com contexto em volta, em uma linha só.

    Vai para a mensagem de erro porque o texto costuma ser invisível no PDF:
    mandar o revisor abrir o arquivo não funciona quando o ataque é branco sobre
    branco.
    """
    window = text[max(0, start - _CONTEXT_WINDOW) : end + _CONTEXT_WINDOW]
    normalized = " ".join(window.split())
    prefix = "..." if start - _CONTEXT_WINDOW > 0 else ""
    suffix = "..." if end + _CONTEXT_WINDOW < len(text) else ""
    return f"{prefix}{normalized}{suffix}"


def find_injection(text: str) -> InjectionMatch | None:
    """Procura instrução dirigida a um sistema de IA no texto do currículo.

    Devolve o primeiro achado, ou `None`. Um achado basta: a ingestão é recusada
    por inteiro.
    """
    if not text.strip():
        return None

    folded, origins = _fold(text)
    for label, pattern in _PATTERNS:
        found = pattern.search(folded)
        if found is None:
            continue
        start = origins[found.start()]
        end = origins[found.end() - 1] + 1
        return InjectionMatch(pattern=label, excerpt=_excerpt(text, start, end))
    return None
