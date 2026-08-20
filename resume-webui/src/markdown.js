// Render incremental fence-aware. `marked.parse()` sobre markdown pela metade
// renderiza lixo: tabela sem separador vira parágrafo, fence sem fechamento
// engole o resto da resposta, link de PDF cortado vira texto cru — ou pior,
// um botão "Ver currículo" apontando para lugar nenhum.
//
// A solução é separar o buffer em prefixo estável (já dá para renderizar) e
// cauda pendente (ainda em construção, mostrada como texto puro).

import { marked } from 'marked'

/**
 * @param {string} text
 * @returns {{ stable: string, pending: string }}
 */
export function splitStable(text) {
    const fenceIdx = findOpenFenceIndex(text)
    if (fenceIdx !== null) {
        return { stable: text.slice(0, fenceIdx), pending: text.slice(fenceIdx) }
    }

    const tableIdx = findOpenTableIndex(text)
    if (tableIdx !== null) {
        return { stable: text.slice(0, tableIdx), pending: text.slice(tableIdx) }
    }

    if (!text.endsWith('\n')) {
        const lineStart = text.lastIndexOf('\n') + 1
        if (lineStart < text.length) {
            return { stable: text.slice(0, lineStart), pending: text.slice(lineStart) }
        }
    }

    return { stable: text, pending: '' }
}

// Regra 1: fence aberta. Conta ``` no início de linha; se for ímpar, o
// bloco em construção (a partir da fence de abertura) inteiro vai para
// `pending` — é o que garante que um bloco ```chart só é processado com o
// dado inteiro.
function findOpenFenceIndex(text) {
    const lines = text.split('\n')
    let offset = 0
    let count = 0
    let openOffset = null

    for (const line of lines) {
        if (/^```/.test(line)) {
            count++
            openOffset = count % 2 === 1 ? offset : null
        }
        offset += line.length + 1
    }

    return count % 2 === 1 ? openOffset : null
}

// Regra 2: tabela em construção. Se as últimas linhas começam com `|` e
// ainda não existe linha em branco depois delas, o bloco inteiro vai para
// `pending`.
function findOpenTableIndex(text) {
    const lines = text.split('\n')
    const offsets = []
    let offset = 0
    for (const line of lines) {
        offsets.push(offset)
        offset += line.length + 1
    }

    let start = null
    for (let i = lines.length - 1; i >= 0; i--) {
        const line = lines[i]
        if (line.trim() === '') break
        if (line.startsWith('|')) {
            start = i
        } else {
            break
        }
    }

    return start === null ? null : offsets[start]
}

function escapeHtml(str) {
    return str
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;')
}

/** Caminho do meio do stream: `stable` vira markdown, `pending` entra como texto escapado. */
export function renderStream(text) {
    const { stable, pending } = splitStable(text)
    const html = marked.parse(stable)
    if (!pending) return html
    return `${html}<span class="pending">${escapeHtml(pending)}</span>`
}

// Gráfico trafega como fence ```chart contendo um JSON. Fence é a única
// construção de markdown com delimitador de fechamento explícito, então o
// parser incremental sabe com certeza quando o dado acabou — e a regra 1 do
// `splitStable` já garante que nenhum ponto é plotado antes do fence fechar.
const CHART_FENCE_RE = /```chart\r?\n([\s\S]*?)```/g

/**
 * Troca cada bloco ```chart``` por um placeholder `<div data-chart-id>` e
 * devolve o markdown resultante junto com os specs já parseados. JSON
 * inválido (resposta de LLM não é entrada confiável) não derruba a
 * mensagem: o bloco original fica intacto e vira código na tela.
 */
export function extractCharts(markdown) {
    const charts = []
    const text = (markdown ?? '').replace(CHART_FENCE_RE, (match, body) => {
        let spec
        try {
            spec = JSON.parse(body)
        } catch {
            return match
        }
        const id = `chart-${charts.length}`
        charts.push({ id, spec })
        return `\n\n<div class="chart-placeholder" data-chart-id="${id}"></div>\n\n`
    })
    return { markdown: text, charts }
}

/**
 * Caminho do `done`: markdown completo, sem cauda. Único lugar que chama
 * `extractCharts` — `renderStream` nunca desenha gráfico.
 */
export function renderFinal(text) {
    const { markdown, charts } = extractCharts(text)
    return { html: marked.parse(markdown), charts }
}

/**
 * Único ponto de extensão para desenhar o gráfico de verdade — escolher
 * biblioteca é decisão separada da de streaming. Implementação provisória:
 * a tabela dos dados.
 */
export function renderChart(spec, container) {
    const rows = Array.isArray(spec?.data) ? spec.data : []
    const title = spec?.title ? `<p class="chart-title">${escapeHtml(String(spec.title))}</p>` : ''
    const rowsHtml = rows
        .map(
            row =>
                `<tr><td>${escapeHtml(String(row?.label ?? ''))}</td><td>${escapeHtml(String(row?.value ?? ''))}</td></tr>`
        )
        .join('')

    container.innerHTML = `
        <div class="chart-fallback">
            ${title}
            <table class="chart-fallback-table"><tbody>${rowsHtml}</tbody></table>
        </div>
    `
}
