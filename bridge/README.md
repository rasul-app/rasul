# Rasul Bridge

The recommended companion transport keeps profile credentials on your computer.
The phone authenticates using its own certificate. An opaque WebSocket carries
TLS 1.3 all the way from the phone to this bridge, including through a tunnel.
See the [protocol](../docs/bridge-protocol.md) for the client contract.

This package supplies the computer side of the bridge. Pair the phone by scanning
the QR code from `rasul-bridge pair`; the companion app handles connection
security and device authentication.

## Setup

1. Install Python 3.11 or newer and the upstream application separately. In a
   virtual environment, from the repository root:

   ```sh
   python -m pip install --require-hashes -r bridge/requirements.txt
   python -m pip install --no-deps -e bridge
   ```

2. Run `rasul-bridge init`. This creates a data directory, server identity,
   and `config.toml`. The data directory is the current account's local
   application-data directory on Windows, or `~/.local/share/rasul-bridge` on Linux.
   Use `rasul-bridge --data-dir <directory> <command>` to select another directory.
   Keep that directory private and backed up securely: losing the server key
   changes its pin and requires pairing again.

3. Edit `config.toml`. Keep `hermes_url` on loopback. Choose `listen`, `port`,
   and `public_urls` for the transport below. `public_urls` is ordered and contains
   full `ws://` or `wss://` addresses ending in `/v1/link`. Example for a tunnel:

   ```toml
   hermes_url = "http://127.0.0.1:8642"
   listen = "127.0.0.1"
   port = 8787
   public_urls = ["wss://example.com/v1/link"]
   name = "my-pc"
   restart_command = []

   [[templates]]
   id = "blank"
   label = "Blank"
   ```

   The upstream profile root is found the way the upstream application finds it:
   the `HERMES_HOME` environment variable if set, otherwise its per-user local
   application directory on Windows (`%LOCALAPPDATA%\hermes`) or `~/.hermes`
   elsewhere. `hermes_home` in `config.toml` overrides both. Default plus named
   directories are discovered.
   For named profiles, enable upstream profile multiplexing in its configuration.
   `templates` may contain `clone_config = "default"` or an existing profile name
   that the owner approves. Configuration cloning also copies installed skills;
   approve the source locally. No remote source, channel cloning, full-state
   cloning, or import synchronization is accepted. New cloned profiles receive
   a distinct API key. The phone cannot supply CLI flags or paths.

4. Enable the upstream API locally (`API_SERVER_ENABLED=true`), then run
   `rasul-bridge init-profiles`. This explicit command imports existing profile
   keys into protected bridge storage and generates a random key where missing.
   Before changing an environment file it saves a protected backup in bridge
   storage. It never prints keys. Existing configuration outside the key remains
   intact. A configuration-only API key must be migrated into the profile
   environment file before using this bridge.

5. Restart the gateway yourself after key changes. The bridge does not restart it
   by default. An optional owner-supplied `restart_command` is an argument list;
   it is used after phone-created profiles only if authenticated
   `/health/detailed` reports an integer `active_agents` equal to zero. Busy or
   unavailable status defers readiness; the owner can restart manually. Profile
   operations wait up to 120 seconds for authenticated `/v1/models` before reporting
   success. A timeout requires local inspection; a request UUID is never
   automatically dispatched again, even after a process interruption.

6. Run `rasul-bridge serve`. In another terminal run `rasul-bridge pair`. Scan
   its terminal QR or temporary PNG with a bridge-compatible in-app scanner.
   Compare all six confirmation digits on both devices before typing `y` on
   the computer. The invitation expires after 120 seconds, allows one ceremony,
   and contains no upstream credential. Decline or timeout consumes it. The
   temporary image is deleted when the ceremony finishes or times out.
   An anonymous connection must finish TLS within ten seconds of TCP accept and
   send its one request within five seconds after that; the connection holding
   the pairing slot then waits up to 120 seconds for the owner decision. It
   closes after one request. Reconnect with the enrolled
   certificate after successful pairing.

