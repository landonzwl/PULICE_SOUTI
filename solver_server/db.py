"""SQLite 持久化：只保存答案快照和展示内容，不再保存图片。"""
from __future__ import annotations

import json
import os
import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Any, Iterator

BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
DB_PATH = Path(os.getenv("SOLVER_DB_PATH", DATA_DIR / "solver.db"))

SCHEMA = """
CREATE TABLE IF NOT EXISTS frames (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    ts REAL NOT NULL,
    path TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'processing',
    error TEXT,
    client_frame_id TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_frames_device ON frames(device_id, id);

CREATE TABLE IF NOT EXISTS questions (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    frame_id INTEGER NOT NULL REFERENCES frames(id) ON DELETE CASCADE,
    device_id TEXT NOT NULL,
    no TEXT NOT NULL DEFAULT '',
    stem TEXT NOT NULL DEFAULT '',
    options_json TEXT NOT NULL DEFAULT '{}',
    qtype TEXT NOT NULL DEFAULT '',
    answer TEXT,
    confidence REAL NOT NULL DEFAULT 0,
    method TEXT NOT NULL DEFAULT '',
    reason TEXT NOT NULL DEFAULT '',
    ts REAL NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_questions_device ON questions(device_id, id);
CREATE INDEX IF NOT EXISTS idx_questions_frame ON questions(frame_id);

CREATE TABLE IF NOT EXISTS device_display (
    device_id TEXT PRIMARY KEY,
    items_json TEXT NOT NULL DEFAULT '[]',
    updated_at REAL NOT NULL
);

CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id TEXT NOT NULL,
    session_id TEXT NOT NULL DEFAULT '',
    client_frame_id TEXT NOT NULL DEFAULT '',
    stage TEXT NOT NULL,
    status TEXT NOT NULL,
    message TEXT NOT NULL DEFAULT '',
    created_at REAL NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_events_device ON events(device_id, id);
"""


@contextmanager
def connect() -> Iterator[sqlite3.Connection]:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys = ON")
    conn.execute("PRAGMA busy_timeout = 30000")
    try:
        yield conn
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()


def init_db() -> None:
    with connect() as conn:
        conn.execute("PRAGMA journal_mode = WAL")
        conn.executescript(SCHEMA)
        columns = {row["name"] for row in conn.execute("PRAGMA table_info(frames)")}
        if "client_frame_id" not in columns:
            conn.execute("ALTER TABLE frames ADD COLUMN client_frame_id TEXT")
        conn.execute(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_frames_client "
            "ON frames(device_id, client_frame_id)"
        )


def record_answer_report(
    device_id: str,
    client_frame_id: str,
    captured_at: float,
    answers: list[dict[str, Any]],
    final: bool,
) -> int:
    """手机上报一帧的文字答案；同一帧的暂定结果会被后续结果覆盖。"""
    ts = captured_at if captured_at > 0 else time.time()
    with connect() as conn:
        conn.execute("BEGIN IMMEDIATE")
        row = conn.execute(
            "SELECT id FROM frames WHERE device_id = ? AND client_frame_id = ?",
            (device_id, client_frame_id),
        ).fetchone()
        if row is None:
            cursor = conn.execute(
                "INSERT INTO frames(device_id, ts, path, status, client_frame_id) "
                "VALUES (?, ?, '', 'processing', ?)",
                (device_id, ts, client_frame_id),
            )
            frame_id = int(cursor.lastrowid)
        else:
            frame_id = int(row["id"])
            conn.execute("UPDATE frames SET ts = ? WHERE id = ?", (ts, frame_id))

        conn.execute("DELETE FROM questions WHERE frame_id = ?", (frame_id,))
        _insert_frame_questions(conn, frame_id, answers)
        conn.execute(
            "UPDATE frames SET status = ?, error = NULL WHERE id = ?",
            ("done" if final else "processing", frame_id),
        )
        return frame_id


