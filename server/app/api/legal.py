from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import FileResponse

router = APIRouter(prefix="/legal", tags=["legal"])

LEGAL_DIR = Path(__file__).resolve().parents[2] / "static" / "legal"


@router.get("/privacy", response_class=FileResponse)
def privacy() -> FileResponse:
    return FileResponse(LEGAL_DIR / "privacy.html", media_type="text/html; charset=utf-8")


@router.get("/location", response_class=FileResponse)
def location_terms() -> FileResponse:
    return FileResponse(LEGAL_DIR / "location-terms.html", media_type="text/html; charset=utf-8")
