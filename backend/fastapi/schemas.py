"""
schemas.py — Pydantic request/response schemas for the TPE FastAPI backend.

Mirrors the JSON payloads sent by the Android app:
  - PunishmentEventCreate  — POST /api/tpe/punishment
  - LocationRecordCreate   — POST /api/tpe/location
  - BatteryEventCreate     — POST /api/tpe/battery
"""

from pydantic import BaseModel, Field


# ---------------------------------------------------------------------------
# Punishment
# ---------------------------------------------------------------------------

class PunishmentEventCreate(BaseModel):
    """Body sent by ConsequenceDispatcher when a punishment is triggered."""

    reason: str = Field(..., min_length=1, description="Human-readable description of the rule that was violated.")
    timestamp: int = Field(..., description="Unix epoch milliseconds from the Android device.")

    model_config = {"json_schema_extra": {"example": {"reason": "Explicit content detected by NudeNet classifier", "timestamp": 1714555200000}}}


class PunishmentEventOut(BaseModel):
    """Response returned after a punishment event is stored."""

    id: int
    reason: str
    device_timestamp: int

    model_config = {"from_attributes": True}


# ---------------------------------------------------------------------------
# Location
# ---------------------------------------------------------------------------

class LocationRecordCreate(BaseModel):
    """Body sent by LocationTrackingService on each GPS fix."""

    latitude: float = Field(..., ge=-90.0, le=90.0)
    longitude: float = Field(..., ge=-180.0, le=180.0)
    accuracy: float | None = Field(None, ge=0.0, description="Horizontal accuracy radius in metres.")
    altitude: float | None = Field(None, description="Altitude in metres above WGS84 ellipsoid.")
    timestamp: int = Field(..., description="Unix epoch milliseconds from the Android device (location.time).")

    model_config = {"json_schema_extra": {"example": {"latitude": 51.5074, "longitude": -0.1278, "accuracy": 12.5, "altitude": 11.0, "timestamp": 1714555200000}}}


class LocationRecordOut(BaseModel):
    """Response returned after a location record is stored."""

    id: int
    latitude: float
    longitude: float

    model_config = {"from_attributes": True}


# ---------------------------------------------------------------------------
# Battery
# ---------------------------------------------------------------------------

class BatteryEventCreate(BaseModel):
    """Body sent by BatteryMonitorReceiver when a threshold is crossed."""

    percent: int = Field(..., ge=0, le=100, description="Battery level (0–100).")
    severity: str = Field(..., pattern="^(warning|critical)$", description="'warning' (< 15%) or 'critical' (< 5%).")
    timestamp: int = Field(..., description="Unix epoch milliseconds from the Android device.")

    model_config = {"json_schema_extra": {"example": {"percent": 4, "severity": "critical", "timestamp": 1714555200000}}}


class BatteryEventOut(BaseModel):
    """Response returned after a battery event is stored."""

    id: int
    percent: int
    severity: str

    model_config = {"from_attributes": True}
