"""Modelos de request e response da API — é o que o Swagger documenta.

Um módulo por router, para achar o schema pelo endpoint que você está lendo:

    error.py       -> corpo de erro, usado por todos os routers
    candidate.py   -> routers/candidates.py
    resume.py      -> routers/resumes.py
    chat.py        -> routers/chat.py

Sem re-exportação aqui: cada router importa do módulo onde o schema mora, para
o caminho do import dizer de onde o símbolo veio.
"""
