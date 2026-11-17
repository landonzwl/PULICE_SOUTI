"""答案整理器：跨帧去重、多数合并、题号排序并生成手机端快照。"""
from __future__ import annotations

import re
import threading
import unicodedata
from collections import defaultdict
from typing import Any

from . import db


_organize_lock = threading.Lock()


def _normalize(value: Any) -> str:
    text = unicodedata.normalize("NFKC", str(value or "")).lower()
    return re.sub(r"[\W_]+", "", text, flags=re.UNICODE)


def _group_key(row: dict[str, Any]) -> tuple[str, str]:
    # 题号与题干共同定位，避免新一套题沿用题号时被误合并。
    return _normalize(row.get("no")), _normalize(row.get("stem"))[:30]


def _select_answer(rows: list[dict[str, Any]]) -> str:
    """最新帧优先；同帧 DeepSeek > Qwen > 其他来源，再按写入顺序裁决。"""
    latest_ts = max(float(row.get("ts") or 0) for row in rows)
    latest_rows = [row for row in rows if float(row.get("ts") or 0) == latest_ts]
    source_priority = {"dual_deepseek": 2, "dual_qwen": 1}
    selected = max(
        latest_rows,
        key=lambda row: (
            source_priority.get(str(row.get("method") or ""), 0),
            int(row.get("id") or 0),
        ),
    )
    return str(selected["answer"]).strip().upper()


def organize_questions(rows: list[dict[str, Any]]) -> list[dict[str, str]]:
    groups: dict[tuple[str, str], list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        answer = str(row.get("answer") or "").strip().upper()
        if answer not in set("ABCDEFGH"):
            continue
        clean_row = dict(row)
        clean_row["answer"] = answer
        groups[_group_key(clean_row)].append(clean_row)

    merged: list[dict[str, Any]] = []
    for key, members in groups.items():
        newest = max(members, key=lambda row: (float(row.get("ts") or 0), int(row.get("id") or 0)))
        merged.append({
            "no": str(newest.get("no") or "").strip(),
            "stem": str(newest.get("stem") or "").strip(),
            "answer": _select_answer(members),
            "ts": float(newest.get("ts") or 0),
            "id": int(newest.get("id") or 0),
        })

    # 同一数字题号若因 OCR 差异形成多个组，只展示最新一组，避免重复答案。
    numbered: dict[int, dict[str, Any]] = {}
    unnumbered: list[dict[str, Any]] = []
    for item in merged:
        match = re.fullmatch(r"\d+", item["no"])
        if not match:
            unnumbered.append(item)
            continue
        number = int(item["no"])
        current = numbered.get(number)
        if current is None or (item["ts"], item["id"]) > (current["ts"], current["id"]):
            numbered[number] = item

    result = list(numbered.values()) + unnumbered
    result.sort(key=lambda item: (-item["ts"], -item["id"]))
    return [{
        "no": item["no"] or "—",
        "prefix": item["stem"][:3],
        "answer": item["answer"],
    } for item in result]


def organize_device(device_id: str) -> list[dict[str, str]]:
    with _organize_lock:
        items = organize_questions(db.list_device_questions(device_id))
        db.save_display(device_id, items)
        return items
