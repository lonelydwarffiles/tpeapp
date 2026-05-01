"""
routers/location.py — API routes for the Location Tracking domain.

Endpoint:
  POST /api/tpe/location
    Receives a GPS fix from LocationTrackingService on the Android app and
    stores it in the database for partner review / geofence auditing.
"""

from fastapi import APIRouter, Depends, status
from sqlalchemy.orm import Session

from database import get_db
from models import LocationRecord
from schemas import LocationRecordCreate, LocationRecordOut

router = APIRouter(prefix="/api/tpe/location", tags=["location"])


@router.post(
    "",
    response_model=LocationRecordOut,
    status_code=status.HTTP_201_CREATED,
    summary="Store a GPS location fix",
    description=(
        "Called by the Android LocationTrackingService at a configurable interval "
        "(default: every 5 minutes or 50 m displacement).  "
        "Latitude and longitude are in decimal degrees (WGS84)."
    ),
)
def store_location(
    body: LocationRecordCreate,
    db: Session = Depends(get_db),
) -> LocationRecordOut:
    record = LocationRecord(
        latitude=body.latitude,
        longitude=body.longitude,
        accuracy=body.accuracy,
        altitude=body.altitude,
        device_timestamp=body.timestamp,
    )
    db.add(record)
    db.commit()
    db.refresh(record)
    return record


@router.get(
    "",
    response_model=list[LocationRecordOut],
    summary="List location records",
    description="Returns stored GPS fixes, newest first.",
)
def list_locations(
    skip: int = 0,
    limit: int = 100,
    db: Session = Depends(get_db),
) -> list[LocationRecordOut]:
    return (
        db.query(LocationRecord)
        .order_by(LocationRecord.id.desc())
        .offset(skip)
        .limit(limit)
        .all()
    )
