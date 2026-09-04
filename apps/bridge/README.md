# Desktop bridge

The bridge exposes a small HTTP API for the companion app and translates it into Codex App Server requests. It also serves a browser-based test client.

Run `npm start` for localhost-only access, or `./start-network.ps1` on Windows to make it reachable on a trusted private network.

The bridge is unauthenticated by default. Set `CODEX_WATCH_TOKEN` before exposing it beyond a network you control.
