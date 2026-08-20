import './style.css'
import { marked } from 'marked'
import { readSse } from './sse.js'
import { renderStream, renderFinal, renderChart } from './markdown.js'

const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8000'
const CHAT_URL = `${API_URL}/chat`
const CHAT_STREAM_URL = `${API_URL}/chat/stream`

const TOOL_LABELS = {
    find_in_resumes: 'Buscando nos currículos…',
    find_candidate_by_name: 'Procurando o candidato…',
    list_resumes: 'Conferindo a base completa…',
}
const DEFAULT_TOOL_LABEL = 'Consultando a base…'

// Casa `/candidates/<id ou email>/resume`, absoluto ou relativo, como o
// agente devolve no markdown (ver `find_candidate_by_name` em agent.py).
const RESUME_LINK_RE = /(https?:\/\/[^\s)]+)?\/candidates\/([^/\s)]+)\/resume/g

marked.setOptions({ breaks: true })

function generateSessionId() {
    return 'session-' + Date.now() + '-' + Math.random().toString(36).slice(2, 9)
}

function getSessionId() {
    let sid = localStorage.getItem('chat-session-id')
    if (!sid) {
        sid = generateSessionId()
        localStorage.setItem('chat-session-id', sid)
    }
    return sid
}

let sessionId = getSessionId()
const app = document.getElementById('app')

// Logo do agente: currículo triado com carimbo de aprovação
function logo(size, id) {
    return `<svg class="logo" width="${size}" height="${size}" viewBox="0 0 48 48" fill="none" aria-hidden="true">
    <defs>
        <linearGradient id="${id}" x1="6" y1="4" x2="42" y2="44" gradientUnits="userSpaceOnUse">
            <stop stop-color="#6366f1"/>
            <stop offset="1" stop-color="#22d3ee"/>
        </linearGradient>
    </defs>
    <rect x="1.5" y="1.5" width="45" height="45" rx="13" fill="url(#${id})"/>
    <path d="M16 11h11l7 7v16a3 3 0 0 1-3 3H16a3 3 0 0 1-3-3V14a3 3 0 0 1 3-3z" fill="#fff"/>
    <path d="M27 11l7 7h-7z" fill="#c7d2fe"/>
    <path d="M18.5 23.5h10M18.5 28.5h5.5" stroke="#6366f1" stroke-width="2.4" stroke-linecap="round"/>
    <circle cx="33" cy="34" r="8.5" fill="#fff"/>
    <path d="M29.2 34.2l2.6 2.6 4.9-5.4" stroke="url(#${id})" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/>
</svg>`
}

