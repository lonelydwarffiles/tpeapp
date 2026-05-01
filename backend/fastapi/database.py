"""
database.py — SQLAlchemy engine and session factory for the TPE FastAPI backend.

Uses SQLite by default (suitable for a single-device deployment).  Override
the database URL by setting the ``DATABASE_URL`` environment variable:

    DATABASE_URL=postgresql+psycopg2://user:pass@localhost/tpe uvicorn main:app

The database file is created automatically on first start.
"""

import os

from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker

DATABASE_URL: str = os.getenv("DATABASE_URL", "sqlite:///./tpe.db")

# SQLite requires connect_args for thread safety in FastAPI's async context.
connect_args = {"check_same_thread": False} if DATABASE_URL.startswith("sqlite") else {}

engine = create_engine(DATABASE_URL, connect_args=connect_args)

SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


class Base(DeclarativeBase):
    """Shared declarative base for all ORM models."""


def get_db():
    """FastAPI dependency that yields a SQLAlchemy session per request."""
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
