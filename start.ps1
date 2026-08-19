<#
.SYNOPSIS
    Sobe a stack inteira da Resume Platform: Postgres/pgvector, MinIO, resume-agent e resume-webui.
.DESCRIPTION
    Ctrl+C derruba tudo. Equivalente Windows do start.sh.
#>
[CmdletBinding()]
param(
    [switch]$Build,
    [switch]$NoBuild,
    [switch]$Seed,
    [switch]$NoReload,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'

$RootDir      = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogDir       = Join-Path $RootDir 'logs'
$AgentDir     = Join-Path $RootDir 'resume-agent'
$WebuiDir     = Join-Path $RootDir 'resume-webui'
$ComposeFile  = Join-Path $AgentDir 'docker-compose.yaml'
$AgentEnvFile = Join-Path $AgentDir '.env'
$SamplesDir   = Join-Path $AgentDir 'resumes_samples'

$AgentPort        = 8000
$WebuiPort        = 5173
$DbPort           = 5432
$MinioPort        = 9000
$MinioConsolePort = 9001

# Preenchidos durante a subida; usados pelo Stop-Stack.
$script:AgentProcess = $null
$script:WebuiProcess = $null
$script:StackStarted = $false
$script:ShutdownDone = $false

# --------------------------------------------------------------------------------------
# Saída
# --------------------------------------------------------------------------------------

function Write-Info {
    param([string]$Message)
    Write-Host "  $Message"
}

function Write-Step {
    param([string]$Message)
    Write-Host ''
    Write-Host "==> $Message"
}

function Write-Warn {
    param([string]$Message)
    Write-Host "  AVISO: $Message" -ForegroundColor Yellow
}

class StackFailure : System.Exception {
    StackFailure([string]$message) : base($message) { }
}

function Fail {
    param([string]$Message)
    throw [StackFailure]::new($Message)
}

# --------------------------------------------------------------------------------------
# Flags
# --------------------------------------------------------------------------------------

function Show-Help {
    @'
Uso: .\start.ps1 [opções]

  (nenhuma)     sobe tudo sem reinstalar dependências
  -Build        força 'uv sync' no agent e 'npm install' no webui
  -NoBuild      nunca instala: falha se faltar .venv ou node_modules (o padrão instala
                nesse caso, por ser a primeira execução)
  -Seed         ingere os PDFs de resumes_samples\ se a base estiver vazia. Fora do
                padrão porque cada página custa uma chamada de embedding e uma de LLM
  -NoReload     sobe o uvicorn sem --reload (não reinicia ao editar o código)
  -Help         imprime esta tabela

URLs depois da subida:
  http://localhost:5173   webui
  http://localhost:8000   resume-agent (API)
  http://localhost:8000/docs   Swagger
  http://localhost:9001   console do MinIO
'@ | Write-Host
}

function Test-Flags {
    if ($Build -and $NoBuild) {
        Fail '-Build e -NoBuild são mutuamente exclusivos.'
    }
}

# --------------------------------------------------------------------------------------
# Pré-checagens
# --------------------------------------------------------------------------------------

function Test-PortBusy {
    param([int]$Port)
    $connections = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    return $null -ne $connections
}

function Test-Docker {
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        Fail 'docker não encontrado. Instale o Docker Desktop.'
    }
    docker info *> $null
    if ($LASTEXITCODE -ne 0) {
        Fail 'o daemon do Docker não está rodando. Suba o Docker Desktop e tente de novo.'
    }
    docker compose version *> $null
    if ($LASTEXITCODE -ne 0) {
        Fail "'docker compose' não disponível. Instale o plugin Compose v2."
    }
    Write-Info 'Docker ok'
}

function Test-Uv {
    if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {
        Fail 'uv não encontrado. Instale: https://docs.astral.sh/uv/'
    }
    Write-Info "uv $((& uv --version) -replace '^uv\s+', '')"
}

