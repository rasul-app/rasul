"""Hermes update queue and MCP manage routes, against a fake dashboard and gateway."""

import asyncio
import json
from urllib.parse import urlsplit

import pytest

from test_bridge import asynchronous, client, read_response, send
from test_desktop import FakeDesktop, PREFIX, desktop
import rasul_bridge.updater as updater_module

ACTION = "a" * 32
REAL_PROCESS_ELEVATED = updater_module.process_elevated
SERVERS = [
    {"name": "files", "transport": "stdio", "enabled": True, "source": "config", "plugin": None,
     "command": "SECRET", "args": ["SECRET"], "env": {"K": "SECRET"}, "headers": {"A": "SECRET"}},
    {"name": "bundled", "transport": "stdio", "enabled": True, "source": "plugin", "plugin": "x"},
]
CATALOG = [
    {"name": "plain", "description": "Plain\nentry", "auth_type": "none", "required_env": [],
     "needs_install": False, "installed": False, "enabled": False, "command": "SECRET", "url": "SECRET",
     "args": ["SECRET"], "bootstrap": ["SECRET"], "suggest": None},
    {"name": "needskey", "description": "d", "auth_type": "api_key", "required_env": [{"name": "K"}],
     "needs_install": False, "installed": False, "enabled": False},
    {"name": "clone", "description": "d", "auth_type": "none", "required_env": [],
     "needs_install": True, "installed": False, "enabled": False},
    {"name": "have", "description": "d", "auth_type": "none", "required_env": [],
     "needs_install": False, "installed": True, "enabled": True},
]


class FakeHermes(FakeDesktop):
    def __init__(self, directory):
        super().__init__(directory)
        self.commits = [{"sha": "abcdef1", "summary": "Fix things", "author": "x", "at": 1}]
        self.behind = 1
        self.receipt = {"outcome": "success", "action_id": ACTION, "pre_sha": "1234567",
                        "post_sha": "7654321", "post_version": "2.0", "followups": []}
        self.running = False
        self.mcp_ok = True
        self.version = "1.0"

    def result(self, method, path, value):
        if path == "/api/hermes/update/check":
            return {"install_method": "git", "current_version": self.version, "behind": self.behind,
                    "update_available": self.behind > 0, "can_apply": True, "update_command": "SECRET",
                    "message": "SECRET", "commits": self.commits}
        if path == "/api/hermes/update":
            return {"ok": True, "pid": 7, "name": "hermes-update", "action_id": ACTION}
        if path == "/api/actions/hermes-update/status":
            if self.running:
                return {"name": "hermes-update", "running": True, "exit_code": None, "pid": 7, "lines": ["SECRET"]}
            return {"name": "hermes-update", "running": False, "exit_code": 0, "pid": 7,
                    "lines": ["SECRET"], "receipt": self.receipt}
        if path == "/api/mcp/servers":
            return {"servers": SERVERS}
        if path.startswith("/api/mcp/servers/") and path.endswith("/enabled"):
            SERVERS[0]["enabled"] = value["enabled"]
            assert value["profile"] == self.profile
            return {"ok": True}
        if path.startswith("/api/mcp/servers/") and path.endswith("/test"):
            if not self.mcp_ok:
                return {"ok": False, "error": "boom\n" + "x" * 500, "tools": []}
            return {"ok": True, "tools": [{"name": "t", "description": "d", "schema_chars": 5}],
                    "prompts": 1, "resources": 2}
        if path == "/api/mcp/catalog":
            return {"entries": CATALOG, "diagnostics": [{"message": "SECRET"}]}
        if path == "/api/mcp/catalog/install":
            assert value == {"name": "plain", "env": {}, "enable": True, "profile": self.profile}
            return {"ok": True, "name": "plain", "background": False}
        return super().result(method, path, value)

    def posts(self, path):
        return [r for r in self.requests if r[0] == "POST" and urlsplit(r[1]).path == path]