app.innerHTML = `
<div class="shell">
    <header class="topbar">
        <div class="brand">
            ${logo(36, 'lg-brand')}
            <div class="brand-text">
                <span class="brand-name">Resume <em>Agent</em></span>
                <span class="brand-sub">Triagem de currículos com IA</span>
            </div>
        </div>
        <button id="theme-toggle" class="icon-btn" title="Alternar tema claro/escuro" aria-label="Alternar tema">
            <svg class="icon-sun" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                <circle cx="12" cy="12" r="4.5"/>
                <path d="M12 2.5v2M12 19.5v2M4.3 4.3l1.4 1.4M18.3 18.3l1.4 1.4M2.5 12h2M19.5 12h2M4.3 19.7l1.4-1.4M18.3 5.7l1.4-1.4"/>
            </svg>
            <svg class="icon-moon" width="18" height="18" viewBox="0 0 24 24" fill="currentColor">
                <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>
            </svg>
        </button>
    </header>

    <main class="app">
        <section class="chat-pane">
            <div class="pane-header">
                <div class="agent-identity">
                    <span class="avatar">${logo(26, 'lg-avatar')}</span>
                    <div class="agent-meta">
                        <span class="agent-name">Resume Agent</span>
                        <span class="agent-status"><i></i>online</span>
                    </div>
                </div>
                <button id="new-chat" class="ghost-btn" title="Nova conversa">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round">
                        <path d="M12 5v14M5 12h14"/>
                    </svg>
                    Nova conversa
                </button>
            </div>
            <div class="chat" id="chat"></div>
            <div class="input-area">
                <textarea id="input" placeholder="Pergunte sobre a base de currículos..." rows="1"></textarea>
                <button id="send" class="send-btn" title="Enviar" aria-label="Enviar mensagem">
                    <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M12 19V5"/>
                        <path d="m5 12 7-7 7 7"/>
                    </svg>
                </button>
            </div>
        </section>

        <section class="viewer-pane hidden" id="viewer-pane">
            <div class="pane-header">
                <div class="viewer-heading">
                    <svg width="17" height="17" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
                        <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/>
                        <path d="M14 3v5h5"/>
                    </svg>
                    <span id="viewer-title">Currículo</span>
                </div>
                <button id="close-viewer" class="ghost-btn" title="Fechar visualizador">
                    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round">
                        <path d="M6 6l12 12M18 6L6 18"/>
                    </svg>
                    Fechar
                </button>
            </div>
            <div class="viewer-body" id="viewer-body"></div>
        </section>
    </main>

    <footer class="app-footer">
        <span class="footer-credit">Desenvolvido por <strong>Fabio Barboza de Oliveira</strong></span>
        <div class="footer-links">
            <a href="mailto:barboza.oliveira@gmail.com" title="barboza.oliveira@gmail.com">
                <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
                    <rect x="3" y="5" width="18" height="14" rx="2.5"/>
                    <path d="m3.5 7.5 8.5 6 8.5-6"/>
                </svg>
                barboza.oliveira@gmail.com
            </a>
            <a href="https://www.linkedin.com/in/fabio-oliveira-20a977a1/" target="_blank" rel="noopener" title="LinkedIn">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M20.447 20.452h-3.554v-5.569c0-1.328-.027-3.037-1.852-3.037-1.853 0-2.136 1.445-2.136 2.939v5.667H9.351V9h3.414v1.561h.046c.477-.9 1.637-1.85 3.37-1.85 3.601 0 4.267 2.37 4.267 5.455v6.286zM5.337 7.433c-1.144 0-2.063-.926-2.063-2.065 0-1.138.92-2.063 2.063-2.063 1.14 0 2.064.925 2.064 2.063 0 1.139-.925 2.065-2.064 2.065zm1.782 13.019H3.555V9h3.564v11.452zM22.225 0H1.771C.792 0 0 .774 0 1.729v20.542C0 23.227.792 24 1.771 24h20.451C23.2 24 24 23.227 24 22.271V1.729C24 .774 23.2 0 22.225 0z"/>
                </svg>
                LinkedIn
            </a>
            <a href="https://github.com/fabio-barboza" target="_blank" rel="noopener" title="GitHub">
                <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297c0-6.627-5.373-12-12-12"/>
                </svg>
                GitHub
            </a>
        </div>
    </footer>
</div>
`

const chat = document.getElementById('chat')
const input = document.getElementById('input')
const sendBtn = document.getElementById('send')
const newChatBtn = document.getElementById('new-chat')
const viewerPane = document.getElementById('viewer-pane')
const viewerBody = document.getElementById('viewer-body')
const viewerTitle = document.getElementById('viewer-title')
const closeViewerBtn = document.getElementById('close-viewer')
const themeToggle = document.getElementById('theme-toggle')

let currentPdfUrl = null

// ===== Health check periódico (pinga /health e atualiza o indicador) =====

const HEALTH_URL = `${API_URL}/health`
const HEALTH_INTERVAL_MS = 10000
let healthInterval = null

function setHealthState(online) {
    const label = document.querySelector('.agent-status')
    if (!label) return
    label.classList.toggle('offline', !online)
    label.innerHTML = online ? '<i></i>online' : '<i></i>offline'
}

async function checkHealth() {
    try {
        const res = await fetch(HEALTH_URL)
        const data = await res.json()
        if (data.status === 'ok') {
            setHealthState(true)
        } else {
            setHealthState(false)
        }
    } catch {
        setHealthState(false)
    }
}

function startHealthCheck() {
    checkHealth() // primeiro check imediato
    healthInterval = setInterval(checkHealth, HEALTH_INTERVAL_MS)
}

function stopHealthCheck() {
    if (healthInterval) {
        clearInterval(healthInterval)
        healthInterval = null
    }
}

// ===== Tema claro/escuro (preferência persistida) =====

function currentTheme() {
    return document.documentElement.getAttribute('data-theme') || 'dark'
}

function setTheme(theme) {
    document.documentElement.setAttribute('data-theme', theme)
    localStorage.setItem('theme', theme)
}

themeToggle.addEventListener('click', () => {
    setTheme(currentTheme() === 'dark' ? 'light' : 'dark')
})

// ===== Estado vazio com sugestões =====

const SUGGESTIONS = [
    'Quantos currículos estão na base?',
    'Quem tem experiência com Python?',
    'Quais tecnologias aparecem com mais frequência?',
    'Quem tem experiência com React?',
]

function renderEmptyState() {
    chat.innerHTML = `
        <div class="empty-state">
            ${logo(76, 'lg-empty')}
            <h2>Pergunte sobre a base de currículos</h2>
            <p>Experiência, tecnologias, formação — eu consulto a base e mostro o PDF do candidato na hora.</p>
            <div class="chips">
                ${SUGGESTIONS.map(s => `<button type="button" class="chip">${s}</button>`).join('')}
            </div>
        </div>
    `
    chat.querySelectorAll('.chip').forEach(chip => {
        chip.addEventListener('click', () => {
            input.value = chip.textContent
            sendMessage()
        })
    })
}

