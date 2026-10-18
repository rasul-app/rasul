import asyncio
import base64
from pathlib import Path
import secrets
import time
from types import SimpleNamespace

import pytest
import tomllib

import rasul_bridge.config as config_module
import rasul_bridge.network as network
import rasul_bridge.server as server_module
from rasul_bridge.cli import initialize
from rasul_bridge.config import DEFAULT_CONFIG, Config
from rasul_bridge.status import FALLBACK_VERSION, bridge_version
from rasul_bridge.storage import Store
from rasul_bridge.tls import new_identity
from test_bridge import asynchronous, client, pair_body, read_response, registered, running, send


def clean_env(monkeypatch, tmp_path):
    for name in ("HERMES_HOME", "HERMES_DATA_DIR_SUFFIX", "LOCALAPPDATA"):
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv("HOME", str(tmp_path / "home"))
    monkeypatch.setenv("USERPROFILE", str(tmp_path / "home"))


def test_hermes_home_environment_wins(monkeypatch, tmp_path):
    clean_env(monkeypatch, tmp_path)
    monkeypatch.setenv("HERMES_HOME", str(tmp_path / "chosen"))
    assert config_module.default_hermes_home() == tmp_path / "chosen"
    assert Config().hermes_home == tmp_path / "chosen"


def test_hermes_home_windows_uses_local_app_data(monkeypatch, tmp_path):
    clean_env(monkeypatch, tmp_path)
    monkeypatch.setattr(config_module.sys, "platform", "win32")
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path / "local"))
    assert config_module.default_hermes_home() == tmp_path / "local" / "hermes"
    monkeypatch.delenv("LOCALAPPDATA")
    assert config_module.default_hermes_home() == tmp_path / "home" / "AppData" / "Local" / "hermes"


def test_hermes_home_other_platforms_use_dot_hermes(monkeypatch, tmp_path):
    clean_env(monkeypatch, tmp_path)
    monkeypatch.setattr(config_module.sys, "platform", "linux")
    assert config_module.default_hermes_home() == tmp_path / "home" / ".hermes"
    monkeypatch.setenv("HERMES_DATA_DIR_SUFFIX", "-x")
    assert config_module.default_hermes_home() == tmp_path / "home" / ".hermes-x"


def test_explicit_hermes_home_in_the_file_still_wins(monkeypatch, tmp_path):
    clean_env(monkeypatch, tmp_path)
    monkeypatch.setenv("HERMES_HOME", str(tmp_path / "chosen"))
    path = tmp_path / "config.toml"
    path.write_text(f'hermes_home = "{(tmp_path / "file").as_posix()}"\n' + DEFAULT_CONFIG, encoding="utf-8")
    assert Config.load(path).hermes_home == tmp_path / "file"


def test_new_flags_default_off_and_must_be_booleans(tmp_path):
    path = tmp_path / "config.toml"
    path.write_text(DEFAULT_CONFIG, encoding="utf-8")
    loaded = Config.load(path)
    assert (loaded.lan, loaded.tailscale) == (False, False)
    path.write_text('lan = "yes"\n' + DEFAULT_CONFIG.replace("lan = false\n", ""), encoding="utf-8")
    with pytest.raises(ValueError):
        Config.load(path)


PC = ["127.0.0.1", "169.254.7.7", "192.168.1.20", "10.1.2.3", "172.20.0.4", "172.32.0.1",
      "100.64.0.9", "100.127.255.1", "100.128.0.1", "203.0.113.8", "198.51.100.8", "172.15.255.1", "11.0.0.1", "100.63.255.1", "0.0.0.0", "fe80::1", "junk", "192.168.1.20"]


def test_address_classification():
    assert network.lan_addresses(lambda: PC) == ["192.168.1.20", "10.1.2.3", "172.20.0.4"]
    assert network.tailscale_addresses(lambda: PC) == ["100.64.0.9", "100.127.255.1"]


def test_link_urls_order_always_ws_and_off_by_default(monkeypatch):
    monkeypatch.setattr(network, "local_ipv4", lambda: PC)
    config = Config(port=8787, public_urls=["wss://relay.example.test/v1/link"])
    assert network.link_urls(config) == (["wss://relay.example.test/v1/link"], [], [])
    config.lan = config.tailscale = True
    public, lan, tail = network.link_urls(config)
    assert public == ["wss://relay.example.test/v1/link"]
    assert lan[0] == "ws://192.168.1.20:8787/v1/link" and len(lan) == 3
    assert tail == ["ws://100.64.0.9:8787/v1/link", "ws://100.127.255.1:8787/v1/link"]