@pytest.fixture(autouse=True)
def not_elevated(monkeypatch):
    monkeypatch.setattr(updater_module, "elevated", lambda: False)
    monkeypatch.setattr(updater_module, "process_elevated", lambda pid: False, raising=False)
    SERVERS[0]["enabled"] = True


def hermes(tmp_path, monkeypatch):
    return desktop(tmp_path, monkeypatch, FakeHermes)


async def until(condition, seconds=3):
    async with asyncio.timeout(seconds):
        while not condition():
            await asyncio.sleep(0.005)


def fast(bridge):
    bridge.desktop.updater.poll = 0.01
    return bridge.desktop.updater


@asynchronous
async def test_check_mapping_caps_and_text_filtering(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (_, fake, call):
        fake.behind = 30
        fake.commits = ([{"sha": "abcdef1", "summary": "A\x00B\n\tC<>" + "z" * 400},
                         {"sha": "x", "summary": 5}, {"sha": "x", "summary": "  "}, "junk"]
                        + [{"sha": "b" * 7, "summary": "n%d" % i} for i in range(40)])
        status, value = await call("GET", "/hermes/update")
        assert status == 200
        assert set(value) == {"current", "latest", "behind", "can_apply", "reason", "changes",
                              "update_available", "state", "active_agents", "queued_at", "result"}
        assert (value["current"], value["latest"], value["behind"]) == ("1.0", "abcdef1", 30)
        assert value["can_apply"] is True and value["reason"] is None and value["state"] == "idle"
        assert len(value["changes"]) == 17  # 20 raw commits, 3 of them junk
        assert value["changes"][0]["title"].startswith("A B C<>")
        assert all(len(c["title"]) <= 200 and "\x00" not in c["title"] for c in value["changes"])
        assert "SECRET" not in json.dumps(value) and fake.token not in json.dumps(value)
        assert value["active_agents"] == 0


@asynchronous
async def test_up_to_date_and_unknown(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (_, fake, call):
        fake.behind, fake.commits = 0, []
        value = (await call("GET", "/hermes/update"))[1]
        assert (value["can_apply"], value["reason"], value["changes"], value["latest"]) == (False, "up_to_date", [], None)
        assert await call("POST", "/hermes/update", {"mode": "now"}) == (409, {"error": "up_to_date"})
        assert not fake.posts("/api/hermes/update")


@asynchronous
async def test_now_runs_and_reports_done_without_pre_sha(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        fast(bridge)
        fake.running = True
        status, value = await call("POST", "/hermes/update", {"mode": "now"})
        assert status == 202 and value["state"] == "running"
        assert len(fake.posts("/api/hermes/update")) == 1
        assert (await call("GET", "/hermes/update"))[1]["reason"] == "update_in_progress"
        fake.running = False
        await until(lambda: bridge.desktop.updater.state == "done")
        value = (await call("GET", "/hermes/update"))[1]
        assert value["state"] == "done"
        assert value["result"] == {"ok": True, "from": "1.0", "to": "2.0", "message": "Update completed."}
        assert "1234567" not in json.dumps(value) and "SECRET" not in json.dumps(value)
        assert "1234567" in bridge.audit.path.read_text()
        assert fake.token not in bridge.audit.path.read_text()


@asynchronous
async def test_failed_result(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        fast(bridge)
        fake.receipt = {**fake.receipt, "outcome": "failed"}
        assert (await call("POST", "/hermes/update", {"mode": "now"}))[0] == 202
        await until(lambda: bridge.desktop.updater.state == "failed")
        result = (await call("GET", "/hermes/update"))[1]["result"]
        assert result["ok"] is False and result["from"] == "1.0"


@asynchronous
async def test_receipt_of_another_action_is_ignored(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        fast(bridge)
        fake.receipt = {**fake.receipt, "action_id": "b" * 32}
        fake.running = False
        await call("POST", "/hermes/update", {"mode": "now"})
        await until(lambda: bridge.desktop.updater.state == "done")  # falls back to exit code 0
        assert bridge.desktop.updater.result["to"] == "1.0"


@asynchronous
async def test_dashboard_gone_during_update_keeps_waiting(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        fast(bridge)
        await call("POST", "/hermes/update", {"mode": "now"})
        saved = fake.record.read_text()
        fake.record.unlink()
        await asyncio.sleep(0.1)
        assert bridge.desktop.updater.state == "running"
        value = (await call("GET", "/hermes/update"))[1]
        assert value["state"] == "running"
        fake.record.write_text(saved)
        await until(lambda: bridge.desktop.updater.state == "done")


@asynchronous
async def test_queue_waits_for_two_idle_polls(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        updater = fast(bridge)
        fake.running = True
        fake.gateway.active_agents = 2
        status, value = await call("POST", "/hermes/update", {"mode": "when_idle"})
        assert status == 202 and value["state"] == "queued" and value["queued_at"]
        await asyncio.sleep(0.15)
        assert updater.state == "queued" and not fake.posts("/api/hermes/update")
        assert (await call("GET", "/hermes/update"))[1]["active_agents"] == 2
        polls = []
        original = updater.active_agents

        async def counted():
            polls.append(await original())
            return polls[-1]
        updater.active_agents = counted
        fake.gateway.active_agents = 0
        await until(lambda: updater.state == "running")
        await until(lambda: fake.posts("/api/hermes/update"))
        assert polls and set(polls) == {0} and len(fake.posts("/api/hermes/update")) == 1  # one idle poll may predate the counter


@asynchronous
async def test_one_idle_poll_between_busy_polls_does_not_start(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        updater = fast(bridge)
        fake.running = True
        sequence = iter([2, 0, 3, 0, 0])

        async def scripted():
            return next(sequence, 5)
        updater.active_agents = scripted
        await call("POST", "/hermes/update", {"mode": "when_idle"})
        await until(lambda: updater.state == "running")
        assert next(sequence, "drained") == "drained"


@asynchronous
async def test_queue_expires_after_24_hours(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        updater = fast(bridge)
        now = [1000.0]
        bridge.desktop.clock = lambda: now[0]
        fake.gateway.active_agents = 1
        await call("POST", "/hermes/update", {"mode": "when_idle"})
        now[0] += 24 * 3600 + 1
        await until(lambda: updater.state == "idle")
        value = (await call("GET", "/hermes/update"))[1]
        assert value["result"]["ok"] is False and "expired" in value["result"]["message"]
        assert not fake.posts("/api/hermes/update")


@asynchronous
async def test_cancel_queued_but_not_running(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        fast(bridge)
        fake.gateway.active_agents = 1
        fake.running = True
        assert await call("DELETE", "/hermes/update") == (409, {"error": "nothing_queued"})
        await call("POST", "/hermes/update", {"mode": "when_idle"})
        assert await call("POST", "/hermes/update", {"mode": "now"}) == (409, {"error": "update_in_progress"})
        status, value = await call("DELETE", "/hermes/update")
        assert status == 200 and value["state"] == "idle" and value["result"]["message"] == "Cancelled."
        fake.gateway.active_agents = 0
        await asyncio.sleep(0.1)
        assert not fake.posts("/api/hermes/update")
        await call("POST", "/hermes/update", {"mode": "now"})
        assert await call("DELETE", "/hermes/update") == (409, {"error": "update_running"})


@asynchronous
async def test_rate_limit_two_applies_per_hour(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        fast(bridge)
        for _ in range(2):
            assert (await call("POST", "/hermes/update", {"mode": "now"}))[0] == 202
            await until(lambda: bridge.desktop.updater.state == "done")
        assert await call("POST", "/hermes/update", {"mode": "now"}) == (429, {"error": "update_rate_limited"})
        assert len(fake.posts("/api/hermes/update")) == 2


@asynchronous
async def test_elevated_bridge_is_refused(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (_, fake, call):
        monkeypatch.setattr(updater_module, "elevated", lambda: True)
        assert await call("POST", "/hermes/update", {"mode": "now"}) == (403, {"error": "bridge_elevated"})
        value = (await call("GET", "/hermes/update"))[1]
        assert value["can_apply"] is False and value["reason"] == "bridge_elevated"
        assert not fake.posts("/api/hermes/update")


@asynchronous
async def test_dashboard_not_running_is_refused(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (_, fake, call):
        fake.record.unlink()
        assert await call("POST", "/hermes/update", {"mode": "when_idle"}) == (403, {"error": "dashboard_not_running"})
        status, value = await call("GET", "/hermes/update")
        assert status == 200 and value["can_apply"] is False and value["reason"] == "dashboard_not_running"


@pytest.mark.parametrize("body", [{"mode": "later"}, {"mode": 1}, {}, {"mode": "now", "x": 1}, {"mode": ["now"]}])
@asynchronous
async def test_update_body_is_strict(tmp_path, monkeypatch, body):
    async with hermes(tmp_path, monkeypatch) as (_, fake, call):
        assert await call("POST", "/hermes/update", body) == (400, {"error": "desktop_invalid"})
        assert not fake.requests


@asynchronous
async def test_update_failed_start_is_reported(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (_, fake, call):
        original = fake.result
        fake.result = lambda m, p, v: ({"ok": False, "error": "SECRET"} if p == "/api/hermes/update"
                                       else original(m, p, v))
        assert await call("POST", "/hermes/update", {"mode": "now"}) == (502, {"error": "update_not_started"})
        value = (await call("GET", "/hermes/update"))[1]
        assert value["state"] == "failed" and "SECRET" not in json.dumps(value)


@asynchronous
async def test_mcp_toggle_test_catalog_install(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (_, fake, call):
        assert await call("PUT", "/mcp/files/enabled", {"enabled": False}) == (200, {"name": "files", "enabled": False})
        status, value = await call("POST", "/mcp/files/test")
        assert status == 200
        assert value == {"name": "files", "ok": True, "tools": [{"name": "t", "description": "d"}],
                         "prompts": 1, "resources": 2}
        fake.mcp_ok = False
        value = (await call("POST", "/mcp/files/test"))[1]
        assert value["ok"] is False and len(value["error"]) <= 200 and "\n" not in value["error"]
        status, value = await call("GET", "/mcp/catalog")
        assert status == 200
        assert value == {"entries": [
            {"id": "plain", "name": "plain", "description": "Plain entry", "installed": False, "enabled": False, "installable": True},
            {"id": "needskey", "name": "needskey", "description": "d", "installed": False, "enabled": False, "installable": False},
            {"id": "clone", "name": "clone", "description": "d", "installed": False, "enabled": False, "installable": False},
            {"id": "have", "name": "have", "description": "d", "installed": True, "enabled": True, "installable": False}]}
        assert await call("POST", "/mcp/catalog/install", {"id": "plain"}) == (200, {"id": "plain", "state": "installed"})
        assert len(fake.posts("/api/mcp/catalog/install")) == 1


@asynchronous
async def test_mcp_responses_never_carry_secrets(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (_, fake, call):
        seen = [await call("GET", "/mcp"), await call("PUT", "/mcp/files/enabled", {"enabled": True}),
                await call("POST", "/mcp/files/test"), await call("GET", "/mcp/catalog")]
        text = json.dumps(seen)
        for forbidden in ("SECRET", fake.token, "command", "args", "env", "headers", "url", "required_env", "schema_chars"):
            assert forbidden not in text


@pytest.mark.parametrize("name", ["missing", "bundled"])
@asynchronous
async def test_mcp_unknown_or_plugin_server_refused(tmp_path, monkeypatch, name):
    async with hermes(tmp_path, monkeypatch) as (_, fake, call):
        assert await call("PUT", f"/mcp/{name}/enabled", {"enabled": False}) == (400, {"error": "desktop_invalid"})
        assert await call("POST", f"/mcp/{name}/test") == (400, {"error": "desktop_invalid"})
        assert not any(r[0] in ("PUT", "POST") for r in fake.requests)


@pytest.mark.parametrize("identifier", ["missing", "needskey", "clone", "have", "https://example.invalid/x",
                                        "plain/../x", "plain extra", ""])
@asynchronous
async def test_mcp_install_refuses_everything_but_listed_installable(tmp_path, monkeypatch, identifier):
    async with hermes(tmp_path, monkeypatch) as (_, fake, call):
        assert await call("POST", "/mcp/catalog/install", {"id": identifier}) == (400, {"error": "desktop_invalid"})
        assert not fake.posts("/api/mcp/catalog/install")


@pytest.mark.parametrize("path", ["/mcp/x/enabled", "/mcp/x y/test", "/mcp/../enabled", "/mcp/.x/test"])
@asynchronous
async def test_mcp_bad_names_never_route(tmp_path, monkeypatch, path):
    async with hermes(tmp_path, monkeypatch) as (_, fake, call):
        method = "PUT" if path.endswith("enabled") else "POST"
        status, _ = await call(method, path, {"enabled": True} if method == "PUT" else None)
        assert status in (400, 404)
        assert not any(r[0] in ("PUT", "POST") for r in fake.requests)


@asynchronous
async def test_mcp_install_rate_limit_and_toggle_body(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (_, fake, call):
        assert (await call("POST", "/mcp/catalog/install", {"id": "plain"}))[0] == 200
        assert await call("POST", "/mcp/catalog/install", {"id": "plain"}) == (429, {"error": "desktop_rate_limited"})
        for body in ({"enabled": 1}, {"enabled": "x"}, {}, {"enabled": True, "name": "x"}):
            assert await call("PUT", "/mcp/files/enabled", body) == (400, {"error": "desktop_invalid"})


@pytest.mark.parametrize("method,suffix,body", [
    ("GET", "/hermes/update", None), ("POST", "/hermes/update", {"mode": "now"}), ("DELETE", "/hermes/update", None),
    ("PUT", "/mcp/files/enabled", {"enabled": True}), ("POST", "/mcp/files/test", None),
    ("GET", "/mcp/catalog", None), ("POST", "/mcp/catalog/install", {"id": "plain"})])
@asynchronous
async def test_unauthenticated_calls_are_refused(tmp_path, monkeypatch, method, suffix, body):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, _):
        async with client(bridge, tmp_path) as (link, reader, _):
            await send(link, method, PREFIX + suffix, body)
            assert (await read_response(reader))[0] == 403
        assert not fake.requests


# -- dashboard rights, stuck states, unknown counts, idle signals ---------------------


@asynchronous
async def test_dashboard_elevated_or_unknown_is_refused(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (_, fake, call):
        for answer, reason in ((True, "dashboard_elevated"), (None, "cannot_verify_dashboard_rights")):
            monkeypatch.setattr(updater_module, "process_elevated", lambda pid, answer=answer: answer)
            assert await call("POST", "/hermes/update", {"mode": "now"}) == (403, {"error": reason})
            value = (await call("GET", "/hermes/update"))[1]
            assert value["can_apply"] is False and value["reason"] == reason
        assert not fake.posts("/api/hermes/update")


@asynchronous
async def test_dashboard_checked_is_the_one_in_the_host_record(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (_, fake, call):
        seen = []
        monkeypatch.setattr(updater_module, "process_elevated", lambda pid: seen.append(pid) or False)
        assert (await call("POST", "/hermes/update", {"mode": "now"}))[0] == 202
        assert seen and set(seen) == {fake.pid}


@asynchronous
async def test_queued_update_does_not_start_if_dashboard_becomes_elevated(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        updater = fast(bridge)
        fake.gateway.active_agents = 1
        assert (await call("POST", "/hermes/update", {"mode": "when_idle"}))[0] == 202
        monkeypatch.setattr(updater_module, "process_elevated", lambda pid: True)
        fake.gateway.active_agents = 0
        await until(lambda: updater.state == "failed")
        assert not fake.posts("/api/hermes/update")


def test_process_elevated_answers_or_admits_it_cannot():
    import os
    assert REAL_PROCESS_ELEVATED(os.getpid()) in (True, False, None)
    assert REAL_PROCESS_ELEVATED(2 ** 30) is None


@asynchronous
async def test_key_lookup_failure_in_queue_does_not_stick_and_expiry_applies(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        updater = fast(bridge)
        now = [1000.0]
        bridge.desktop.clock = lambda: now[0]

        def broken():
            raise RuntimeError("store unreadable")
        monkeypatch.setattr(updater, "gateway_key", broken)
        assert (await call("POST", "/hermes/update", {"mode": "when_idle"}))[0] == 202
        await asyncio.sleep(0.1)
        assert updater.state == "queued" and not fake.posts("/api/hermes/update")
        now[0] += 24 * 3600 + 1
        await until(lambda: updater.state == "idle")
        assert "expired" in updater.result["message"]


@asynchronous
async def test_unexpected_error_in_queue_task_ends_failed_and_frees_the_slot(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        updater = fast(bridge)
        fake.gateway.active_agents = 0
        original = updater.begin

        async def boom(profile):
            raise RuntimeError("boom")
        monkeypatch.setattr(updater, "begin", boom)
        assert (await call("POST", "/hermes/update", {"mode": "when_idle"}))[0] == 202
        await until(lambda: updater.state == "failed")
        assert updater.result["ok"] is False
        assert await call("DELETE", "/hermes/update") == (409, {"error": "nothing_queued"})
        monkeypatch.setattr(updater, "begin", original)
        assert (await call("POST", "/hermes/update", {"mode": "now"}))[0] == 202


@asynchronous
async def test_unexpected_error_when_starting_now_is_not_stuck_running(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        updater = fast(bridge)
        original = updater.dashboard
        calls = []

        async def flaky(profile):
            calls.append(1)
            if len(calls) > 1:  # the check passes, the start call breaks
                raise RuntimeError("boom")
            return await original(profile)
        monkeypatch.setattr(updater, "dashboard", flaky)
        assert await call("POST", "/hermes/update", {"mode": "now"}) == (502, {"error": "update_not_started"})
        assert updater.state == "failed"
        assert not fake.posts("/api/hermes/update")
        monkeypatch.setattr(updater, "dashboard", original)
        assert (await call("POST", "/hermes/update", {"mode": "now"}))[0] == 202


@asynchronous
async def test_monitor_error_ends_failed(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        updater = fast(bridge)

        async def boom(*args):
            raise RuntimeError("boom")
        monkeypatch.setattr(updater, "complete", boom)
        assert (await call("POST", "/hermes/update", {"mode": "now"}))[0] == 202
        await until(lambda: updater.state == "failed")
        assert (await call("GET", "/hermes/update"))[1]["result"]["message"] == "The update stopped unexpectedly."


@asynchronous
async def test_mcp_toggle_when_server_vanishes_is_clean(tmp_path, monkeypatch):
    saved = list(SERVERS)
    try:
        async with hermes(tmp_path, monkeypatch) as (_, fake, call):
            original = fake.result

            def result(method, path, value):
                if method == "PUT" and path.endswith("/enabled"):
                    SERVERS[:] = SERVERS[1:]
                return original(method, path, value)
            fake.result = result
            status, value = await call("PUT", "/mcp/files/enabled", {"enabled": False})
            assert status == 404 and value == {"error": "desktop_invalid"}
    finally:
        SERVERS[:] = saved


@asynchronous
async def test_exit_code_fallback_reports_the_new_version(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        fast(bridge)
        fake.receipt = {**fake.receipt, "action_id": "b" * 32}
        original = fake.result

        def result(method, path, value):
            if path == "/api/hermes/update":
                fake.version = "3.0"
            return original(method, path, value)
        fake.result = result
        await call("POST", "/hermes/update", {"mode": "now"})
        await until(lambda: bridge.desktop.updater.state == "done")
        assert bridge.desktop.updater.result["from"] == "1.0" and bridge.desktop.updater.result["to"] == "3.0"


@asynchronous
async def test_update_available_with_unknown_count_is_null_and_applicable(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (_, fake, call):
        fake.behind = -1
        original = fake.result

        def result(method, path, value):
            data = original(method, path, value)
            if path == "/api/hermes/update/check":
                data["update_available"] = True
            return data
        fake.result = result
        value = (await call("GET", "/hermes/update"))[1]
        assert value["behind"] is None and value["can_apply"] is True and value["reason"] is None
        assert (await call("POST", "/hermes/update", {"mode": "now"}))[0] == 202


@pytest.mark.parametrize("extra,expected", [
    ({}, 0),
    ({"readiness": {"checks": {"background_queues": {"active_api_runs": 2, "active_delegations": 1,
                                                     "process_completions": 9}}}}, 3),
    ({"readiness": {"checks": {"background_queues": {"active_api_runs": 0, "active_delegations": 1}}}}, 1),
    ({"gateway_busy": True}, 1),
    ({"readiness": {"checks": {"background_queues": {"active_api_runs": "x"}}}}, None),
    ({"readiness": {"checks": {"background_queues": {"active_delegations": -1}}}}, None),
    ({"active_agents": "many"}, None),
    ({"active_agents": None}, None)])
@asynchronous
async def test_idle_counts_every_busy_signal(tmp_path, monkeypatch, extra, expected):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        fake.gateway.health_extra = extra
        assert await bridge.desktop.updater.active_agents() == expected


@asynchronous
async def test_background_work_blocks_a_queued_update(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        updater = fast(bridge)
        fake.running = True
        fake.gateway.health_extra = {"readiness": {"checks": {"background_queues": {"active_delegations": 1}}}}
        await call("POST", "/hermes/update", {"mode": "when_idle"})
        await asyncio.sleep(0.15)
        assert updater.state == "queued" and not fake.posts("/api/hermes/update")
        fake.gateway.health_extra = {}
        await until(lambda: updater.state == "running")


@asynchronous
async def test_other_profile_key_is_used_when_default_has_none(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        keys = bridge.store.read("profile-keys.json", {})
        key = keys.pop("default")
        bridge.store.write("profile-keys.json", {**keys, "work": key})
        fake.gateway.expected_key = lambda target: key
        fake.gateway.active_agents = 4
        assert await bridge.desktop.updater.active_agents() == 4


@asynchronous
async def test_no_key_at_all_means_idle_is_unknown_and_never_starts(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        updater = fast(bridge)
        bridge.store.write("profile-keys.json", {})
        assert await updater.active_agents() is None
        await call("POST", "/hermes/update", {"mode": "when_idle"})
        await asyncio.sleep(0.15)
        assert updater.state == "queued" and not fake.posts("/api/hermes/update")
        assert (await call("GET", "/hermes/update"))[1]["active_agents"] is None


@asynchronous
async def test_unreachable_gateway_between_polls_is_never_idle(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        updater = fast(bridge)
        fake.running = True
        sequence = iter([0, None, 0, None, 0, 0])
        seen = []

        async def scripted():
            seen.append(next(sequence, 0))
            return seen[-1]
        updater.active_agents = scripted
        await call("POST", "/hermes/update", {"mode": "when_idle"})
        await until(lambda: updater.state == "running")
        assert seen == [0, None, 0, None, 0, 0]


@asynchronous
async def test_failing_gateway_request_counts_as_unknown(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        fake.gateway.expected_key = lambda target: "different-key-" + "x" * 20  # the gateway answers 401
        assert await bridge.desktop.updater.active_agents() is None


@asynchronous
async def test_two_concurrent_applies_start_exactly_one(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        fast(bridge)
        fake.running = True
        outcomes = await asyncio.gather(
            bridge.desktop.updater.post("default", "now"), bridge.desktop.updater.post("default", "now"),
            return_exceptions=True)
        assert sorted(type(o).__name__ for o in outcomes) == ["HTTPError", "tuple"]
        failure = next(o for o in outcomes if isinstance(o, Exception))
        assert (failure.status, failure.message) == (409, "update_in_progress")
        assert next(o for o in outcomes if isinstance(o, tuple))[0] == 202
        assert len(fake.posts("/api/hermes/update")) == 1


@asynchronous
async def test_profile_missing_from_the_host_record_still_checks_and_updates(tmp_path, monkeypatch):
    """The record lists the profiles served when the dashboard started; a profile created later is
    not in it, but the update belongs to the installation, so it runs through a listed profile."""
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        (bridge.config.hermes_home / "profiles" / "admin").mkdir(parents=True)
        fast(bridge)
        other = "/v1/profiles/admin/desktop"
        status, value = await call("GET", "/hermes/update", prefix=other)
        assert status == 200
        assert (value["current"], value["can_apply"], value["reason"]) == ("1.0", True, None)
        status, value = await call("POST", "/hermes/update", {"mode": "now"}, prefix=other)
        assert status == 202
        assert len(fake.posts("/api/hermes/update")) == 1
        assert all("profile=admin" not in request[1] for request in fake.requests)
        await until(lambda: bridge.desktop.updater.state == "done")


@asynchronous
async def test_profile_missing_from_the_record_with_no_dashboard_is_still_not_running(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        (bridge.config.hermes_home / "profiles" / "admin").mkdir(parents=True)
        fake.record.unlink()
        value = (await call("GET", "/hermes/update", prefix="/v1/profiles/admin/desktop"))[1]
        assert value["can_apply"] is False and value["reason"] == "dashboard_not_running"


@asynchronous
async def test_other_discovery_error_does_not_fall_back(tmp_path, monkeypatch):
    """A profile the record lists but whose discovery fails is not swapped for the default one."""
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        (bridge.config.hermes_home / "profiles" / "admin").mkdir(parents=True)
        record = json.loads(fake.record.read_text())
        record["profiles"] = ["default", "admin"]
        fake.record.write_text(json.dumps(record))
        asked, original = [], bridge.desktop.discover

        async def discover(profile):
            asked.append(profile)
            if profile == "admin":
                raise updater_module.HTTPError(503, "desktop_unavailable")
            return await original(profile)
        monkeypatch.setattr(bridge.desktop, "discover", discover)
        value = (await call("GET", "/hermes/update", prefix="/v1/profiles/admin/desktop"))[1]
        assert (value["can_apply"], value["reason"]) == (False, "dashboard_not_running")
        assert asked == ["admin"]
        assert not fake.requests


@asynchronous
async def test_record_serving_only_a_non_default_profile_is_used(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        for name in ("admin", "other"):
            (bridge.config.hermes_home / "profiles" / name).mkdir(parents=True)
        record = json.loads(fake.record.read_text())
        record["profiles"] = ["other"]
        fake.record.write_text(json.dumps(record))
        fake.profile = "other"
        fast(bridge)
        prefix = "/v1/profiles/admin/desktop"
        status, value = await call("GET", "/hermes/update", prefix=prefix)
        assert status == 200
        assert (value["current"], value["can_apply"], value["reason"]) == ("1.0", True, None)
        assert fake.requests and all("profile=other" in request[1] for request in fake.requests
                                     if request[1].startswith("/api/hermes"))
        assert (await call("POST", "/hermes/update", {"mode": "now"}, prefix=prefix))[0] == 202
        await until(lambda: bridge.desktop.updater.state == "done")


@asynchronous
async def test_queued_update_keeps_hermes_update_available(tmp_path, monkeypatch):
    async with hermes(tmp_path, monkeypatch) as (bridge, fake, call):
        fake.behind = -1
        original = fake.result

        def result(method, path, value):
            data = original(method, path, value)
            if path == "/api/hermes/update/check":
                data["update_available"] = True
            return data
        fake.result = result
        fake.running = True
        fast(bridge)
        assert (await call("GET", "/hermes/update"))[1]["update_available"] is True
        assert (await call("POST", "/hermes/update", {"mode": "now"}))[0] == 202
        value = (await call("GET", "/hermes/update"))[1]
        assert value["state"] == "running" and value["reason"] == "update_in_progress"
        assert value["behind"] is None and value["can_apply"] is False
        assert value["update_available"] is True
        fake.running = False
        await until(lambda: bridge.desktop.updater.state == "done")
