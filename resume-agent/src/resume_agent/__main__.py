"""
Entrypoint do pacote: sobe a API de currículos e cai no REPL do agente.

Os dois vivem no mesmo processo e compartilham o pool de conexões — um
currículo ingerido pelo Swagger já está visível na busca seguinte do agente.

Uso:
    python -m resume_agent
"""

from uuid import uuid4

from resume_agent.api import serve_in_background
from resume_agent.config import swagger_url
from resume_agent.infra import close_checkpointer


def main():
    serve_in_background()
    print(f"API de currículos: {swagger_url()}")
    print("Agente de currículos. Digite 'sair' para encerrar.\n")

    # Depois do print: montar o agente conecta em LLM e Langfuse e demora.
    from resume_agent.agent import agent

    # Thread nova a cada execução: o REPL é sessão de terminal, não conversa
    # retomável. O estado vai para o Postgres como o da API, só não é
    # reendereçado depois.
    session_id = f"repl-{uuid4()}"
    config = {"configurable": {"thread_id": session_id}}

    try:
        while True:
            try:
                question = input("Você: ").strip()
            except (EOFError, KeyboardInterrupt):
                print()
                break
            if question.lower() in ("sair", "exit", "quit"):
                break
            if not question:
                continue
            result = agent.invoke(
                {"messages": [{"role": "user", "content": question}]}, config
            )
            result["messages"][-1].pretty_print()
            print()
    finally:
        # Sem isto o pool do checkpointer morre junto com o interpretador e o
        # psycopg despeja aviso de thread não encerrada na saída do REPL.
        close_checkpointer()


if __name__ == "__main__":
    main()