function Test-Node {
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        Fail 'node não encontrado. Instale o Node 20 ou superior.'
    }
    if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
        Fail 'npm não encontrado. Instale o Node 20 ou superior.'
    }
    Write-Info "Node $(& node -v)"
}

# Um container do compose já de pé segurando a porta não é conflito: o 'up -d' reaproveita.
function Test-ComposeOwnsPort {
    param([string]$Service)
    $cid = (docker compose -f $ComposeFile ps -q $Service 2>$null | Out-String).Trim()
    if (-not $cid) {
        return $false
    }
    $state = docker inspect -f '{{.State.Running}}' $cid 2>$null
    return ($LASTEXITCODE -eq 0) -and (($state | Out-String).Trim() -eq 'true')
}

function Test-Ports {
    $busy = @()

    $infra = @(
        @{ Port = $DbPort;    Label = 'Postgres'; Service = 'pgvector' },
        @{ Port = $MinioPort; Label = 'MinIO';    Service = 'minio' }
    )
    foreach ($entry in $infra) {
        if (Test-PortBusy -Port $entry.Port) {
            if (Test-ComposeOwnsPort -Service $entry.Service) {
                Write-Info "container do $($entry.Label) já está de pé — será reaproveitado"
            }
            else {
                Write-Host "  porta $($entry.Port) ($($entry.Label)) ocupada por outro processo"
                $busy += $entry.Port
            }
        }
    }

    $apps = [ordered]@{
        $AgentPort = 'resume-agent'
        $WebuiPort = 'resume-webui'
    }
    foreach ($port in $apps.Keys) {
        if (Test-PortBusy -Port ([int]$port)) {
            Write-Host "  porta $port ($($apps[$port])) já está ocupada"
            $busy += $port
        }
    }

    if ($busy.Count -gt 0) {
        Fail "libere as portas acima antes de subir. Um Postgres de outro projeto pode estar segurando a $DbPort: 'docker ps' mostra quem."
    }
    Write-Info "portas $AgentPort e $WebuiPort livres"
}

function Test-Llm {
    $mainUrl  = if ($env:MAIN_MODEL_BASE_URL) { $env:MAIN_MODEL_BASE_URL } else { 'http://localhost:8200/v1' }
    $embedUrl = if ($env:EMBEDDING_MODEL_BASE_URL) { $env:EMBEDDING_MODEL_BASE_URL } else { 'http://localhost:8892/v1' }

    try {
        Invoke-WebRequest -Uri "$mainUrl/models" -TimeoutSec 3 -UseBasicParsing | Out-Null
        Write-Info "LLM respondendo em $mainUrl"
    }
    catch {
        Write-Warn "LLM não respondeu em $mainUrl — a stack sobe, mas o chat vai falhar até o modelo estar no ar."
    }

    try {
        Invoke-WebRequest -Uri "$embedUrl/models" -TimeoutSec 3 -UseBasicParsing | Out-Null
        Write-Info "embeddings respondendo em $embedUrl"
    }
    catch {
        Write-Warn "embeddings não responderam em $embedUrl — busca e ingestão vão falhar até o modelo estar no ar."
    }
}

function Test-Prereqs {
    Write-Step 'Checando pré-requisitos'
    Test-Docker
    Test-Uv
    Test-Node
    Test-Ports
    Test-Llm
}

# --------------------------------------------------------------------------------------
# Ambiente
# --------------------------------------------------------------------------------------

