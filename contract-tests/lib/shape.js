import assert from 'node:assert/strict'

// O contrato inclui o conjunto exato de chaves: campo a mais ou a menos é divergência entre
// as versões, e campo nulo continua presente como `null` (é o que o Pydantic serializa).
export function assertKeys(object, expected, label) {
    assert.ok(object && typeof object === 'object', `${label}: esperava objeto, veio ${JSON.stringify(object)}`)
    assert.deepEqual(Object.keys(object).sort(), [...expected].sort(), `${label}: chaves divergentes`)
}

export function assertTimestamp(value, label) {
    assert.equal(typeof value, 'string', `${label}: timestamp deveria ser string`)
    assert.ok(!Number.isNaN(Date.parse(value)), `${label}: timestamp ilegível: ${value}`)
}

export const CANDIDATE_KEYS = ['id', 'name', 'email', 'phone', 'created_at']
export const RESUME_KEYS = [
    'document_id', 'filename', 'file_hash', 'pages', 'status', 'ingested_at', 'chunk_count', 'candidate',
]
export const INGESTION_KEYS = [
    'filename', 'document_id', 'candidate_id', 'status', 'chunk_count', 'duplicate', 'error',
]

export function assertCandidate(candidate, label) {
    assertKeys(candidate, CANDIDATE_KEYS, label)
    assert.equal(typeof candidate.id, 'number', `${label}.id`)
    for (const field of ['name', 'email', 'phone']) {
        assert.ok(candidate[field] === null || typeof candidate[field] === 'string', `${label}.${field}`)
    }
    assertTimestamp(candidate.created_at, `${label}.created_at`)
}

export function assertResume(resume, label) {
    assertKeys(resume, RESUME_KEYS, label)
    assert.equal(typeof resume.document_id, 'number')
    assert.match(resume.file_hash, /^[0-9a-f]{64}$/, `${label}.file_hash não é SHA-256`)
    assert.ok(Number.isInteger(resume.pages) && resume.pages > 0, `${label}.pages`)
    assert.ok(['ingested', 'pending_review'].includes(resume.status), `${label}.status: ${resume.status}`)
    assertTimestamp(resume.ingested_at, `${label}.ingested_at`)
    assert.ok(Number.isInteger(resume.chunk_count), `${label}.chunk_count`)
    assertCandidate(resume.candidate, `${label}.candidate`)
}

/** Erro de domínio: status e corpo `{"detail": "<mensagem>"}`, só essa chave. */
export function assertDomainError(response, status, label) {
    assert.equal(response.status, status, `${label}: status`)
    assertKeys(response.json, ['detail'], `${label}: corpo de erro`)
    assert.equal(typeof response.json.detail, 'string', `${label}: detail deveria ser texto`)
}
