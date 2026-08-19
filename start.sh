#!/usr/bin/env bash
#
# Sobe a stack inteira da Resume Platform: Postgres/pgvector, MinIO, resume-agent e resume-webui.
# Ctrl+C derruba tudo.
#
set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$ROOT_DIR/logs"
AGENT_DIR="$ROOT_DIR/resume-agent"
WEBUI_DIR="$ROOT_DIR/resume-webui"
COMPOSE_FILE="$AGENT_DIR/docker-compose.yaml"
AGENT_ENV_FILE="$AGENT_DIR/.env"
SAMPLES_DIR="$AGENT_DIR/resumes_samples"

AGENT_PORT=8000
WEBUI_PORT=5173
DB_PORT=5432
MINIO_PORT=9000
MINIO_CONSOLE_PORT=9001

# Preenchidos durante a subida; usados pelo shutdown.
AGENT_PID=""
WEBUI_PID=""
STACK_STARTED=false

# Flags
FORCE_BUILD=false
SKIP_BUILD=false
SEED=false
NO_RELOAD=false

# --------------------------------------------------------------------------------------
# Saída
# --------------------------------------------------------------------------------------

info() {
    echo "  $1"
}

step() {
    echo ""
    echo "==> $1"
}

warn() {
    echo "  AVISO: $1"
}

# Só derruba a stack se este script chegou a subir alguma coisa. Falha nas pré-checagens
# (porta ocupada, por exemplo) não pode parar um container que não é nosso.
fail() {
    echo ""
    echo "ERRO: $1" >&2
    if [ "$STACK_STARTED" = true ]; then
        shutdown 1
    fi
    exit 1
}

# --------------------------------------------------------------------------------------
# Flags
# --------------------------------------------------------------------------------------

print_help() {
    cat <<'EOF'
Uso: ./start.sh [opções]

  (nenhuma)     sobe tudo sem reinstalar dependências
  --build       força 'uv sync' no agent e 'npm install' no webui
  --no-build    nunca instala: falha se faltar .venv ou node_modules (o padrão instala
                nesse caso, por ser a primeira execução)
  --seed        ingere os PDFs de resumes_samples/ se a base estiver vazia. Fora do
                padrão porque cada página custa uma chamada de embedding e uma de LLM
  --no-reload   sobe o uvicorn sem --reload (não reinicia ao editar o código)
  --help        imprime esta tabela

URLs depois da subida:
  http://localhost:5173   webui
  http://localhost:8000   resume-agent (API)
  http://localhost:8000/docs   Swagger
  http://localhost:9001   console do MinIO
EOF
}

parse_flags() {
    while [ $# -gt 0 ]; do
        case "$1" in
            --build)      FORCE_BUILD=true ;;
            --no-build)   SKIP_BUILD=true ;;
            --seed)       SEED=true ;;
            --no-reload)  NO_RELOAD=true ;;
            --help|-h)    print_help; exit 0 ;;
            *)            echo "Flag desconhecida: $1" >&2; echo ""; print_help; exit 1 ;;
        esac
        shift
    done

    if [ "$FORCE_BUILD" = true ] && [ "$SKIP_BUILD" = true ]; then
        fail "--build e --no-build são mutuamente exclusivos."
    fi
}

# --------------------------------------------------------------------------------------
# Pré-checagens
# --------------------------------------------------------------------------------------

port_is_busy() {
    local port="$1"
    if command -v ss >/dev/null 2>&1; then
        ss -ltn "sport = :$port" 2>/dev/null | grep -q LISTEN
    elif command -v lsof >/dev/null 2>&1; then
        lsof -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1
    else
        # Sem ferramenta para checar: não bloqueia a subida.
        return 1
    fi
}

check_docker() {
    command -v docker >/dev/null 2>&1 || fail "docker não encontrado. Instale o Docker."
    docker info >/dev/null 2>&1 || fail "o daemon do Docker não está rodando. Suba o Docker e tente de novo."
    docker compose version >/dev/null 2>&1 || fail "'docker compose' não disponível. Instale o plugin Compose v2."
    info "Docker ok"
}

