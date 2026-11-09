# Remote Wake Relay Server

A tiny WebSocket server that forwards screen frames from Phone A to Phone B and
touch commands back. All data passes through it in transit; nothing is stored.

## Run locally

```bash
pip install websockets
python server.py
# Relay listening on ws://0.0.0.0:8765
```

Find your computer's IP (`ipconfig` on Windows, `ifconfig`/`ip addr` on Linux/Mac)
and enter `ws://<your-ip>:8765` in both phones' **Control relay** field.

## Run with Docker

```bash
docker build -t remotewake-relay .
docker run -p 8765:8765 remotewake-relay
```

## Deploy for free (with TLS)

### Render.com
1. Push the `relay/` folder to a GitHub repo.
2. On [render.com](https://render.com), create a new **Web Service** from the repo.
3. Set **Root Directory** to `relay`, **Build Command** to `pip install -r requirements.txt`,
   **Start Command** to `python server.py`, **Port** to `8765`.
4. You get a URL like `wss://remotewake-relay.onrender.com`.

### Any VPS (behind Caddy for automatic TLS)
```bash
# Install Caddy, then:
caddy reverse-proxy --from your-domain.com --to localhost:8765
```
Enter `wss://your-domain.com` in the app.
