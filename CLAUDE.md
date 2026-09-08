# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Idioma

**Código em inglês, texto em português do Brasil.** A divisão é rígida:

- **Inglês**: todo identificador — variáveis, funções, métodos, classes, módulos, pacotes,
  argumentos, constantes, tabelas, colunas, chaves de JSON, rotas e nomes de arquivo.
- **Português do Brasil**: comentários, docstrings, prompts (`SYSTEM_PROMPT` e afins), mensagens
  de erro e de log voltadas ao usuário, READMEs, `.env.example` e commits.

Nomes de teste seguem a regra do código: função `test_*` em inglês, docstring e mensagem de
assert em português.

## Comandos

Tudo abaixo roda de `resume-agent/`, exceto o `./start.sh` (raiz).

```bash
./start.sh                  # sobe a stack inteira (compose + migrações + API + webui)
./start.sh --seed           # + ingere resumes_samples/ se a base estiver vazia
./start.sh --build          # força uv sync + npm install
./start.sh --no-reload      # uvicorn sem --reload
# Windows: .\start.bat (wrapper do start.ps1, mesmas flags com -Build/-Seed/...)

cd resume-agent
uv sync
docker compose up -d                     # Postgres/pgvector :5432 + MinIO :9000/:9001
uv run alembic upgrade head              # schema (obrigatório antes de subir a API)
uv run uvicorn resume_agent.api:app --reload   # só a API
uv run python -m resume_agent            # API numa thread + REPL do agente no terminal

uv run pytest                            # evals pulados por padrão (addopts -m "not eval")
uv run pytest -m eval                    # evals de verdade: chamam LLM e embeddings
uv run pytest tests/test_guardrails.py::test_x -k nome   # um teste só
uv run ruff check . && uv run ruff format .

uv run alembic revision -m "descrição"   # migração nova (SQL à mão; nada de autogenerate cego)
uv run alembic downgrade -1

cd ../resume-webui && npm run dev        # Vite :5173
```

Os **modelos são externos e não sobem pelo compose**: LLM em `MAIN_MODEL_BASE_URL`
(`http://localhost:8200/v1` por padrão) e embeddings em `EMBEDDING_MODEL_BASE_URL`
(`http://localhost:8892/v1`). A stack sobe sem eles; chat e ingestão falham até voltarem.

`cp resume-agent/.env.example resume-agent/.env` é pré-requisito de tudo. O `.env.example` é a
documentação viva das variáveis — mudou config, atualize-o.

## Arquitetura

Dois projetos: `resume-agent/` (Python 3.13, FastAPI + LangChain/LangGraph, :8000) e
`resume-webui/` (Vite + JS puro + marked, :5173, chat que renderiza markdown e abre o PDF ao lado).

### Camadas do agent (a regra é rígida)

```
api/routers/   → HTTP fino: só traduz request/response, zero regra de negócio
api/schemas/   → Pydantic v2, um módulo por router
api/errors.py  → erro de domínio → status HTTP (único lugar que conhece códigos)
services/      → regra de negócio, chamável fora do FastAPI
db/repositories.py, db/vector_store.py → queries; db/errors.py não vaza ORM/driver
guardrails/    → o que a base recusa receber e o que o agente recusa responder
agent.py       → as 4 tools + middlewares; o texto do prompt vive em prompts/
prompts/       → system_prompt.md, carregado por `prompts.render` (variáveis `$nome`)
infra/model.py → factory de modelos (papéis MAIN_*/WORKER_*/EMBEDDING_*, tudo do .env)
infra/observability.py → único liga/desliga do Langfuse
```

Regra de negócio nova vai em `services/`, nunca no router. Endpoints são `def` síncrono de
propósito (threadpool do FastAPI) — não converta para `async` sem necessidade real.

### RAG agêntico com 4 tools

O agente decide **se** e **como** busca; não há recuperação obrigatória. As quatro existem porque
nenhuma sozinha resolve:

