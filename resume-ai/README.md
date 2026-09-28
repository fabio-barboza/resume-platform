# resume-ai

O [`resume-agent`](../resume-agent/README.md) reescrito em **Java 21 + Spring Boot 4 + Spring AI 2**.
Mesma API HTTP, mesmo banco, mesmo bucket, mesmos guardrails, mesmo prompt: a
[`resume-webui`](../resume-webui/) conversa com ele sem mudar uma linha e não sabe qual dos dois está
respondendo.

Os dois usam a porta **8000** e o mesmo Postgres. Sobe um **ou** outro, nunca os dois:

```bash
./start.sh --java      # da raiz do repositório
```

## Stack

| Camada | resume-agent (Python) | resume-ai (Java) |
|--------|-----------------------|------------------|
| HTTP | FastAPI, `def` síncrono no threadpool | Spring MVC, síncrono em threads virtuais |
| Agente | LangChain `create_agent` + middlewares | laço de tool calling próprio sobre o `ChatModel` do Spring AI |
| Ferramentas | `@tool` | `@Tool` do Spring AI |
| Modelos | `init_chat_model` / `OpenAIEmbeddings` | `OpenAiChatModel` / `OpenAiEmbeddingModel` (SDK openai-java) |
| Persistência | SQLAlchemy Core | JPA/Hibernate 7 + Spring Data JPA, `hibernate-vector` para a coluna `vector(1024)` |
| Migrações | Alembic | Flyway |
| Histórico de conversa | checkpointer do LangGraph (`checkpoint_*`) | tabela `chat_messages` (JPA) |
| PDF | pypdf + `RecursiveCharacterTextSplitter` | PDFBox + port fiel do mesmo splitter |
| Bucket | boto3 | AWS SDK for Java v2 |
| Swagger | `/docs` | `/docs` (springdoc), spec em `/openapi.json` |
| Tracing | Langfuse via callback do LangChain | Micrometer + OpenTelemetry, exportado por OTLP para o Langfuse |
| Build | uv | Maven (wrapper `mvnw`) |

## Rodando

```bash
cp .env.example .env              # mesmas variáveis do resume-agent/.env
./mvnw package -DskipTests        # target/resume-ai-0.1.0.jar
java -jar target/resume-ai-0.1.0.jar
```

Pré-requisito: Postgres/pgvector e MinIO no ar
(`docker compose --env-file .env -f ../infra/docker-compose.yaml up -d`; o compose fica na raiz
porque serve às duas versões). **Não há passo de migração separado**: o Flyway roda na subida, antes de a
aplicação aceitar request. O `.env` é lido do diretório de trabalho; variável já exportada vence o
arquivo, como no `load_dotenv()` do Python.

## O que precisa ficar igual nos dois

A compatibilidade não é só de rota. Estas são as peças que, se divergirem, quebram a troca entre as
versões — mudou em um, mude no outro:

