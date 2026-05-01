"""
models.py — SQLAlchemy ORM models for the three new TPE accountability domains:

  1. PunishmentEvent   — records every punishment dispatched by the Android app,
                         including the rule / reason that triggered it.
  2. LocationRecord    — stores periodic GPS coordinates sent by the device's
                         background LocationTrackingService.
  3. BatteryEvent      — captures battery-level events reported by the device's
                         BatteryMonitorReceiver (warning at < 15 %, critical at < 5 %).
"""

from datetime import datetime

from sqlalchemy import DateTime, Float, Integer, String, func
from sqlalchemy.orm import Mapped, mapped_column

from database import Base


class PunishmentEvent(Base):
    """
    Records every punishment triggered on the device.

    The ``reason`` field contains the human-readable description of the rule
    that was violated — this is the same string shown in the local Android
    notification so the partner dashboard and the device notification are
    always in sync.
    """

    __tablename__ = "punishment_events"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True, autoincrement=True)
    reason: Mapped[str] = mapped_column(String, nullable=False)
    device_timestamp: Mapped[int] = mapped_column(Integer, nullable=False, comment="Unix epoch ms from the Android device")
    server_received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
    )


class LocationRecord(Base):
    """
    Stores a periodic GPS fix reported by the device's LocationTrackingService.

    ``accuracy`` is the horizontal accuracy radius in metres as reported by
    the Android platform.  ``altitude`` is in metres above WGS84 ellipsoid.
    ``device_timestamp`` is the fix time in Unix epoch milliseconds reported
    by the Android Location object (``location.time``).
    """

    __tablename__ = "location_records"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True, autoincrement=True)
    latitude: Mapped[float] = mapped_column(Float, nullable=False)
    longitude: Mapped[float] = mapped_column(Float, nullable=False)
    accuracy: Mapped[float] = mapped_column(Float, nullable=True, comment="Horizontal accuracy radius in metres")
    altitude: Mapped[float] = mapped_column(Float, nullable=True, comment="Altitude in metres above WGS84 ellipsoid")
    device_timestamp: Mapped[int] = mapped_column(Integer, nullable=False, comment="Unix epoch ms from the Android device")
    server_received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
    )


class BatteryEvent(Base):
    """
    Captures a battery-level accountability event from BatteryMonitorReceiver.

    ``percent`` is the battery level (0–100) at the time the event was raised.
    ``severity`` is either ``"warning"`` (< 15 %) or ``"critical"`` (< 5 %).
    """

    __tablename__ = "battery_events"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, index=True, autoincrement=True)
    percent: Mapped[int] = mapped_column(Integer, nullable=False, comment="Battery level 0–100 at event time")
    severity: Mapped[str] = mapped_column(String(16), nullable=False, comment="'warning' or 'critical'")
    device_timestamp: Mapped[int] = mapped_column(Integer, nullable=False, comment="Unix epoch ms from the Android device")
    server_received_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
    )
