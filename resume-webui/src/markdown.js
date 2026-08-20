// Render incremental fence-aware. `marked.parse()` sobre markdown pela metade
// renderiza lixo: tabela sem separador vira parágrafo, fence sem fechamento
// engole o resto da resposta, link de PDF cortado vira texto cru — ou pior,
// um botão "Ver currículo" apontando para lugar nenhum.
//
// A solução é separar o buffer em prefixo estável (já dá para renderizar) e
// cauda pendente (ainda em construção, mostrada como texto puro).

import { marked } from 'marked'
import Chart from 'chart.js/auto'

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
// Qualquer fence (```chart, ```json ou sem tag) cujo corpo seja um spec de
// gráfico vira gráfico: o modelo erra a tag com frequência e o usuário não
// tem culpa. Fence com tag `chart` é aceita mesmo sem `data` reconhecível —
// aí quem decide é o `normalizeSpec`/fallback de tabela.
const ANY_FENCE_RE = /^[ \t]*(`{3,})[ \t]*([^\r\n`]*)\r?\n([\s\S]*?)^[ \t]*\1[ \t]*$/gim

// O modelo às vezes esquece a fence e cospe o JSON cru. Em vez de mostrar o
// objeto na tela, reconhecemos um bloco `{ ... }` no início de linha que
// pareça um spec de gráfico.
const BARE_SPEC_RE = /^[ \t]*\{[\s\S]*?^[ \t]*\}[ \t]*$/gim

function looksLikeChartSpec(spec) {
    return (
        typeof spec === 'object' &&
        spec !== null &&
        Array.isArray(spec.data) &&
        spec.data.some(row => row && typeof row === 'object' && 'label' in row && 'value' in row)
    )
}

function parseSpec(body) {
    try {
        return JSON.parse(body)
    } catch {
        return null
    }
}

/**
 * Troca cada bloco de gráfico por um placeholder `<div data-chart-id>` e
 * devolve o markdown resultante junto com os specs já parseados. JSON
 * inválido (resposta de LLM não é entrada confiável) não derruba a
 * mensagem: o bloco original fica intacto e vira código na tela.
 */
export function extractCharts(markdown) {
    const charts = []

    const placeholder = spec => {
        const id = `chart-${charts.length}`
        charts.push({ id, spec })
        return `\n\n<div class="chart-placeholder" data-chart-id="${id}"></div>\n\n`
    }

    let text = (markdown ?? '').replace(ANY_FENCE_RE, (match, _ticks, info, body) => {
        const spec = parseSpec(body)
        if (!spec) return match
        const isChartTag = info.trim().toLowerCase() === 'chart'
        if (!isChartTag && !looksLikeChartSpec(spec)) return match
        return placeholder(spec)
    })

    text = text.replace(BARE_SPEC_RE, match => {
        const spec = parseSpec(match)
        return spec && looksLikeChartSpec(spec) ? placeholder(spec) : match
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
 * Caminho de spec inválido: a tabela dos dados brutos.
 */
export function renderChartFallback(spec, container) {
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

const CHART_TYPES = ['bar', 'line', 'pie', 'doughnut']

const CHART_COLORS = [
    'rgba(99, 102, 241, 0.85)',
    'rgba(34, 211, 238, 0.85)',
    'rgba(245, 158, 11, 0.85)',
    'rgba(239, 68, 68, 0.85)',
    'rgba(139, 92, 246, 0.85)',
    'rgba(236, 72, 153, 0.85)',
    'rgba(20, 184, 166, 0.85)',
    'rgba(249, 115, 22, 0.85)',
]

const CHART_COLORS_BORDER = CHART_COLORS.map(c => c.replace('0.85', '1'))

// Instâncias vivas — permite recolorir no toggle de tema (`refreshCharts`) e
// destruir tudo ao trocar de conversa (`destroyCharts`), evitando vazamento.
const chartRegistry = []

function cssVar(name) {
    return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}

function chartThemeColors() {
    return {
        tick: cssVar('--chart-tick'),
        grid: cssVar('--chart-grid'),
        legend: cssVar('--chart-legend'),
    }
}

function applyChartTheme(chart) {
    const c = chartThemeColors()
    const opts = chart.options
    if (opts.plugins?.legend?.labels) opts.plugins.legend.labels.color = c.legend
    if (opts.scales) {
        for (const key of Object.keys(opts.scales)) {
            const scale = opts.scales[key]
            if (scale.ticks) scale.ticks.color = c.tick
            if (scale.grid) scale.grid.color = c.grid
        }
    }
    chart.update('none')
}

/** Recolore todos os gráficos vivos — chamado após o toggle de tema. */
export function refreshCharts() {
    chartRegistry.forEach(applyChartTheme)
}

/** Destroi todas as instâncias vivas e zera o registro — chamado em "Novo chat". */
export function destroyCharts() {
    chartRegistry.forEach(chart => chart.destroy())
    chartRegistry.length = 0
}

// Spec de LLM não é entrada confiável: objeto com `data` não vazio e ao
// menos um `value` numérico. `value` não numérico descarta o ponto (não vira
// `0`); sobrando zero ponto válido, spec é inválido.
function normalizeSpec(spec) {
    if (typeof spec !== 'object' || spec === null || !Array.isArray(spec.data)) return null

    const points = spec.data
        .map(row => ({ label: String(row?.label ?? ''), value: row?.value }))
        .filter(row => typeof row.value === 'number' && Number.isFinite(row.value))

    if (!points.length) return null

    const type = CHART_TYPES.includes(spec.type) ? spec.type : 'bar'
    return { type, title: spec.title ? String(spec.title) : '', points }
}

/**
 * Único ponto de extensão para desenhar o gráfico de verdade. Spec inválido
 * cai no fallback de tabela (`renderChartFallback`).
 */
export function renderChart(spec, container) {
    const normalized = normalizeSpec(spec)
    if (!normalized) return renderChartFallback(spec, container)

    const { type, title, points } = normalized
    const isPie = type === 'pie' || type === 'doughnut'
    const theme = chartThemeColors()

    container.innerHTML = `
        <div class="chart-card">
            ${title ? `<p class="render-title">${escapeHtml(title)}</p>` : ''}
            <div class="chart-box"><canvas></canvas></div>
        </div>
    `
    const canvas = container.querySelector('canvas')

    const dataset = {
        label: title || undefined,
        data: points.map(p => p.value),
        backgroundColor: isPie
            ? points.map((_, i) => CHART_COLORS[i % CHART_COLORS.length])
            : CHART_COLORS[0],
        borderColor: isPie
            ? points.map((_, i) => CHART_COLORS_BORDER[i % CHART_COLORS_BORDER.length])
            : CHART_COLORS_BORDER[0],
        borderWidth: 1,
        borderRadius: type === 'bar' ? 4 : 0,
    }

    const chart = new Chart(canvas, {
        type,
        data: { labels: points.map(p => p.label), datasets: [dataset] },
        options: {
            responsive: true,
            maintainAspectRatio: false,
            plugins: {
                legend: { labels: { color: theme.legend } },
            },
            scales: isPie
                ? {}
                : {
                      x: { ticks: { color: theme.tick }, grid: { color: theme.grid } },
                      y: { ticks: { color: theme.tick }, grid: { color: theme.grid } },
                  },
        },
    })

    chartRegistry.push(chart)
}
