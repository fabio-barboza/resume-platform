"""Endpoint de cadastro do candidato. Router fino."""

from fastapi import APIRouter, Path, Response

from resume_agent.api.schemas.candidate import (
    CandidateReplaceRequest,
    CandidateSummary,
)
from resume_agent.api.schemas.error import ErrorResponse
from resume_agent.services import candidate_service

router = APIRouter(prefix="/candidates", tags=["Candidatos"])


@router.put(
    "/{candidate_id}",
    response_model=CandidateSummary,
    summary="Substituir o cadastro do candidato por inteiro",
    description=(
        "Substituição total: campo ausente no body vira null. Não toca em "
        "chunks nem em embeddings — a contagem de chunks do candidato não "
        "muda. Atenção: uma nova ingestão de currículo sobrescreve estes "
        "dados, porque o arquivo é a fonte da verdade do cadastro."
    ),
    responses={
        404: {"model": ErrorResponse, "description": "Candidato inexistente."},
        409: {
            "model": ErrorResponse,
            "description": "O email já pertence a outro candidato.",
        },
    },
)
def replace_candidate(
    payload: CandidateReplaceRequest,
    candidate_id: int = Path(description="ID do candidato."),
) -> CandidateSummary:
    updated = candidate_service.replace_candidate(
        candidate_id, payload.name, payload.email, payload.phone
    )
    return CandidateSummary.from_row(updated)


@router.get(
    "/{identifier}/resume",
    summary="Baixar o PDF do currículo do candidato",
    description=(
        "Aceita o ID numérico ou o email do candidato. Se ele tiver mais de "
        "um currículo vinculado, devolve o mais recente."
    ),
    responses={
        200: {
            "content": {"application/pdf": {}},
            "description": "PDF do currículo.",
        },
        404: {
            "model": ErrorResponse,
            "description": "Candidato, currículo ou arquivo inexistente.",
        },
    },
)
def download_resume(
    identifier: str = Path(description="ID numérico ou email do candidato."),
) -> Response:
    content, filename = candidate_service.get_resume_file(identifier)
    return Response(
        content=content,
        media_type="application/pdf",
        headers={"Content-Disposition": f'attachment; filename="{filename}"'},
    )
