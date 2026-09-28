import assert from 'node:assert/strict'

// Formato exato do frame que o sse.js da webui parseia: `event: <tipo>\ndata: <json>\n\n`,
// com espaço depois dos dois-pontos e o JSON numa linha só.
const FRAME = /^event: ([a-z]+)\ndata: (.*)$/

export function parseSse(body) {
    const frames = body.split('\n\n').filter(frame => frame.trim())
    return frames.map(frame => {
        const match = FRAME.exec(frame)
        assert.ok(match, `frame SSE fora do formato: ${JSON.stringify(frame)}`)
        return { event: match[1], data: JSON.parse(match[2]) }
    })
}

/** O texto que fica na tela, aplicando os `reset` como a webui faz. */
export function rendered(frames) {
    let buffer = ''
    for (const { event, data } of frames) {
        if (event === 'reset') buffer = ''
        if (event === 'token') buffer += data.text
    }
    return buffer
}
