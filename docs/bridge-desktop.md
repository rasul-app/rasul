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
of a profile; any profile may be named. Only when the dashboard's host record does not list that profile
(it was created after the dashboard started), the check and the update run through the default profile,
else through any profile the record lists. Any other discovery failure is reported as it is.
State is held in memory: a bridge restart drops a
queued update (state returns to `idle`).

| Method and suffix | Request | Response | Per minute |
| --- | --- | --- | --- |
| GET `/hermes/update` | none | status object | 12 |
| POST `/hermes/update` | `{mode:"when_idle"\|"now"}` | HTTP 202 status object | 6 |
| DELETE `/hermes/update` | none | HTTP 200 status object | 6 |

Status object: `{current, latest, behind, can_apply, reason, changes:[{title}], update_available, state,
active_agents, queued_at, result}`. `update_available` is Hermes' own flag and stays true while an update
is queued or running. `latest` is the newest upstream commit id or null; `changes` has at most 20 text
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
| PUT `/channels/{id}/enabled` | `{enabled}` | the channel row above plus `needs_restart` (true unless the desktop says it served the change to the running gateway at once; always true when `state` is `pending_restart`), read again after the change | 6 |
| POST `/channels/{id}/test` | none | `{ok, state, message, auth_posture?}` | 3 |
| POST `/channels/restart` | `{force}` | `{ok:true}` | 2 |
| POST `/channels/telegram/setup` | optional `{bot_name}`, at most 64 characters | `{pairing_id, deep_link, suggested_username, expires_at}` | 3 |
| GET `/channels/telegram/setup/{pairing_id}` | none | `{status:"waiting"\|"ready", bot_username?, owner_user_id?, expires_at?}` | 30 |
| POST `/channels/telegram/setup/{pairing_id}/apply` | `{allowed_user_ids, force}` | `{ok, bot_username, restart_started, needs_restart}` | 3 |
| DELETE `/channels/telegram/setup/{pairing_id}` | none | `{ok:true}` | 6 |
| POST `/channels/whatsapp/setup` | `{mode, allowed_users?}` | `{pairing_id, status, qr_payload?, expires_at?, account_name?, account_phone_last4?, error?}` | 3 |
| GET `/channels/whatsapp/setup/{pairing_id}` | none | the same shape | 30 |
| POST `/channels/whatsapp/setup/{pairing_id}/apply` | `{force, mode?, allowed_users?}` | `{ok, restart_started, needs_restart}` | 3 |
| DELETE `/channels/whatsapp/setup/{pairing_id}` | none | `{ok:true}` | 6 |
| PUT `/channels/{id}/settings` | `{values?:{KEY:string}, clear?:[KEY]}` | the channel row above plus `needs_restart` | 6 |

`{id}` matches `[a-z0-9_]{1,32}` and `{pairing_id}` matches `[A-Za-z0-9_-]{8,128}`; anything else is
404 before any backend call. Bodies are strict: unknown keys, wrong types or a missing key are 400
`desktop_invalid`. `allowed_user_ids` is 1 to 20 strings of decimal digits (at most 20 digits each);
repeats are dropped. Switching a channel on or off sends only `enabled` and the profile, never
any setting or secret.

WhatsApp setup (0.2.8): `mode` is `bot` or `self-chat`. `allowed_users` is a comma list of at most 20 phone numbers, each decimal digits with an optional leading `+`, no spaces; repeats are dropped, an empty string means none. `status` is one of `installing`, `starting`, `waiting`, `connected`, `error`, `cancelled`. `qr_payload` is returned only while `status` is `waiting`, and only when it is 1 to 1024 printable ASCII characters. The account phone number is reduced to its last 4 digits (`account_phone_last4`), the account id and the allow-list are never returned, and `account_name` that only looks like a phone number (digits, `+`, spaces, dashes) is reduced to its last 4 digits. `error` is never the desktop's text, which can hold local paths, names or numbers; it is one fixed code: `whatsapp_bridge_missing`, `whatsapp_install_failed`, `whatsapp_timeout`, `whatsapp_logged_out`, or `whatsapp_setup_failed` for anything else (the app translates the code). The bodies of the setup and apply calls are at most 1024 bytes, checked before they are parsed. Apply is refused with 409 `gateway_busy` while any agent works or the count is unknown, unless `force` is true. Unknown pairing 404 `channel_setup_not_found`, not connected yet 409 `channel_setup_not_ready` (with a fixed `message`). The strict digit check on `allowed_users` applies to this setup route only; the same list written through the channel settings route is checked by the desktop alone.