# O .env é gitignored e carrega credenciais do banco, do MinIO e dos modelos. Sem ele o
# compose e a aplicação caem nos defaults do .env.example, que só servem para o dev local.
function Import-DotEnv {
    if (-not (Test-Path $AgentEnvFile)) {
        Write-Warn 'resume-agent\.env não existe — usando os defaults. Crie com: copy resume-agent\.env.example resume-agent\.env'
        return
    }

    foreach ($line in Get-Content $AgentEnvFile) {
        $trimmed = $line.Trim()
        if ($trimmed -eq '' -or $trimmed.StartsWith('#')) { continue }
        $pair = $trimmed -split '=', 2
        if ($pair.Count -ne 2) { continue }
        $name  = $pair[0].Trim()
        $value = $pair[1].Trim().Trim('"').Trim("'")
        [Environment]::SetEnvironmentVariable($name, $value, 'Process')
    }
    Write-Info 'resume-agent\.env carregado'

    # Tracing é opcional e não sobe nesta stack: o compose daqui tem só banco e
    # bucket. Avisar aqui evita procurar trace que nunca foi exportado.
    if ($env:LANGFUSE_ENABLED -eq 'true') {
        $baseUrl = if ($env:LANGFUSE_BASE_URL) { $env:LANGFUSE_BASE_URL } else { 'http://localhost:8060' }
        Write-Info "Langfuse: ligado — traces vão para $baseUrl"
    }
    else {
        Write-Info 'Langfuse: desligado (LANGFUSE_ENABLED != true em resume-agent\.env)'
    }
}

# --------------------------------------------------------------------------------------
# Build
# --------------------------------------------------------------------------------------

function Sync-Agent {
    Write-Step 'Sincronizando dependências do resume-agent (uv sync)'
    Push-Location $AgentDir
    try {
        & uv sync
        if ($LASTEXITCODE -ne 0) {
            Fail "falha no 'uv sync' do resume-agent."
        }
    }
    finally {
        Pop-Location
    }
    Write-Info 'dependências do agent prontas'
}

function Install-Webui {
    Write-Step 'Instalando dependências do resume-webui (npm install)'
    Push-Location $WebuiDir
    try {
        & npm install --silent
        if ($LASTEXITCODE -ne 0) {
            Fail "falha no 'npm install' do resume-webui."
        }
    }
    finally {
        Pop-Location
    }
    Write-Info 'dependências do webui prontas'
}

# Um .venv movido junto com a pasta do projeto continua apontando para o caminho antigo
# nos shebangs, e todo console script quebra com "arquivo não encontrado". O 'uv sync' não
# conserta isso sozinho — só recriar. Barato de checar, caro de descobrir depois.
function Test-VenvHealthy {
    Push-Location $AgentDir
    try {
        & uv run python -c "pass" *> $null
        return $LASTEXITCODE -eq 0
    }
    finally {
        Pop-Location
    }
}

function Invoke-BuildAll {
    if ($Build) {
        Write-Step 'Reinstalando dependências (-Build)'
        Sync-Agent
        Install-Webui
        return
    }

    Write-Step 'Verificando dependências'

    $venvDir = Join-Path $AgentDir '.venv'
    if ((Test-Path $venvDir) -and (Test-VenvHealthy)) {
        Write-Info 'resume-agent — .venv encontrado'
    }
    elseif ($NoBuild) {
        Fail '-NoBuild informado, mas o .venv do resume-agent não existe ou está quebrado. Rode com -Build.'
    }
    elseif (Test-Path $venvDir) {
        Write-Warn 'o .venv do resume-agent está quebrado (provavelmente a pasta do projeto foi movida) — recriando'
        Remove-Item -Recurse -Force $venvDir
        Sync-Agent
    }
    else {
        Write-Info 'resume-agent sem .venv — instalando (primeira execução)'
        Sync-Agent
    }

    if (Test-Path (Join-Path $WebuiDir 'node_modules')) {
        Write-Info 'resume-webui — node_modules encontrado'
    }
    elseif ($NoBuild) {
        Fail '-NoBuild informado, mas resume-webui\node_modules não existe. Rode com -Build.'
    }
    else {
        Write-Info 'resume-webui sem node_modules — instalando (primeira execução)'
        Install-Webui
    }

    Write-Info 'mexeu nas dependências? rode com -Build'
}

# --------------------------------------------------------------------------------------
# Espera
# --------------------------------------------------------------------------------------

