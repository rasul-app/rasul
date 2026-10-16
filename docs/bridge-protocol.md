# Rasul Bridge protocol v1 (contract for the bridge and the app; copy this into the repo as docs/bridge-protocol.md)

## Parts
- Hermes API server stays on 127.0.0.1:8642 (default) on the PC. Only the bridge talks to it.
- The bridge (Python 3.11+, `bridge/` in the repo) runs on the same PC as the same Windows/Linux user.
- The phone talks only to the bridge.

## Layers
1. Outer transport: WebSocket (RFC 6455) at path `/v1/link`. Over the internet the bridge sits behind an outbound tunnel (for example Cloudflare Tunnel) that terminates outer HTTPS (`wss://<host>/v1/link`). On a LAN or Tailscale the phone may use `ws://<addr>:<port>/v1/link`. The outer layer is NOT trusted: it only carries bytes.
2. Inner TLS: every WebSocket connection carries one raw TLS 1.3 byte stream (binary frames) that ends at the bridge. Server certificate = self-signed ECDSA P-256 made by the bridge at first start; the phone pins SHA-256 of its SubjectPublicKeyInfo (from the QR). TLS 1.3 only, no 0-RTT, no session resumption.
3. Inner HTTP/1.1 over that TLS (keep-alive allowed; SSE streaming allowed). One WebSocket = one TLS connection; the phone opens several in parallel when it needs parallel requests.

Implementation hints: bridge = WebSocket endpoint that pipes each connection's bytes to an internal TLS HTTP server bound to 127.0.0.1 only. Android = an OkHttp `SocketFactory` whose sockets are backed by an OkHttp WebSocket, plus an `SSLSocketFactory` with the device key (AndroidKeyStore) and a pinning TrustManager; then the normal OkHttp client runs HTTP over it.

## Device identity
- The phone creates a non-exportable EC P-256 key in AndroidKeyStore (StrongBox when available, else TEE) with a self-signed certificate. Key use requires the device to be unlocked (setUnlockedDeviceRequired) ; profile creation additionally needs a fresh biometric/device-credential confirmation in the app.
- After pairing, every inner TLS connection must present that device certificate (mutual TLS). The bridge accepts only certificates whose SPKI SHA-256 is in its device registry and not revoked.

## Pairing (owner starts it on the PC)
1. Owner runs `rasul-bridge pair` (terminal). The bridge creates an invitation: id (128-bit random), secret (256-bit random), expiry now+120 s, single use; stores only SHA-256(secret).
2. It prints a QR (terminal + PNG in a temp dir that is deleted after use) with the text:
   `rasul-pair:1:<base64url(JSON)>` where JSON = {"v":1,"bridge_id":..,"name":<PC name>,"urls":[<outer URLs, in preference order>],"pin":<base64url SHA-256 SPKI>,"inv":<invitation id>,"sec":<base64url secret>,"exp":<unix seconds>}. No Hermes key, ever.
3. App scans it with its own scanner (no deep link), connects to the first reachable URL, does inner TLS pinned to `pin` WITHOUT a client certificate (only `/v1/pair` is allowed on such a connection), and POSTs `/v1/pair` {inv, sec, device_name, device_cert_pem}.
4. The bridge checks expiry/hash/unused, computes a 6-digit code = first 6 decimal digits derived from SHA-256(server SPKI || device SPKI || inv), shows it in the terminal with the device name and waits up to 120 s for the owner to type `y`. The app shows the same code. On `y`: invitation consumed, device registered; response {device_id}. On `n`/timeout: 403, invitation consumed.
5. The phone saves {bridge_id, name, urls, pin, device_id}; the key stays in AndroidKeyStore.

## API (inner HTTP, all need a registered device except /v1/pair)
- `GET /v1/profiles` -> {"revision":n,"profiles":[{"id","name","display_name","ready":bool,"default":bool}]}. Lists the default profile plus every Hermes profile directory.
- `GET /v1/profile-events?after=<revision>` -> SSE; event `profiles` with the same JSON as above whenever the profile set or readiness changes; `: keepalive` every 15 s. A reconnect gets a fresh snapshot.
- `POST /v1/profiles` {"request_id":uuid,"name":<[a-z0-9-]{2,32}>,"template":<id from GET /v1/profile-templates>} -> 202 {"operation_id"}. Same request_id = same operation (idempotent).
- `GET /v1/profile-templates` -> [{"id","label"}] from the bridge config (owner-defined; default only "blank").
- `GET /v1/operations/{id}` -> {"state":"running|done|failed","profile_id"?, "error"?}.
- `ANY /v1/profiles/{id}/hermes/<path>` -> proxied to the Hermes API at `/p/<profile>/<path>` (or `/<path>` for the default profile) with the bridge adding that profile's bearer key. Only an allowlist of Hermes routes the app uses (health, capabilities, models, runs + events + stop + approval, sessions list/messages, jobs list/pause/resume/run). Any Authorization header from the phone is dropped. Streaming responses are passed through unbuffered.
- `GET /v1/devices` / `DELETE /v1/devices/{id}` are LOCAL only (CLI `rasul-bridge devices` / `rasul-bridge revoke <id>`), not reachable from phones.

## Limits and logs
- Max 32 concurrent paired-device WebSocket connections total, 16 per device; request body 1 MiB. Registered-certificate connections leave pre-auth admission immediately after TLS validation.
- Stage A (pre-auth): 256 connections, upgrade request within 3 s of accept and TLS completed within 10 s of accept; when full, evict uniformly at random including the newcomer. WebSocket receive queue: 2 frames.
- Stage B (anonymous awaiting proof): 32 connections, complete proof within 5 s of TLS completion, body at most 16 KiB (413 before reading an oversized body); random eviction among unprotected connections including the newcomer.
- Stage C (verified invitation and secret): one pairing, protected from eviction during the owner's 120 s decision. Only attempts matching the current unused invitation count toward the 5 per minute pairing limit.
- Floods above these application-level rates are handled by edge rate limiting.
- Audit log (JSON lines): time, device_id, method, route template, profile id, status. Never keys, secrets, prompts, bodies or QR text.
- Bridge secrets (server key, registry, per-profile Hermes keys it generates) in the bridge data dir with owner-only permissions (Windows: DPAPI-protected file or ACL to the current user only).

## Hermes profile keys
- For each profile without an `API_SERVER_KEY` the bridge can generate one (`rasul-bridge init-profiles`, explicit owner command): back up the profile `.env`, append the key, report that a Hermes gateway restart is needed. The bridge never restarts the gateway itself unless the owner configured `restart_command`, and then only when `/health/detailed` reports no active agents.
- Creating a profile from the phone: the bridge runs the Hermes CLI `hermes profile create <name>` (argument list, no shell) with only the template's allowed options, then the key step above, then reports `ready` after the gateway serves `/p/<name>/health`.