function hideEmptyState() {
    const empty = chat.querySelector('.empty-state')
    if (empty) empty.remove()
}

// ===== Mensagens =====

function addUserMessage(text) {
    hideEmptyState()
    const div = document.createElement('div')
    div.className = 'msg user'
    div.textContent = text
    chat.appendChild(div)
    chat.scrollTop = chat.scrollHeight
    return div
}

function dedupeByIdentifier(matches) {
    const seen = new Set()
    return matches.filter(m => {
        if (seen.has(m[2])) return false
        seen.add(m[2])
        return true
    })
}

function buildPdfActions(content) {
    const links = [...(content ?? '').matchAll(RESUME_LINK_RE)]
    if (!links.length) return null

    const actions = document.createElement('div')
    actions.className = 'msg-actions'
    for (const [, base, identifier] of dedupeByIdentifier(links)) {
        const url = `${base ?? API_URL}/candidates/${identifier}/resume`
        const btn = document.createElement('button')
        btn.className = 'view-pdf-btn'
        btn.innerHTML = `
            <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round">
                <path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"/>
                <path d="M14 3v5h5"/>
            </svg>
            Ver currículo em PDF`
        btn.addEventListener('click', () => showResume(url))
        actions.appendChild(btn)
    }
    return actions
}

// Markdown completo → HTML + gráficos montados nos placeholders. Único ponto
// que chama `renderFinal`, então nenhum gráfico é desenhado com dado parcial.
function mountFinalContent(textDiv, content) {
    const { html, charts } = renderFinal(content)
    textDiv.innerHTML = html
    for (const { id, spec } of charts) {
        const container = textDiv.querySelector(`[data-chart-id="${id}"]`)
        if (container) renderChart(spec, container)
    }
}

function isNearBottom() {
    return chat.scrollHeight - chat.scrollTop - chat.clientHeight < 80
}

// Mensagem "viva": criada vazia, atualizada conforme os eventos do SSE chegam.
function addStreamingMessage() {
    hideEmptyState()

    const div = document.createElement('div')
    div.className = 'msg assistant streaming'

    const statusDiv = document.createElement('div')
    statusDiv.className = 'msg-status'
    statusDiv.innerHTML = '<span class="typing" role="status" aria-label="Pensando"><i></i><i></i><i></i></span>'

    const textDiv = document.createElement('div')
    textDiv.className = 'msg-text'

    div.appendChild(statusDiv)
    div.appendChild(textDiv)
    chat.appendChild(div)
    chat.scrollTop = chat.scrollHeight

    let buffer = ''
    let renderScheduled = false

    function scheduleRender() {
        if (renderScheduled) return
        renderScheduled = true
        requestAnimationFrame(() => {
            renderScheduled = false
            const wasNearBottom = isNearBottom()
            textDiv.innerHTML = renderStream(buffer) + '<span class="stream-cursor"></span>'
            if (wasNearBottom) chat.scrollTop = chat.scrollHeight
        })
    }

    return {
        getText() {
            return buffer
        },
        setStatus(label) {
            statusDiv.textContent = label
        },
        pushToken(text) {
            buffer += text
            scheduleRender()
        },
        finish(content, { interrupted = false } = {}) {
            const wasNearBottom = isNearBottom()
            div.classList.remove('streaming')
            statusDiv.remove()
            mountFinalContent(textDiv, content)

            const actions = buildPdfActions(content)
            if (actions) div.appendChild(actions)

            if (interrupted) {
                const note = document.createElement('p')
                note.className = 'msg-interrupted-note'
                note.textContent = 'Resposta interrompida.'
                div.appendChild(note)
            }

            if (wasNearBottom) chat.scrollTop = chat.scrollHeight
        },
        fail(detail) {
            const wasNearBottom = isNearBottom()
            div.classList.remove('streaming')
            statusDiv.remove()

            if (!buffer) {
                div.classList.add('error')
                textDiv.textContent = detail
            } else {
                mountFinalContent(textDiv, buffer)
                const note = document.createElement('p')
                note.className = 'msg-error-note'
                note.textContent = detail
                textDiv.appendChild(note)
            }

            if (wasNearBottom) chat.scrollTop = chat.scrollHeight
        },
    }
}

// ===== Visualizador de PDF =====

