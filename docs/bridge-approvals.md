# Phone approvals: computer-side contract

This optional bridge feature mirrors approvals that are waiting in the local
process. The computer's existing approval card stays active. The companion app
can use the routes below. The phone app ships the matching screen: pending
requests appear as cards under each bridge bot, with a banner and per-bot
badges while any wait.

## Setup

1. In the bridge's owner-controlled `config.toml`, add `approvals_port = 8788`.
   Choose a free port distinct from the paired-device port. The new listener is
   always bound to IPv4 loopback; there is no setting to expose it remotely.
   The feature is disabled when this setting is absent.
2. On the next owner-managed bridge start, each discovered profile receives a
   random credential in `approval-secrets/<profile>.secret` inside the bridge's
   own data directory. Keep it there. The directory is private to the current
   account, and files use mode 0600 on Linux or an inherited owner-only ACL on
   Windows. Never paste the credential into configuration or send it to a phone.
3. Copy the complete `rasul-approvals` directory from `hermes-plugin/` to the
   selected profile's user-plugin directory. Merge this single configuration
   line into that profile's settings, preserving other enabled plugins:

   ```yaml
   plugins: {enabled: [rasul-approvals], entries: {rasul-approvals: {settings: {secret_path: "<credential-file>"}}}}
   ```

   Replace the placeholder with the credential file's location, not its content.
   If the bridge uses a different internal port, add `port` beside `secret_path`.
   Load the plugin on the next owner-managed application start. A missing
   credential configuration leaves the plugin inactive. A bad configuration
   logs a fixed warning and leaves local prompts working. Newly discovered
   profiles receive their credential on the next bridge start.
   Credentials are loaded into memory at that start. Internal authentication
   does not access the credential files or acquire the data-directory lock;
   profiles discovered later cannot authenticate until the next start.

The integration uses the host's in-process approval queue and redactor. It does
not register an approval transport. Interactive command-line prompts without a
queue entry are not mirrored; automatic decisions and coalesced requests are
skipped. Install only for a host version providing the hook, queue snapshot,
exact-ID resolver, timeout accessor, profile identity and unload APIs. These
queue APIs are internal and need compatibility testing on host upgrades.

## Paired-device routes

All routes use the existing pinned TLS link with an active paired-device
certificate. They never forward to an upstream API. Identifiers and query
strings are matched strictly: profiles use `[a-z0-9][a-z0-9_-]{0,63}` and request
IDs are exactly 32 lowercase hexadecimal characters. No query parameters are
accepted. The existing paired-device connection and body limits also apply.

| Method | Route | Result |
|---|---|---|
| GET | `/v1/profiles/{id}/approvals` | 200, pending requests for this profile |
| GET | `/v1/profiles/{id}/approvals/events` | 200, chunked SSE |
| POST | `/v1/profiles/{id}/approvals/{request_id}/answer` | 200, records one permitted choice |
| GET | `/v1/approvals` | 200, pending requests of every profile, ordered by `expires_at` then `request_id` |
| GET | `/v1/approvals/events` | 200, chunked SSE for every profile |

The two all-profile routes use the same item shape, framing, auth, rate limit
and error codes as the per-profile routes. Their events carry `profile` and
`request_id`; a profile removed from discovery is skipped. Answers stay on the
per-profile route. Any other method or sub-path under `/v1/approvals` answers
404 `approvals_not_found`. A bridge without these routes answers 404, so a
client falls back to one stream per profile. Use the all-profile stream
(one connection per device) when several bots are present.

The list response is `{"approvals": [...]}`. Each item has exactly this shape:

```json
{
  "request_id": "00000000000000000000000000000001",
  "session_key": "sample-session",
  "profile": "default",
  "surface": "gateway",
  "command": "sample action",
  "description": "sample reason",
  "allow_session": true,
  "allow_permanent": true,
  "expires_at": 2000000000
}
```