check_uv() {
    command -v uv >/dev/null 2>&1 || fail "uv não encontrado. Instale: https://docs.astral.sh/uv/"
    info "uv $(uv --version 2>/dev/null | awk '{print $2}')"
}

check_node() {
    command -v node >/dev/null 2>&1 || fail "node não encontrado. Instale o Node 20 ou superior."
    command -v npm >/dev/null 2>&1 || fail "npm não encontrado. Instale o Node 20 ou superior."
    info "Node $(node -v)"
}

# Um container do compose já de pé segurando a porta não é conflito: o 'up -d' reaproveita.
compose_owns_port() {
    local service="$1"
    local cid
    cid="$(docker compose -f "$COMPOSE_FILE" ps -q "$service" 2>/dev/null)"
    [ -n "$cid" ] && [ "$(docker inspect -f '{{.State.Running}}' "$cid" 2>/dev/null)" = "true" ]
}

check_ports() {
    local busy=false

    for entry in "$DB_PORT:Postgres:pgvector" "$MINIO_PORT:MinIO:minio"; do
        local port="${entry%%:*}" rest="${entry#*:}"
        local label="${rest%%:*}" service="${rest##*:}"
        if port_is_busy "$port"; then
            if compose_owns_port "$service"; then
                info "container do $label já está de pé — será reaproveitado"
            else
                echo "  porta $port ($label) ocupada por outro processo" >&2
                busy=true
            fi
        fi
    done

    for entry in "$AGENT_PORT:resume-agent" "$WEBUI_PORT:resume-webui"; do
        local port="${entry%%:*}" label="${entry##*:}"
        if port_is_busy "$port"; then
            echo "  porta $port ($label) já está ocupada" >&2
            busy=true
        fi
    done

    if [ "$busy" = true ]; then
        fail "libere as portas acima antes de subir. Um Postgres de outro projeto pode estar segurando a $DB_PORT: 'docker ps' mostra quem."
    fi
    info "portas $AGENT_PORT e $WEBUI_PORT livres"
}

check_llm() {
    local main_url="${MAIN_MODEL_BASE_URL:-http://localhost:8200/v1}"
    local embed_url="${EMBEDDING_MODEL_BASE_URL:-http://localhost:8892/v1}"

    if curl -s -o /dev/null --max-time 3 "$main_url/models"; then
        info "LLM respondendo em $main_url"
    else
        warn "LLM não respondeu em $main_url — a stack sobe, mas o chat vai falhar até o modelo estar no ar."
    fi

    if curl -s -o /dev/null --max-time 3 "$embed_url/models"; then
        info "embeddings respondendo em $embed_url"
    else
        warn "embeddings não responderam em $embed_url — busca e ingestão vão falhar até o modelo estar no ar."
    fi
}

check_prereqs() {
    step "Checando pré-requisitos"
    command -v curl >/dev/null 2>&1 || fail "curl não encontrado — necessário para os healthchecks."
    check_docker
    check_uv
    check_node
    check_ports
    check_llm
}

# --------------------------------------------------------------------------------------
# Ambiente
# --------------------------------------------------------------------------------------

# O .env é gitignored e carrega credenciais do banco, do MinIO e dos modelos. Sem ele o
# compose e a aplicação caem nos defaults do .env.example, que só servem para o dev local.
load_env() {
    if [ ! -f "$AGENT_ENV_FILE" ]; then
        warn "resume-agent/.env não existe — usando os defaults. Crie com: cp resume-agent/.env.example resume-agent/.env"
        return
    fi
    set -a
    # shellcheck disable=SC1091
    . "$AGENT_ENV_FILE"
    set +a
    info "resume-agent/.env carregado"

    # Tracing é opcional e não sobe nesta stack: o compose daqui tem só banco e
    # bucket. Avisar aqui evita procurar trace que nunca foi exportado.
    if [ "${LANGFUSE_ENABLED:-false}" = "true" ]; then
        info "Langfuse: ligado — traces vão para ${LANGFUSE_BASE_URL:-http://localhost:8060}"
    else
        info "Langfuse: desligado (LANGFUSE_ENABLED != true em resume-agent/.env)"
    fi
}

