# Security

Codex Watch is a local-network prototype. Its safest default deployment is one computer, one phone, and one watch on a private network you control.

## Important defaults

- The bridge listens on localhost with `npm start` and on all interfaces with `start-network.ps1`.
- Bridge API authentication is disabled unless `CODEX_WATCH_TOKEN` is set.
- The current Android companion does not send that optional bridge token.
- The bridge can read Codex task metadata, send messages, and request that Codex Desktop close.

Do not expose port `8787` to the public Internet. Use a trusted LAN, VPN, or equivalent private overlay, and rely on that network's access controls.

## Credentials

OpenAI access and refresh tokens are obtained only after the user completes sign-in on Android. They are encrypted at rest with a key held by Android Keystore. They must never be committed, pasted into configuration files, or included in bug reports.

## Reporting a vulnerability

Open a GitHub security advisory for the repository rather than posting credentials, private task contents, recordings, or exploit details in a public issue.
