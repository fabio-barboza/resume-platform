# contract-tests

O contrato HTTP que o **resume-agent** (Python) e o **resume-ai** (Java) precisam cumprir do mesmo
jeito. A webui foi escrita contra esse contrato e não sabe qual backend está respondendo; esta suíte
é o que garante que ela nunca precise saber.

Roda contra um backend **já no ar** e não sabe qual deles é: só imprime o nome no log.

```bash
./start.sh --python          # ou --java
cd contract-tests && npm test

BASE_URL=http://localhost:8010 npm test   # outro endereço
```

Node 20+ puro (`node:test` e `fetch` nativos), **sem dependências**: nada de `npm install`, e nenhum
dos dois backends "é dono" da ferramenta que o testa.

## O que é contrato

| Área | Verificado |
|------|-----------|
| Infra | `/health` exato, Swagger em `/docs`, `/openapi.json` com as mesmas rotas, métodos e nomes de parâmetro, CORS aberto (simples e preflight) |
| `/resumes` | formato exato do resultado de ingestão, falha isolada por arquivo, dedup por hash, paginação e 422, PUT no-op e 409, DELETE 204 sem corpo, cascata para o candidato órfão |
| `/candidates` | substituição total (ausente vira `null`), 409 de email, download por id e por email com o PDF byte a byte e o `Content-Disposition` |
| Erros | `{"detail": "..."}` com essa chave só; 404/409/422 nos mesmos casos |
| `/chat` | `{"content"}` exato, 422 sem campo, histórico vazio para sessão nova |
| `/chat/stream` | headers, frame `event: <tipo>\ndata: <json>\n\n`, chaves de cada evento, `start` primeiro, `done` e `error` exclusivos, `reset` + tokens reconstruindo o `done`, histórico gravado igual ao que a tela mostra |

O conjunto de chaves é comparado **exatamente**: campo a mais numa versão também é divergência.

## Custo e efeitos

- O ciclo de `/resumes` ingere dois PDFs sintéticos (com um token por execução, para não cair no
  dedup) e os apaga no fim. Precisa do modelo de embeddings no ar; sem LLM de extração, o
  fallback por regex identifica o candidato.
- Os testes de `/chat` chamam o LLM de verdade (poucos turnos curtos) e deixam as conversas
  `contrato-*` no histórico do backend testado. Verificam o protocolo, nunca o texto da resposta.
