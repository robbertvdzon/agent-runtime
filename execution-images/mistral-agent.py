#!/usr/bin/env python3
"""Pinned Vibe 3.0.2 adapter for HKH structured subscription jobs.

The hook and usage reader deliberately use its versioned unified-session journal.
Raw reasoning and export configuration never leave the execution container.
"""
import contextlib
import json
import os
import re
from pathlib import Path
import shlex
import signal
import subprocess
import sys
import tempfile
import threading
import time

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
    value = parse_answer(text)
    if not isinstance(value, dict):
        raise ValueError("The final answer must be a JSON object")
    if schema is not None:
        Draft202012Validator(schema).validate(value)
    return value


def hook(schema_path):
    # Capture the explicit final candidate before Vibe can replace it with a CLI
    # acknowledgement. Formatting happens separately with all tools disabled.
    try:
        invocation = json.load(sys.stdin)
        candidate = pending_candidate(invocation['transcript_path'])
        if not candidate.strip() or len(candidate.encode('utf-8')) > MAX_RESULT_BYTES:
            raise ValueError('Missing or oversized candidate')
        draft = Path(os.environ['AR_MISTRAL_DRAFT_FILE'])
        draft.write_text(candidate)
        draft.chmod(0o600)
        response = {'decision': 'allow'}
    except Exception:
        response = {'decision': 'deny', 'reason': 'Return the complete bounded JSON answer; no final candidate could be captured.'}
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


def limit_events(session):
    for record in records(session, "completion_failed"):
        if record.get("type") != "action_result":
            continue
        result = record.get("payload", {}).get("result", {})
        if result.get("type") != "completion_failed":
            continue
        event = limit_event({"error": result.get("error")})
        if event:
            event["request_id"] = result.get("action_id")
            yield event


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


def limit_event(export):
    """Only provider failures establish limits; token_limit is a local session cap.

    Vibe error strings can establish an unknown limit. Only explicit period codes
    establish a week/month cap. Never publish raw error text or configuration.
    """
    error = export.get("error")
    if not error:
        return None
    detail = error if isinstance(error, dict) else {}
    extra = detail.get("details") if isinstance(detail.get("details"), dict) else {}
    text = json.dumps(error).lower()
    code = str(detail.get("code", ""))
    status = detail.get("status_code", detail.get("status", extra.get("httpStatus")))
    if status not in (429,) and not any(word in text for word in ("429", "quota", "rate limit", "rate_limit")):
        return None
    kind = {"weekly_quota_exceeded": "WEEK", "monthly_quota_exceeded": "MONTH"}.get(code, "UNKNOWN")
    event = {"type": "mistral.limit", "kind": kind}
    if isinstance(status, int) and 100 <= status <= 599:
        event["http_status"] = status
    elif "429" in text:
        event["http_status"] = 429
    if re.fullmatch(r"[A-Za-z0-9_.:-]{1,120}", code):
        event["provider_code"] = code
    retry = detail.get("retry_after_seconds")
    if isinstance(retry, int) and not isinstance(retry, bool) and 0 <= retry <= 31536000:
        event["retry_after_seconds"] = retry
    reset = detail.get("resets_at")
    if isinstance(reset, (int, str)):
        event["resets_at"] = reset
    return event


def failure_code(export, exit_code):
    outcome = export.get("outcome")
    local_limits = {"token_limit": "SESSION_TOKEN_LIMIT", "turn_limit": "SESSION_TURN_LIMIT",
                    "price_limit": "SESSION_PRICE_LIMIT", "length": "MODEL_OUTPUT_LIMIT"}
    if outcome in local_limits:
        return local_limits[outcome]
    if outcome in ("deadline", "time_limit", "terminated"):
        return "EXECUTION_TIMEOUT"
    error = json.dumps(export.get("error", "")).lower()
    if any(word in error for word in ("429", "quota", "rate limit", "rate_limit", "401", "unauthorized", "403", "503", "overloaded")):
        return "PROVIDER_UNAVAILABLE"
    return "ENGINE_FAILED"