# --------------------------------------------------------------------------------------
# Build
# --------------------------------------------------------------------------------------

sync_agent() {
    step "Sincronizando dependências do resume-agent (uv sync)"
    (cd "$AGENT_DIR" && uv sync) \
        || fail "falha no 'uv sync' do resume-agent."
    info "dependências do agent prontas"
}

install_webui() {
    step "Instalando dependências do resume-webui (npm install)"
    (cd "$WEBUI_DIR" && npm install --silent) \
        || fail "falha no 'npm install' do resume-webui."
    info "dependências do webui prontas"
}

# Um .venv movido junto com a pasta do projeto continua apontando para o caminho antigo
# nos shebangs, e todo console script quebra com "arquivo não encontrado". O 'uv sync' não
# conserta isso sozinho — só recriar. Barato de checar, caro de descobrir depois.
venv_is_healthy() {
    (cd "$AGENT_DIR" && uv run python -c "pass" >/dev/null 2>&1)
}

build_all() {
    if [ "$FORCE_BUILD" = true ]; then
        step "Reinstalando dependências (--build)"
        sync_agent
        install_webui
        return
    fi

    step "Verificando dependências"

    if [ -d "$AGENT_DIR/.venv" ] && venv_is_healthy; then
        info "resume-agent — .venv encontrado"
    elif [ "$SKIP_BUILD" = true ]; then
        fail "--no-build informado, mas o .venv do resume-agent não existe ou está quebrado. Rode com --build."
    elif [ -d "$AGENT_DIR/.venv" ]; then
        warn "o .venv do resume-agent está quebrado (provavelmente a pasta do projeto foi movida) — recriando"
        rm -rf "$AGENT_DIR/.venv"
        sync_agent
    else
        info "resume-agent sem .venv — instalando (primeira execução)"
        sync_agent
    fi

    if [ -d "$WEBUI_DIR/node_modules" ]; then
        info "resume-webui — node_modules encontrado"
    elif [ "$SKIP_BUILD" = true ]; then
        fail "--no-build informado, mas resume-webui/node_modules não existe. Rode com --build."
    else
        info "resume-webui sem node_modules — instalando (primeira execução)"
        install_webui
    fi

    info "mexeu nas dependências? rode com --build"
}

# --------------------------------------------------------------------------------------
# Espera
# --------------------------------------------------------------------------------------

# O 4º argumento é o PID da app. Se ela morreu (import quebrado, porta em uso, exception
# no startup), não faz sentido esperar o timeout inteiro — aborta na hora.
wait_for_http() {
    local url="$1" label="$2" timeout="$3" pid="${4:-}"
    local waited=0

    echo -n "  aguardando $label"
    while [ "$waited" -lt "$timeout" ]; do
        if curl -s -o /dev/null -f --max-time 3 "$url"; then
            echo " ok (${waited}s)"
            return 0
        fi
        if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
            echo " processo morreu"
            return 1
        fi
        sleep 2
        waited=$((waited + 2))
        echo -n "."
    done

    echo " timeout"
    return 1
}

wait_for_port() {
    local port="$1" label="$2" timeout="$3" pid="${4:-}"
    local waited=0

    echo -n "  aguardando $label"
    while [ "$waited" -lt "$timeout" ]; do
        if port_is_busy "$port"; then
            echo " ok (${waited}s)"
            return 0
        fi
        if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
            echo " processo morreu"
            return 1
        fi
        sleep 2
        waited=$((waited + 2))
        echo -n "."
    done

    echo " timeout"
    return 1
}

wait_for_postgres() {
    local user="${POSTGRES_USER:-resume_agent}"
    local db="${POSTGRES_DB:-resume_agent}"
    local waited=0

    echo -n "  aguardando Postgres"
    while [ "$waited" -lt 60 ]; do
        if docker compose -f "$COMPOSE_FILE" exec -T pgvector pg_isready -U "$user" -d "$db" >/dev/null 2>&1; then
            echo " ok (${waited}s)"
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
        echo -n "."
    done

    echo " falhou"
    return 1
}

