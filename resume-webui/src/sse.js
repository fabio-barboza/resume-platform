// Leitor de SSE sobre `fetch` — o `EventSource` do navegador é GET-only e não
// manda body, então não serve para `POST /chat/stream`.

/** Erro de status HTTP não-2xx — `main.js` usa `.status` para decidir o fallback. */
export class SseHttpError extends Error {
    constructor(status) {
        super(`status ${status}`)
        this.status = status
    }
}

/**
 * Consome um endpoint SSE via POST e devolve um gerador assíncrono de
 * `{ event, data }`, um por frame (`\n\n`) recebido.
 *
 * @param {string} url
 * @param {object} body
 * @param {{ signal?: AbortSignal }} [options]
 */
export async function* readSse(url, body, { signal } = {}) {
    const response = await fetch(url, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Accept: 'text/event-stream',
        },
        body: JSON.stringify(body),
        signal,
    })

    if (!response.ok) {
        throw new SseHttpError(response.status)
    }

    if (!response.body) {
        throw new Error('stream-unsupported')
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    try {
        while (true) {
            const { done, value } = await reader.read()
            if (done) break

            // `{ stream: true }` é obrigatório: sem isso, caractere multibyte
            // (acento) cortado entre dois chunks TCP vira `�`.
            buffer += decoder.decode(value, { stream: true })

            let sepIdx
            while ((sepIdx = buffer.indexOf('\n\n')) !== -1) {
                const frame = buffer.slice(0, sepIdx)
                buffer = buffer.slice(sepIdx + 2)
                const parsed = parseFrame(frame)
                if (parsed) yield parsed
            }
        }
    } finally {
        reader.releaseLock()
    }
}

function parseFrame(frame) {
    let event = 'message'
    const dataLines = []

    for (const line of frame.split('\n')) {
        if (!line || line.startsWith(':')) continue
        if (line.startsWith('event:')) {
            event = line.slice('event:'.length).trim()
        } else if (line.startsWith('data:')) {
            dataLines.push(line.slice('data:'.length).trim())
        }
    }

    if (!dataLines.length) return null
    return { event, data: JSON.parse(dataLines.join('\n')) }
}