def emit(event):
    print(json.dumps(event, ensure_ascii=False), flush=True)


MAX_REPAIRS = 3
MAX_SESSION_TOKENS = 3_500_000
JSON_BLOCK = re.compile(r'(?:Here is the JSON:\s*)?```(?:json)?\s*(.*?)\s*```', re.DOTALL | re.IGNORECASE)
OUTCOMES = {'finished', 'usage_error', 'config_error', 'infrastructure_failure', 'turn_limit',
            'token_limit', 'price_limit', 'deadline', 'terminated', 'length', 'refusal', 'aborted'}
ERROR_CODES = {'model_stream_failed', 'model_request_failed', 'context_length_exceeded',
               'weekly_quota_exceeded', 'monthly_quota_exceeded', 'rate_limit_exceeded'}


def parse_answer(text):
    if len(text.encode('utf-8')) > MAX_RESULT_BYTES:
        raise ValueError('Answer exceeds result limit')
    match = JSON_BLOCK.fullmatch(text.strip())
    def invalid_constant(value):
        raise ValueError('Non-JSON number')
    return json.loads(match.group(1) if match else text, parse_constant=invalid_constant)


def preserve_valid_fields(original, corrected, schema):
    # For flat result schemas protect already valid text and source IDs. Complex schemas
    # are validated as a whole; don't guess at dependencies between properties.
    if not isinstance(original, dict) or not isinstance(corrected, dict) or not schema:
        return corrected
    if any(k in schema for k in ('$ref', 'allOf', 'anyOf', 'oneOf', 'if', 'dependentSchemas', 'dependentRequired')):
        return corrected
    errors = list(Draft202012Validator(schema).iter_errors(original))
    if any(not e.absolute_path and e.validator not in ('required', 'additionalProperties') for e in errors):
        return corrected
    invalid = {next(iter(e.absolute_path)) for e in errors if e.absolute_path}
    result = dict(corrected)
    for name in schema.get('properties', {}):
        if name in original and name not in invalid:
            result[name] = original[name]
    return result


def diagnostic(export, exit_code, stderr, stage, attempt, exception=None):
    # Strict allowlist, not redaction of arbitrary provider messages: stderr, configuration,
    # credentials, draft content and reasoning can never be copied into diagnostics.
    error = export.get('error') or {}
    detail = error if isinstance(error, dict) else {}
    extra = detail.get('details') if isinstance(detail.get('details'), dict) else {}
    status = detail.get('status_code', detail.get('status', extra.get('httpStatus')))
    text = (json.dumps(error) + ' ' + stderr).lower()
    category = 'none' if exit_code == 0 and export.get('outcome') == 'finished' and exception is None else 'unknown_execution_failure'
    for words, label in [
        (('context length', 'context window', 'too many tokens'), 'context_limit'),
        (('429', 'quota', 'rate limit', 'rate_limit'), 'provider_limit'),
        (('401', '403', 'unauthorized', 'authentication'), 'authentication'),
        (('connection', 'timed out', 'timeout', '503', '502', 'overloaded'), 'provider_connection'),
        (('hook',), 'hook_failure'),
    ]:
        if any(word in text for word in words):
            category = label
            break
    if isinstance(exception, FileNotFoundError):
        category = 'export_missing'
    elif isinstance(exception, (ValueError, json.JSONDecodeError)):
        category = 'export_invalid'
    elif export.get('outcome') in ('config_error', 'usage_error'):
        category = 'configuration'
    result = {'type': 'mistral.diagnostic', 'stage': stage, 'attempt': attempt,
              'exit_code': exit_code, 'outcome': export.get('outcome') if export.get('outcome') in OUTCOMES else 'unknown',
              'category': category, 'stderr_present': bool(stderr)}
    if detail.get('code') in ERROR_CODES:
        result['provider_error_code'] = detail['code']
    if isinstance(status, int) and not isinstance(status, bool) and 100 <= status <= 599:
        result['http_status'] = status
    if exception:
        result['exception_type'] = type(exception).__name__ if type(exception) in (OSError, FileNotFoundError, PermissionError, TimeoutError, ValueError, KeyError, TypeError, json.JSONDecodeError) else 'AdapterError'
    return result