7. Use `rasul-bridge status` for local identity/profile information and
   `rasul-bridge --version` for the bridge version.
   `rasul-bridge devices` lists enrolled devices. Revoke a lost phone with
   `rasul-bridge revoke <id>`; the serving process closes its open connections
   and refuses subsequent requests. Re-pair with a new device key after revocation.

## Pairing problems

- QR expired: run `rasul-bridge pair` again and scan the new code.
- Codes do not match: answer `n` on the computer, then run `rasul-bridge pair` again.
- Phone says it cannot connect: check `rasul-bridge status`, confirm the tunnel or host is reachable, and keep the computer awake.

## Choose a transport

- **Cloudflare Tunnel:** route an outbound tunnel for `example.com` to the local
  bridge listener, and put `wss://example.com/v1/link` in `public_urls`. Enable
  WebSocket forwarding. Expose the bridge only; leave the upstream API on loopback.
- **Tailscale:** set `tailscale = true` (or run `rasul-bridge init --tailscale`
  on a new setup). See "Same Wi-Fi and Tailscale" below. Restrict access with
  overlay policy and the host firewall. Inner TLS authenticates and encrypts even
  with outer `ws`.
- **LAN:** set `lan = true` (or `rasul-bridge init --lan`). See below. Allow the
  port only on the trusted network. A phone on another network needs a tunnel or
  overlay. The upstream remains on loopback.

### Same Wi-Fi and Tailscale

