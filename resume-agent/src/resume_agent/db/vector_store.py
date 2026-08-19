"""Busca semântica sobre a tabela `chunks`.

Substitui o `similarity_search` do vector store: mesma semântica (top-k por
similaridade de cosseno), agora com o metadado relacional vindo do JOIN em vez
de um blob de metadata.
"""

from langchain_core.documents import Document as LangChainDocument
from sqlalchemy import select

from resume_agent.db.engine import session
from resume_agent.db.models import Candidate, Chunk, Document
from resume_agent.infra import Model


def similarity_search(question: str, k: int = 4) -> list[LangChainDocument]:
    """Top-k chunks mais próximos da pergunta, por distância de cosseno.

    `cosine_distance` emite o operador `<=>`, que é o coberto pelo índice HNSW
    `vector_cosine_ops`. O ORDER BY usa a expressão, não o rótulo `distance`,
    para o plano continuar idêntico ao da versão em SQL.
    """
    query_embedding = Model.get_embedding_model().embed_query(question)
    distance = Chunk.embedding.cosine_distance(query_embedding)

    stmt = (
        select(
            Chunk.content,
            Chunk.page,
            Chunk.chunk_index,
            Document.id.label("document_id"),
            Document.filename,
            Candidate.id.label("candidate_id"),
            Candidate.name.label("candidate_name"),
            Candidate.email.label("candidate_email"),
            distance.label("distance"),
        )
        .join(Document, Document.id == Chunk.document_id)
        .join(Candidate, Candidate.id == Document.candidate_id)
        .order_by(distance)
        .limit(k)
    )

    with session() as sess:
        rows = sess.execute(stmt).all()

    return [
        LangChainDocument(
            page_content=row.content,
            metadata={
                "source": row.filename,
                "page": row.page,
                "chunk_index": row.chunk_index,
                "document_id": row.document_id,
                "candidate_id": row.candidate_id,
                "candidate_name": row.candidate_name,
                "candidate_email": row.candidate_email,
                "score": 1.0 - float(row.distance),
            },
        )
        for row in rows
    ]
