#!/usr/bin/env python3
"""Pinned Vibe 3.0.2 adapter for HKH structured subscription jobs.

The hook and usage reader deliberately use its versioned unified-session journal.
Raw reasoning and export configuration never leave the execution container.
"""
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import tempfile
import threading

from jsonschema import Draft202012Validator

MAX_RESULT_BYTES = 1024 * 1024
SUPPORTED_MODELS = {"glm-5-3": "zai-glm-5-3"}


def records(session, contains=None):
    for path in sorted((Path(session) / "journal").glob("*.jsonl")):
        with path.open(encoding="utf-8") as stream:
            for line in stream:
                if contains is not None and contains not in line:
                    continue
                try:
                    yield json.loads(line)
                except json.JSONDecodeError:
                    # A writer can still be appending the last line during a live read.
                    continue


def pending_candidate(session):
    latest = None
    for record in records(session, "post_agent_turn"):
        if record.get("type") != "action_intent":
            continue
        request = record["payload"].get("request", {})
        if request.get("hook") == "post_agent_turn":
            latest = request["input"]["candidate"]["message"]
    if latest is None:
        raise ValueError("Vibe journal has no pending final candidate")
    return text_content(latest.get("content", []))


def text_content(parts):
    return "".join(p.get("text", "") for p in parts if p.get("type") == "text")


def validate(text, schema):
    if not text.strip():
        raise ValueError("The final JSON answer is missing")
    if len(text.encode("utf-8")) > MAX_RESULT_BYTES:
        raise ValueError("The final JSON answer exceeds 1 MiB")
    # Reject non-JSON numeric constants accepted by Python's permissive default.
    def invalid_constant(value):
        raise ValueError(f"Invalid JSON constant: {value}")
    value = json.loads(text, parse_constant=invalid_constant)
    if not isinstance(value, dict):
        raise ValueError("The final answer must be a JSON object")
    if schema is not None:
        Draft202012Validator(schema).validate(value)
    return value


def hook(schema_path):
    try:
        invocation = json.load(sys.stdin)
        schema = json.loads(Path(schema_path).read_text()) if Path(schema_path).exists() else None
        candidate = pending_candidate(invocation["transcript_path"])
        try:
            validate(candidate, schema)
            response = {"decision": "allow"}
        except Exception as error:
            # Truncate only feedback, never silently truncate or accept the result.
            detail = str(error)[:3000]
            response = {"decision": "deny", "reason": (
                f"Final answer validation failed: {detail}\n"
                f"Read {schema_path} and return the COMPLETE corrected JSON object only. "
                "Keep the existing research and source references; correct the output format.\n"
                f"Rejected answer:\n{candidate[:MAX_RESULT_BYTES]}"
            )}
    except Exception:
        # Vibe hook failures default to passthrough, so explicitly deny extraction failures.
        response = {"decision": "deny", "reason": "The runtime could not validate the final answer. Return the complete JSON object according to the response schema."}
    print(json.dumps(response), flush=True)


def usage_events(session):
    """One record per completed model request, including hook-rejected answers."""
    seen = set()
    for record in records(session, "completion_succeeded"):
        if record.get("type") != "action_result":
            continue
        result = record["payload"].get("result", {})
        action = result.get("action_id")
        if result.get("type") != "completion_succeeded" or not action or action in seen:
            continue
        seen.add(action)
        usage = result["result"].get("usage")
        if isinstance(usage, dict):
            yield {"type": "mistral.usage", "request_id": action, "session_id": json.loads((Path(session) / "meta.json").read_text())["session_id"], "usage": usage}


def public_event(entry):
    """Convert visible messages/tools to the existing provider-neutral Codex envelope."""
    if (entry.get("outcome") or {}).get("type") == "discarded":
        return None
    if entry.get("type") == "message" and entry.get("role") == "assistant":
        text = text_content(entry.get("content", []))
        if text:
            return {"type": "item.completed", "item": {"id": entry["id"], "type": "agent_message", "text": text}}
    if entry.get("type") == "effect":
        detail = entry.get("detail") or {}
        state = entry.get("state") or {}
        return {"type": "item.completed", "item": {
            "id": entry["id"], "type": "mcp_tool_call", "tool": detail.get("toolName", entry.get("title", "Vibe tool")),
            "arguments": detail.get("input", {}),
            "result": json.dumps(state.get("output", {}), ensure_ascii=False)[:16000],
            "status": state.get("status", "completed"),
        }}
    return None


def failure_code(export, exit_code):
    error = json.dumps(export.get("error", "")).lower()
    if exit_code == 3 or export.get("outcome") == "time_limit":
        return "EXECUTION_TIMEOUT"
    if any(word in error for word in ("429", "quota", "rate limit", "rate_limit", "401", "unauthorized", "403", "503", "overloaded")):
        return "PROVIDER_UNAVAILABLE"
    return "ENGINE_FAILED"


def emit(event):
    print(json.dumps(event, ensure_ascii=False), flush=True)


