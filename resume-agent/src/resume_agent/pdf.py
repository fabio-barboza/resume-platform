"""Leitura e fatiamento de PDF.

Puro processamento de texto: não conhece banco, LLM nem HTTP. A estratégia de
chunking (tamanho, overlap) e a de ID determinístico vêm do antigo
`ingest.py` e não mudaram — só passaram a ser chamáveis a partir do serviço.
"""

import hashlib
from collections import defaultdict
from dataclasses import dataclass
from io import BytesIO

from langchain_core.documents import Document
from langchain_text_splitters import RecursiveCharacterTextSplitter
from pypdf import PdfReader

CHUNK_SIZE = 2000
CHUNK_OVERLAP = 500


@dataclass(frozen=True)
class Chunk:
    """Um pedaço de currículo pronto para virar linha na tabela `chunks`."""

    page: int
    chunk_index: int
    content: str

    def id_for(self, document_id: int) -> str:
        """ID determinístico `{document_id}-p{página}-c{índice}`.

        Ancorado no documento, não no nome do arquivo: o PUT troca o arquivo
        mas preserva o `document_id`, e reprocessar o mesmo conteúdo precisa
        gerar exatamente as mesmas chaves para o upsert ser idempotente.
        """
        return f"{document_id}-p{self.page}-c{self.chunk_index}"


def file_hash(content: bytes) -> str:
    """SHA-256 do arquivo. Chave de deduplicação antes de qualquer processamento."""
    return hashlib.sha256(content).hexdigest()


def read_pages(content: bytes) -> list[str]:
    """Texto de cada página, na ordem. Página sem texto extraível vira ''."""
    reader = PdfReader(BytesIO(content))
    return [(page.extract_text() or "") for page in reader.pages]


def first_pages_text(pages: list[str], limit: int) -> str:
    """Texto das primeiras `limit` páginas — entrada da extração de dados."""
    return "\n\n".join(pages[:limit]).strip()


def split_pages(pages: list[str], source: str) -> list[Document]:
    documents = [
        Document(page_content=text, metadata={"source": source, "page": page_number})
        for page_number, text in enumerate(pages)
        if text.strip()
    ]
    splitter = RecursiveCharacterTextSplitter(
        chunk_size=CHUNK_SIZE,
        chunk_overlap=CHUNK_OVERLAP,
        length_function=len,
    )
    return splitter.split_documents(documents)


def build_chunks(pages: list[str], source: str) -> list[Chunk]:
    """Fatiamento das páginas, com `chunk_index` reiniciado a cada página.

    O ID só é formado na gravação (`Chunk.id_for`), quando o `document_id` já
    existe — o conteúdo dos chunks não depende dele, então dá para calcular os
    embeddings antes de abrir a transação.
    """
    counters: defaultdict[tuple[str, int], int] = defaultdict(int)
    chunks = []
    for doc in split_pages(pages, source):
        page = doc.metadata["page"]
        key = (doc.metadata["source"], page)
        chunk_index = counters[key]
        counters[key] += 1
        chunks.append(
            Chunk(page=page, chunk_index=chunk_index, content=doc.page_content)
        )
    return chunks