| Tool | Como | Por quê |
|---|---|---|
| `find_in_resumes` | vetorial, top-k cosseno (k=4) | experiência, tech, formação |
| `find_candidate_by_name` | textual em SQL, sem acento, fora de ordem | embedding não recupera pessoa por nome |
| `count_candidates_by_skill` | contagem literal em SQL (ILIKE, distinct por candidato) | número por tecnologia sem chute do modelo |
| `list_resumes` | inventário completo, sem embedding | "quantos", "nenhum", contato |

Só `list_resumes` e `find_candidate_by_name` autorizam o agente a afirmar que alguém **não** está
na base. O `SYSTEM_PROMPT` mora em `prompts/system_prompt.md`, é numerado em ordem crescente (1-17) e as regras 10-11
travam o formato exato do link de PDF (`/candidates/<candidate_id>/resume`) — a webui depende desse
formato para virar botão de visualização. As regras 12-14 travam do mesmo jeito o formato da fence ` ```chart ` — o
`markdown.js:extractCharts` da webui depende dele pra desenhar o gráfico. Mexer no prompt exige
rodar `pytest -m eval` — inclusive mexer no **recuo**: o `agent.py` reaplica 4 espaços em
toda linha ao carregar o arquivo, e sem eles o eval de gráfico quebra (3 execuções, 3 falhas).

### Guardrails são código, não prompt

| Guardrail | Onde | Efeito |
|---|---|---|
| Injeção de prompt (regex, determinística) | `ingestion_service._prepare` | `422` no upload |
| Critério protegido (classificador LLM, **falha aberto**) | middleware `before_agent` | encerra o turno, zero tool calls |
| `MAX_RESUME_PAGES` (3) | `_prepare` | `422` |
| `MAX_TOOL_CALLS_PER_QUESTION` (5) | `ToolCallLimitMiddleware`, `exit_behavior="continue"` | bloqueia a busca excedente, responde com o que tem |
| Grounding (regex + contagem, determinístico) | middleware `after_model` | descarta a resposta e manda o modelo buscar; na segunda falha do turno, desiste |

O de grounding é a versão em código das regras 5, 7 e 14 do prompt: o modelo responde
perguntas de recomendação inteiras com zero tool calls, inventando candidato, link de PDF e número
de gráfico. Ele conta as tool calls desde a última `HumanMessage` — busca de turno anterior não
fundamenta resposta do turno atual — e procura na resposta três sinais de afirmação sobre a base:
nome próprio de pessoa, `/candidates/<id>/resume` e fence ` ```chart `. Erra para o lado de
bloquear; saudação, instrução de API e a recusa do guardrail de critério protegido passam porque
não citam nenhum dos três. Pelo mesmo motivo o agente usa `get_factual_model()` (temperatura 0):
com temperatura de conversa ele prefere opinar de cabeça a buscar.

A reação é `jump_to="model"`, não recusa: quem errou foi o modelo, não quem perguntou, então a
resposta inventada é removida do histórico (`RemoveMessage`) e uma `HumanMessage` corretiva
marcada com `additional_kwargs["grounding_retry"]` entra no lugar, mandando ele buscar. Essa marca
é o freio do laço — ela vira o corte do turno, então na segunda falha o guardrail desiste e
descarta a resposta. `tests/test_grounding_guardrail.py` cobre o veredito chamando o hook direto e
a segunda volta montando um agente com modelo falso ensaiado; nenhum dos dois precisa de LLM.

O detector de nome (`person_mentions`) é regex de Title Case mais o blocklist `_NOT_A_PERSON`, e o
eval de recomendação usa a mesma função para conferir se todo nome citado existe no cadastro —
então falso positivo dele reprova um agente que não inventou nada. O blocklist é incompleto por
construção: nome de produto ou protocolo em Title Case ("Model Context Protocol") tem a forma de
nome de pessoa e só sai de lá por enumeração, e modelo maior escreve prosa mais rica e encontra
termo novo. Falso positivo novo se conserta acrescentando a palavra na lista; os casos
estruturais (conjunção "e" ligando dois nomes, hífen de termo composto) já estão cobertos em
`TestPersonMentions`.

