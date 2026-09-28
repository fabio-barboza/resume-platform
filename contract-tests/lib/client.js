// Cliente mínimo do backend sob teste. BASE_URL escolhe o alvo; a suíte não sabe (nem pode
// saber) se do outro lado está o resume-agent ou o resume-ai.
export const BASE_URL = (process.env.BASE_URL ?? 'http://localhost:8000').replace(/\/$/, '')

export async function request(path, options = {}) {
    const response = await fetch(`${BASE_URL}${path}`, options)
    const text = await response.text()
    let json
    try {
        json = text ? JSON.parse(text) : undefined
    } catch {
        json = undefined
    }
    return { status: response.status, headers: response.headers, text, json }
}

export function postJson(path, body) {
    return request(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    })
}

export function putJson(path, body) {
    return request(path, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    })
}

/** Multipart com um ou mais arquivos no mesmo campo, como o upload do Swagger. */
export function upload(method, path, field, files) {
    const form = new FormData()
    for (const { name, bytes, type = 'application/pdf' } of files) {
        form.append(field, new Blob([bytes], { type }), name)
    }
    return request(path, { method, body: form })
}

export async function download(path) {
    const response = await fetch(`${BASE_URL}${path}`)
    return {
        status: response.status,
        headers: response.headers,
        bytes: new Uint8Array(await response.arrayBuffer()),
    }
}

/** Qual backend respondeu — só para o log; nenhuma asserção pode depender disso. */
export async function backendName() {
    const spec = await request('/openapi.json')
    return spec.json?.info?.title ?? 'desconhecido'
}

export const uniqueId = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 8)}`
