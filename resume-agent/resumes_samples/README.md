# Currículos de exemplo

PDFs fictícios, para quem for testar não precisar sair atrás de currículo.
Nenhum dado real de candidato.

**Nada aqui é lido pela aplicação.** O código não conhece esta pasta: é só um
material de apoio do repositório. A base se popula por upload na API, e os
arquivos recebidos vão para `data/resumes/`.

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
