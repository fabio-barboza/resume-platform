# Currículos de exemplo

PDFs fictícios, para quem for testar não precisar sair atrás de currículo.
Nenhum dado real de candidato.

**Nada aqui é lido pela aplicação.** Nenhum dos dois backends (resume-agent e
resume-ai) conhece esta pasta em runtime: a base se popula por upload na API, e
os arquivos recebidos vão para o bucket S3 (MinIO em dev). Quem lê daqui são o
`--seed` do `start.sh` e as suítes de teste das duas versões — por isso a pasta
fica na raiz do repositório, e não dentro de um dos projetos.

Os comandos abaixo rodam da raiz do repositório.

## Como usar

Suba pelo Swagger, em `POST /resumes` — o endpoint aceita vários arquivos de
uma vez. Ou pela linha de comando:

```bash
curl -X POST http://localhost:8000/resumes \
  -F "files=@resumes_samples/curriculo_rafael_mendes.pdf" \
  -F "files=@resumes_samples/curriculo_ana_martins.pdf"
```

Todos de uma vez:

```bash
curl -X POST http://localhost:8000/resumes \
  $(for f in resumes_samples/*.pdf; do printf -- "-F files=@%s " "$f"; done)
```

## O que esperar

Todos têm bloco de contato (email, telefone, cidade), então entram como
`ingested`. Para exercitar o fluxo de `pending_review` — documento processado
sem email identificado — use um PDF sem linha de contato: o candidato é criado
assim mesmo e o documento fica marcado para revisão manual.

Reenviar o mesmo arquivo é no-op: a deduplicação por `file_hash` acontece antes
de qualquer processamento.