# O parâmetro -Process é a app sendo esperada. Se ela morreu (import quebrado, porta em uso,
# exception no startup), não faz sentido esperar o timeout inteiro — aborta na hora.
function Wait-ForHttp {
    param([string]$Url, [string]$Label, [int]$TimeoutSeconds, [System.Diagnostics.Process]$Process)

    $waited = 0
    Write-Host "  aguardando $Label" -NoNewline
    while ($waited -lt $TimeoutSeconds) {
        try {
            Invoke-WebRequest -Uri $Url -TimeoutSec 3 -UseBasicParsing | Out-Null
            Write-Host " ok ($waited`s)"
            return $true
        }
        catch {
            if ($null -ne $Process -and $Process.HasExited) {
                Write-Host ' processo morreu'
                return $false
            }
            Start-Sleep -Seconds 2
            $waited += 2
            Write-Host '.' -NoNewline
        }
    }
    Write-Host ' timeout'
    return $false
}

function Wait-ForPort {
    param([int]$Port, [string]$Label, [int]$TimeoutSeconds, [System.Diagnostics.Process]$Process)

    $waited = 0
    Write-Host "  aguardando $Label" -NoNewline
    while ($waited -lt $TimeoutSeconds) {
        if (Test-PortBusy -Port $Port) {
            Write-Host " ok ($waited`s)"
            return $true
        }
        if ($null -ne $Process -and $Process.HasExited) {
            Write-Host ' processo morreu'
            return $false
        }
        Start-Sleep -Seconds 2
        $waited += 2
        Write-Host '.' -NoNewline
    }
    Write-Host ' timeout'
    return $false
}

function Wait-ForPostgres {
    $user = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { 'resume_agent' }
    $db   = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { 'resume_agent' }
    $waited = 0

    Write-Host '  aguardando Postgres' -NoNewline
    while ($waited -lt 60) {
        docker compose -f $ComposeFile exec -T pgvector pg_isready -U $user -d $db *> $null
        if ($LASTEXITCODE -eq 0) {
            Write-Host " ok ($waited`s)"
            return $true
        }
        Start-Sleep -Seconds 2
        $waited += 2
        Write-Host '.' -NoNewline
    }
    Write-Host ' falhou'
    return $false
}

function Wait-ForMinio {
    $waited = 0
    Write-Host '  aguardando MinIO' -NoNewline
    while ($waited -lt 60) {
        try {
            Invoke-WebRequest -Uri "http://localhost:$MinioPort/minio/health/live" -TimeoutSec 3 -UseBasicParsing | Out-Null
            Write-Host " ok ($waited`s)"
            return $true
        }
        catch {
            Start-Sleep -Seconds 2
            $waited += 2
            Write-Host '.' -NoNewline
        }
    }
    Write-Host ' falhou'
    return $false
}

# --------------------------------------------------------------------------------------
# Subida
# --------------------------------------------------------------------------------------

function Start-Infra {
    Write-Step 'Subindo Postgres/pgvector e MinIO'
    $script:StackStarted = $true
    docker compose -f $ComposeFile up -d
    if ($LASTEXITCODE -ne 0) {
        Fail 'falha ao subir a infra via docker compose.'
    }
    if (-not (Wait-ForPostgres)) {
        Fail "Postgres não ficou pronto em 60s. Veja: docker compose -f $ComposeFile logs pgvector"
    }
    if (-not (Wait-ForMinio)) {
        Fail "MinIO não ficou pronto em 60s. Veja: docker compose -f $ComposeFile logs minio"
    }
}

# O schema não é criado pela aplicação: sem isso o agent sobe e falha na primeira query.
function Invoke-Migrations {
    Write-Step 'Aplicando migrações (alembic upgrade head)'
    Push-Location $AgentDir
    try {
        & uv run alembic upgrade head
        if ($LASTEXITCODE -ne 0) {
            Fail "falha ao aplicar as migrações. Rode 'uv run alembic upgrade head' em resume-agent para ver o erro."
        }
    }
    finally {
        Pop-Location
    }
    Write-Info 'schema em dia'
}

