import assert from 'node:assert/strict'
import { after, before, describe, it } from 'node:test'

import { download, putJson, request, uniqueId, upload } from '../lib/client.js'
import { buildPdf } from '../lib/pdf.js'
import {
    INGESTION_KEYS, assertCandidate, assertDomainError, assertKeys, assertResume,
} from '../lib/shape.js'

// Currículos sintéticos com um token por execução: a deduplicação é por hash do arquivo, e
// um arquivo igual ao de uma rodada anterior viraria `duplicate` em vez de ingestão nova.
// Precisam de email: sem LLM de extração no ar, é o fallback por regex que os identifica.
const run = uniqueId()
const emailA = `contrato.a.${run}@example.com`
const emailB = `contrato.b.${run}@example.com`
const pdfA = buildPdf(`Curriculo de contrato A ${run}, contato ${emailA}, telefone 11 91234-5678`)
const pdfB = buildPdf(`Curriculo de contrato B ${run}, contato ${emailB}`)
const fileA = `contrato-a-${run}.pdf`

const MISSING = 999999999

describe('/resumes e /candidates: ciclo de vida completo', { timeout: 300_000 }, () => {
    let docA
    let docB
    let uploadResults

    before(async () => {
        const created = await upload('POST', '/resumes', 'files', [
            { name: fileA, bytes: pdfA },
            { name: 'nao-e-pdf.pdf', bytes: new TextEncoder().encode('isto não é um PDF') },
        ])
        assert.equal(created.status, 201, created.text)
        assertKeys(created.json, ['results'], 'POST /resumes')
        uploadResults = created.json.results
        docA = uploadResults[0]

        const second = await upload('POST', '/resumes', 'files', [{ name: `contrato-b-${run}.pdf`, bytes: pdfB }])
        docB = second.json.results[0]
    })

    after(async () => {
        for (const doc of [docA, docB]) {
            if (doc?.document_id) await request(`/resumes/${doc.document_id}`, { method: 'DELETE' })
        }
    })

    it('POST /resumes: um resultado por arquivo, na ordem, com o formato exato', () => {
        const [ok, failed] = uploadResults
        assertKeys(ok, INGESTION_KEYS, 'resultado ingerido')
        assert.equal(ok.filename, fileA)
        assert.equal(ok.status, 'ingested')
        assert.equal(ok.duplicate, false)
        assert.equal(ok.error, null, 'campo nulo continua presente como null')
        assert.ok(ok.chunk_count > 0)
        assert.equal(typeof ok.document_id, 'number')
        assert.equal(typeof ok.candidate_id, 'number')

        // A falha de um arquivo não derruba os demais.
        assertKeys(failed, INGESTION_KEYS, 'resultado com falha')
        assert.equal(failed.status, 'failed')
        assert.equal(failed.document_id, null)
        assert.equal(failed.candidate_id, null)
        assert.equal(failed.chunk_count, 0)
        assert.equal(typeof failed.error, 'string')
    })

    it('reenviar o mesmo arquivo é no-op com duplicate: true', async () => {
        const again = await upload('POST', '/resumes', 'files', [{ name: fileA, bytes: pdfA }])
        assert.equal(again.status, 201)
        const [result] = again.json.results
        assert.equal(result.duplicate, true)
        assert.equal(result.document_id, docA.document_id)
        assert.equal(result.candidate_id, docA.candidate_id)
    })

    it('GET /resumes/{document_id}: documento com o candidato embutido', async () => {
        const detail = await request(`/resumes/${docA.document_id}`)
        assert.equal(detail.status, 200)
        assertResume(detail.json, 'GET /resumes/{id}')
        assert.equal(detail.json.document_id, docA.document_id)
        assert.equal(detail.json.filename, fileA)
        assert.equal(detail.json.chunk_count, docA.chunk_count)
        assert.equal(detail.json.candidate.id, docA.candidate_id)
        assert.equal(detail.json.candidate.email, emailA)
    })

    it('GET /resumes: total e itens paginados', async () => {
        const list = await request('/resumes?limit=500&offset=0')
        assert.equal(list.status, 200)
        assertKeys(list.json, ['total', 'items'], 'GET /resumes')
        assert.ok(list.json.total >= 2)
        const mine = list.json.items.find(item => item.document_id === docA.document_id)
        assert.ok(mine, 'o documento recém-ingerido não está no inventário')
        assertResume(mine, 'item do inventário')

        const page = await request('/resumes?limit=1&offset=1')
        assert.equal(page.json.items.length, 1)
        assert.equal(page.json.total, list.json.total)
    })

    it('GET /resumes com paginação inválida é 422', async () => {
        for (const query of ['limit=0', 'limit=501', 'offset=-1']) {
            const response = await request(`/resumes?${query}`)
            assert.equal(response.status, 422, query)
            assert.ok('detail' in response.json, `${query}: corpo sem detail`)
        }
    })

    it('GET /candidates/{id}/resume e /candidates/{email}/resume devolvem o PDF enviado', async () => {
        for (const identifier of [docA.candidate_id, emailA]) {
            const pdf = await download(`/candidates/${encodeURIComponent(identifier)}/resume`)
            assert.equal(pdf.status, 200, `download por ${identifier}`)
            assert.equal(pdf.headers.get('content-type'), 'application/pdf')
            assert.equal(pdf.headers.get('content-disposition'), `attachment; filename="${fileA}"`)
            assert.deepEqual(pdf.bytes, pdfA, 'o arquivo baixado não é o enviado')
        }
    })

    it('PUT /resumes/{id} com o mesmo arquivo é no-op', async () => {
        const same = await upload('PUT', `/resumes/${docA.document_id}`, 'file', [{ name: fileA, bytes: pdfA }])
        assert.equal(same.status, 200)
        assertKeys(same.json, INGESTION_KEYS, 'PUT /resumes/{id}')
        assert.equal(same.json.duplicate, true)
        assert.equal(same.json.document_id, docA.document_id)
    })

    it('PUT /resumes/{id} com o arquivo de outro documento é 409', async () => {
        const clash = await upload('PUT', `/resumes/${docA.document_id}`, 'file', [{ name: 'b.pdf', bytes: pdfB }])
        assertDomainError(clash, 409, 'PUT com arquivo de outro documento')
    })

    it('PUT /candidates/{id} com email de outro candidato é 409', async () => {
        const clash = await putJson(`/candidates/${docA.candidate_id}`, { name: 'X', email: emailB, phone: null })
        assertDomainError(clash, 409, 'PUT /candidates com email duplicado')
    })

    it('PUT /candidates/{id} substitui por inteiro: campo ausente vira null', async () => {
        const replaced = await putJson(`/candidates/${docA.candidate_id}`, { name: 'Contrato Renomeado' })
        assert.equal(replaced.status, 200)
        assertCandidate(replaced.json, 'PUT /candidates/{id}')
        assert.equal(replaced.json.id, docA.candidate_id)
        assert.equal(replaced.json.name, 'Contrato Renomeado')
        assert.equal(replaced.json.email, null)
        assert.equal(replaced.json.phone, null)
    })

    it('recursos inexistentes são 404 com {"detail"}', async () => {
        assertDomainError(await request(`/resumes/${MISSING}`), 404, 'GET /resumes/{id}')
        assertDomainError(await request(`/resumes/${MISSING}`, { method: 'DELETE' }), 404, 'DELETE /resumes/{id}')
        assertDomainError(await putJson(`/candidates/${MISSING}`, { name: 'x' }), 404, 'PUT /candidates/{id}')
        assertDomainError(await request(`/candidates/${MISSING}/resume`), 404, 'GET /candidates/{id}/resume')
        assertDomainError(
            await upload('PUT', `/resumes/${MISSING}`, 'file', [{ name: 'x.pdf', bytes: buildPdf(`x ${run}`) }]),
            404,
            'PUT /resumes/{id}',
        )
    })

    it('DELETE /resumes/{id} é 204 sem corpo e remove o documento', async () => {
        const deleted = await request(`/resumes/${docA.document_id}`, { method: 'DELETE' })
        assert.equal(deleted.status, 204)
        assert.equal(deleted.text, '')
        assertDomainError(await request(`/resumes/${docA.document_id}`), 404, 'GET depois do DELETE')
        // O candidato ficou sem currículo e sai junto.
        assertDomainError(await request(`/candidates/${docA.candidate_id}/resume`), 404, 'candidato órfão')
        docA = null
    })
})