wait_for_minio() {
    local waited=0

    echo -n "  aguardando MinIO"
    while [ "$waited" -lt 60 ]; do
        if curl -s -o /dev/null -f --max-time 3 "http://localhost:$MINIO_PORT/minio/health/live"; then
            echo " ok (${waited}s)"
            return 0
        fi
        sleep 2
        waited=$((waited + 2))
        echo -n "."
    done

    echo " falhou"
    return 1
}

# --------------------------------------------------------------------------------------
# Subida
# --------------------------------------------------------------------------------------

start_infra() {
    step "Subindo Postgres/pgvector e MinIO"
    STACK_STARTED=true
    docker compose -f "$COMPOSE_FILE" up -d || fail "falha ao subir a infra via docker compose."
    wait_for_postgres || fail "Postgres não ficou pronto em 60s. Veja: docker compose -f $COMPOSE_FILE logs pgvector"
    wait_for_minio || fail "MinIO não ficou pronto em 60s. Veja: docker compose -f $COMPOSE_FILE logs minio"
}

# O schema não é criado pela aplicação: sem isso o agent sobe e falha na primeira query.
run_migrations() {
    step "Aplicando migrações (alembic upgrade head)"
    (cd "$AGENT_DIR" && uv run alembic upgrade head) \
        || fail "falha ao aplicar as migrações. Rode 'uv run alembic upgrade head' em resume-agent para ver o erro."
    info "schema em dia"
}

# O 'exec' faz o uv substituir o subshell, então $! é o PID do próprio uv —
# sem isso o TERM iria para o subshell e deixaria o processo órfão.
start_agent() {
    step "Subindo resume-agent"

    local reload_flag="--reload"
    [ "$NO_RELOAD" = true ] && reload_flag=""

    (
        cd "$AGENT_DIR" && exec uv run uvicorn resume_agent.api:app \
            $reload_flag --host 0.0.0.0 --port "$AGENT_PORT"
    ) > "$LOG_DIR/resume-agent.log" 2>&1 &
    AGENT_PID=$!

    if ! wait_for_http "http://localhost:$AGENT_PORT/health" "resume-agent" 90 "$AGENT_PID"; then
        fail "resume-agent não subiu. Veja: tail -n 50 $LOG_DIR/resume-agent.log"
    fi
}

start_webui() {
    step "Subindo resume-webui"
    ( cd "$WEBUI_DIR" && exec npm run dev ) > "$LOG_DIR/resume-webui.log" 2>&1 &
    WEBUI_PID=$!

    if ! wait_for_port "$WEBUI_PORT" "resume-webui" 60 "$WEBUI_PID"; then
        fail "resume-webui não subiu. Veja: tail -n 50 $LOG_DIR/resume-webui.log"
    fi
}

# --------------------------------------------------------------------------------------
# Seed
# --------------------------------------------------------------------------------------

count_resumes() {
    curl -s --max-time 5 "http://localhost:$AGENT_PORT/resumes" 2>/dev/null \
        | sed -n 's/.*"total"[[:space:]]*:[[:space:]]*\([0-9]\+\).*/\1/p'
}

