#!/usr/bin/env python3
"""
Remote Wake screen-sharing relay.

Forwards screen frames from Phone A (source) to Phone B (viewer)
and touch commands back. All data is end-to-end between your phones;
this server only passes it along and keeps nothing.

Run:
    pip install websockets
    python server.py

Both phones connect to:
    ws://<your-ip>:8765/<pairing-code>/source   (Phone A)
    ws://<your-ip>:8765/<pairing-code>/viewer   (Phone B)

Deploy with TLS (recommended):
    Put it behind nginx / Caddy with a certificate, or use the Dockerfile.
"""

import asyncio
import logging
from collections import defaultdict
import websockets

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
log = logging.getLogger("relay")

# rooms["code"] = {"source": ws, "viewer": ws}
rooms: dict[str, dict[str, websockets.WebSocketServerProtocol]] = defaultdict(dict)


async def handler(ws):
    # Path: /<code>/<role>
    parts = [p for p in ws.request.path.strip("/").split("/") if p]
    if len(parts) < 2 or parts[1] not in ("source", "viewer"):
        await ws.close(1008, "URL must be /<pairing-code>/source or /<pairing-code>/viewer")
        return

    code, role = parts[0], parts[1]
    other = "viewer" if role == "source" else "source"

    # Replace any stale connection for this role.
    old = rooms[code].get(role)
    if old:
        await old.close(1000, "Replaced")

    rooms[code][role] = ws
    log.info("%s joined room %s…", role, code[:8])

    try:
        async for msg in ws:
            target = rooms[code].get(other)
            if target:
                await target.send(msg)
    except websockets.ConnectionClosed:
        pass
    finally:
        if rooms[code].get(role) is ws:
            del rooms[code][role]
        if not rooms[code]:
            del rooms[code]
        log.info("%s left room %s…", role, code[:8])


async def main():
    # max_size = 1 MB, enough for a compressed screen frame.
    async with websockets.serve(handler, "0.0.0.0", 8765, max_size=2**20):
        log.info("Relay listening on ws://0.0.0.0:8765")
        await asyncio.Future()  # run forever


if __name__ == "__main__":
    asyncio.run(main())
