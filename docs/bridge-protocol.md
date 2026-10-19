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
- `GET /v1/approvals` and `GET /v1/approvals/events` -> pending approvals and an SSE stream for all profiles (one stream per device; older bridges answer 404, so clients fall back to the per-profile routes). See `docs/bridge-approvals.md`.
- `GET /v1/profile-templates` -> [{"id","label"}] from the bridge config (owner-defined; default only "blank").
- `GET /v1/operations/{id}` -> {"state":"running|done|failed","profile_id"?, "error"?}.
- `ANY /v1/profiles/{id}/hermes/<path>` -> proxied to the Hermes API at `/p/<profile>/<path>` (or `/<path>` for the default profile) with the bridge adding that profile's bearer key. Only an allowlist of Hermes routes the app uses (health, capabilities, skills, toolsets, models, model options, runs/events/actions, sessions list/read/title update/delete/messages, jobs list/create/read/update/delete/actions). Any Authorization header from the phone is dropped. Streaming responses are passed through unbuffered.
- `POST /v1/profiles/{id}/uploads`, `PUT /v1/uploads/{id}/chunks/{index}`, `POST /v1/uploads/{id}/complete`, `DELETE /v1/uploads/{id}` -> send a file from the phone to the agent. See "Sending files" below.
- `GET /v1/devices` / `DELETE /v1/devices/{id}` are LOCAL only (CLI `rasul-bridge devices` / `rasul-bridge revoke <id>`), not reachable from phones.

## Limits and logs
- Max 128 concurrent paired-device WebSocket connections total, 64 per device (refused links leave one audit line per device per minute); request body 1 MiB. Registered-certificate connections leave pre-auth admission immediately after TLS validation.
- Stage A (pre-auth): 256 connections, upgrade request within 3 s of accept and TLS completed within 10 s of accept; when full, evict uniformly at random including the newcomer. WebSocket receive queue: 2 frames.
- Stage B (anonymous awaiting proof): 32 connections, complete proof within 5 s of TLS completion, body at most 16 KiB (413 before reading an oversized body); random eviction among unprotected connections including the newcomer.
- Stage C (verified invitation and secret): one pairing, protected from eviction during the owner's 120 s decision. Only attempts matching the current unused invitation count toward the 5 per minute pairing limit.
- Floods above these application-level rates are handled by edge rate limiting.
- Audit log (JSON lines): time, device_id, method, route template, profile id, status. Never keys, secrets, prompts, bodies or QR text.
- Bridge secrets (server key, registry, per-profile Hermes keys it generates) in the bridge data dir with owner-only permissions (Windows: DPAPI-protected file or ACL to the current user only).

## Hermes profile keys
- For each profile without an `API_SERVER_KEY` the bridge can generate one (`rasul-bridge init-profiles`, explicit owner command): back up the profile `.env`, append the key, report that a Hermes gateway restart is needed. The bridge never restarts the gateway itself unless the owner configured `restart_command`, and then only when `/health/detailed` reports no active agents.
- Creating a profile from the phone: the bridge runs the Hermes CLI `hermes profile create <name>` (argument list, no shell) with only the template's allowed options, then the key step above, then reports `ready` after the gateway serves `/p/<name>/health`.

## Sending files (phone to agent)
The phone sends any type of file; the bridge saves it in the profile's Hermes document cache (`<profile home>/cache/documents`) and tells the agent where it is with the same note Hermes's own messaging gateway adds for a document. Pictures keep their current path. A direct connection (no bridge) cannot send files.

Limits: at most 4 files per message; each file 1 byte to 50 MiB (52,428,800 bytes); chunks of 786,432 bytes (they fit the 1 MiB request body limit); at most 4 open uploads per device; an unfinished upload is deleted after 1 hour; a finished file id can be used in a run for 24 hours (ids are kept in memory, so a bridge restart forgets them).