def run_vibe(prompt_text, schema_path, workdir, output_dir, deadline, model, stage, attempt, budget, observe):
    with tempfile.TemporaryDirectory(prefix='ar-vibe-') as temporary:
        home = Path(temporary)
        draft = home / 'candidate.txt'
        config = (
            'active_model = "glm-5-3"\nenable_update_checks = false\nenable_auto_update = false\n'
            'enable_telemetry = false\nenable_otel = false\nenable_subagents = false\n'
            'enable_connectors = false\nenable_mcp = false\napi_retry_max_elapsed_time = 0.0\n'
            + ('disabled_tools = ["*"]\n' if stage == 'repair' else '') +
            '[session_logging]\ngenerate_titles = false\n'
            '[[models]]\nname = "zai-glm-5-3"\nalias = "glm-5-3"\nprovider = "mistral"\n'
            'max_output_tokens = 65536\nthinking = "high"\n')
        (home / 'config.toml').write_text(config)
        command = shlex.join([sys.executable, str(Path(__file__).resolve()), 'hook', str(schema_path)])
        (home / 'hooks.toml').write_text('[[hooks]]\nname = "runtime-answer-capture"\ntype = "post_agent"\ncommand = ' + json.dumps(command) + '\n')
        prompt = home / 'prompt.md'
        prompt.write_text(prompt_text)
        exported = home / 'export'
        env = {**os.environ, 'VIBE_HOME': str(home), 'VIBE_ACTIVE_MODEL': model, 'AR_MISTRAL_DRAFT_FILE': str(draft)}
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Job deadline reached')
        args = [os.environ.get('AR_VIBE_BINARY', 'vibe'), '--prompt-file', str(prompt), '--output', 'streaming', '--auto-approve', '--trust',
                '--workdir', str(workdir), '--output-dir', str(exported), '--time-limit', str(max(1, int(remaining))),
                '--max-turns', '2' if stage == 'repair' else '60', '--max-tokens', str(budget)]
        started = time.monotonic()
        last_model_at = None
        process = subprocess.Popen(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, start_new_session=True)
        interrupted = False
        stderr_tail = bytearray()
        def read_stderr():
            while True:
                chunk = process.stderr.read(1024)
                if not chunk:
                    break
                stderr_tail.extend(chunk.encode('utf-8', errors='replace'))
                del stderr_tail[:-16384]
        stderr_thread = threading.Thread(target=read_stderr, daemon=True)
        stderr_thread.start()
        def terminate(sig):
            with contextlib.suppress(ProcessLookupError):
                os.killpg(process.pid, sig)
        def stop(signum, frame):
            nonlocal interrupted
            interrupted = True
            terminate(signal.SIGTERM)
        previous = signal.signal(signal.SIGTERM, stop)
        done = threading.Event()
        emitted = set()
        limit_ids = set()
        accounting_error = False
        def collect(session):
            nonlocal accounting_error, last_model_at
            try:
                for event in limit_events(session):
                    if event['request_id'] not in limit_ids:
                        emit(event)
                        limit_ids.add(event['request_id'])
                for event in usage_events(session):
                    if event['request_id'] not in emitted:
                        emitted.add(event['request_id'])
                        last_model_at = time.monotonic()
                        event['request_id'] = f'{stage}-{attempt}-{event["request_id"]}'
                        observe(event)
            except (OSError, ValueError, KeyError, TypeError):
                accounting_error = True
        def monitor():
            while not done.wait(0.5):
                if time.monotonic() >= deadline:
                    terminate(signal.SIGTERM)
                    if not done.wait(3):
                        terminate(signal.SIGKILL)
                for session in (home / 'logs' / 'session' / 'unified').glob('*'):
                    collect(session)
        monitor_thread = threading.Thread(target=monitor, daemon=True)
        monitor_thread.start()
        final = ''
        try:
            for line in process.stdout:
                try:
                    entry = json.loads(line)
                    public = public_event(entry)
                    if public and stage == 'research':
                        emit(public)
                    if entry.get('type') == 'message' and entry.get('role') == 'assistant' and (entry.get('outcome') or {}).get('type') == 'committed':
                        final = text_content(entry.get('content', []))
                except (ValueError, KeyError, TypeError, AttributeError):
                    continue
            exit_code = process.wait(timeout=15)
        finally:
            done.set()
            monitor_thread.join(timeout=5)
            signal.signal(signal.SIGTERM, previous)
            if process.poll() is None:
                terminate(signal.SIGKILL)
                process.wait()
            stderr_thread.join(timeout=5)
            process.stdout.close()
            process.stderr.close()
        export = {}
        export_error = None
        try:
            export = json.loads((exported / 'export.json').read_text())
            if not isinstance(export, dict):
                raise ValueError('Invalid export')
        except (OSError, ValueError) as error:
            export, export_error = {}, error
        session = exported / 'session'
        if session.exists():
            collect(session)
        else:
            for session in (home / 'logs' / 'session' / 'unified').glob('*'):
                collect(session)
        # Journal may contain the useful cause while the final export has no error.
        if not export.get('error'):
            for record in records(session, 'completion_failed'):
                failure = record.get('payload', {}).get('result', {})
                if failure.get('type') == 'completion_failed':
                    export['error'] = failure.get('error')
        fallback_limit = limit_event(export)
        if fallback_limit and not limit_ids:
            emit(fallback_limit)
        timeout = interrupted or time.monotonic() >= deadline
        complete = (bool(emitted) and export.get('steps') == len(emitted) and not timeout
                    and not accounting_error and export.get('outcome') == 'finished' and export_error is None)
        diag = diagnostic(export, exit_code, stderr_tail.decode('utf-8', errors='replace'), stage, attempt, export_error)
        diag['candidate_available'] = draft.is_file() or bool(final)
        diag['duration_seconds'] = round(time.monotonic() - started, 1)
        diag['model_calls'] = len(emitted)
        if last_model_at is not None:
            diag['last_model_seconds_ago'] = round(time.monotonic() - last_model_at, 1)
        emit(diag)
        code = 'EXECUTION_TIMEOUT' if timeout else failure_code(export, exit_code)
        if export_error or exit_code != 0 or export.get('outcome') != 'finished':
            if code == 'ENGINE_FAILED':
                code = {'context_limit': 'SESSION_TOKEN_LIMIT', 'provider_connection': 'PROVIDER_UNAVAILABLE',
                        'authentication': 'PROVIDER_UNAVAILABLE'}.get(diag['category'], code)
            return None, code, complete
        if export.get('config', {}).get('active_model') != model:
            return None, 'ENGINE_FAILED', complete
        # An explicit post-agent candidate is more reliable than a final CLI acknowledgement.
        candidate = draft.read_text() if draft.is_file() and draft.stat().st_size <= MAX_RESULT_BYTES else final
        return candidate, None, complete


