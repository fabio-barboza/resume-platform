import assert from 'node:assert/strict'
import { describe, it } from 'node:test'

import { BASE_URL, postJson, request, uniqueId } from '../lib/client.js'
import { assertKeys } from '../lib/shape.js'
import { parseSse, rendered } from '../lib/sse.js'

// Estes testes chamam o LLM de verdade (o chat não tem modo sem modelo). O que se verifica é
// o protocolo — formato dos frames, ordem, histórico —, nunca o texto da resposta.
const LLM_TIMEOUT = 300_000

const EVENT_KEYS = {
    start: ['session_id'],
    tool: ['name', 'status'],
    token: ['text'],
    reset: [],
    done: ['content'],
    error: ['detail'],
}

describe('/chat', () => {
    it('GET /chat/{session_id} de sessão inexistente é lista vazia, não 404', async () => {
        const session = `contrato-${uniqueId()}`
        const history = await request(`/chat/${session}`)
        assert.equal(history.status, 200)
        assert.deepEqual(history.json, { session_id: session, messages: [] })
    })

    it('POST /chat sem campo obrigatório é 422', async () => {
        for (const body of [{ session_id: 'x' }, { message: 'oi' }]) {
            const response = await postJson('/chat', body)
            assert.equal(response.status, 422, JSON.stringify(body))
            assert.ok('detail' in response.json)
        }
    })

    it('POST /chat devolve exatamente {"content": "<markdown>"}', { timeout: LLM_TIMEOUT }, async () => {
        const response = await postJson('/chat', { session_id: `contrato-${uniqueId()}`, message: 'Olá! O que você faz?' })
        assert.equal(response.status, 200, response.text)
        assertKeys(response.json, ['content'], 'POST /chat')
        assert.equal(typeof response.json.content, 'string')
        assert.ok(response.json.content.length > 0)
    })

    it('POST /chat/stream: frames, ordem e histórico persistido', { timeout: LLM_TIMEOUT }, async () => {
        const session = `contrato-${uniqueId()}`
        const question = 'Quantos currículos existem na base?'
        const response = await fetch(`${BASE_URL}/chat/stream`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
            body: JSON.stringify({ session_id: session, message: question }),
        })

        assert.equal(response.status, 200)
        assert.match(response.headers.get('content-type') ?? '', /^text\/event-stream/)
        assert.match(response.headers.get('cache-control') ?? '', /no-cache/)
        assert.equal(response.headers.get('x-accel-buffering'), 'no')

        const frames = parseSse(await response.text())
        for (const { event, data } of frames) {
            assert.ok(event in EVENT_KEYS, `evento desconhecido: ${event}`)
            assertKeys(data, EVENT_KEYS[event], `data de ${event}`)
        }
        for (const { data } of frames.filter(f => f.event === 'tool')) {
            assert.ok(['start', 'end'].includes(data.status), `tool.status: ${data.status}`)
        }

        assert.deepEqual(frames[0], { event: 'start', data: { session_id: session } })
        const last = frames.at(-1)
        assert.equal(last.event, 'done', `o turno terminou em ${JSON.stringify(last)}`)
        assert.equal(frames.filter(f => f.event === 'done' || f.event === 'error').length, 1, 'done e error são exclusivos')
        // O done é canônico, e aplicar os reset sobre os tokens tem que chegar nele.
        assert.equal(rendered(frames), last.data.content)

        const history = await request(`/chat/${session}`)
        assert.deepEqual(history.json, {
            session_id: session,
            messages: [
                { role: 'user', content: question },
                { role: 'assistant', content: last.data.content },
            ],
        })
    })
})
