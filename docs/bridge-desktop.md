# Bridge desktop contract

All routes below require a paired device and use the bridge-resolved profile namespace
`/v1/profiles/{id}/desktop`. The existing `/hermes/` forwarder remains separate.
The backend must already be running locally. The bridge never starts a service.

| Method and suffix | Request | Response | Requests per minute |
| --- | --- | --- | --- |
| GET `/jobs/{job_id}/runs` | Optional `limit`, integer 1–100, default 20 | `{runs:[{id,title,started_at,ended_at,is_active,preview}]}` | 10 |
| GET `/skills` | No query or body | `{skills:[{name,description,category,enabled,essential?}]}` | 30 |
| PUT `/skills/toggle` | `{name,enabled}`, at most 1 KiB | `{name,enabled}` after rereading effective state | 6 |
| GET `/skills/catalog` | No query or body | `{skills:[{identifier,name,description,installed}]}` | 6 |
| POST `/skills/install` | `{identifier}`, at most 1 KiB | HTTP 202 `{identifier,state:"started"}` | 1 |
| GET `/mcp` | No query or body | `{servers:[{name,transport,enabled,source,plugin}]}` | 12 |
| GET `/usage` | Optional `days`, integer 1–365, default 30 | `{daily:[{day,input_tokens,output_tokens,cache_read_tokens,reasoning_tokens,estimated_cost,sessions,api_calls}],totals:{input_tokens,output_tokens,cache_read_tokens,reasoning_tokens,estimated_cost,sessions,api_calls},period_days}` | 6 |

Limits use a rolling 60-second window per device, profile and route. Job identifiers
contain 1–200 ASCII letters, digits, underscores or hyphens. Numbers use canonical
positive decimal notation, without signs, spaces, leading zeroes or encoding.
Each device has its own 256-key capacity across profiles and routes; one device
cannot exhaust another device's capacity.
Unknown or duplicate query/body keys, nonempty read bodies, wrong body types and
oversized mutation bodies return HTTP 400 `{error:"desktop_invalid"}`.
Unknown routes or unsupported methods return 404; unknown profiles return 404.
The literal profile id `current` is refused with 404 because the backend interprets
it as an ambient-profile alias, even if a directory with that name exists locally.
Rate exhaustion returns 429 `{error:"desktop_rate_limited"}`. No route accepts a
phone-supplied profile override, URL, credentials, or arbitrary backend target.

Skill names must match an installed skill from the selected profile. Toggle
responses reflect a fresh read, including any backend-enforced essential-skill
policy. Uncategorized skills have a null `category`. An optional `essential`
boolean is included only when the backend supplies it.
Catalog entries come exclusively from `/api/skills/hub/official`. Install requires
an exact identifier from a successful catalog read by this device for this profile
in the preceding 60 seconds, with the same discovered backend token. Identifiers
have the form `official/name` or `official/category/name` (including nested categories), with ASCII letters, digits, underscores or
hyphens in each component. The phone must show confirmation before sending install.
The bridge makes one spawn request and never retries it. The receipt means spawning
started, not installation completed. Reread `/skills` to observe installation;
an interrupted request has an uncertain outcome and must not be automatically retried.

Run fields are projected; raw session columns are never returned. Times are epoch
seconds; `title`, `ended_at`, and `preview` may be null. `is_active` is a boolean,
not an execution success indicator. To prevent upstream fallback across profiles,
every returned row must carry the selected profile's owner marker internally.
Unmarked output-only rows or mismatched rows cause 502 `desktop_error` for the
whole response. Empty histories are allowed. This is a current history limitation.

MCP lists configuration, not health; `plugin` is a string or null. URL, command,
arguments, environment, authentication and tool configuration are omitted.
Usage counters/cost may be null when the backend SQL aggregate is null. Totals
use the same names as daily counters, without `day`; backend `total_*` fields
are mapped to these names. Cost is the backend's estimated cost. Daily dates are
local calendar dates. Rate-limit information continues to use the existing
API-server `/api/model/options` route under the `/hermes/` namespace.

When discovery or owner authentication cannot establish a local backend, return
503 `{error:"desktop_unavailable"}`. The phone should show a clear instruction to
open the desktop application on the PC. Upstream failures, redirects, malformed
responses, excess response size and timeout return 502 `{error:"desktop_error"}`.
Upstream 401/403 text and headers are never forwarded. Pairing admission retains
its existing 403 response. A response that contains the local credential is rejected.

Discovery uses the owner rendezvous record and adjacent private token file, never
a port alone. The record's token fingerprint, protocol version and live owner
PID/role identity must agree. The record and its parent must belong to the current
OS user on Unix; Windows uses the per-user state directory, matching the producer's
ownership rule. The requested profile must be in the record's served `profiles`
list before identity or profile requests. An empty recorded `home` is supported;
when nonempty it must resolve to the configured profile root (case-insensitive on
Windows). After a successful identity response, the bridge requests
`GET /api/status?profile=<id>` on that same proven connection. Its `hermes_home`
must be an absolute path resolving to the selected profile's directory after
path normalization and platform case normalization. For `default` this is the
configured root; for a named profile it is that root's `profiles/<id>` directory.
An empty recorded `home` never substitutes for this proof. A missing or mismatched
status home, a malformed identity, or an unavailable persistent connection rejects
that role with 503; discovery can try the next role.
Before every authenticated request, the bridge checks the live process start
fingerprint and listener ownership, opens TCP without sending bytes, then proves
that the server end of that established connection belongs to the same process.
The proof matches both loopback endpoints and ports, including the bridge's local
ephemeral port, using the operating system's connection ownership data. Only after
another process-incarnation check does it write the request on that same socket.
An exact connection tuple with a pending handshake or an unassigned socket owner
is polled on the same socket with short sleeps for at most two seconds. On Linux,
inode zero is always pending and never proves ownership. No request bytes are
written during this wait. An absent tuple or a different owner fails immediately.
Windows process fingerprints allow one centisecond of rounding difference;
Linux fingerprints must match exactly.
Missing or mismatched proof closes the connection without sending and returns 503;
unsupported operating systems also return 503. Legacy records require a matching
process creation time. Only literal IPv4 loopback is dialed; IPv6-only ownership
rows fail closed. Gated deployments
that cannot authenticate the owner with the local token are unavailable. Credentials
remain in process memory and are excluded from responses and audit records.
Requests have a 10-second total deadline and a 1 MiB upstream response cap;
redirects are refused. Desktop audit records contain only a static route name and status.
Memory review, quick commands, MCP mutation and general command dispatch are absent.
