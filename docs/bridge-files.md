# Files from the agent to the phone

The agent can hand a file to a paired phone: PDF, document, spreadsheet, archive, text, image, audio, video. The phone never sees the file's path on the computer. It sees a link carrying a one-time-per-file token and downloads the bytes from the bridge over the same encrypted link.

## What the agent writes

The agent names a file with a tag on its own line:

    MEDIA:/path/to/report.pdf

The bridge recognises the same tag forms the agent does (plain, quoted, backticked, wrapped in `*` or `_`), for absolute paths and for the delivery extensions listed in `bridge/rasul_bridge/files.py` (`EXTENSIONS`).

## What the phone receives

A tag the agent wrote in its own reply is replaced by a markdown link:

    [report.pdf (1.2 MB)](rasul-file://<token>)

* `<token>` is 43 characters of `A-Z a-z 0-9 - _` (`secrets.token_urlsafe(32)`).
* The link text is the file name (square brackets replaced by parentheses) and the size in B, KB or MB.
* An older app shows this as ordinary link text and never shows a path. A current app renders it as a file card and treats the `rasul-file://` scheme as "download from the bridge".
* A tag in text the agent did not write (tool results and previews, reasoning, sub-agent output, user, tool and system messages, the request itself) is never turned into a link and its path is not checked: it is shown as `[file]`.
* A tag that cannot be delivered (missing, denied, not a regular file, too large, unsupported type) becomes `[file not available: name]` or `[file not available]`. Nothing else of the tag remains.
* Data URLs the agent already inlined for small images (`![image](data:...)`) are left untouched. An image the agent did not inline (larger than its limit) arrives as a normal file link; use the extension to show it as an image.

Places where text is rewritten:

| Route (under `/v1/profiles/{id}/hermes`) | Fields that can become links | Everything else |
|---|---|---|
| `GET /v1/runs/{run}/events` (server-sent events) | `message.delta.delta`, `message.interim.text`, the `output` of every `run.*` event | every other string: tags become `[file]` |
| `GET /v1/runs/{run}` | `output` | every other string: tags become `[file]` |
| `GET /api/sessions/{id}/messages` | `content` of messages with `role` `assistant` | every other string and message: tags become `[file]` |

The agent's history answer is an object whose messages are in a `data` list (older servers use a `messages` list); the bridge treats either as the message list, and every other key is stripped like any other text.

The history body is buffered in memory (limit 64 MB). A longer one is refused with `413` and `{"error": "history too large"}`; show "history too large" and let the user open a shorter session.

Streaming detail: a tag can arrive split over several `message.delta` events. The bridge holds back text from the start of a possible tag until the end of that line, so no fragment of a path is ever sent. A held line longer than 4096 characters is released at once with its tags replaced (an unfinished tag becomes `[file not available]` and the rest of that line is dropped). The held text is released, rewritten, in a later `message.delta` (the `seq` of every original event is kept; an event whose text was held carries an empty `delta`). If the run ends first, the held text is sent as one extra `message.delta` (it has no `id:` line) just before the terminal event. Replaying a stream from `after=<seq>` starts a new filter, so a tag cut in half by the replay point shows only its tail, never the start of the path.

## Download

    GET /v1/files/<token>

Same encrypted link and device certificate as every other `/v1` route.

Success: `200`, with `Content-Type` from the extension (`application/octet-stream` when unknown), `Content-Length`, `Content-Disposition: attachment; filename="ascii-name"; filename*=UTF-8''percent-encoded-name`, `X-Content-Type-Options: nosniff` and `Cache-Control: no-store`. Files of type `.html`, `.htm`, `.svg`, `.xml` and `.js` are sent as `application/octet-stream` with `Content-Security-Policy: sandbox`. **The app must never render a downloaded file inline in a WebView**; hand it to the system viewer or a share sheet instead. The body is sent in 64 KB chunks read from the open file, so a 50 MB file is never held in memory.

Errors (JSON `{"error": "<code>"}`):

| Status | Code | Meaning |
|---|---|---|
| 403 | `Registered device certificate required` | not a paired device |
| 404 | `file_not_found` | unknown token, malformed token, or a token minted for another device (indistinguishable on purpose) |
| 410 | `file_expired` | older than 24 hours |
| 410 | `file_changed` | the file's size, modification time or file identity differs from when it was offered (replaced or edited) |
| 410 | `file_unavailable` | the file was removed, became unreadable, or no longer passes the delivery rules |
| 429 | `file_rate_limited` | more than 30 downloads in a minute from this device |

If the file shrinks while it is being sent, the connection is closed without a second response; treat a short body as a failed download.

## Rules the bridge applies

* The path must be absolute and resolve (symbolic links followed) to an existing regular file with a delivery extension, readable, at most 50 MB. Network shares, mapped network drives (a drive letter the system reports as remote, and any path that resolves to a share or to such a drive; the system is asked about each drive letter at most once a minute, so a newly mapped drive is noticed within a minute) and device paths (`//host/...`, `\\host\...`, `\\.\...`, `\\?\...`) and alternate data streams (a `:` after the drive letter) are refused before the disk is touched.
* The same refusals the agent applies: system directories, credential folders in the user's home, the agent's own credential and history files (for the main home and every profile, including its cache credential files and kanban boards).
* Also refused by default: the user's roaming and local application-data folders, every folder directly under the home directory whose name starts with a dot (`~/.ssh`, `~/.aws`, `~/.config`, `~/.docker`, `~/.kube`, and any tool's folder). The rule holds when such a folder is reached through a symbolic link or junction, from either side of the link (the folders are resolved before the path is compared), and nothing under a home directory that cannot be resolved is offered (the other refusals still apply, also when no home directory is known at all; the list of dot folders under the home is read at most once a minute); any file whose name contains "credential" or matches `*oauth*.json` or `*token*.json`; `*.jks`, `*.keystore`, `*.pem`, `*.key`, `*.p12`, `*.pfx`, `*.kdbx`, `id_rsa*`, `id_ed25519*`, `.env` and `.env.*`. Ordinary folders (Documents, Desktop, Downloads, project folders) stay deliverable. The agent's own cache folders and folders named in its allow list are exempt from the folder rules, never from the file-name rules.
* Never delivered under any setting: anything inside the bridge's own data folder.
* Strict mode follows the agent's configuration. The bridge reads `gateway.strict`, `gateway.trust_recent_files`, `gateway.trust_recent_files_seconds` and `gateway.media_delivery_allow_dirs` from the profile's own `config.yaml` (the main profile uses the main home, a named profile its own folder), and `HERMES_MEDIA_DELIVERY_STRICT=1` in the bridge's own environment also turns strict mode on. A `HERMES_MEDIA_DELIVERY_STRICT` line in the agent's `.env` file is not seen by the bridge: set `strict` in `config.yaml` instead. The values are read like the agent reads them: strict is on unless the value is exactly one of the spellings the agent's YAML 1.1 parser reads as false or null (`false`/`False`/`FALSE`, `no`/`No`/`NO`, `off`/`Off`/`OFF`, `null`/`Null`/`NULL`, `~`, empty, or a number equal to zero such as `0`); mixed case such as `FaLsE` and a quoted "false" are on, and so is a flag followed by a deeper-indented continuation line or a `<<` merge key in the section, and a missing file means the agent's defaults. A file that cannot be read or understood (unreadable, not UTF-8, byte-order mark, tabs, a flow-style `gateway:` section) turns strict mode on. Files in the system temp folder are delivered only when written within the recency window. Windows device names (`CON`, `PRN`, `AUX`, `NUL`, `COM0-9`, `LPT0-9` and their superscript-digit forms, with any extension) are refused. A tag that starts with a quote, backtick or backslash but is not a complete path is replaced by the not-available note, with the rest of its line. In strict mode only files in the agent's cache folders, in an allow-listed folder, or written within the recency window (default 10 minutes; none if `trust_recent_files` is off) are delivered.
* Tokens are kept in memory only, last 24 hours, are bound to one device, one profile and the resolved path, and are limited to 200 per device (oldest dropped). Offering the same unchanged file again reuses its token. A file already offered to this device stays available until its link expires (24 hours) while it is unchanged, also after the recency window has passed; a changed file, another device or profile, an expired link and every other refusal above still apply. A bridge restart invalidates all tokens; the app should show the usual "no longer available" state for `404` and `410`.
* Audit log: `file_offered`, `file_sent` and `file_denied` records carry device id, profile id, file name and size. The path is never written.

## Asking the agent for a file

Ask in plain words: "send me the quarterly report as a PDF" or "zip the notes folder and send it to me". The agent writes the file (or finds it) and answers with a `MEDIA:` line; the chat shows a file you can open, save or share. If it says it cannot send files, tell it to end its reply with a line `MEDIA:` followed by the full path of the file.