Channel settings (0.2.8): every key must be one of the `fields` of that channel in a fresh read of the channel list made by the bridge (otherwise 400 `channel_unknown_setting`; an unknown channel is 404 `channel_not_found`). A key is 1 to 64 capital letters, digits or underscores. A value is 1 to 4096 ASCII characters without control characters (any other character is refused with 400 `channel_setting_invalid`, because the desktop would drop it silently); a value that looks like a masked preview (contains `****` or the ellipsis character, is `***`, starts with the redaction marker, or has the shape of four characters, three dots, four characters) is refused with 400 `channel_setting_redacted`. At most 8 values and 8 keys to clear per call, none in both lists, and at least one in total; the body may be up to 256 KiB. Only `env`, `clear_env` and the profile are forwarded. A refusal by the desktop is the fixed code 400 `channel_setting_invalid`. The reply adds `needs_restart`, true unless the desktop says it served the new value to the running gateway at once (or the channel is waiting for a restart). Values are write-only: they never appear in a response or in the audit file, which records the action and key names only.

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

Errors: 409 `channel_conflict` (Hermes refused to enable the channel, with a fixed `message`),
404 `channel_not_found`, 409 `gateway_busy`, 502 `channel_setup_bad_link`, 410
`channel_setup_expired` (start a new setup), 404 `channel_setup_not_found`, 409
`channel_setup_not_ready` (the phone has not finished in Telegram yet; with a fixed `message`), 400
`desktop_invalid`, 429 `desktop_rate_limited`, 502 `desktop_error`.

## Logs (0.2.8)

| Method and suffix | Request | Response | Per minute |
| --- | --- | --- | --- |
| GET `/logs` | Optional `file` = `errors` (default), `agent` or `gateway`; `lines`, integer 1-200, default 100; `level` = `ERROR`, `WARNING`, `INFO` or `ALL` (default) | `{file, lines:[string]}` | 10 |

Any other query key, a repeated key, an unknown value or a value that is not plain letters and digits is 400
`desktop_invalid` before any backend call. Each name maps to one fixed upstream log of the selected profile.
The newest lines are returned, oldest first. Every line is redacted on the computer, then cut to 1000
characters; the whole body stays within 256 KiB, dropping the oldest lines first. An upstream 404 is 404
`logs_not_found`; any other upstream failure or malformed answer is 502 `desktop_error`.

Redaction replaces the secret with `[hidden]` (a home folder becomes `~`). Timestamps, levels and component names stay.
Each line is first cut to 4000 characters (a word cut in half at the cut is dropped; in a line with no space, the whole run of
letters, digits and `+/=_.~%-` that the cut lands in is dropped, unless it is the whole line). Every rule starts only at a
boundary, so a long run of one character is scanned once, not once per character. Measured with 200 lines of the worst
shapes found (long runs of slashes, backslashes, dashes, dots, quotes, brackets, `@` and `=`, with and without `Users` or
`home` words), one request takes under 2 seconds (the slowest case measured was 1.3 s on a busy computer). All lines are
redacted in a worker thread, off the bridge's event loop, one line per call, so the loop keeps ticking (the longest gap
measured was 0.05 s); each result is cut to 1000 characters.
Rules, in the order the bridge applies them:

- control characters (tab, line breaks, the escape character, the C0 and C1 ranges) and text-direction controls (U+061C,
  U+200E, U+200F, U+202A to U+202E, U+2066 to U+2069) are replaced by a space. The rest of a colour sequence such as
  `[31m` stays as plain text
- the real home folder of the account running the bridge becomes `~` (either slash style, any letter case, doubled
  backslashes); `HOST\name` or `DOMAIN\name` with the real account name becomes `~` as well
- any other `Users\<name>` folder becomes `~`, in the forms `C:\Users\name`, `\\?\C:\Users\name`,
  `\\?\UNC\HOST\Users\name`, `/c/Users/name`, `/mnt/c/Users/name`, `/cygdrive/c/Users/name`, `\\HOST\Users\name` and
  `\\HOST\C$\Users\name` (the host is hidden too), `file:///C:/Users/Zed%20Q` and `file:///C%3A/Users/Zed` (percent-encoded), 8.3 short names such
  as `ZEDQ~1`, and `/Users/name`. A name may contain spaces when a slash follows it directly; without a following slash only
  the first word is the name
- `/home/<name>` and `\\wsl$\<distro>\home\<name>` (also `wsl.localhost`) become `~`
- URL user information (everything up to the last `@` before the path, also for a scheme-relative `//user:pass@host`),
  the `;jsessionid=` path parameter, every query value and the fragment (the keys stay); the path of a Slack or Discord
  webhook (the host stays)