def run(input_dir=Path('/job/input'), output_dir=Path('/job/output'), workdir=Path('/work')):
    model = os.environ.get('AR_MODEL', '')
    result_path = Path(os.environ.get('AR_RESULT_FILE', str(output_dir / 'result.json')))
    status_path = output_dir / 'mistral-status.json'
    draft_path = output_dir / '.mistral-draft.txt'
    for path in (result_path, status_path, draft_path):
        path.unlink(missing_ok=True)
    output_dir.mkdir(parents=True, exist_ok=True)
    all_complete = True
    spent = 0
    def observe(event):
        nonlocal spent
        usage = event['usage']
        spent += int(usage.get('input_tokens', 0)) + int(usage.get('output_tokens', 0))
        emit(event)
    def failed(code):
        status_path.write_text(json.dumps({'code': code}))
        emit({'type': 'mistral.status', 'code': code})
        return 1
    if model not in SUPPORTED_MODELS:
        return failed('ENGINE_FAILED')
    schema_path = input_dir / 'response-schema.json'
    schema = json.loads(schema_path.read_text()) if schema_path.exists() else None
    if schema is not None:
        Draft202012Validator.check_schema(schema)
    deadline = time.monotonic() + max(1, int(os.environ.get('AR_TIME_LIMIT_SECONDS', '1500')))
    prompt = (input_dir / 'prompt.md').read_text() + f'\nReturn ONLY the complete JSON object satisfying {schema_path}. Do not write a result file yourself.\n'
    candidate = None
    try:
        for attempt in range(MAX_REPAIRS + 1):
            stage = 'research' if attempt == 0 else 'repair'
            emit({'type': 'mistral.phase', 'stage': stage, 'attempt': attempt})
            budget = MAX_SESSION_TOKENS - spent
            if budget <= 0:
                return failed('SESSION_TOKEN_LIMIT')
            # Tool-free repair gets an empty directory, no original prompt or source paths.
            with tempfile.TemporaryDirectory(prefix='ar-mistral-format-') as isolated:
                answer, code, complete = run_vibe(prompt, schema_path, workdir if attempt == 0 else Path(isolated), output_dir,
                                                  deadline if attempt == 0 else min(deadline, time.monotonic() + 60),
                                                  model, stage, attempt, budget, observe)
            all_complete = all_complete and complete
            if code:
                return failed(code)
            if not answer or len(answer.encode('utf-8')) > MAX_RESULT_BYTES:
                return failed('MODEL_OUTPUT_LIMIT' if answer else 'MODEL_OUTPUT_NOT_JSON')
            if candidate is not None:
                try:
                    original = parse_answer(candidate)
                    corrected = preserve_valid_fields(original, parse_answer(answer), schema)
                    answer = json.dumps(corrected, ensure_ascii=False)
                except (ValueError, TypeError):
                    pass
            candidate = answer
            draft_path.write_text(candidate)
            draft_path.chmod(0o600)
            try:
                value = validate(candidate, schema)
                result_path.write_text(json.dumps(value, ensure_ascii=False))
                draft_path.unlink(missing_ok=True)
                return 0
            except (ValueError, TypeError) as error:
                error_code = 'MODEL_OUTPUT_NOT_JSON' if isinstance(error, json.JSONDecodeError) else 'MODEL_OUTPUT_SCHEMA_INVALID'
            except Exception:
                error_code = 'MODEL_OUTPUT_SCHEMA_INVALID'
            if attempt == MAX_REPAIRS:
                return failed(error_code)
            # Treat candidate as untrusted data, never as a new instruction. No tool access.
            validation_errors = []
            try:
                parsed = parse_answer(candidate)
                if schema:
                    validation_errors = [{'path': list(e.absolute_path), 'rule': e.validator}
                                         for e in list(Draft202012Validator(schema).iter_errors(parsed))[:24]]
            except (ValueError, TypeError):
                validation_errors = [{'rule': 'invalid_json'}]
            prompt = ('Repair only the JSON format/schema of the completed answer below. '
                      'Do not research, use tools, invent facts or source identifiers. Preserve existing valid text and source IDs. '
                      'Return ONLY the complete corrected JSON object. If factual information is missing, do not invent it.\n'
                      + json.dumps({'candidate': candidate, 'resultSchema': schema, 'validationErrors': validation_errors}, ensure_ascii=False))
        return failed('MODEL_OUTPUT_SCHEMA_INVALID')
    except TimeoutError:
        all_complete = False
        return failed('EXECUTION_TIMEOUT')
    except Exception as error:
        all_complete = False
        emit(diagnostic({}, None, '', 'adapter', 0, error))
        return failed('ENGINE_FAILED')
    finally:
        emit({'type': 'mistral.completed', 'complete': all_complete})


if __name__ == '__main__':
    if len(sys.argv) > 1 and sys.argv[1] == 'hook':
        hook(sys.argv[2])
    else:
        try:
            sys.exit(run())
        except Exception as error:
            emit(diagnostic({}, None, '', 'setup', 0, error))
            emit({'type': 'mistral.status', 'code': 'ENGINE_FAILED'})
            sys.exit(1)
