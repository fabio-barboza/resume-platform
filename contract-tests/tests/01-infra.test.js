import assert from 'node:assert/strict'
import { before, describe, it } from 'node:test'

import { BASE_URL, backendName, request } from '../lib/client.js'
import { assertKeys } from '../lib/shape.js'

describe('infra: health, Swagger e CORS', () => {
    before(async () => {
        const health = await request('/health').catch(() => null)
        assert.ok(health, `nenhum backend respondendo em ${BASE_URL} — suba com ./start.sh (--python ou --java)`)
        console.log(`# backend sob teste: ${await backendName()} em ${BASE_URL}`)
    })

    it('GET /health devolve exatamente {"status": "ok"}', async () => {
        const response = await request('/health')
        assert.equal(response.status, 200)
        assert.deepEqual(response.json, { status: 'ok' })
    })

    // O prompt do agente manda o usuário abrir o Swagger nesse endereço.
    it('Swagger em /docs e especificação em /openapi.json', async () => {
        const docs = await request('/docs')
        assert.equal(docs.status, 200)
        assert.match(docs.headers.get('content-type') ?? '', /text\/html/)

        const spec = await request('/openapi.json')
        assert.equal(spec.status, 200)
        assertKeys(spec.json.paths['/health'] ?? {}, ['get'], '/health no openapi')
    })

    it('a especificação publica todas as rotas, com os mesmos nomes de parâmetro', async () => {
        const { paths } = (await request('/openapi.json')).json
        const expected = {
            '/health': ['get'],
            '/resumes': ['get', 'post'],
            '/resumes/{document_id}': ['delete', 'get', 'put'],
            '/candidates/{candidate_id}': ['put'],
            '/candidates/{identifier}/resume': ['get'],
            '/chat': ['post'],
            '/chat/stream': ['post'],
            '/chat/{session_id}': ['get'],
        }
        for (const [path, methods] of Object.entries(expected)) {
            assert.ok(paths[path], `rota ausente no openapi: ${path}`)
            assert.deepEqual(Object.keys(paths[path]).sort(), methods, `métodos de ${path}`)
        }
    })

    // A webui roda em outra porta (Vite, :5173).
    it('CORS aberto em request simples e em preflight', async () => {
        const simple = await request('/health', { headers: { Origin: 'http://localhost:5173' } })
        assert.equal(simple.headers.get('access-control-allow-origin'), '*')

        const preflight = await request('/chat/stream', {
            method: 'OPTIONS',
            headers: {
                Origin: 'http://localhost:5173',
                'Access-Control-Request-Method': 'POST',
                'Access-Control-Request-Headers': 'content-type',
            },
        })
        assert.equal(preflight.status, 200)
        assert.equal(preflight.headers.get('access-control-allow-origin'), '*')
        assert.match(preflight.headers.get('access-control-allow-methods') ?? '', /POST|\*/)
    })
})
