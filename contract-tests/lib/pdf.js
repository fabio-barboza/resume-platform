// PDF mínimo montado byte a byte (tabela xref válida), com `text` como único conteúdo da
// página. Mesmo formato do build_pdf do conftest do resume-agent: pypdf e PDFBox extraem o
// texto dele, então os dois backends conseguem ingerir. Sem acento e sem parênteses no texto.
export function buildPdf(text) {
    const content = `BT /F1 12 Tf 72 712 Td (${text}) Tj ET`
    const objects = [
        '<< /Type /Catalog /Pages 2 0 R >>',
        '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
        '<< /Type /Page /Parent 2 0 R /Resources << /Font << /F1 4 0 R >> >> ' +
            '/MediaBox [0 0 612 792] /Contents 5 0 R >>',
        '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
        `<< /Length ${content.length} >>\nstream\n${content}\nendstream`,
    ]

    let pdf = '%PDF-1.4\n'
    const offsets = []
    objects.forEach((object, index) => {
        offsets.push(Buffer.byteLength(pdf, 'latin1'))
        pdf += `${index + 1} 0 obj\n${object}\nendobj\n`
    })
    const xrefOffset = Buffer.byteLength(pdf, 'latin1')
    const size = objects.length + 1
    pdf += `xref\n0 ${size}\n0000000000 65535 f \n`
    for (const offset of offsets) {
        pdf += `${String(offset).padStart(10, '0')} 00000 n \n`
    }
    pdf += `trailer\n<< /Size ${size} /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF`
    return new Uint8Array(Buffer.from(pdf, 'latin1'))
}