`command` and `description` are force-redacted with the host's redactor,
including URL credentials, before leaving the plugin hook. They are display
text, never executable instructions. Each is limited to 2048 characters after
redaction; shortened text ends with `[truncated]` within that limit. The plugin
encodes request JSON as UTF-8 without escaping non-ASCII display text.
`expires_at` is Unix time in seconds,
computed at hook observation from the host's configured approval timeout
(default 300 seconds), capped at one hour in both plugin and bridge. A zero or
negative host timeout is already expired and is not mirrored. The queue remains
authoritative if it expires earlier.
The phone should hide expired entries and refetch after every SSE change.

The answer body has exactly one field: `{"choice":"once"}`. `once` and `deny`
are always allowed. `session` requires `allow_session`; `always` requires both
flags. A successful answer returns
`{"request_id":"00000000000000000000000000000001","state":"answered"}`.
This acknowledges recording at the bridge; the plugin then attempts exact-ID
settlement in the owning process. It never falls back to the oldest queue entry.
Only the first phone answer is recorded. If the computer already settled the
queue entry, that outcome remains authoritative and the phone entry is cleared.
There is no atomic ordering between a phone HTTP response and a local PC click;
the first settlement under the host queue lock determines execution.

The SSE stream sends an initial `snapshot` event with the complete list shape.
Subsequent `new` and `resolved` events contain only `profile` and `request_id`:

```text
id: 1
event: new
data: {"profile":"default","request_id":"00000000000000000000000000000001"}

id: 2
event: resolved
data: {"profile":"default","request_id":"00000000000000000000000000000001"}

```

`resolved` also covers expiry or withdrawal. Events never contain command text.
A slow consumer receives a fresh snapshot if it falls behind the bounded event
history. Comments provide a keepalive every 15 seconds. Reconnect with a fresh
GET and replace local state from the snapshot; event IDs are process-local,
and there is no cursor/resume query contract. Revocation closes the existing
paired link. After an answer, remove or disable the card and refetch the list.

## Errors and display messages

Errors are JSON `{"error":"<code>"}`. The phone screen shows the matching
message from this table inline on the card for a failed answer, or as the
screen's error with a Retry for a list/stream failure. An answer that returns
409, 410 or 404 is final: the card stays disabled and the list refetches.
A list or stream 404 — or a profile that discovery reports missing or not
ready — displays "This bot is no longer available on the PC." instead of the
request-level 404 message.

| HTTP | Code | English | العربية |
|---|---|---|---|
| 400 | `approvals_invalid` | The approval request is invalid. | طلب الموافقة غير صالح. |
| 400 | `approvals_invalid_choice` | This choice is not allowed for this request. | هذا الخيار غير مسموح لهذا الطلب. |
| 401 | `approvals_unauthorized` | The local approval connection is not authorized. | اتصال الموافقات المحلي غير مصرح به. |
| 404 | `approvals_not_found` | This approval is no longer available. | طلب الموافقة هذا لم يعد متاحًا. |
| 409 | `approvals_conflict` | This approval has already been answered or closed. | تمت الإجابة عن طلب الموافقة هذا أو إغلاقه. |
| 410 | `approvals_expired` | This approval has expired. | انتهت صلاحية طلب الموافقة هذا. |
| 429 | `approvals_rate_limited` | Too many approval requests. Wait a moment. | طلبات الموافقة كثيرة جدًا. انتظر قليلًا. |

Unknown requests and requests belonging to another profile return 404. An
expired request returns 410 while its tombstone is retained (until 60 seconds
after expiry), then 404. A repeated answer before expiry returns 409. Each
device may make 60 approval requests per rolling minute; opening an SSE stream
counts once. An unpaired link is refused with the existing 403 certificate
error. Disabled or unmatched routes return the existing 404 route error.

## Local plugin routes and security

The separate plaintext listener accepts only IPv4 loopback connections. The
per-profile credential stays in its owner-only file and is never sent in either
direction. Requests and successful responses use HMAC-SHA256 with that credential
as the key. The internal routes are never reachable through the paired-device
namespace or the WebSocket admission route. Paired certificates alone cannot
authenticate this listener. Do not forward its port through a proxy or tunnel.

