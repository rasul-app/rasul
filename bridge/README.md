# Rasul Bridge

The recommended companion transport keeps profile credentials on your computer.
The phone authenticates using its own certificate. An opaque WebSocket carries
TLS 1.3 all the way from the phone to this bridge, including through a tunnel.
See the [protocol](../docs/bridge-protocol.md) for the client contract.

This package supplies the computer side. A phone client must implement the v1
bridge scanner, SPKI pinning, inner TLS transport, and device-key authentication.
The existing direct-connection screen does not implement that transport.

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

   The upstream profile root defaults to `~/.hermes`; `hermes_home` can select
   another owner-controlled root. Default plus named directories are discovered.
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
   Each anonymous connection lasts at most 15 seconds from TCP accept, including
   the owner decision, and closes after one request. Reconnect with the enrolled
   certificate after successful pairing.

7. Use `rasul-bridge status` for local identity/profile information.
   `rasul-bridge devices` lists enrolled devices. Revoke a lost phone with
   `rasul-bridge revoke <id>`; the serving process closes its open connections
   and refuses subsequent requests. Re-pair with a new device key after revocation.

## Choose a transport

- **Cloudflare Tunnel:** route an outbound tunnel for `example.com` to the local
  bridge listener, and put `wss://example.com/v1/link` in `public_urls`. Enable
  WebSocket forwarding. Expose the bridge only; leave the upstream API on loopback.
- **Tailscale:** bind the bridge to the computer's overlay address and use, for
  example, `ws://100.64.0.7:8787/v1/link`. Restrict access with overlay policy and
  the host firewall. Inner TLS authenticates and encrypts even with outer `ws`.
- **LAN:** bind the bridge to a selected local interface, allow its port only on
  the trusted network, and add that interface's reachable WebSocket URL. A phone
  on another network needs a tunnel or overlay. The upstream remains on loopback.

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

The bridge admits at most 32 authenticated links and 16 per device. Separate pools
allow four anonymous links and eight pending upgrades or TLS handshakes. A full
pending pool drops its oldest socket so incomplete upgrades cannot hold all
admission slots. Pending sockets have a ten-second deadline from TCP accept;
anonymous sockets have an absolute 15-second lifetime and allow one pairing
request only. Pairing attempts are limited to five per minute globally and
request bodies to 1 MiB. Requests have strict HTTP framing. The proxy
allowlist covers health, capabilities, models, runs/events/actions, session
listing/messages, and job listing/actions. It drops phone Authorization and
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