def test_extra_plan_unchanged_unless_enabled(monkeypatch):
    monkeypatch.setattr(network, "local_ipv4", lambda: PC)
    config = Config()
    assert network.extra_plan(config) == []
    config.tailscale = True
    assert network.extra_plan(config) == [("tailscale", "100.64.0.9"), ("tailscale", "100.127.255.1")]
    config.listen = "0.0.0.0"
    assert network.extra_plan(config) == []
    monkeypatch.setattr(network, "local_ipv4", lambda: ["127.0.0.1"])
    config.listen = "127.0.0.1"
    assert network.extra_plan(config) == []


def test_pairing_urls_are_capped_to_the_app_limit(monkeypatch):
    many = [f"192.168.1.{n}" for n in range(1, 21)]
    monkeypatch.setattr(network, "local_ipv4", lambda: many)
    config = Config(public_urls=["wss://relay.example.test/v1/link"], lan=True)
    urls, dropped = network.pairing_urls(config)
    assert len(urls) == network.QR_URL_LIMIT == 8 and dropped == 13
    assert urls[0] == "wss://relay.example.test/v1/link" and urls[1] == "ws://192.168.1.1:8787/v1/link"
    config.lan = False
    assert network.pairing_urls(config) == (["wss://relay.example.test/v1/link"], 0)


def test_init_flags_write_the_config(tmp_path):
    store = Store(tmp_path / "data")
    initialize(store, lan=True, tailscale=True)
    loaded = Config.load(store.root / "config.toml")
    assert (loaded.lan, loaded.tailscale) == (True, True)
    other = Store(tmp_path / "plain")
    initialize(other)
    assert not Config.load(other.root / "config.toml").lan
    initialize(other, lan=True)  # an existing file is updated, not replaced
    assert Config.load(other.root / "config.toml").lan


def test_fallback_version_matches_the_project_file():
    project = tomllib.loads((Path(__file__).parent.parent / "pyproject.toml").read_text(encoding="utf-8"))
    assert FALLBACK_VERSION == project["project"]["version"] == "0.2.6"
    assert isinstance(bridge_version(), str) and bridge_version()


def test_cli_version_flag_prints_the_version(monkeypatch, capsys):
    from rasul_bridge.cli import main
    monkeypatch.setattr("sys.argv", ["rasul-bridge", "--version"])
    with pytest.raises(SystemExit) as stop:
        main()
    assert stop.value.code == 0
    assert capsys.readouterr().out.strip() == "rasul-bridge " + bridge_version()


