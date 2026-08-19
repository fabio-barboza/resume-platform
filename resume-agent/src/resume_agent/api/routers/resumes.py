"""Endpoints de currículo. Router fino: só traduz HTTP <-> serviço."""

import logging

from fastapi import APIRouter, File, Path, Query, UploadFile, status

from resume_agent.api.schemas.error import ErrorResponse
from resume_agent.api.schemas.resume import (
    ResumeIngestionResponse,
    ResumeIngestionResult,
    ResumeListResponse,
    ResumeSummary,
)
from resume_agent.services import document_service, ingestion_service
from resume_agent.services.errors import DomainError

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/resumes", tags=["Currículos"])


@router.post(
    "",
    response_model=ResumeIngestionResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Ingerir um ou mais currículos",
    description=(
        "Extrai nome, email e telefone do próprio arquivo e grava candidato, "
        "documento e chunks. Cada arquivo é processado de forma independente: "
        "a falha de um não interrompe os demais. Reenviar um arquivo já "
        "ingerido é no-op (`duplicate: true`)."
    ),
)
def ingest_resumes(
    files: list[UploadFile] = File(description="Um ou mais currículos em PDF."),
) -> ResumeIngestionResponse:
    results = []
    for upload in files:
        filename = upload.filename or "sem-nome.pdf"
        try:
            outcome = ingestion_service.ingest_resume(filename, upload.file.read())
            results.append(
                ResumeIngestionResult(
                    filename=outcome.filename,
                    document_id=outcome.document_id,
                    candidate_id=outcome.candidate_id,
                    status=outcome.status,
                    chunk_count=outcome.chunk_count,
                    duplicate=outcome.duplicate,
                )
            )
        except DomainError as exc:
            results.append(
                ResumeIngestionResult(
                    filename=filename, status="failed", error=str(exc)
                )
            )
        except Exception as exc:
            logger.exception("Falha inesperada ao ingerir '%s'", filename)
            results.append(
                ResumeIngestionResult(
                    filename=filename, status="failed", error=str(exc)
                )
            )
    return ResumeIngestionResponse(results=results)


@router.put(
    "/{document_id}",
    response_model=ResumeIngestionResult,
    summary="Substituir um currículo por inteiro",
    description=(
        "Troca o arquivo preservando o `document_id`: remove os chunks "
        "antigos, reingere e reexecuta a extração. O cadastro do candidato "
        "passa a valer exatamente o que foi extraído do novo arquivo. "
        "Reenviar o mesmo arquivo é no-op."
    ),
    responses={
        404: {"model": ErrorResponse, "description": "Documento inexistente."},
        409: {
            "model": ErrorResponse,
            "description": "O arquivo enviado já pertence a outro documento.",
        },
        422: {"model": ErrorResponse, "description": "PDF ilegível ou sem texto."},
    },
)
def replace_resume(
    document_id: int = Path(description="ID do documento a substituir."),
    file: UploadFile = File(description="Novo currículo em PDF."),
) -> ResumeIngestionResult:
    outcome = ingestion_service.replace_resume(
        document_id, file.filename or "sem-nome.pdf", file.file.read()
    )
    return ResumeIngestionResult(
        filename=outcome.filename,
        document_id=outcome.document_id,
        candidate_id=outcome.candidate_id,
        status=outcome.status,
        chunk_count=outcome.chunk_count,
        duplicate=outcome.duplicate,
    )


@router.get(
    "",
    response_model=ResumeListResponse,
    summary="Inventário da base de currículos",
)
def list_resumes(
    limit: int = Query(default=100, ge=1, le=500),
    offset: int = Query(default=0, ge=0),
) -> ResumeListResponse:
    total, rows = document_service.list_resumes(limit=limit, offset=offset)
    return ResumeListResponse(
        total=total, items=[ResumeSummary.from_row(row) for row in rows]
    )


@router.get(
    "/{document_id}",
    response_model=ResumeSummary,
    summary="Detalhe do currículo e do candidato vinculado",
    responses={404: {"model": ErrorResponse, "description": "Documento inexistente."}},
)
def get_resume(document_id: int) -> ResumeSummary:
    return ResumeSummary.from_row(document_service.get_resume(document_id))


@router.delete(
    "/{document_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    summary="Remover o currículo e seus chunks",
    description=(
        "Remove o documento e, por cascata, seus chunks. Se o candidato ficar "
        "sem nenhum currículo, ele também é removido."
    ),
    responses={404: {"model": ErrorResponse, "description": "Documento inexistente."}},
)
def delete_resume(document_id: int) -> None:
    document_service.delete_resume(document_id)