| O quê | Por quê |
|-------|---------|
| Contrato HTTP (`/resumes`, `/candidates`, `/chat`, `/chat/stream`, `/chat/{session_id}`, `/health`) | a webui e qualquer cliente foram escritos contra o FastAPI. JSON em snake_case, campo nulo presente como `null`, erro como `{"detail": ...}`, request malformado como **422** |
| Frame SSE `event: <tipo>\ndata: <json>\n\n` e a lógica de `reset`/`done` | é o que o `sse.js` da webui parseia |
| `prompts/system_prompt.md` | **cópia** do arquivo do resume-agent, com o mesmo recuo de 4 espaços aplicado no carregamento. As regras 10-14 travam o formato do link de PDF e da fence ` ```chart ` que a webui renderiza |
| Nome, descrição e texto de saída das 4 ferramentas | o prompt cita as ferramentas pelo nome; o modelo lê a saída formatada |
| `_NOT_A_PERSON` do grounding (`PersonMentions.NOT_A_PERSON`) | falso positivo corrigido num lado e não no outro faz as versões divergirem no guardrail |
| Chunking (2000/500, separadores `\n\n`, `\n`, espaço) e ID `{document_id}-p{página}-c{índice}` | os dois gravam na mesma tabela. Medido nos 31 PDFs de exemplo que o Python já tinha ingerido: 28 geram chunks byte a byte idênticos; nos outros 3 a única diferença é o marcador de lista — o pypdf descarta o glifo `•`, o PDFBox o preserva. O splitter em si é idêntico ao do LangChain (teste-ouro) |
| Chave do objeto no bucket (slug + sufixo de hash) e metadado `sha256` | PDF gravado por uma versão é baixado pela outra |

A lista acima é verificada por [`contract-tests/`](../contract-tests/README.md), que roda a mesma
suíte HTTP contra qualquer um dos dois backends.

## Mesmo banco, duas ferramentas de migração

O resume-ai usa o **mesmo banco** do resume-agent — dá para alternar `--python`/`--java` sem
reingerir nada. Alembic e Flyway convivem assim:

- **As migrações Flyway são idempotentes** (`CREATE ... IF NOT EXISTS`, com os mesmos nomes de
  constraint e índice que o SQLAlchemy gera). Com `baseline-on-migrate` na versão 0, num banco criado
  pelo Alembic elas rodam sem mudar nada e só ficam registradas.
- **O Alembic não é idempotente**: um `alembic upgrade head` num banco criado pelo Java tentaria
  criar tabelas que já existem. Por isso a `V2` do Flyway, quando é ela quem cria o schema, grava
  `alembic_version = '0002'` — o equivalente às revisões 0001 e 0002. O Alembic segue da 0003 (as
  tabelas do checkpointer, que só o Python usa). Os dois sentidos foram testados em banco vazio.
- **Coluna nova em tabela compartilhada exige migração nos dois projetos.** Tabela só de um lado
  (`checkpoint_*` no Python, `chat_messages` no Java) não.
- `ddl-auto=validate`: o Hibernate confere se as entidades batem com o schema e recusa subir se não
  baterem. Nenhum `CREATE TABLE` em runtime, como no Python.

**O histórico de conversa não é compartilhado.** O checkpointer do LangGraph serializa o estado em
msgpack num formato que só a lib Python lê. Trocar de versão com a mesma `session_id` na webui abre
a conversa vazia; os currículos continuam lá.

## O agente

O Spring AI 2 não executa ferramenta dentro do `ChatModel`: quem roda o laço é um advisor do
`ChatClient`. O `ResumeAgent` roda o laço ele mesmo ("user-controlled tool execution"), porque cada
volta tem decisão que advisor genérico não expressa — e são as mesmas do resume-agent, na mesma
ordem:

1. **Critério protegido** (`ProtectedCriterionGuardrail`), antes de tudo: classificador LLM, falha
   aberto. Barrando, nenhuma busca roda e o modelo nem é chamado.
2. **Teto de `MAX_TOOL_CALLS_PER_QUESTION` buscas**: a chamada excedente não roda e volta ao modelo
   como resultado de ferramenta dizendo para responder com o que tem (o `exit_behavior="continue"`
   do LangChain).
3. **Grounding** (`GroundingGuardrail`), depois de cada resposta final: link de PDF falso ou
   afirmação sobre a base sem tool call no turno descartam a resposta e mandam o modelo buscar —
   na segunda falha do turno, a resposta vira a recusa. Gráfico degenerado é reparado sem nova
   rodada.

Cada passada do modelo é em streaming; os deltas viram eventos `token`, e a correção do grounding
vira `reset`. O turno só é gravado em `chat_messages` quando termina bem, numa transação — cliente
que desconecta ou modelo que cai no meio não deixam pergunta órfã (o `_rollback_turn` do Python,
sem precisar desfazer nada).

Structured output (extração de nome/email/telefone e o classificador) usa `ChatClient.entity()`,
que manda o formato por instrução no prompt, não por `response_format` — o mesmo motivo pelo qual o
Python usa `function_calling`: o DeepSeek recusa schema nativo com 400.

## Camadas

Clean Architecture com as dependências apontando para o `core`: `entrypoint` e `infra` dependem dele, ele
não depende de nenhum dos dois. O `core` não importa JPA, Spring AI nem Micrometer — só `@Service` e as
anotações/templates de transação do Spring.

```
core/
  domain/            records do negócio, chat/ (conversa do agente em tipos próprios), exception/,
                     guardrail/ (injeção, critério protegido, grounding), chunking/, text/, settings/,
                     service/ (etapas que mais de um use case compartilha)
  usecase/           um caso de uso por classe (@Service), separados por domínio:
                     resume/, candidate/, chat/, search/, health/ — use case nunca injeta outro
  gateway/           interfaces para tudo que é externo, uma por agregado, com todas as operações
infra/
  gateway/           *GatewayImpl: implementam core/gateway com Spring Data, Spring AI, S3, PDFBox
  repository/        Spring Data + fragments Criteria, entity/ (*Entity), projection/, mapper/
  client/            factory dos modelos (papéis MAIN/WORKER/EMBEDDING) e cliente S3
entrypoint/
  controller/        controllers finos + request/ response/ mapper/ + sse/ (eventos do /chat/stream);
                     ApiExceptionHandler é o único lugar com status HTTP
  agent/tools/       as 4 tools do agente: para a aplicação o modelo é só mais um cliente, então elas
                     chamam use cases como um controller faz
