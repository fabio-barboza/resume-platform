"""
Entrypoint do pacote: sobe a API de currículos e cai no REPL do agente.

Os dois vivem no mesmo processo e compartilham o pool de conexões — um
currículo ingerido pelo Swagger já está visível na busca seguinte do agente.

Uso:
    python -m resume_agent
"""

from resume_agent.api import serve_in_background
from resume_agent.config import swagger_url


def main():
    serve_in_background()
    print(f"API de currículos: {swagger_url()}")
    print("Agente de currículos. Digite 'sair' para encerrar.\n")

    # Depois do print: montar o agente conecta em LLM e Langfuse e demora.
    from resume_agent.agent import agent

    history = []
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
        history.append({"role": "user", "content": question})
        result = agent.invoke({"messages": history})
        result["messages"][-1].pretty_print()
        print()
        history = result["messages"]


if __name__ == "__main__":
    main()