- Telegram bot tokens (digits, a colon, then 30 or more letters, digits, `_` or `-`)
- the whole value of an `Authorization`, `Proxy-Authorization`, `X-Api-Key`, `Cookie` or `Set-Cookie` header, quoted
  (`'Authorization': 'Basic ...'`, any scheme, an optional `b` prefix) or not (then up to the end of the line or the next
  closing bracket); a header written as a pair (`('Authorization', 'Basic ...')`, `(b'authorization', b'...')`); a header
  inside a quoted block after a literal backslash-r or backslash-n; the value after `Bearer`; and the rest of an `Authentication: Basic ...` line (also Digest, Negotiate, NTLM,
  Bearer, Token), while `Authentication: failed` stays
- keys with a known prefix: `sk-`, `xox?-`, `ghp_` (and the other `gh?_` forms), `github_pat_`, `AIza`, `hf_`, `glpat-`,
  `AKIA` or `ASIA` plus 16 characters, `sk_live_`, `sk_test_`, `rk_live_`, `rk_test_`, `npm_`, `pypi-`
- JWT-like text: `eyJ...` header and payload, three dot-separated parts of 20 or more characters each, and the three-part
  chat-bot token shape (24, 6 and 27 or more characters)
- a command-line flag whose name ends in `key`, `token`, `secret`, `password`, `passwd`, `pwd` or `passphrase`, followed by a
  space and its value (`--token x`)
- the value after a name that ends in `key`, `token`, `secret`, `password`, `passwd`, `pwd`, `passphrase` or `auth` (and the
  same with a trailing `_id` or `_base`, as in `aws_access_key_id` and `secret_key_base`), followed by `=` or `:`. The
  words `key`, `token`, `secret` and `auth` must start a word: they begin the name, follow `_`, `-` or `.`, are a CamelCase
  capital (`apiKey`), or follow `api`, `access`, `secret`, `private`, `auth`, `bot`, `refresh`, `client`, `master`, `session`,
  `pass`, `ssh`, `id`, `csrf`, `xsrf`, `jwt`, `app`, `webhook`, `fcm`, `push`, `device`, `priv`, `encryption`, `signing`,
  `consumer` or `shared` (`apikey`, `csrftoken`); so `monkey: banana` and `turkey=1` stay readable. The password words
  (`password`, `passwd`, `pwd`, `passphrase`) match anywhere in a name (`PGPASSWORD`, `dbpassword`). The name may have a prefix, dotted
  (`client.token: x`) or not. The value is one word, a quoted string (with an optional `b`, `r`, `u` or `f` prefix), a whole
  tuple (`_auth = ('user', 'pw')`), a list or an object in brackets or braces, two levels deep (`{"token": {"value": "x"}}`), a call (`HTTPBasicAuth('admin', 'pw')`), or a placeholder in square brackets of any
  length without spaces (`[REDACTED]`, `[***]`), replaced as a whole. Only a dotted name ending in `auth` before `:` keeps
  its message, so a logger such as `gateway.auth:` is not touched
- email addresses
- hexadecimal runs of 32 or more characters, and runs of 32 or more base64 or base64url characters (letters, digits, `+`,
  `/`, `_`, `-`) that contain at least one digit and one letter of any case. A run is kept when it reads as a name: a UUID, or
  at most 64 pieces separated by `/`, `_`, `-` or `+` that are each a word with at most one group of digits inside and up to
  four at the end, in one case or Capitalized (so snake_case and CamelCase identifiers, paths and `request-<uuid>` stay)
- phone-like numbers: 9 or more digits in a row, a `+` number written with spaces, dashes or brackets, or a local number that
  starts with 0 and has three groups of digits split by spaces or dashes (`0944 123 456`)

Shorter numbers, dotted module names, UUIDs, plain words and URLs without credentials are left as they are.

Not caught (documented limits): a secret that has no digit and no known prefix (letters only), or that is shorter than
32 characters without a name, flag or header in front of it; a long run of letters with a single group of digits, which reads
like a name; a value split by quotes inside it (`token=abc'def'ghi` hides only `abc`); a list that holds another list, or an object nested more than two levels deep, under a secret name; names in other languages or scripts
and names that are not in the list above (`sessionid=`, `credentials=`, `signature=`); a phone number that is not written in
one of the forms above (for example `0944 123456` with two groups, or with dots); an address without a dot in its domain
(`zed@localhost`) or with a non-ASCII domain; the header line of a private key (`-----BEGIN PRIVATE KEY-----`), whose body
is only hidden where it forms a long run; a header written without a separator (`X-API-KEY abc`); and free text that is not
a recognised secret shape, such as a password quoted in a sentence. The opposite also happens: ten-digit times, chat ids of
9 or more digits and 40-character commit ids are hidden by the phone and hexadecimal rules, and a `/home/<name>` folder of a
service account is hidden like any other.
