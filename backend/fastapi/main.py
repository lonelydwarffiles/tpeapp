"""
main.py — FastAPI application for the TPE accountability backend.

New domains handled here (Node.js server.js handles pairing / FCM / audit):
  - POST /api/tpe/punishment  — Punishment Notifications
  - GET  /api/tpe/punishment  — List punishment events
  - POST /api/tpe/location    — Location Tracking
  - GET  /api/tpe/location    — List location records
  - POST /api/tpe/battery     — Battery Accountability
  - GET  /api/tpe/battery     — List battery events

Setup:
  pip install -r requirements.txt
  uvicorn main:app --host 0.0.0.0 --port 8000

Optional environment variables:
  DATABASE_URL  — SQLAlchemy connection string (default: sqlite:///./tpe.db)
  TPE_BEARER_TOKEN — if set, all /api/tpe/* routes require a matching
                     Authorization: Bearer <token> header.
"""

import os

from fastapi import Depends, FastAPI, HTTPException, Security, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

import models
from database import engine
from routers import battery, location, punishment

# ---------------------------------------------------------------------------
# Create tables on startup (idempotent; SQLAlchemy will not recreate existing
# tables, so this is safe to run on every start).
# ---------------------------------------------------------------------------
models.Base.metadata.create_all(bind=engine)

# ---------------------------------------------------------------------------
# Optional bearer-token authentication
# ---------------------------------------------------------------------------
_BEARER_TOKEN: str | None = os.getenv("TPE_BEARER_TOKEN")
_security = HTTPBearer(auto_error=False)


def verify_token(
    credentials: HTTPAuthorizationCredentials | None = Security(_security),
) -> None:
    """Dependency that enforces the TPE_BEARER_TOKEN when it is configured."""
    if not _BEARER_TOKEN:
        return  # Token check disabled — server is running without auth.
    if credentials is None or credentials.credentials != _BEARER_TOKEN:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid or missing Bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )


# ---------------------------------------------------------------------------
# Application
# ---------------------------------------------------------------------------
app = FastAPI(
    title="TPE Accountability — FastAPI Panel",
    description=(
        "Backend endpoints for the three new TPE domains:\n\n"
        "- **Punishment Notifications** — receives & stores punishment events with the triggering rule.\n"
        "- **Location Tracking** — stores periodic GPS fixes from the device's background service.\n"
        "- **Battery Accountability** — stores battery-threshold events and escalation records."
    ),
    version="1.0.0",
)

# Apply optional token verification to all routes as a global dependency.
app.include_router(punishment.router, dependencies=[Depends(verify_token)])
app.include_router(location.router,   dependencies=[Depends(verify_token)])
app.include_router(battery.router,    dependencies=[Depends(verify_token)])


@app.get("/healthz", tags=["health"], summary="Health check")
def healthz() -> dict:
    return {"status": "ok"}