async function showResume(url) {
    viewerPane.classList.remove('hidden')
    viewerTitle.textContent = 'Carregando currículo...'
    viewerBody.innerHTML = ''

    try {
        const response = await fetch(url)
        if (!response.ok) throw new Error(`status ${response.status}`)
        const blob = await response.blob()

        if (currentPdfUrl) URL.revokeObjectURL(currentPdfUrl)
        currentPdfUrl = URL.createObjectURL(blob)

        const iframe = document.createElement('iframe')
        iframe.className = 'pdf-frame'
        iframe.src = currentPdfUrl
        viewerBody.appendChild(iframe)
        viewerTitle.textContent = 'Currículo'
    } catch (err) {
        viewerTitle.textContent = 'Currículo'
        viewerBody.innerHTML = '<p class="viewer-placeholder">Não foi possível carregar o PDF.</p>'
    }
}

function closeViewer() {
    if (currentPdfUrl) {
        URL.revokeObjectURL(currentPdfUrl)
        currentPdfUrl = null
    }
    viewerTitle.textContent = 'Currículo'
    viewerBody.innerHTML = ''
    viewerPane.classList.add('hidden')
}

// ===== Envio =====

function resetInput() {
    input.value = ''
    input.style.height = '44px'
}

const SEND_ICON = sendBtn.innerHTML
const STOP_ICON = `
    <svg width="15" height="15" viewBox="0 0 24 24" fill="currentColor">
        <rect x="5" y="5" width="14" height="14" rx="2.5"/>
    </svg>`

let activeController = null

function setStreamingUi(streaming) {
    sendBtn.classList.toggle('stop-btn', streaming)
    sendBtn.innerHTML = streaming ? STOP_ICON : SEND_ICON
    sendBtn.title = streaming ? 'Parar' : 'Enviar'
    sendBtn.setAttribute('aria-label', streaming ? 'Parar resposta' : 'Enviar mensagem')
}

// `/chat/stream` pode não existir (API antiga), o proxy pode não deixar
// passar SSE, ou `ReadableStream` pode faltar. Uma vez confirmado que não dá
// para streamar, não repete a tentativa nas próximas mensagens da sessão.
let streamingSupported = true

// Devolve `true` quando o turno foi resolvido (sucesso, erro ou cancelado) e
// `false` quando deve cair para `runFallback` — só quando nenhum evento
// chegou a ser recebido, para não refazer a pergunta e duplicar a resposta.
async function runStream(msg, text) {
    const controller = new AbortController()
    activeController = controller
    setStreamingUi(true)

    let receivedEvent = false

    try {
        for await (const { event, data } of readSse(
            CHAT_STREAM_URL,
            { session_id: sessionId, message: text },
            { signal: controller.signal }
        )) {
            receivedEvent = true
            if (event === 'tool') msg.setStatus(TOOL_LABELS[data.name] ?? DEFAULT_TOOL_LABEL)
            if (event === 'token') msg.pushToken(data.text)
            if (event === 'done') msg.finish(data.content)
            if (event === 'error') msg.fail(data.detail)
        }
        return true
    } catch (err) {
        if (err.name === 'AbortError') {
            msg.finish(msg.getText(), { interrupted: true })
            return true
        }

        if (!receivedEvent) {
            streamingSupported = false
            return false
        }

        msg.fail('Conexão com o agente caiu. A resposta acima pode estar incompleta.')
        return true
    } finally {
        activeController = null
        setStreamingUi(false)
    }
}

async function runFallback(msg, text) {
    try {
        const response = await fetch(CHAT_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ session_id: sessionId, message: text }),
        })
        if (!response.ok) throw new Error(`status ${response.status}`)
        const data = await response.json()
        msg.finish(data.content)
    } catch {
        msg.fail('Erro ao conectar com o agente.')
    }
}

async function sendMessage() {
    if (activeController) {
        activeController.abort()
        return
    }

    const text = input.value.trim()
    if (!text) return

    resetInput()
    addUserMessage(text)

    const msg = addStreamingMessage()

    const handled = streamingSupported ? await runStream(msg, text) : false
    if (!handled) await runFallback(msg, text)

    input.focus()
}

function startNewChat() {
    sessionId = generateSessionId()
    localStorage.setItem('chat-session-id', sessionId)
    closeViewer()
    renderEmptyState()
    resetInput()
    input.focus()
}

sendBtn.addEventListener('click', sendMessage)

input.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault()
        sendMessage()
    }
})

// Textarea cresce com o conteúdo (até 140px)
input.addEventListener('input', () => {
    input.style.height = 'auto'
    input.style.height = Math.min(input.scrollHeight, 140) + 'px'
})

newChatBtn.addEventListener('click', startNewChat)
closeViewerBtn.addEventListener('click', closeViewer)

renderEmptyState()
input.focus()
startHealthCheck()