Each request carries `X-Approval-Nonce` (32 random lowercase hexadecimal
characters), `X-Approval-Timestamp` (canonical positive integer Unix seconds),
and `X-Approval-Mac` (64 lowercase hexadecimal characters). The request MAC input
is the ASCII encoding of these fields, joined by one newline, with no trailing
newline: `plugin`, HTTP method, exact path, lowercase SHA256 of the exact body
bytes (empty for GET), nonce, timestamp. The bridge checks the MAC in constant
time, accepts clock skew of at most 60 seconds, and consumes each nonce once per
profile. Nonces remain cached through timestamp plus 60 seconds, including the
boundary. The cache holds at most 2048 live nonces per profile; saturation rejects new
requests rather than evicting replay protection. Authentication failures return
the existing 401 `approvals_unauthorized` error, without a signed success reply.

Each successful response carries `X-Approval-Mac`, computed over newline-joined
ASCII fields `bridge`, the originating request nonce, and lowercase SHA256 of
the exact response body. Distinct domain labels prevent request/response
reflection. The plugin checks the signature in constant time before parsing JSON
or using any field, for publication acknowledgements, answers and resolved
acknowledgements. Unsigned, tampered or replayed responses are ignored and produce
the existing fixed warning once per plugin instance; no credential or approval
text is logged.

Before every operation, the plugin sends an empty signed `GET .../hello` and
verifies the response on the same TCP connection. Only then does it send the
operation, including any approval content. The listener supports this two-request
exchange; the plugin refuses a closed hello connection instead of opening a new
one for publication. This prevents a process holding the port without the secret
from receiving approval content or supplying an answer. The hello reveals only
the profile route and authentication metadata. Plaintext HTTP provides no
confidentiality against a process able to inspect another account's network
traffic; the protected file and mutual authentication address port impersonation.

| Method | Route | Body / result |
|---|---|---|
| GET | `/internal/profiles/{id}/approvals/hello` | Empty body; returns `{}` as server proof |
| POST | `/internal/profiles/{id}/approvals` | Exact list-item shape above; returns `{"request_id":"..."}` |
| GET | `/internal/profiles/{id}/approvals/answers` | `{"answers":[{"request_id":"...","session_key":"...","choice":"once"}]}` |
| POST | `/internal/profiles/{id}/approvals/{request_id}/resolved` | `{}`; returns `{"request_id":"...","state":"resolved"}` |

The plugin uses a 0.5-second socket timeout and a bounded queue. The hook does
no network I/O and does not raise into the host. A failed publication produces
one fixed warning per plugin instance and remains tracked for withdrawal,
including when the bridge stored it but its acknowledgement was lost. Answers
for such requests are ignored. Failed withdrawals are retried until the local
deadline without blocking cleanup of other requests. While requests are tracked,
the worker polls every 0.2 seconds, ignores answers owned by another process,
and reports resolved entries when they disappear from the queue (including
computer answers, cancellation and expiry). Local prompts continue during
outages. There is no replay of skipped publications after an outage.

Approval content is memory-only, bounded to 1024 pending or answered requests,
and removed on resolution/expiry. Up to 1024 separate status tombstones reject
duplicate answers and do not consume live capacity. Resolved tombstones retire
at the earlier of expiry plus 60 seconds or resolution plus 120 seconds; expired
tombstones retire at expiry plus 60 seconds. The oldest closed entries may be
removed earlier when that separate bound is reached. The
event history stores only identifiers. A bridge restart clears all pending
state; existing computer prompts remain local. Phone answer attempts are
audited with device, profile, request ID, choice and recorded HTTP status, never command,
description, session text or plugin credential. Unknown choices are recorded as
`invalid` so arbitrary request text cannot enter the audit. Existing audit rotation and drop reporting
apply. A credential grants approval access to its profile, so keep its file
private. Same-account malicious software remains outside this boundary.
An answer recorded successfully is audited as 200 even if the phone disconnects
before receiving the response; the recorded answer can still be applied.