function Start-BackgroundApp {
    param([string]$FilePath, [string[]]$Arguments, [string]$WorkingDirectory, [string]$LogFile)

    return Start-Process -FilePath $FilePath `
        -ArgumentList $Arguments `
        -WorkingDirectory $WorkingDirectory `
        -RedirectStandardOutput $LogFile `
        -RedirectStandardError "$LogFile.err" `
        -NoNewWindow -PassThru
}

function Start-Agent {
    Write-Step 'Subindo resume-agent'

    $uvicornArgs = @('run', 'uvicorn', 'resume_agent.api:app')
    if (-not $NoReload) {
        $uvicornArgs += '--reload'
    }
    $uvicornArgs += @('--host', '0.0.0.0', '--port', "$AgentPort")

    $script:AgentProcess = Start-BackgroundApp -FilePath 'uv' -Arguments $uvicornArgs `
        -WorkingDirectory $AgentDir `
        -LogFile (Join-Path $LogDir 'resume-agent.log')

    if (-not (Wait-ForHttp -Url "http://localhost:$AgentPort/health" -Label 'resume-agent' -TimeoutSeconds 90 -Process $script:AgentProcess)) {
        Fail 'resume-agent não subiu. Veja: logs\resume-agent.log'
    }
}

function Start-Webui {
    Write-Step 'Subindo resume-webui'
    $script:WebuiProcess = Start-BackgroundApp -FilePath 'npm.cmd' -Arguments @('run', 'dev') `
        -WorkingDirectory $WebuiDir `
        -LogFile (Join-Path $LogDir 'resume-webui.log')

    if (-not (Wait-ForPort -Port $WebuiPort -Label 'resume-webui' -TimeoutSeconds 60 -Process $script:WebuiProcess)) {
        Fail 'resume-webui não subiu. Veja: logs\resume-webui.log'
    }
}

# --------------------------------------------------------------------------------------
# Seed
# --------------------------------------------------------------------------------------

function Get-ResumeCount {
    try {
        $response = Invoke-RestMethod -Uri "http://localhost:$AgentPort/resumes" -TimeoutSec 5
        return [int]$response.total
    }
    catch {
        return $null
    }
}

# Ingestão passa por LLM (extração de nome/email/telefone) e embedding de cada página,
# então semear é opt-in: subir a stack não pode gastar token sem o usuário pedir.
function Initialize-SeedIfEmpty {
    if (-not $Seed) {
        return
    }

    Write-Step 'Verificando currículos de exemplo (-Seed)'

    $total = Get-ResumeCount
    if ($null -eq $total) {
        Write-Warn 'não consegui contar os currículos pela API — seed ignorado.'
        return
    }

    if ($total -gt 0) {
        Write-Info "base já populada ($total currículos) — seed ignorado."
        return
    }

    if (-not (Test-Path $SamplesDir)) {
        Write-Warn "$SamplesDir não existe — seed ignorado."
        return
    }

    $pdfs = Get-ChildItem -Path $SamplesDir -Filter '*.pdf' -File
    if ($pdfs.Count -eq 0) {
        Write-Warn "nenhum PDF em $SamplesDir — seed ignorado."
        return
    }

    Write-Info 'base vazia — ingerindo os PDFs de resumes_samples\ (leva alguns minutos)...'

    # curl.exe e não Invoke-RestMethod: o parâmetro -Form (multipart) só existe no
    # PowerShell 6+, e o start.bat chama o powershell.exe 5.1. O curl vem no Windows 10+.
    $curl = Get-Command curl.exe -ErrorAction SilentlyContinue
    if (-not $curl) {
        Write-Warn 'curl.exe não encontrado — seed ignorado. Suba os PDFs pelo Swagger.'
        return
    }

    $curlArgs = @('-s', '-o', 'NUL', '-f', '--max-time', '900', '-X', 'POST', "http://localhost:$AgentPort/resumes")
    foreach ($pdf in $pdfs) {
        $curlArgs += @('-F', "files=@$($pdf.FullName)")
    }

    & $curl.Source @curlArgs
    if ($LASTEXITCODE -eq 0) {
        Write-Info "seed aplicado ($(Get-ResumeCount) currículos)."
    }
    else {
        Write-Warn 'a ingestão dos exemplos falhou. Veja: logs\resume-agent.log'
    }
}

# --------------------------------------------------------------------------------------
# Shutdown
# --------------------------------------------------------------------------------------

# O 'npm run dev' cria o Vite como processo filho, e o 'uv run' cria o uvicorn; matar só o
# pai deixaria o filho segurando a porta. Por isso o taskkill usa /T na árvore inteira.
function Stop-ProcessTree {
    param([System.Diagnostics.Process]$Process, [string]$Label)

    if ($null -eq $Process -or $Process.HasExited) {
        return
    }

    Write-Info "parando $Label (pid $($Process.Id))"
    try {
        taskkill /PID $Process.Id /T /F *> $null
        $Process.WaitForExit(10000) | Out-Null
    }
    catch {
        Write-Warn "não consegui parar $Label : $($_.Exception.Message)"
    }
}

function Stop-Stack {
    if ($script:ShutdownDone) {
        return
    }
    $script:ShutdownDone = $true

    Write-Step 'Derrubando a stack'

    Stop-ProcessTree -Process $script:WebuiProcess -Label 'resume-webui'
    Stop-ProcessTree -Process $script:AgentProcess -Label 'resume-agent'

    # 'stop' e não 'down': preserva os volumes, com o banco e os PDFs, para o próximo start.
    Write-Info 'parando Postgres e MinIO (dados preservados)'
    docker compose -f $ComposeFile stop *> $null

    Write-Host ''
    Write-Host 'Stack derrubada. Dados do Postgres e do MinIO preservados.'
}

function Show-Urls {
    @"

================================================================
  Resume Platform no ar

  webui         http://localhost:$WebuiPort
  resume-agent  http://localhost:$AgentPort
  Swagger       http://localhost:$AgentPort/docs
  MinIO         http://localhost:$MinioConsolePort

  Logs em logs\ (ex.: Get-Content -Wait logs\resume-agent.log)
  Ctrl+C derruba tudo.
================================================================
"@ | Write-Host
}

# --------------------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------------------

if ($Help) {
    Show-Help
    exit 0
}

$exitCode = 0

try {
    Test-Flags
    New-Item -ItemType Directory -Path $LogDir -Force | Out-Null

    Import-DotEnv
    Test-Prereqs
    Invoke-BuildAll
    Start-Infra
    Invoke-Migrations
    Start-Agent
    Initialize-SeedIfEmpty
    Start-Webui
    Show-Urls

    # Fica em foreground até o Ctrl+C; se qualquer app morrer sozinho, derruba o resto.
    while ($true) {
        Start-Sleep -Seconds 3
        foreach ($entry in @(
                @{ Process = $script:AgentProcess; Label = 'resume-agent' },
                @{ Process = $script:WebuiProcess; Label = 'resume-webui' })) {
            if ($null -ne $entry.Process -and $entry.Process.HasExited) {
                Write-Warn "$($entry.Label) morreu — derrubando o resto da stack. Veja logs\$($entry.Label).log"
                $exitCode = 1
                break
            }
        }
        if ($exitCode -ne 0) {
            break
        }
    }
}
catch [StackFailure] {
    Write-Host ''
    Write-Host "ERRO: $($_.Exception.Message)" -ForegroundColor Red
    $exitCode = 1
}
finally {
    # Cobre Ctrl+C, erro e saída normal. Só derruba se este script chegou a subir algo:
    # falha nas pré-checagens não pode parar um container que não é nosso.
    if ($script:StackStarted) {
        Stop-Stack
    }
}

exit $exitCode