def _insert_frame_questions(
    conn: sqlite3.Connection,
    frame_id: int,
    questions: list[dict[str, Any]],
) -> None:
    conn.executemany(
        """
        INSERT INTO questions(
            frame_id, device_id, no, stem, options_json, qtype,
            answer, confidence, method, reason, ts
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        [
            (
                frame_id,
                q["device_id"],
                str(q.get("no") or ""),
                str(q.get("stem") or ""),
                json.dumps(q.get("options") or {}, ensure_ascii=False),
                str(q.get("type") or q.get("qtype") or ""),
                q.get("answer"),
                float(q.get("confidence") or 0),
                str(q.get("method") or ""),
                str(q.get("reason") or ""),
                float(q["ts"]),
            )
            for q in questions
        ],
    )


def list_device_questions(device_id: str) -> list[dict[str, Any]]:
    with connect() as conn:
        rows = conn.execute(
            """
            SELECT q.* FROM questions q
            JOIN frames f ON f.id = q.frame_id
            WHERE q.device_id = ? AND f.status IN ('done', 'processing')
            ORDER BY q.ts, q.id
            """,
            (device_id,),
        ).fetchall()
        return [dict(row) for row in rows]


def save_display(device_id: str, items: list[dict[str, str]]) -> float:
    updated_at = time.time()
    with connect() as conn:
        conn.execute(
            """
            INSERT INTO device_display(device_id, items_json, updated_at)
            VALUES (?, ?, ?)
            ON CONFLICT(device_id) DO UPDATE SET
                items_json = excluded.items_json,
                updated_at = excluded.updated_at
            """,
            (device_id, json.dumps(items, ensure_ascii=False), updated_at),
        )
    return updated_at


def get_display(device_id: str) -> dict[str, Any]:
    with connect() as conn:
        row = conn.execute(
            "SELECT items_json, updated_at FROM device_display WHERE device_id = ?",
            (device_id,),
        ).fetchone()
        # 只把近两分钟的处理中帧算作待整理，避免手机中途退出后一直显示加载。
        pending = conn.execute(
            "SELECT COUNT(*) FROM frames WHERE device_id = ? AND status = 'processing' "
            "AND created_at > datetime('now', '-120 seconds')",
            (device_id,),
        ).fetchone()[0]
    if row is None:
        return {"device_id": device_id, "items": [], "updated_at": None, "pending": pending}
    return {
        "device_id": device_id,
        "items": json.loads(row["items_json"]),
        "updated_at": row["updated_at"],
        "pending": pending,
    }


def latest_device_id() -> str | None:
    """没有显式指定设备时，自动跟随最近上报的设备。"""
    with connect() as conn:
        for table in ("frames", "events"):
            row = conn.execute(
                f"SELECT device_id FROM {table} ORDER BY id DESC LIMIT 1"
            ).fetchone()
            if row is not None:
                return str(row["device_id"])
    return None


def record_event(
    device_id: str,
    session_id: str,
    client_frame_id: str,
    stage: str,
    status: str,
    message: str,
) -> None:
    """记录手机端流水线事件，只保留最近 800 条，避免小机器磁盘增长。"""
    with connect() as conn:
        conn.execute(
            "INSERT INTO events(device_id, session_id, client_frame_id, stage, status, message, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            (device_id, session_id, client_frame_id, stage, status, message[:500], time.time()),
        )
        count = conn.execute(
            "SELECT COUNT(*) FROM events WHERE device_id = ?", (device_id,)
        ).fetchone()[0]
        if count > 900:
            conn.execute(
                "DELETE FROM events WHERE device_id = ? AND id NOT IN "
                "(SELECT id FROM events WHERE device_id = ? ORDER BY id DESC LIMIT 800)",
                (device_id, device_id),
            )


_STAGE_BUCKETS = {
    "capture": "capture",
    "process": "process",
    "model": "model",
    "post": "post",
    "done": "done",
}


def get_status(device_id: str) -> dict[str, Any]:
    with connect() as conn:
        events = [
            dict(row)
            for row in conn.execute(
                "SELECT id, session_id, client_frame_id, stage, status, message, created_at "
                "FROM events WHERE device_id = ? ORDER BY id DESC LIMIT 40",
                (device_id,),
            )
        ]
        frame = conn.execute(
            "SELECT id, status, created_at FROM frames WHERE device_id = ? "
            "ORDER BY id DESC LIMIT 1",
            (device_id,),
        ).fetchone()
        answer_count = 0
        if frame is not None:
            answer_count = conn.execute(
                "SELECT COUNT(*) FROM questions WHERE frame_id = ?", (frame["id"],)
            ).fetchone()[0]
        totals = conn.execute(
            "SELECT COUNT(*) frames, COALESCE(SUM(status = 'done'), 0) done "
            "FROM frames WHERE device_id = ?",
            (device_id,),
        ).fetchone()
        pending = conn.execute(
            "SELECT COUNT(*) FROM frames WHERE device_id = ? AND status = 'processing' "
            "AND created_at > datetime('now', '-120 seconds')",
            (device_id,),
        ).fetchone()[0]
        display = conn.execute(
            "SELECT updated_at FROM device_display WHERE device_id = ?", (device_id,)
        ).fetchone()
        questions = conn.execute(
            "SELECT COUNT(*) FROM questions WHERE device_id = ?", (device_id,)
        ).fetchone()[0]

    stages: dict[str, dict[str, Any]] = {}
    for event in events:
        bucket = _STAGE_BUCKETS.get(event["stage"])
        if bucket and bucket not in stages:
            stages[bucket] = event

    return {
        "device_id": device_id,
        "server_time": time.time(),
        "stages": stages,
        "events": events,
        "last_frame": None if frame is None else {
            "id": frame["id"],
            "status": frame["status"],
            "created_at": frame["created_at"],
            "answers": answer_count,
        },
        "counts": {
            "frames": totals["frames"],
            "done": totals["done"],
            "questions": questions,
            "pending": pending,
        },
        "display_updated_at": None if display is None else display["updated_at"],
    }
