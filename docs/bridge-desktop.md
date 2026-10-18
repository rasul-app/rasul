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
Memory review, quick commands, adding MCP servers and general command dispatch are absent.

## Hermes update (0.2.6)

Routes use the same prefix and rules as above. The update is a property of the installation, not
of a profile; any served profile may be named. State is held in memory: a bridge restart drops a
queued update (state returns to `idle`).

| Method and suffix | Request | Response | Per minute |
| --- | --- | --- | --- |
| GET `/hermes/update` | none | status object | 12 |
| POST `/hermes/update` | `{mode:"when_idle"\|"now"}` | HTTP 202 status object | 6 |
| DELETE `/hermes/update` | none | HTTP 200 status object | 6 |

Status object: `{current, latest, behind, can_apply, reason, changes:[{title}], state, active_agents,
queued_at, result}`. `latest` is the newest upstream commit id or null; `changes` has at most 20 text
titles of at most 200 characters; `state` is `idle|queued|running|done|failed`; `active_agents` is the
gateway's current count or null; `queued_at` is an ISO time while queued; `result` is
`{ok, from, to, message}` or null. `reason` (when `can_apply` is false) is one of `up_to_date`,
`not_applicable`, `check_failed`, `dashboard_not_running`, `bridge_elevated`, `dashboard_elevated`,
`cannot_verify_dashboard_rights`, `update_in_progress`. `behind` is a number, or null when Hermes
knows an update exists but cannot count the commits (`can_apply` stays true then).
Raw update log lines, paths, environment and commit ids of the previous version never appear.

`when_idle` queues: the bridge polls the gateway every 15 seconds and starts the update after two
consecutive polls with nothing busy; the queue expires after 24 hours. Busy means active agents, API
runs, background delegations or a busy gateway as reported by the gateway health call (the key of the
default profile, else of any profile that has one). If that call fails, is unreadable or no profile has a
key, the state is unknown and counts as busy. `now` starts at once
(Hermes drains and restarts itself). Only one update runs at a time; at most 2 applies per hour.
While it runs the dashboard restarts; the bridge keeps waiting (up to 30 minutes) and reports
`done` or `failed` from the update receipt. The previous commit id stays in the bridge audit log
only. To undo, follow the undo procedure that Hermes documents on the PC.

A background failure ends the state in `failed` ("The update stopped unexpectedly."), never in a stuck
`queued` or `running`.

Errors: 403 `bridge_elevated` (the bridge runs as administrator or root; run it as the normal
user), 403 `dashboard_elevated` (the dashboard that would run the update has administrator or root
rights), 403 `cannot_verify_dashboard_rights` (those rights could not be read), 403 `dashboard_not_running`, 403 `not_applicable` or `check_failed`, 409 `up_to_date`,
409 `update_in_progress`, 409 `update_running` and `nothing_queued` (DELETE), 429
`update_rate_limited`, 502 `update_not_started`, 400 `desktop_invalid` (bad body).

## MCP manage (0.2.6)

| Method and suffix | Request | Response | Per minute |
| --- | --- | --- | --- |
| PUT `/mcp/{name}/enabled` | `{enabled}` | `{name, enabled}` after rereading | 6 |
| POST `/mcp/{name}/test` | none | `{name, ok, tools:[{name,description}], prompts?, resources?, error?}` | 3 |
| GET `/mcp/catalog` | none | `{entries:[{id,name,description,installed,enabled,installable}]}` | 6 |
| POST `/mcp/catalog/install` | `{id}` | `{id, state:"installed"}` | 1 |

`{name}` must be an existing server of the selected profile that is not provided by a plugin
(otherwise 400 `desktop_invalid`). Install accepts only an `id` present in a fresh catalog read
made by the bridge itself that is `installable`: no credentials or OAuth required, no repository
build step, not already installed. Free-form commands or URLs are never accepted. Command,
arguments, environment, headers, URLs and credentials never appear in any response. A test is
bounded by the 10-second upstream deadline.

## Messaging channels (0.2.7)

| Method and suffix | Request | Response | Per minute |
| --- | --- | --- | --- |
| GET `/channels` | none | `{channels:[{id,name,description,enabled,configured,gateway_running,state,error_code,error_message,home_channel_set,fields:[{key,required,is_set,is_list,prompt}]}]}` | 12 |
| PUT `/channels/{id}/enabled` | `{enabled}` | the channel row above plus `needs_restart` (true when `state` is `pending_restart`), read again after the change | 6 |
| POST `/channels/{id}/test` | none | `{ok, state, message, auth_posture?}` | 3 |
| POST `/channels/restart` | `{force}` | `{ok:true}` | 2 |
| POST `/channels/telegram/setup` | optional `{bot_name}`, at most 64 characters | `{pairing_id, deep_link, suggested_username, expires_at}` | 3 |
| GET `/channels/telegram/setup/{pairing_id}` | none | `{status:"waiting"\|"ready", bot_username?, owner_user_id?, expires_at?}` | 30 |
| POST `/channels/telegram/setup/{pairing_id}/apply` | `{allowed_user_ids, force}` | `{ok, bot_username, restart_started, needs_restart}` | 3 |
| DELETE `/channels/telegram/setup/{pairing_id}` | none | `{ok:true}` | 6 |

`{id}` matches `[a-z0-9_]{1,32}` and `{pairing_id}` matches `[A-Za-z0-9_-]{8,128}`; anything else is
404 before any backend call. Bodies are strict: unknown keys, wrong types or a missing key are 400
`desktop_invalid`. `allowed_user_ids` is 1 to 20 strings of decimal digits (at most 20 digits each);
repeats are dropped. Switching a channel on or off sends only `enabled` and the profile, never
any setting or secret.

Responses are rebuilt from checked fields. Channel rows are dropped when their id, flags or name are
malformed. Tokens, allow-list values, redacted previews, local paths, documentation and callback
addresses, help text and the setup poll token never appear; `fields` only says which settings exist
and whether each one is set. The setup start call returns only the four listed values, and the
status call never returns the bot token, which stays on the computer and is saved there by the
apply call. `deep_link` is returned only when it is an `https` link to `t.me` or `telegram.me`
with no user information, port or fragment, a path of 1 to 64 letters, digits, underscores or
slashes, and a query of at most 256 letters, digits, `_`, `=`, `&` or `-`; otherwise 502
`channel_setup_bad_link`.

A restart and an apply refuse while agents are working: 409 `{error:"gateway_busy", active_agents}`
where `active_agents` is the count, or null when it cannot be read (unknown counts as busy). Sending
`force: true` skips that check. Both write an audit line with only the action and the force flag.

Errors: 409 `channel_conflict` (Hermes refused to enable the channel, with a cleaned `message`),
404 `channel_not_found`, 409 `gateway_busy`, 502 `channel_setup_bad_link`, 410
`channel_setup_expired` (start a new setup), 404 `channel_setup_not_found`, 409
`channel_setup_not_ready` (the phone has not finished in Telegram yet; with a cleaned `message`), 400
`desktop_invalid`, 429 `desktop_rate_limited`, 502 `desktop_error`.