def run(input_dir=Path("/job/input"), output_dir=Path("/job/output"), workdir=Path("/work")):
    model = os.environ.get("AR_MODEL", "")
    result_path = Path(os.environ.get("AR_RESULT_FILE", str(output_dir / "result.json")))
    status_path = output_dir / "mistral-status.json"
    result_path.unlink(missing_ok=True)
    status_path.unlink(missing_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)

    def failed(code):
        status_path.write_text(json.dumps({"code": code}))
        emit({"type": "mistral.status", "code": code})
        return 1

    if model not in SUPPORTED_MODELS:
        return failed("ENGINE_FAILED")
    schema_path = input_dir / "response-schema.json"
    schema = json.loads(schema_path.read_text()) if schema_path.exists() else None
    if schema is not None:
        Draft202012Validator.check_schema(schema)
    with tempfile.TemporaryDirectory(prefix="ar-vibe-") as temporary:
        home = Path(temporary)
        config = (
            'active_model = "glm-5-3"\nenable_update_checks = false\nenable_auto_update = false\n'
            'enable_telemetry = false\nenable_otel = false\nenable_subagents = false\n'
            'enable_connectors = false\nenable_mcp = false\n'
            '[session_logging]\ngenerate_titles = false\n'
            '[[models]]\nname = "zai-glm-5-3"\nalias = "glm-5-3"\nprovider = "mistral"\n'
            'max_output_tokens = 65536\nthinking = "high"\n'
        )
        (home / "config.toml").write_text(config)
        command = shlex.join([sys.executable, str(Path(__file__).resolve()), "hook", str(schema_path)])
        (home / "hooks.toml").write_text('[[hooks]]\nname = "runtime-json-validation"\ntype = "post_agent"\ncommand = ' + json.dumps(command) + '\n')
        prompt = home / "prompt.md"
        prompt.write_text((input_dir / "prompt.md").read_text() +
                         f"\nReturn ONLY the complete JSON object satisfying {schema_path}. Do not write a result file yourself.\n")
        exported = home / "export"
        env = {**os.environ, "VIBE_HOME": str(home), "VIBE_ACTIVE_MODEL": model}
        deadline = max(1, int(os.environ.get("AR_TIME_LIMIT_SECONDS", "1500")))
        args = [os.environ.get("AR_VIBE_BINARY", "vibe"), "--prompt-file", str(prompt), "--output", "streaming", "--auto-approve", "--trust", "--workdir", str(workdir), "--output-dir", str(exported), "--time-limit", str(deadline), "--max-turns", "60", "--max-tokens", "3500000"]
        process = subprocess.Popen(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        interrupted = False

        def stop(signum, frame):
            nonlocal interrupted
            interrupted = True
            process.terminate()
        previous = signal.signal(signal.SIGTERM, stop)
        final = ""
        emitted_usage = set()

        def read_usage(session):
            for event in usage_events(session):
                if event['request_id'] not in emitted_usage:
                    emit(event)
                    emitted_usage.add(event['request_id'])

        # The separate monitor reports tokens while a tool is active, not only on exit.
        monitor_done = threading.Event()
        def monitor():
            while not monitor_done.wait(1):
                # Session path is recovered from the isolated VIBE_HOME, never a host profile.
                for session in (home / "logs" / "session" / "unified").glob("*"):
                    try:
                        read_usage(session)
                    except (OSError, ValueError, KeyError):
                        pass
        thread = threading.Thread(target=monitor, daemon=True)
        thread.start()
        try:
            for line in process.stdout:
                try:
                    entry = json.loads(line)
                    public = public_event(entry)
                    if public:
                        emit(public)
                    if entry.get("type") == "message" and entry.get("role") == "assistant" and (entry.get("outcome") or {}).get("type") == "committed":
                        final = text_content(entry.get("content", []))
                except (ValueError, KeyError, TypeError, AttributeError):
                    continue
            exit_code = process.wait(timeout=15)
        finally:
            process.stdout.close()
            monitor_done.set()
            thread.join(timeout=5)
            signal.signal(signal.SIGTERM, previous)
            if process.poll() is None:
                process.kill()
                process.wait()
        export = json.loads((exported / "export.json").read_text()) if (exported / "export.json").exists() else {}
        session = exported / "session"
        if session.exists():
            read_usage(session)
        # Don't report accounting complete unless the expected number of model calls is present.
        steps = export.get("steps", 0)
        usage_complete = bool(emitted_usage) and steps == len(emitted_usage) and not interrupted
        emit({"type": "mistral.completed", "session_id": export.get("session_id"), "complete": usage_complete})
        if interrupted or exit_code != 0 or export.get("outcome") != "finished":
            return failed(failure_code(export, 3 if interrupted else exit_code))
        effective = export.get("config", {}).get("active_model")
        if effective != model:
            return failed("ENGINE_FAILED")
        try:
            value = validate(final, schema)
        except json.JSONDecodeError:
            return failed("MODEL_OUTPUT_NOT_JSON")
        except Exception:
            return failed("MODEL_OUTPUT_SCHEMA_INVALID")
        result_path.write_text(json.dumps(value, ensure_ascii=False))
        return 0


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "hook":
        hook(sys.argv[2])
    else:
        try:
            sys.exit(run())
        except Exception:
            # Never print provider configuration, credentials or raw reasoning on failure.
            emit({"type": "mistral.status", "code": "ENGINE_FAILED"})
            sys.exit(1)