Both keys default to `false`, which leaves listening and pairing exactly as
before. With `lan = true` the bridge also listens on this computer's private
IPv4 addresses (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16). With
`tailscale = true` it also listens on addresses in 100.64.0.0/10. Loopback and
link-local addresses are skipped; virtual adapters cannot be told apart using
the standard library, so a virtual adapter's private address may also be listed.
The addresses are detected again on every `rasul-bridge serve` and every
`rasul-bridge pair`, always as `ws://` on `port` (the bridge listener is plain
`ws`; the inner pinned TLS protects the traffic), even when a tunnel URL in
`public_urls` is `wss`. The pairing code lists `public_urls` first, then LAN, then
Tailscale addresses, at most eight in all (the app's limit); the command prints how
many were left out. The phone tries them in turn and remembers the one that
worked. When the computer's address changes (for example a new DHCP lease),
nothing needs to be done if another listed address still works; otherwise run
`rasul-bridge pair` again. The pairing rate limit is shared by every listener. An address that fails to bind is
skipped (the bridge prints how many of the extra addresses failed) while the
configured `listen` address must still bind.

The range 100.64.0.0/10 is also carrier-grade NAT space. With `tailscale = true` on a
mobile-carrier connection the bridge may listen on a carrier-assigned address, so
leave it off unless the overlay network is what you use.
With `listen = "0.0.0.0"` the bridge already listens on every address and
nothing is added; the pairing code still lists the detected addresses.

### Self-check

An enrolled phone can read `GET /v1/status`. It returns the bridge version,
whether the upstream directory exists, per profile whether a key is configured and
the gateway answers `/health`, the upstream version and `active_agents`, whether
the desktop dashboard was found, whether the approvals listener is running, and
how many `lan` and `tailscale` addresses actually bound and how many `public` URLs are configured. `dashboard_found` is always a boolean; `dashboard_check` is `"timeout"` when the probe timed out (so `false` is not conclusive) and `"ok"` otherwise. It returns counts and
flags only: no addresses, keys or paths. Each probe has a short timeout, so the
route answers within three seconds even when the upstream is down. It is limited
to 30 requests per minute per device.

With approvals enabled, `GET /v1/approvals` and `GET /v1/approvals/events` list and stream pending approvals of all profiles over one connection per device (older bridges answer 404; see `docs/bridge-approvals.md`).

Messaging channels: list, switch on/off, test; Telegram setup from the phone. The bot token stays on the computer.

Tunnel setup, routing, computer sleep, and network policy affect availability.
No tunnel account or credential belongs in this repository.

## Autostart

On Windows, `rasul-bridge install-task` registers an interactive, least-privilege
task at the current account's logon using `schtasks` and an argument list.
`rasul-bridge uninstall-task` removes it. Run these commands as the same account
used for setup. The virtual environment must remain installed at its location.

On Linux, copy [the example unit](../docs/rasul-bridge.service) into your user
service directory, adjust `ExecStart` to your installed executable, and use
`systemctl --user enable --now rasul-bridge.service`. Run pairing from a terminal
in the same account; the service never prompts on its own standard input.

## Security model

The outer transport is untrusted. It can observe traffic size/timing or disrupt
connections, but inner TLS terminates here. The client pins the server SPKI from
the invitation and uses a non-exportable P-256 key for subsequent mutual TLS.
No client certificate permits only pairing; every other route needs a registered,
unrevoked device. TLS is restricted to 1.3 with no tickets, resumption, or early data.

The bridge admits at most 128 authenticated links and 64 per device. A refused link
leaves one audit line per device per minute. Separate pools
allow 32 anonymous links and 256 pending upgrades or TLS handshakes. A full
pending pool drops a random socket so incomplete upgrades cannot hold all
admission slots. Pending sockets have a ten-second deadline from TCP accept;
anonymous sockets have an absolute five-second lifetime from TLS completion (the one
socket that holds the pairing slot is kept for the owner decision instead, at most
120 seconds) and allow one pairing request only. Pairing attempts are limited to
five per minute globally, anonymous request bodies to 16 KiB and all others to 1 MiB. Requests have strict HTTP framing. The proxy
allowlist covers health, capabilities, skills, toolsets, models, model options,
runs and their events/actions, session listing/reading/title updates/deletion/messages,
and job listing/creation/reading/updates/deletion/actions. It drops phone Authorization and
hop-by-hop headers, injects the local profile key, and never follows redirects.
SSE chunks are forwarded as they arrive. Device administration is CLI-only.

On Windows, private keys, registry records, invitations, operation records,
backups, and upstream keys use current-account DPAPI; the containing directory
has inheritance removed and a current-account SID grant. On Linux the directory
is mode 700 and files are mode 600. The TLS library briefly loads a plaintext
PEM in a private temporary directory and removes it immediately. Public server
certificates and configuration do not contain secrets. Do not share the data
directory or enroll an untrusted phone. A paired phone can initiate terminal-capable
work through the upstream service; profiles do not provide OS-level isolation.

Audit JSON lines contain only time, device id, validated method, static route
template, existing profile id, and status. Bodies, prompts, headers, pairing
secrets, and QR text are excluded. Local operating-system compromise defeats
these protections. Phone lock/biometric enforcement is a client responsibility.
Audit retention is limited to three files of 5 MiB each. Rejected anonymous and
rate-limited traffic produces at most one aggregate counter record per minute.
Successful pairing retains its individual audit record.

The confirmation code uses the first six decimal digits of the unsigned big-endian
SHA-256 integer for `server SPKI DER || device SPKI DER || invitation id UTF-8`.
The invitation id is lowercase hexadecimal; the QR secret is unpadded base64url.

## Dependencies and tests

There are three direct runtime dependencies, all pinned exactly in the package
metadata and hash-locked in `requirements.txt`:

- `websockets`: maintained RFC 6455 transport and bounded async framing.
- `cryptography`: P-256 key/certificate creation, validation, and SPKI encoding.
- `qrcode`: terminal QR matrix generation. PNG encoding uses the standard library,
  so no image library is needed.

The lock also includes required transitive dependencies: `cffi`, `pycparser`, and
the Windows terminal dependency `colorama`. Regenerate it with
`pip-compile --generate-hashes --no-header --no-emit-index-url --no-emit-trusted-host --output-file bridge/requirements.txt bridge/requirements.in`.

Install `pytest` for development, then run from `bridge/`:

```sh
python -m pytest -q
```

Tests generate throwaway identities at runtime, use a fake loopback upstream,
and do not touch an installed gateway or real profile files.
