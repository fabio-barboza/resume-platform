import './style.css'
import { marked } from 'marked'

const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8000'
const CHAT_URL = `${API_URL}/chat`

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

app.innerHTML = `
<div class="app">
    <div class="chat-pane">
        <div class="header">
            <span>Resume Agent</span>
            <button id="new-chat" title="Nova conversa">+ Nova conversa</button>
        </div>
        <div class="chat" id="chat"></div>
        <div class="input-area">
            <textarea id="input" placeholder="Digite sua mensagem..."></textarea>
            <button id="send">Enviar</button>
        </div>
    </div>
    <div class="viewer-pane hidden" id="viewer-pane">
        <div class="header">
            <span id="viewer-title">Currículo</span>
            <button id="close-viewer" title="Fechar visualizador">Fechar</button>
        </div>
        <div class="viewer-body" id="viewer-body"></div>
    </div>
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

let currentPdfUrl = null

function addUserMessage(text) {
    const div = document.createElement('div')
    div.className = 'msg user'
    div.textContent = text
    chat.appendChild(div)
    chat.scrollTop = chat.scrollHeight
    return div
}

function addLoadingMessage() {
    const div = document.createElement('div')
    div.className = 'msg assistant loading'
    div.textContent = 'Pensando...'
    chat.appendChild(div)
    chat.scrollTop = chat.scrollHeight
    return div
}

function addAssistantMessage(content) {
    const div = document.createElement('div')
    div.className = 'msg assistant'

    const textDiv = document.createElement('div')
    textDiv.className = 'msg-text'
    textDiv.innerHTML = marked.parse(content ?? '')
    div.appendChild(textDiv)

    const links = [...(content ?? '').matchAll(RESUME_LINK_RE)]
    if (links.length) {
        const actions = document.createElement('div')
        actions.className = 'msg-actions'
        for (const [, base, identifier] of dedupeByIdentifier(links)) {
            const url = `${base ?? API_URL}/candidates/${identifier}/resume`
            const btn = document.createElement('button')
            btn.className = 'view-pdf-btn'
            btn.textContent = 'Ver currículo em PDF'
            btn.addEventListener('click', () => showResume(url))
            actions.appendChild(btn)
        }
        div.appendChild(actions)
    }

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

async function sendMessage() {
    const text = input.value.trim()
    if (!text) return

    input.value = ''
    sendBtn.disabled = true

    addUserMessage(text)
    const loadingMsg = addLoadingMessage()

    try {
        const response = await fetch(CHAT_URL, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ session_id: sessionId, message: text }),
        })

        if (!response.ok) throw new Error(`status ${response.status}`)
        const data = await response.json()
        loadingMsg.remove()
        addAssistantMessage(data.content)
    } catch (err) {
        loadingMsg.className = 'msg assistant'
        loadingMsg.textContent = 'Erro ao conectar com o agente.'
    }

    sendBtn.disabled = false
    input.focus()
}

function startNewChat() {
    sessionId = generateSessionId()
    localStorage.setItem('chat-session-id', sessionId)
    chat.innerHTML = ''
    closeViewer()
    input.focus()
}

sendBtn.addEventListener('click', sendMessage)

input.addEventListener('keydown', e => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault()
        sendMessage()
    }
})

newChatBtn.addEventListener('click', startNewChat)
closeViewerBtn.addEventListener('click', closeViewer)
