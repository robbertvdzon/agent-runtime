#!/usr/bin/env python3
"""Run agentic Codex turns through Ollama, validating only the final answer."""
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import sys

from jsonschema import Draft202012Validator

MAX_RESULT_BYTES = 1024 * 1024
JSON_BLOCK = re.compile(r"(?:Here is the JSON:\s*)?```(?:json)?\s*(.*?)\s*```", re.DOTALL | re.IGNORECASE)


def validate(text, schema):
    match = JSON_BLOCK.fullmatch(text.strip())
    result = json.loads(match.group(1) if match else text)
    errors = list(Draft202012Validator(schema).iter_errors(result))
    if errors:
        # Report paths/validator only: invalid values can contain sensitive source text.
        raise ValueError("; ".join(f"{list(e.absolute_path)}: {e.validator}" for e in errors[:8]))
    return result


def run(input_dir=Path('/job/input'), output_dir=Path('/job/output')):
    schema = json.loads((input_dir / 'response-schema.json').read_text())
    result_path = Path(os.environ.get('AR_RESULT_FILE', str(output_dir / 'result.json')))
    totals = {}
    child = None

    def stop(signum, frame):
        if child is not None and child.poll() is None:
            os.killpg(child.pid, signal.SIGTERM)
        raise SystemExit(128 + signum)

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        for attempt in range(4):
            result_path.unlink(missing_ok=True)
            env = dict(os.environ, AR_ENGINE='OLLAMA_CODEX')
            child = subprocess.Popen(['/usr/local/bin/run-agent'], env=env, stdout=subprocess.PIPE,
                                     text=True, start_new_session=True)
            for line in child.stdout:
                try:
                    event = json.loads(line)
                except json.JSONDecodeError:
                    print(line, end='', flush=True)
                    continue
                # Codex turn totals reset on a new CLI invocation; expose a cumulative total
                # so the worker retains usage of rejected answers as well.
                if event.get('type') == 'turn.completed' and isinstance(event.get('usage'), dict):
                    for key, value in event['usage'].items():
                        if isinstance(value, int) and not isinstance(value, bool) and value >= 0:
                            totals[key] = totals.get(key, 0) + value
                    event['usage'] = dict(totals)
                print(json.dumps(event, ensure_ascii=False), flush=True)
            code = child.wait()
            if code:
                return code
            try:
                if not result_path.is_file() or result_path.stat().st_size > MAX_RESULT_BYTES:
                    raise ValueError('Missing or oversized final result')
                result = validate(result_path.read_text(), schema)
                result_path.write_text(json.dumps(result, ensure_ascii=False))
                return 0
            except (ValueError, UnicodeError) as error:
                if attempt == 3:
                    result_path.unlink(missing_ok=True)
                    (output_dir / 'ollama-status.json').write_text(json.dumps({'code': 'MODEL_OUTPUT_SCHEMA_INVALID'}))
                    return 65
                # Re-read sources rather than trusting a malformed prior answer. Prompt and
                # schema are mounted read-only; pass the correction in the next CLI environment.
                correction = f'\n\nYour previous final response failed JSON/schema validation: {str(error)[:1000]}. Read the sources and schema and return a corrected complete JSON document, without Markdown fences.'
                os.environ['AR_OLLAMA_CORRECTION'] = correction
    finally:
        os.environ.pop('AR_OLLAMA_CORRECTION', None)
    return 65


if __name__ == '__main__':
    sys.exit(run())
