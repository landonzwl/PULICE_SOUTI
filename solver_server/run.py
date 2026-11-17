"""云端答案服务入口：只提供答案接口和展示页，不接收图片。

在项目根目录执行：python -m solver_server.run
"""
from __future__ import annotations

import logging
import os

import uvicorn

from . import db


def main() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    db.init_db()
    uvicorn.run(
        "solver_server.server:app",
        host=os.getenv("SOLVER_HOST", "0.0.0.0"),
        port=int(os.getenv("SOLVER_PORT", "8000")),
        log_level="info",
        workers=1,
    )


if __name__ == "__main__":
    main()