Os dois de ingestão moram em `_prepare` (não no router) porque POST e PUT passam pelos mesmos
motivos, e porque rodam antes da extração e do embedding. Injeção é regex e não LLM de propósito:
barreira de bloqueio precisa ser determinística. `tests/test_guardrails.py` varre os 32 PDFs de
`resumes_samples/` exigindo **zero** achado do detector — falso positivo em currículo legítimo
quebra a suíte.

### Ingestão e dados

```
PDF → pypdf → guardrails → chunks → embeddings → Postgres/pgvector
  ↘ extração (LLM structured output; regex de fallback p/ email e telefone) → candidate
  ↘ arquivo original → bucket S3 (antes da transação; removido se ela falhar)
```

- Tabelas: `candidates` (email = chave natural, único, aceita null), `documents`
  (`file_hash` único, status `ingested`/`pending_review`), `chunks` (`vector(1024)`, HNSW cosseno).
  `ON DELETE CASCADE` nos dois níveis.
- **O currículo é a fonte da verdade do cadastro**: reingerir sobrescreve nome/email/telefone,
  inclusive com nulo. Correção via `PUT /candidates/{id}` se perde numa nova ingestão.
- IDs de chunk determinísticos (`{document_id}-p{página}-c{índice}`) + upsert: reprocessar não
  duplica. Dedup por `file_hash` antes de qualquer trabalho.
- **Trocar `EMBEDDING_MODEL` exige migração nova**: a dimensão está na coluna, não só no `.env`.
- Nenhum `CREATE TABLE` em runtime — schema só muda por migração Alembic.

### Histórico de conversa

No Postgres, pelo checkpointer do LangGraph (`infra/checkpointer.py`), com `thread_id` igual ao
`session_id` gerado pelo cliente. Sobrevive ao restart e é compartilhado entre réplicas — em
memória do processo a conversa quebrava no segundo turno assim que existisse mais de um pod.
Sem isolamento entre sessões além do id.

- Cada turno envia **só a mensagem nova**; o LangGraph carrega o resto do checkpoint. Toda chamada
  ao agente precisa de `config={"configurable": {"thread_id": ...}}` — sem isso ele nem roda.
- O checkpointer grava a pergunta antes de existir resposta. Se o cliente desconectar ou o modelo
  cair no meio, `_rollback_turn` apaga por id o que o turno gravou; sem isso a thread fica com
  pergunta órfã e o turno seguinte alucina em cima dela.
- Pool próprio do psycopg3, independente do `QueuePool` da `db/engine.py`. Some
  `DB_POOL_MAX_SIZE + CHECKPOINTER_POOL_MAX_SIZE`, multiplique pelas réplicas, compare com o
  `max_connections` do Postgres.
- As tabelas vêm da migração `0003`, que executa o `setup()` da lib em vez de copiar o DDL — por
  isso `langgraph-checkpoint-postgres` está preso por `==` no `pyproject.toml`.

`POST /chat` (síncrono, usado pelos evals e pelo REPL) e `POST /chat/stream` (SSE, usado pela
webui) compartilham o mesmo `services/chat_service.py` — é lá que o endereçamento da thread mora,
não no router.

## Testes

`conftest.py` cria um banco descartável (`<POSTGRES_DB>_test`), roda as migrações nele e o derruba;
aborta a suíte se o alvo coincidir com o banco da aplicação. `storage.store`/`discard` são mockados
por fixture autouse — nenhum teste precisa de MinIO. Evals (`test_retrieval_eval.py`,
`test_agent_multiturn_eval.py`) ficam atrás do marcador `eval` porque custam tokens e não são
determinísticos; foi o eval multi-turno que descobriu o defeito de busca por nome.

## Contexto de segurança

Demo local: **sem autenticação**, CORS `allow_origins=["*"]`, download por e-mail expõe o PDF
inteiro, credenciais padrão no `.env.example`. É intencional e está documentado no README. Os
guardrails cobrem **conteúdo**, não **acesso**. Não sugira expor esta stack fora de `localhost`.
