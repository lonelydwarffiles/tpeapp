"""
routers/punishment.py — API routes for the Punishment Notifications domain.

Endpoint:
  POST /api/tpe/punishment
    Receives a punishment event from ConsequenceDispatcher on the Android app
    and persists it to the database for the partner dashboard to review.
"""

from fastapi import APIRouter, Depends, status
from sqlalchemy.orm import Session

from database import get_db
from models import PunishmentEvent
from schemas import PunishmentEventCreate, PunishmentEventOut

router = APIRouter(prefix="/api/tpe/punishment", tags=["punishment"])


@router.post(
    "",
    response_model=PunishmentEventOut,
    status_code=status.HTTP_201_CREATED,
    summary="Record a punishment event",
    description=(
        "Called by the Android ConsequenceDispatcher when a punishment is triggered. "
        "The ``reason`` field identifies exactly which rule was violated — matching "
        "the text shown in the on-device notification."
    ),
)
def record_punishment(
    body: PunishmentEventCreate,
    db: Session = Depends(get_db),
) -> PunishmentEventOut:
    event = PunishmentEvent(
        reason=body.reason.strip(),
        device_timestamp=body.timestamp,
    )
    db.add(event)
    db.commit()
    db.refresh(event)
    return event


@router.get(
    "",
    response_model=list[PunishmentEventOut],
    summary="List punishment events",
    description="Returns all recorded punishment events, newest first.",
)
def list_punishments(
    skip: int = 0,
    limit: int = 100,
    db: Session = Depends(get_db),
) -> list[PunishmentEventOut]:
    return (
        db.query(PunishmentEvent)
        .order_by(PunishmentEvent.id.desc())
        .offset(skip)
        .limit(limit)
        .all()
    )