config/              propriedades tipadas, leitor de .env e o wiring (settings, callbacks das tools)
```

O agente é o `AskAgentUseCase`: carrega o histórico, roda o laço (critério protegido → modelo → tools com
teto de buscas → grounding) e grava o turno. `POST /chat` e `POST /chat/stream` usam o mesmo use case;
a tradução para eventos SSE é protocolo, então mora em `entrypoint/controller/sse/ChatStreamer`. O
`ChatModelGateway` é a única porta do laço para o modelo: chama o LLM em streaming e executa as tools.

O que o POST e o PUT de currículo compartilham (ler, validar, extrair contato e gerar embeddings; resolver
o candidato) está em `core/domain/service/` (`ResumePreparation`, `CandidateResolution`), não num use
case chamando outro. Os callbacks das tools chegam por um bean do
`config/`, para que `infra` não dependa de `entrypoint`.

Transação: `@Transactional` nos use cases simples; `TransactionTemplate` onde há chamada de rede no meio
(ingerir, substituir e apagar currículo, que falam com o S3, e o chat, que não pode segurar conexão
enquanto o LLM responde).

Os `*GatewayImpl` de persistência são `@Component`, não `@Repository`: o `@Repository` liga a tradução de
exceções no próprio gateway, e o tradutor do JPA converte até `IllegalStateException` em
`InvalidDataAccessApiUsageException`. Os repositórios Spring Data já traduzem o que vem do banco.

As colunas `tool_calls`/`tool_responses` de `chat_messages` guardam o JSON com as chaves do Spring AI
(`id`, `type`, `name`, `arguments` / `id`, `name`, `responseData`), porque o histórico já gravado foi
escrito assim; o `ChatMessageEntityMapper` mantém esse formato.

## Configuração

O [`.env.example`](.env.example) é o do resume-agent, com as mesmas chaves. Diferenças:

- `CHECKPOINTER_POOL_*` não existe: o histórico usa o mesmo pool HikariCP (`DB_POOL_*`).
- `API_LOG_LEVEL` é ignorado (o nome `warning` do uvicorn não é um nível do Logback).
- `WORKER_MODEL*` monta o bean, mas nenhum fluxo o usa — igual ao Python.
- `LANGFUSE_ENABLED=true` liga a exportação OTLP para `LANGFUSE_BASE_URL/api/public/otel`, com as
  chaves no header `Authorization`. Cada pergunta vira um span "Agente RAG Curriculos" (mesmo
  nome do resume-agent) com o classificador, as gerações do modelo, as ferramentas e os
  embeddings como filhos. O `/health`, que a webui pinga a cada 10 s, fica fora do tracing.

## Testes

```bash
./mvnw test              # determinísticos: sem LLM; usam Postgres (banco descartável) e nada de MinIO
./mvnw test -Peval       # evals: chamam LLM e embeddings de verdade, ingerem resumes_samples/
```

A suíte cria `<POSTGRES_DB>_ai_test` (ou `TEST_POSTGRES_DB`), deixa o Flyway da aplicação migrá-lo
e o derruba no fim; aborta se o alvo for o banco da aplicação. O bucket é `@MockitoBean`.

| Teste | Cobre |
|-------|-------|
| `InjectionDetectorTest` | ataques conhecidos, falsos positivos, varredura dos PDFs de exemplo com **zero** achados |
| `IngestionGuardrailsTest` | teto de páginas, PDF sem texto, injeção na última página, identidade mínima |
| `GroundingGuardrailTest`, `PersonMentionsTest` | os vereditos do grounding e cada falso positivo real do detector de nome |
| `ProtectedCriterionGuardrailTest` | recusa, falha aberto, classificador falso |
| `ResumeAgentTest` | o laço com modelo roteirizado: segunda volta do grounding, teto de buscas, recusa sem chamar o modelo |
| `CandidateCountTest`, `IngestionInvariantsTest` | contagem literal; dedup, PUT, rollback no meio, cascata, conflito de email |
| `ChatStreamTest`, `ResumeApiTest` | forma exata do SSE, `reset`, histórico, desconexão, contrato JSON e status HTTP |
| `RecursiveCharacterTextSplitterTest` | saída idêntica à do LangChain (fixture gerada por ele) |
| `RetrievalEvalTest`, `AgentEvalTest`, `MultiturnEvalTest` *(eval)* | os evals do resume-agent, portados |

O `TestJobPostingToChart` do Python virou `MultiturnEvalTest.JobPostingToChart`, e vale o mesmo
aviso: reprova em torno de uma rodada em cinco por variação do modelo.

A fixture `src/test/resources/splitter/langchain_golden.json` foi gerada pelo splitter do LangChain,
rodando no ambiente do resume-agent (`uv run python`), com `chunk_size=2000, chunk_overlap=500`.
Mudou o chunking lá, regenere aqui.

## Segurança

Demo local, como o resume-agent: **sem autenticação**, CORS `*`, credenciais padrão no
`.env.example`. Os guardrails cobrem conteúdo, não acesso. Não exponha fora de `localhost`.
