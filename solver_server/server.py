"""FastAPI 接口：接收手机端直连模型得到的文字答案，返回整理后的展示内容。

图片不经过本服务，服务器只承担答案暂存和展示页，适合 2C2G 小机器。
"""
from __future__ import annotations

import re
import time
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, Query
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

from . import db
from .organizer import organize_device

BASE_DIR = Path(__file__).resolve().parent
DEVICE_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


class AnswerItem(BaseModel):
    no: str = ""
    stem: str = ""
    answer: str = ""
    method: str = ""
    confidence: float = 0.0


class AnswerReport(BaseModel):
    device_id: str = "default"
    client_frame_id: str = ""
    captured_at: float = 0.0
    final: bool = False
    answers: list[AnswerItem] = Field(default_factory=list)


class EventReport(BaseModel):
    device_id: str = "default"
    session_id: str = ""
    client_frame_id: str = ""
    stage: str
    status: str = "info"
    message: str = ""


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.init_db()
    yield


app = FastAPI(title="拍照解题服务", lifespan=lifespan)


def _device_id(value: str) -> str:
    value = value.strip() or "default"
    if not DEVICE_PATTERN.fullmatch(value):
        raise HTTPException(status_code=400, detail="设备标识只能包含字母、数字、下划线和短横线")
    return value


def _client_frame_id(value: str) -> str:
    value = value.strip()
    if not DEVICE_PATTERN.fullmatch(value):
        raise HTTPException(status_code=400, detail="帧标识只能包含字母、数字、下划线和短横线")
    return value


async def _resolve_device(value: str | None) -> str:
    """未指定设备或沿用旧的 default 时，自动跟随最近上报的设备。"""
    if value and value.strip() and value.strip() != "default":
        return _device_id(value)
    return await run_in_threadpool(db.latest_device_id) or "default"


@app.get("/")
async def index() -> FileResponse:
    return FileResponse(
        BASE_DIR / "static" / "index.html",
        headers={"Cache-Control": "no-store"},
    )


@app.get("/status")
async def status_page() -> FileResponse:
    return FileResponse(
        BASE_DIR / "static" / "status.html",
        headers={"Cache-Control": "no-store"},
    )


@app.get("/api/health")
async def health() -> dict[str, bool]:
    return {"ok": True}


@app.post("/api/v1/devices/heartbeat", include_in_schema=False)
async def heartbeat() -> dict[str, bool]:
    """手机端保活；答案由 /api/v1/answers 单独上报。"""
    return {"ok": True}


@app.post("/api/v1/answers")
async def report_answers(report: AnswerReport) -> dict[str, Any]:
    device_id = _device_id(report.device_id)
    client_frame_id = _client_frame_id(report.client_frame_id)
    ts = report.captured_at if report.captured_at > 0 else time.time()

    answers: list[dict[str, Any]] = []
    for item in report.answers:
        answer = item.answer.strip().upper()
        if answer not in set("ABCDEFGH"):
            continue
        answers.append({
            "device_id": device_id,
            "no": item.no,
            "stem": item.stem,
            "answer": answer,
            "confidence": item.confidence,
            "method": item.method,
            "ts": ts,
        })

    frame_id = await run_in_threadpool(
        db.record_answer_report,
        device_id,
        client_frame_id,
        report.captured_at,
        answers,
        report.final,
    )
    items = await run_in_threadpool(organize_device, device_id)
    return {"ok": True, "frame_id": frame_id, "items": items}


@app.get("/api/display")
async def display(d: str | None = Query(None)) -> dict[str, object]:
    device_id = await _resolve_device(d)
    return await run_in_threadpool(db.get_display, device_id)


@app.post("/api/v1/events")
async def report_event(report: EventReport) -> dict[str, bool]:
    device_id = _device_id(report.device_id)
    if not re.fullmatch(r"[a-z_]{1,24}", report.stage):
        raise HTTPException(status_code=400, detail="阶段标识非法")
    if report.status not in {"running", "ok", "fail", "info"}:
        raise HTTPException(status_code=400, detail="状态标识非法")
    frame_id = report.client_frame_id.strip()
    if frame_id and not DEVICE_PATTERN.fullmatch(frame_id):
        raise HTTPException(status_code=400, detail="帧标识非法")
    await run_in_threadpool(
        db.record_event,
        device_id,
        report.session_id.strip()[:64],
        frame_id,
        report.stage,
        report.status,
        report.message.strip(),
    )
    return {"ok": True}


@app.get("/api/v1/status")
async def status(d: str | None = Query(None)) -> dict[str, object]:
    device_id = await _resolve_device(d)
    return await run_in_threadpool(db.get_status, device_id)
