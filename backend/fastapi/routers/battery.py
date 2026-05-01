"""
routers/battery.py — API routes for the Battery Accountability domain.

Endpoint:
  POST /api/tpe/battery
    Receives a battery-level event from BatteryMonitorReceiver on the Android
    app and persists it so the partner can review charging compliance.
"""

from fastapi import APIRouter, Depends, status
from sqlalchemy.orm import Session

from database import get_db
from models import BatteryEvent
from schemas import BatteryEventCreate, BatteryEventOut

router = APIRouter(prefix="/api/tpe/battery", tags=["battery"])


@router.post(
    "",
    response_model=BatteryEventOut,
    status_code=status.HTTP_201_CREATED,
    summary="Record a battery accountability event",
    description=(
        "Called by the Android BatteryMonitorReceiver when battery level crosses "
        "a threshold.  severity is 'warning' (< 15%) or 'critical' (< 5%).  "
        "The Android app also triggers ConsequenceDispatcher.punish() for critical events."
    ),
)
def record_battery_event(
    body: BatteryEventCreate,
    db: Session = Depends(get_db),
) -> BatteryEventOut:
    event = BatteryEvent(
        percent=body.percent,
        severity=body.severity,
        device_timestamp=body.timestamp,
    )
    db.add(event)
    db.commit()
    db.refresh(event)
    return event


@router.get(
    "",
    response_model=list[BatteryEventOut],
    summary="List battery events",
    description="Returns all recorded battery accountability events, newest first.",
)
def list_battery_events(
    skip: int = 0,
    limit: int = 100,
    db: Session = Depends(get_db),
) -> list[BatteryEventOut]:
    return (
        db.query(BatteryEvent)
        .order_by(BatteryEvent.id.desc())
        .offset(skip)
        .limit(limit)
        .all()
    )