# Ingestão passa por LLM (extração de nome/email/telefone) e embedding de cada página,
# então semear é opt-in: subir a stack não pode gastar token sem o usuário pedir.
seed_if_empty() {
    [ "$SEED" = true ] || return 0

    step "Verificando currículos de exemplo (--seed)"

    local total
    total="$(count_resumes)"
    if ! [[ "$total" =~ ^[0-9]+$ ]]; then
        warn "não consegui contar os currículos pela API — seed ignorado."
        return
    fi

    if [ "$total" -gt 0 ]; then
        info "base já populada ($total currículos) — seed ignorado."
        return
    fi

    if [ ! -d "$SAMPLES_DIR" ]; then
        warn "$SAMPLES_DIR não existe — seed ignorado."
        return
    fi

    info "base vazia — ingerindo os PDFs de resumes_samples/ (leva alguns minutos)..."
    local args=()
    for pdf in "$SAMPLES_DIR"/*.pdf; do
        [ -f "$pdf" ] || continue
        args+=(-F "files=@$pdf")
    done

    if [ "${#args[@]}" -eq 0 ]; then
        warn "nenhum PDF em $SAMPLES_DIR — seed ignorado."
        return
    fi

    if curl -s -o /dev/null -f --max-time 900 -X POST "http://localhost:$AGENT_PORT/resumes" "${args[@]}"; then
        info "seed aplicado ($(count_resumes) currículos)."
    else
        warn "a ingestão dos exemplos falhou. Veja: tail -n 50 $LOG_DIR/resume-agent.log"
    fi
}

# --------------------------------------------------------------------------------------
# Shutdown
# --------------------------------------------------------------------------------------

# O 'npm run dev' cria o Vite como processo filho, e o 'uv run' cria o uvicorn; matar só o
# pai deixaria o filho segurando a porta. Por isso o sinal vai para a árvore inteira.
kill_with_children() {
    local pid="$1" signal="$2"
    local child
    for child in $(pgrep -P "$pid" 2>/dev/null); do
        kill_with_children "$child" "$signal"
    done
    kill "-$signal" "$pid" 2>/dev/null
}

stop_pid() {
    local pid="$1" label="$2"
    [ -n "$pid" ] || return 0
    kill -0 "$pid" 2>/dev/null || return 0

    info "parando $label (pid $pid)"
    kill_with_children "$pid" TERM

    local waited=0
    while [ "$waited" -lt 10 ] && kill -0 "$pid" 2>/dev/null; do
        sleep 1
        waited=$((waited + 1))
    done

    if kill -0 "$pid" 2>/dev/null; then
        warn "$label não respondeu ao TERM — enviando KILL"
        kill_with_children "$pid" KILL
    fi
}

SHUTDOWN_DONE=false

shutdown() {
    local exit_code="${1:-0}"

    # Idempotente: Ctrl+C durante a subida pode disparar isso mais de uma vez.
    if [ "$SHUTDOWN_DONE" = true ]; then
        return
    fi
    SHUTDOWN_DONE=true

    trap '' INT TERM
    step "Derrubando a stack"

    stop_pid "$WEBUI_PID" resume-webui
    stop_pid "$AGENT_PID" resume-agent

    # 'stop' e não 'down': preserva os volumes, com o banco e os PDFs, para o próximo start.
    info "parando Postgres e MinIO (dados preservados)"
    docker compose -f "$COMPOSE_FILE" stop >/dev/null 2>&1

    echo ""
    echo "Stack derrubada. Dados do Postgres e do MinIO preservados."
    exit "$exit_code"
}

print_urls() {
    cat <<EOF

================================================================
  Resume Platform no ar

  webui         http://localhost:$WEBUI_PORT
  resume-agent  http://localhost:$AGENT_PORT
  Swagger       http://localhost:$AGENT_PORT/docs
  MinIO         http://localhost:$MINIO_CONSOLE_PORT

  Logs em logs/ (ex.: tail -f logs/resume-agent.log)
  Ctrl+C derruba tudo.
================================================================
EOF
}

# --------------------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------------------

main() {
    parse_flags "$@"
    trap shutdown INT TERM

    mkdir -p "$LOG_DIR"

    load_env
    check_prereqs
    build_all
    start_infra
    run_migrations
    start_agent
    seed_if_empty
    start_webui
    print_urls

    # Fica em foreground até o Ctrl+C; se qualquer app morrer sozinho, derruba o resto.
    while true; do
        for entry in "$AGENT_PID:resume-agent" "$WEBUI_PID:resume-webui"; do
            local pid="${entry%%:*}" label="${entry##*:}"
            if [ -n "$pid" ] && ! kill -0 "$pid" 2>/dev/null; then
                warn "$label morreu — derrubando o resto da stack. Veja logs/$label.log"
                shutdown 1
            fi
        done
        sleep 3
    done
}

main "$@"