@asynchronous
async def test_status_contract_and_auth(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        upstream.active_agents = 3
        key = bridge.profiles.key("default")
        async with client(bridge, tmp_path) as (link, reader, _):
            await send(link, "GET", "/v1/status")
            assert (await read_response(reader))[0] == 403
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/status")
            status, value = await read_response(reader)
        assert status == 200
        assert set(value) == {"bridge_version", "hermes_home_found", "profiles", "hermes",
                              "dashboard_found", "dashboard_check", "approvals_listener", "urls"}
        assert value["bridge_version"] == bridge_version()
        assert value["hermes_home_found"] is True
        assert value["profiles"] == [{"id": "default", "api_key_configured": True, "gateway_reachable": True}]
        assert value["hermes"] == {"version": None, "active_agents": 3}
        assert isinstance(value["dashboard_found"], bool) and value["dashboard_check"] in ("ok", "timeout")
        assert value["approvals_listener"] is False
        assert value["urls"] == {"lan": 0, "tailscale": 0, "public": 1}
        assert key not in str(value) and str(tmp_path) not in str(value)


@asynchronous
async def test_status_answers_fast_when_hermes_is_down(tmp_path):
    async with running(tmp_path) as (bridge, upstream):
        await upstream.close()
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            started = time.monotonic()
            await send(link, "GET", "/v1/status")
            status, value = await read_response(reader)
            assert time.monotonic() - started < 3
        assert status == 200
        assert value["profiles"][0]["gateway_reachable"] is False
        assert value["profiles"][0]["api_key_configured"] is True
        assert value["hermes"] == {"version": None, "active_agents": None}


@asynchronous
async def test_status_is_rate_limited(tmp_path):
    async with running(tmp_path) as (bridge, _):
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            for _ in range(30):
                await send(link, "GET", "/v1/status")
                assert (await read_response(reader))[0] == 200
            await send(link, "GET", "/v1/status")
            status, value = await read_response(reader)
        assert (status, value) == (429, {"error": "status_rate_limited"})


@asynchronous
async def test_pairing_rate_limit_spans_every_listener(tmp_path, monkeypatch):
    monkeypatch.setattr(server_module, "extra_plan", lambda config: [("lan", "127.0.0.2")])
    async with running(tmp_path) as (bridge, _):
        sockets = [s for server in (bridge.server, *bridge.extra_servers) for s in server.sockets]
        ports = {s.getsockname()[0]: s.getsockname()[1] for s in sockets}
        assert set(ports) == {"127.0.0.1", "127.0.0.2"}
        first = SimpleNamespace(port=ports["127.0.0.1"], cert=bridge.cert)
        second = SimpleNamespace(port=ports["127.0.0.2"], cert=bridge.cert)
        payload, _ = bridge.pairing.issue(bridge.config.public_urls, "my-pc", "my-pc")
        wrong = pair_body(payload, new_identity()[1])
        wrong["sec"] = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
        for index in range(5):
            target, host = (first, "127.0.0.1") if index % 2 else (second, "127.0.0.2")
            async with client(target, tmp_path, host=host) as (link, reader, _):
                await send(link, "POST", "/v1/pair", wrong)
                assert (await read_response(reader))[0] == 403
        for target, host in ((first, "127.0.0.1"), (second, "127.0.0.2")):
            async with client(target, tmp_path, host=host) as (link, reader, _):
                await send(link, "POST", "/v1/pair", pair_body(payload, new_identity()[1]))
                assert (await read_response(reader))[0] == 429


@asynchronous
async def test_one_unbindable_extra_address_does_not_stop_the_bridge(tmp_path, monkeypatch, capsys):
    monkeypatch.setattr(server_module, "extra_plan", lambda config: [
        ("lan", "127.0.0.2"), ("lan", "203.0.113.77"), ("tailscale", "127.0.0.3")])
    async with running(tmp_path) as (bridge, _):
        assert "1 of 3 extra addresses failed to bind" in capsys.readouterr().err
        assert len(bridge.extra_servers) == 2
        assert bridge.bound == {"lan": 1, "tailscale": 1}
        identity, _ = registered(bridge)
        async with client(bridge, tmp_path, identity) as (link, reader, _):
            await send(link, "GET", "/v1/status")
            status, value = await read_response(reader)
        assert status == 200 and value["urls"] == {"lan": 1, "tailscale": 1, "public": 1}


@asynchronous
async def test_a_failed_configured_listen_address_stays_fatal(tmp_path):
    from test_bridge import FakeUpstream
    upstream = FakeUpstream()
    await upstream.start()
    store = Store(tmp_path / "data")
    initialize(store)
    config = Config(hermes_url=f"http://127.0.0.1:{upstream.port}", port=0,
                    hermes_home=tmp_path / "up", listen="203.0.113.77", approvals_port=None)
    config.hermes_home.mkdir()
    bridge = server_module.Bridge(store, config)
    bridge.profiles.init_keys()
    try:
        with pytest.raises(OSError):
            await bridge.start()
    finally:
        await upstream.close()


@asynchronous
async def test_status_does_not_detect_addresses_on_the_event_loop(tmp_path, monkeypatch):
    def boom(*a, **k):
        raise AssertionError("detection called while answering status")
    async with running(tmp_path) as (bridge, _):
        bridge.config.lan = True
        monkeypatch.setattr(network, "local_ipv4", boom)
        value = await bridge.status.snapshot()
        assert value["urls"]["lan"] == 0


@asynchronous
async def test_status_reports_timeout_with_a_boolean_dashboard(tmp_path, monkeypatch):
    import rasul_bridge.status as status_module
    monkeypatch.setattr(status_module, "TOTAL_TIMEOUT", 0.05)
    async with running(tmp_path) as (bridge, _):
        async def slow(*a, **k):
            await asyncio.sleep(5)
        monkeypatch.setattr(bridge.desktop, "discover", slow)
        value = await bridge.status.snapshot()
        assert value["dashboard_found"] is False and value["dashboard_check"] == "timeout"
