"""Optional same-network and overlay-network listening addresses."""

import ipaddress
import socket

PRIVATE = [ipaddress.ip_network(n) for n in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")]
OVERLAY = ipaddress.ip_network("100.64.0.0/10")
WILDCARDS = ("0.0.0.0", "::")
# PairingQr.kt accepts at most this many urls (it rejects the ninth).
QR_URL_LIMIT = 8


def local_ipv4():
    """Every IPv4 address this PC reports, using only the standard library."""
    found = set()
    try:
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            found.add(item[4][0])
    except OSError:
        pass
    try:
        # Connecting a UDP socket sends nothing; it only selects the outgoing interface.
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("192.0.2.1", 9))
            found.add(probe.getsockname()[0])
    except OSError:
        pass
    return sorted(found)


def _select(accept, detect):
    result = []
    for text in (detect or local_ipv4)():
        try:
            address = ipaddress.IPv4Address(text)
        except ValueError:
            continue
        if address.is_loopback or address.is_link_local or address.is_unspecified:
            continue
        if accept(address) and text not in result:
            result.append(text)
    return result


def lan_addresses(detect=None):
    return _select(lambda a: any(a in net for net in PRIVATE), detect)


def tailscale_addresses(detect=None):
    return _select(lambda a: a in OVERLAY, detect)


def extra_plan(config):
    """(kind, address) pairs to listen on besides config.listen, re-detected on every call."""
    if config.listen in WILDCARDS:
        return []
    result = []
    if config.lan:
        result += [("lan", a) for a in lan_addresses()]
    if config.tailscale:
        result += [("tailscale", a) for a in tailscale_addresses()]
    return [(kind, a) for kind, a in result if a != config.listen]


def link_urls(config):
    """(public, lan, tailscale) URL lists for the pairing code, in that order.

    The bridge listener is plain ws (the inner pinned TLS protects the traffic), so detected
    addresses are always ws, whatever scheme a tunnel URL in public_urls uses."""
    def build(addresses):
        return [f"ws://{a}:{config.port}/v1/link" for a in addresses]
    return (list(config.public_urls),
            build(lan_addresses()) if config.lan else [],
            build(tailscale_addresses()) if config.tailscale else [])


def pairing_urls(config):
    """(urls for the pairing code, number dropped) capped to what the app accepts."""
    urls = []
    for group in link_urls(config):
        urls += [u for u in group if u not in urls]
    return urls[:QR_URL_LIMIT], max(0, len(urls) - QR_URL_LIMIT)