Routes (registered device required; the profile must exist). Errors are `{"error": "<code>"}`.
1. `POST /v1/profiles/{profile}/uploads` with `{"name": "<original file name>", "size": <int>, "type": "<mime or empty>"}` -> `201 {"upload": "<32 lowercase hex>", "chunk_size": 786432}`. Errors: `400 invalid_upload` (bad name, size or type), `413 file_too_large`, `429 too_many_uploads`, `507 disk_full` (free disk space is less than the size plus 1 GiB).
2. `PUT /v1/uploads/{upload}/chunks/{index}` with the raw bytes as the body (`Content-Type: application/octet-stream`). The index starts at 0; every chunk is exactly `chunk_size` bytes except the last; chunks arrive in order. Repeating the last accepted index with identical bytes is accepted (a retry). -> `204` (sent with `Content-Length: 0`). Errors: `404 upload_not_found` (unknown, expired, or another device's upload), `409 chunk_out_of_order`, `400 invalid_chunk` (wrong length, or past the declared size), `507 disk_full` (the disk is full), `500 upload_failed` (any other write problem; the upload is deleted).
3. `POST /v1/uploads/{upload}/complete` with `{"sha256": "<64 lowercase hex>"}` -> `200 {"file": "<32 lowercase hex>", "name": "<cleaned name>", "size": <int>}`. The bridge checks the total size and the SHA-256, then moves the file into the profile's `cache/documents` as `rasul_<8 random hex>_<cleaned name>` (it never overwrites). The response never contains a path on the computer. Errors: `404 upload_not_found`, `400 checksum_mismatch` (the upload is deleted), `409 upload_incomplete`, `400 invalid_upload` (the body is not a lowercase SHA-256), `507 disk_full`, `500 upload_failed` (any other problem saving the file; the upload is deleted). The route also answers `500 upload_failed` when the bridge cannot create the upload on start.
4. `DELETE /v1/uploads/{upload}` -> `204`, also when it is already gone or belongs to another device. Removes the partial data.
5. `POST /v1/profiles/{profile}/hermes/v1/runs` (the existing proxied route): the JSON body may contain `"rasul_files": ["<file id>", ...]` (1 to 4 ids). The bridge removes that field before forwarding, checks that each id belongs to this device and this profile, is not older than 24 hours and that the file is still there unchanged (same size and modification time), and adds one note per file before the user's text in `input` (a text input, or the last message of a message list). The note is the one Hermes uses for a document that is cached but not inlined: for a `text/*` file "[The user sent a text document: '<name>'. It is saved at: <path>. Its content is not inlined here. Read the cached file yourself before answering when the user's request involves its contents.]", for any other file "[The user sent a document: '<name>'. It is saved at: <path>. Its text is not inlined here (it's a binary format such as PDF or DOCX). To read it, extract the document's text yourself — for example with the terminal tool or the ocr-and-documents skill — before answering, instead of asking the user to paste the contents.]". Any bad id (unknown, another device's or profile's, expired, changed, more than 4, none) gives `400 file_unavailable` and nothing is forwarded; a body whose `input` has no place for the notes gives `400 invalid_run_input`. The existing media note still applies.

Name cleaning: Unicode is kept (Arabic names stay); path separators, control characters, characters Windows refuses (`< > : " | ? *`) and bidirectional overrides are dropped, as are lone surrogates; an ampersand, percent sign, caret, dollar sign, backtick or semicolon (shell syntax; the name appears in the agent's note) becomes `_` (parentheses and spaces stay); leading and trailing dots and spaces are stripped; a Windows reserved name (`CON`, `NUL`, `COM1`, ...) gets a leading `_`; the extension is kept; at most 120 characters; an empty result becomes `file`.

Safety: the bridge never opens, previews, unpacks or runs an uploaded file. Partial data lives in the bridge data folder (private permissions), not in Hermes. Unfinished data left by a restart is deleted at start. The audit log records the route template, never the file name or content.

An older bridge answers route 1 with `404 {"error": "Route not found"}`: the app shows "Update the Rasul Bridge on your PC to send files."
