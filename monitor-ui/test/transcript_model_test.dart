import 'dart:convert';

import 'package:agent_runtime_monitor/transcript_model.dart';
import 'package:flutter_test/flutter_test.dart';

Map<String, dynamic> part(
  int sequence,
  dynamic value, {
  String attempt = 'attempt-1',
  String kind = 'AGENT_TEXT',
  String? stream,
  bool finalPart = true,
}) => {
  'partId': '$attempt-part-$sequence',
  'attemptId': attempt,
  'sequence': sequence,
  'kind': kind,
  'text': value is String ? value : jsonEncode(value),
  'streamId': ?stream,
  'final': finalPart,
};

Map<String, dynamic> streamEvent(Map<String, dynamic> event) => {
  'type': 'stream_event',
  'event': event,
};

Map<String, dynamic> startMessage(String id) => streamEvent({
  'type': 'message_start',
  'message': {'id': id},
});

Map<String, dynamic> textStart({int index = 0}) => streamEvent({
  'type': 'content_block_start',
  'index': index,
  'content_block': {'type': 'text', 'text': ''},
});

Map<String, dynamic> textDelta(String text, {int index = 0}) => streamEvent({
  'type': 'content_block_delta',
  'index': index,
  'delta': {'type': 'text_delta', 'text': text},
});

Map<String, dynamic> assistant(String id, String text) => {
  'type': 'assistant',
  'message': {
    'id': id,
    'content': [
      {'type': 'text', 'text': text},
    ],
  },
};

void main() {
  test(
    'Claude per-block snapshots preserve absolute stream indices after thinking',
    () {
      final doc = normalizeTranscript([
        part(1, startMessage('m1')),
        part(
          2,
          streamEvent({
            'type': 'content_block_start',
            'index': 0,
            'content_block': {'type': 'thinking', 'thinking': ''},
          }),
        ),
        part(3, {
          'type': 'assistant',
          'message': {
            'id': 'm1',
            'content': [
              {'type': 'thinking', 'thinking': 'private'},
            ],
          },
        }),
        part(4, streamEvent({'type': 'content_block_stop', 'index': 0})),
        part(5, textStart(index: 1)),
        part(6, textDelta('Ik lees de bron.', index: 1)),
        part(7, assistant('m1', 'Ik lees de bron.')),
        part(8, streamEvent({'type': 'content_block_stop', 'index': 1})),
        part(
          9,
          streamEvent({
            'type': 'content_block_start',
            'index': 2,
            'content_block': {
              'type': 'tool_use',
              'id': 'read-1',
              'name': 'Read',
              'input': {},
            },
          }),
        ),
        part(
          10,
          streamEvent({
            'type': 'content_block_delta',
            'index': 2,
            'delta': {
              'type': 'input_json_delta',
              'partial_json': '{"file_path":"main.dart"}',
            },
          }),
        ),
        part(11, {
          'type': 'assistant',
          'message': {
            'id': 'm1',
            'content': [
              {
                'type': 'tool_use',
                'id': 'read-1',
                'name': 'Read',
                'input': {'file_path': 'main.dart'},
              },
            ],
          },
        }),
        part(12, streamEvent({'type': 'content_block_stop', 'index': 2})),
        part(13, textStart(index: 3)),
        part(14, textDelta('Daarna controleer ik de test.', index: 3)),
        part(15, assistant('m1', 'Daarna controleer ik de test.')),
        part(16, streamEvent({'type': 'content_block_stop', 'index': 3})),
        part(17, streamEvent({'type': 'message_stop'})),
      ]);
      expect(doc.entries, hasLength(3));
      expect(doc.entries.map((entry) => entry.text), [
        'Ik lees de bron.',
        'Bestand lezen',
        'Daarna controleer ik de test.',
      ]);
      expect(jsonDecode(doc.entries[1].input!), {'file_path': 'main.dart'});
      expect(doc.entries.first.status, TranscriptEntryStatus.completed);
      expect(doc.entries.last.status, TranscriptEntryStatus.completed);
    },
  );

  test(
    'Claude snapshots without deltas append separate blocks with the same message ID',
    () {
      final doc = normalizeTranscript([
        part(1, {
          'type': 'assistant',
          'message': {
            'id': 'm1',
            'content': [
              {'type': 'thinking', 'thinking': 'private'},
            ],
          },
        }),
        part(2, assistant('m1', 'Ik lees.')),
        part(3, {
          'type': 'assistant',
          'message': {
            'id': 'm1',
            'content': [
              {
                'type': 'tool_use',
                'id': 'call-1',
                'name': 'Read',
                'input': {'file_path': 'main.dart'},
              },
            ],
          },
        }),
        part(4, assistant('m1', 'Ik test.')),
        part(5, {
          'type': 'assistant',
          'message': {
            'id': 'm1',
            'content': [
              {'type': 'text', 'text': 'Ik lees.'},
              {
                'type': 'tool_use',
                'id': 'call-1',
                'name': 'Read',
                'input': {'file_path': 'main.dart'},
              },
              {'type': 'text', 'text': 'Ik test.'},
            ],
          },
        }),
      ]);
      expect(doc.entries.map((entry) => entry.text), [
        'Ik lees.',
        'Bestand lezen',
        'Ik test.',
      ]);
    },
  );

  test(
    'subagent and main messages and tools are isolated even with reused IDs',
    () {
      Map<String, dynamic> inSubagent(Map<String, dynamic> event) => {
        ...event,
        'parent_tool_use_id': 'parent-call',
      };
      final call = {
        'type': 'assistant',
        'message': {
          'id': 'm1',
          'content': [
            {
              'type': 'tool_use',
              'id': 'call-1',
              'name': 'Read',
              'input': {'file_path': 'main.dart'},
            },
          ],
        },
      };
      final result = {
        'type': 'user',
        'message': {
          'content': [
            {
              'type': 'tool_result',
              'tool_use_id': 'call-1',
              'content': 'subagent output',
            },
          ],
        },
      };
      final doc = normalizeTranscript([
        part(1, startMessage('m1')),
        part(2, textStart()),
        part(3, textDelta('Hoofdtaak')),
        part(4, inSubagent(startMessage('m1'))),
        part(5, inSubagent(textStart())),
        part(6, inSubagent(textDelta('Subtaak'))),
        part(7, assistant('m1', 'Hoofdtaak')),
        part(8, inSubagent(assistant('m1', 'Subtaak'))),
        part(9, call),
        part(10, inSubagent(call)),
        part(11, inSubagent(result)),
      ]);
      expect(doc.entries.map((entry) => entry.text), [
        'Hoofdtaak',
        'Subtaak',
        'Bestand lezen',
        'Bestand lezen',
      ]);
      expect(doc.entries[2].status, TranscriptEntryStatus.running);
      expect(doc.entries[2].output, isNull);
      expect(doc.entries[3].status, TranscriptEntryStatus.completed);
      expect(doc.entries[3].output, 'subagent output');
      expect(doc.entries.map((entry) => entry.id).toSet(), hasLength(4));
    },
  );

  test(
    'malformed known provider shapes do not prevent later valid messages',
    () {
      final doc = normalizeTranscript([
        part(1, {
          'type': 'stream_event',
          'event': {
            'type': 'content_block_start',
            'index': {'invalid': 1},
            'content_block': {'type': 'text', 'text': 'bad'},
          },
        }),
        part(2, {'type': 'assistant', 'message': 123}),
        part(3, {'type': 'item.completed', 'item': false}),
        part(4, startMessage('valid')),
        part(
          5,
          streamEvent({
            'type': 'content_block_start',
            'index': '0',
            'content_block': {'type': 'text', 'text': ''},
          }),
        ),
        part(6, textDelta('Nog steeds leesbaar.')),
        part(7, assistant('valid', 'Nog steeds leesbaar.')),
      ]);
      expect(doc.entries, hasLength(1));
      expect(doc.entries.single.text, 'Nog steeds leesbaar.');
      expect(doc.technicalEntries, hasLength(7));
      expect(doc.technicalEntries.first.reason, contains('Onvolledige'));
    },
  );

  test(
    'Claude deltas grow one message and snapshots and result do not duplicate it',
    () {
      final parts = [
        part(1, startMessage('message-1')),
        part(2, textStart()),
        part(3, textDelta('Ik lees ')),
        part(4, textDelta('de bron.')),
      ];
      final live = normalizeTranscript(parts);
      expect(live.entries, hasLength(1));
      expect(live.entries.single.text, 'Ik lees de bron.');
      expect(live.entries.single.status, TranscriptEntryStatus.running);

      parts.addAll([
        part(5, assistant('message-1', 'Ik lees de bron.')),
        part(6, {
          'type': 'result',
          'subtype': 'success',
          'result': 'Ik lees de bron.',
        }),
      ]);
      final completed = normalizeTranscript(parts);
      expect(completed.entries, hasLength(1));
      expect(completed.entries.single.id, live.entries.single.id);
      expect(completed.entries.single.text, 'Ik lees de bron.');
      expect(completed.entries.single.status, TranscriptEntryStatus.completed);
      expect(completed.technicalEntries, hasLength(parts.length));
    },
  );

  test(
    'Claude tool input streams, snapshots deduplicate, output and errors attach by ID',
    () {
      final doc = normalizeTranscript([
        part(1, startMessage('message-1')),
        part(
          2,
          streamEvent({
            'type': 'content_block_start',
            'index': 0,
            'content_block': {
              'type': 'tool_use',
              'id': 'call-1',
              'name': 'Bash',
              'input': {},
            },
          }),
        ),
        part(
          3,
          streamEvent({
            'type': 'content_block_delta',
            'index': 0,
            'delta': {
              'type': 'input_json_delta',
              'partial_json': '{"command":"flutter ',
            },
          }),
        ),
        part(
          4,
          streamEvent({
            'type': 'content_block_delta',
            'index': 0,
            'delta': {'type': 'input_json_delta', 'partial_json': 'test"}'},
          }),
        ),
        part(5, streamEvent({'type': 'content_block_stop', 'index': 0})),
        part(6, {
          'type': 'assistant',
          'message': {
            'id': 'message-1',
            'content': [
              {
                'type': 'tool_use',
                'id': 'call-1',
                'name': 'Bash',
                'input': {'command': 'flutter test'},
              },
            ],
          },
        }),
        part(7, {
          'type': 'user',
          'message': {
            'content': [
              {
                'type': 'tool_result',
                'tool_use_id': 'call-1',
                'is_error': true,
                'content': [
                  {
                    'type': 'text',
                    'text': 'Test failed: expected 12, actual 11',
                  },
                ],
              },
            ],
          },
        }),
      ]);
      expect(doc.entries, hasLength(1));
      final entry = doc.entries.single;
      expect(entry.kind, TranscriptEntryKind.tool);
      expect(entry.text, 'Opdracht uitvoeren');
      expect(entry.toolName, 'Bash');
      expect(jsonDecode(entry.input!), {'command': 'flutter test'});
      expect(entry.output, 'Test failed: expected 12, actual 11');
      expect(entry.isError, isTrue);
    },
  );

  test(
    'provider reasoning is technical only, explicit public summary is visible',
    () {
      final doc = normalizeTranscript([
        part(1, startMessage('private-message')),
        part(
          2,
          streamEvent({
            'type': 'content_block_start',
            'index': 0,
            'content_block': {'type': 'thinking', 'thinking': ''},
          }),
        ),
        part(
          3,
          streamEvent({
            'type': 'content_block_delta',
            'index': 0,
            'delta': {
              'type': 'thinking_delta',
              'thinking': 'private deliberation',
            },
          }),
        ),
        part(4, {
          'type': 'assistant',
          'message': {
            'id': 'private-message',
            'content': [
              {
                'type': 'thinking',
                'thinking': 'private deliberation',
                'signature': 'sig',
              },
            ],
          },
        }),
        part(5, {
          'type': 'item.completed',
          'item': {
            'id': 'reason-1',
            'type': 'reasoning',
            'text': 'private codex deliberation',
          },
        }),
        part(
          6,
          'Ik vergelijk ',
          kind: 'REASONING_SUMMARY',
          stream: 'public-summary',
          finalPart: false,
        ),
        part(
          7,
          'de bronnen.',
          kind: 'REASONING_SUMMARY',
          stream: 'public-summary',
          finalPart: false,
        ),
        part(
          8,
          'Ik vergelijk de bronnen.',
          kind: 'REASONING_SUMMARY',
          stream: 'public-summary',
        ),
      ]);
      expect(doc.entries, hasLength(1));
      expect(doc.entries.single.text, 'Ik vergelijk de bronnen.');
      expect(doc.entries.single.status, TranscriptEntryStatus.completed);
      expect(doc.technicalEntries, hasLength(8));
      expect(
        doc.technicalEntries.any(
          (entry) => entry.text.contains('private deliberation'),
        ),
        isTrue,
      );
    },
  );

  test(
    'Codex item snapshots update in place and expose failed commands with exitcode',
    () {
      final parts = [
        part(1, {'type': 'thread.started', 'thread_id': 'thread-1'}),
        part(2, {
          'type': 'item.started',
          'item': {'id': 'msg-1', 'type': 'agent_message', 'text': 'Ik'},
        }),
        part(3, {
          'type': 'item.updated',
          'item': {'id': 'msg-1', 'type': 'agent_message', 'text': 'Ik test.'},
        }),
        part(4, {
          'type': 'item.completed',
          'item': {'id': 'msg-1', 'type': 'agent_message', 'text': 'Ik test.'},
        }),
        part(5, {
          'type': 'item.started',
          'item': {
            'id': 'cmd-1',
            'type': 'command_execution',
            'command': 'flutter test',
            'status': 'in_progress',
            'aggregated_output': '',
          },
        }),
        part(6, {
          'type': 'item.updated',
          'item': {
            'id': 'cmd-1',
            'type': 'command_execution',
            'command': 'flutter test',
            'status': 'in_progress',
            'aggregated_output': 'Running',
          },
        }),
        part(7, {
          'type': 'item.completed',
          'item': {
            'id': 'cmd-1',
            'type': 'command_execution',
            'command': 'flutter test',
            'status': 'completed',
            'aggregated_output': '1 test failed',
            'exit_code': 1,
          },
        }),
        part(8, {
          'type': 'turn.completed',
          'usage': {'input_tokens': 5, 'output_tokens': 5},
        }),
      ];
      final doc = normalizeTranscript(parts);
      expect(doc.entries, hasLength(2));
      expect(doc.entries.first.text, 'Ik test.');
      expect(doc.entries.first.status, TranscriptEntryStatus.completed);
      expect(doc.entries.last.toolName, 'command_execution');
      expect(doc.entries.last.input, 'flutter test');
      expect(doc.entries.last.output, '1 test failed\n\nExitcode: 1');
      expect(doc.entries.last.isError, isTrue);
    },
  );

  test(
    'Codex file, MCP and search events remain collapsible tool activities',
    () {
      final doc = normalizeTranscript([
        part(1, {
          'type': 'item.completed',
          'item': {
            'id': 'edit',
            'type': 'file_change',
            'changes': [
              {'path': 'lib/main.dart', 'kind': 'update'},
            ],
            'status': 'completed',
          },
        }),
        part(2, {
          'type': 'item.completed',
          'item': {
            'id': 'mcp',
            'type': 'mcp_tool_call',
            'server': 'docs',
            'tool': 'lookup',
            'arguments': {'query': 'Flutter'},
            'result': {
              'content': [
                {'type': 'text', 'text': 'Found'},
              ],
            },
            'status': 'completed',
          },
        }),
        part(3, {
          'type': 'item.completed',
          'item': {
            'id': 'search',
            'type': 'web_search',
            'query': 'Dart documentation',
          },
        }),
      ]);
      expect(
        doc.entries.every((entry) => entry.kind == TranscriptEntryKind.tool),
        isTrue,
      );
      expect(doc.entries.map((entry) => entry.text), [
        'Bestanden wijzigen',
        'lookup',
        'Zoeken op internet',
      ]);
      expect(doc.entries[0].input, contains('lib/main.dart'));
      expect(doc.entries[1].output, contains('Found'));
      expect(doc.entries[2].input, 'Dart documentation');
    },
  );

  test(
    'legacy malformed JSON, system, unknown events and container logs stay technical',
    () {
      final values = <dynamic>[
        'main: Pulling from robbertvdzon/agent-runtime-execution',
        'Digest: sha256:abc123',
        'Status: Image is up to date for ghcr.io/image:main',
        '123abc456789: Pull complete',
        {'type': 'system', 'subtype': 'init', 'model': 'model'},
        {'type': 'system', 'subtype': 'status', 'status': 'requesting'},
        '{"type":"assistant","message":{"content":[{"type":"text","text":"truncated',
        {'type': 'some_future_provider_event', 'payload': 'data'},
        ['not a provider event'],
      ];
      final doc = normalizeTranscript([
        for (var i = 0; i < values.length; i++) part(i, values[i]),
        part(20, 'Container ready', kind: 'SYSTEM'),
        part(21, 'Ik ga de wijziging controleren.'),
      ]);
      expect(doc.entries, hasLength(1));
      expect(doc.entries.single.text, 'Ik ga de wijziging controleren.');
      expect(doc.technicalEntries, hasLength(values.length + 2));
      expect(doc.technicalEntries[6].reason, contains('afgekapt'));
      expect(doc.technicalEntries[7].reason, contains('Onbekende'));
    },
  );

  test(
    'provider JSON chunks across pages are buffered until final and retain stable ID',
    () {
      final text = jsonEncode(assistant('message-1', 'Een lang bericht.'));
      final cut = text.length ~/ 2;
      final firstPage = [
        part(
          1,
          text.substring(0, cut),
          stream: 'provider-event:1:line-1',
          finalPart: false,
        ),
      ];
      final live = normalizeTranscript(firstPage);
      expect(live.entries, isEmpty);
      expect(live.technicalEntries.single.reason, contains('Onvolledige'));
      final full = normalizeTranscript([
        ...firstPage,
        part(2, text.substring(cut), stream: 'provider-event:1:line-1'),
      ]);
      expect(full.entries.single.text, 'Een lang bericht.');
      expect(full.technicalEntries, hasLength(2));
      expect(
        full.technicalEntries.every(
          (entry) => entry.reason == 'Claude-bericht',
        ),
        isTrue,
      );
      expect(full.technicalEntries.map((entry) => entry.text).join(), text);
    },
  );

  test('the same tool ID is isolated between provider rounds and attempts', () {
    Map<String, dynamic> call(String command) => {
      'type': 'assistant',
      'message': {
        'id': 'reused-message',
        'content': [
          {
            'type': 'tool_use',
            'id': 'reused-tool',
            'name': 'Bash',
            'input': {'command': command},
          },
        ],
      },
    };
    Map<String, dynamic> output(String text) => {
      'type': 'user',
      'message': {
        'content': [
          {
            'type': 'tool_result',
            'tool_use_id': 'reused-tool',
            'content': text,
          },
        ],
      },
    };
    final doc = normalizeTranscript([
      part(1, call('first'), stream: 'provider-event:1:line-1'),
      part(2, output('first result'), stream: 'provider-event:1:line-2'),
      part(3, call('second'), stream: 'provider-event:2:line-1'),
      part(4, output('second result'), stream: 'provider-event:2:line-2'),
      part(
        5,
        call('third'),
        stream: 'provider-event:1:line-1',
        attempt: 'attempt-2',
      ),
      part(
        6,
        output('third result'),
        stream: 'provider-event:1:line-2',
        attempt: 'attempt-2',
      ),
    ]);
    expect(doc.entries, hasLength(3));
    expect(doc.entries.map((entry) => entry.output), [
      'first result',
      'second result',
      'third result',
    ]);
    expect(doc.entries.map((entry) => entry.id).toSet(), hasLength(3));
    expect(doc.entries.map((entry) => entry.attemptId), [
      'attempt-1',
      'attempt-1',
      'attempt-2',
    ]);
  });

  test(
    'legacy init events establish new round even when Codex IDs are reused',
    () {
      final doc = normalizeTranscript([
        part(1, {'type': 'thread.started', 'thread_id': 'first'}),
        part(2, {
          'type': 'item.completed',
          'item': {
            'type': 'agent_message',
            'id': 'item_0',
            'text': 'Eerste ronde',
          },
        }),
        part(3, {'type': 'thread.started', 'thread_id': 'second'}),
        part(4, {
          'type': 'item.completed',
          'item': {
            'type': 'agent_message',
            'id': 'item_0',
            'text': 'Tweede ronde',
          },
        }),
      ]);
      expect(doc.entries.map((entry) => entry.text), [
        'Eerste ronde',
        'Tweede ronde',
      ]);
    },
  );

  test(
    'poll retries do not duplicate chunks and event sequence restores order',
    () {
      final text = jsonEncode(assistant('message-1', 'Goed.'));
      final first = part(
        1,
        text.substring(0, 20),
        stream: 'provider-event:1:line-1',
        finalPart: false,
      );
      final last = part(
        2,
        text.substring(20),
        stream: 'provider-event:1:line-1',
      );
      final doc = normalizeTranscript([last, first, first, last]);
      expect(doc.entries.single.text, 'Goed.');
      expect(doc.technicalEntries, hasLength(2));
    },
  );

  test(
    'tool results can precede a recovered snapshot without losing failure status',
    () {
      final doc = normalizeTranscript([
        part(1, {
          'type': 'user',
          'message': {
            'content': [
              {
                'type': 'tool_result',
                'tool_use_id': 'read-1',
                'content': 'File not found',
                'is_error': true,
              },
            ],
          },
        }),
        part(2, {
          'type': 'assistant',
          'message': {
            'id': 'm1',
            'content': [
              {
                'type': 'tool_use',
                'id': 'read-1',
                'name': 'Read',
                'input': {'file_path': '/missing'},
              },
            ],
          },
        }),
      ]);
      expect(doc.entries, hasLength(1));
      expect(doc.entries.single.text, 'Bestand lezen');
      expect(doc.entries.single.output, 'File not found');
      expect(doc.entries.single.status, TranscriptEntryStatus.failed);
    },
  );

  test(
    'provider failures remain visible and do not impersonate assistant messages',
    () {
      final doc = normalizeTranscript([
        part(1, {
          'type': 'result',
          'subtype': 'error_during_execution',
          'is_error': true,
          'errors': ['Authentication failed'],
        }),
        part(2, {
          'type': 'turn.failed',
          'error': {'message': 'Connection interrupted'},
        }),
      ]);
      expect(doc.entries, hasLength(2));
      expect(
        doc.entries.every(
          (entry) => entry.kind == TranscriptEntryKind.error && entry.isError,
        ),
        isTrue,
      );
      expect(doc.entries.first.text, contains('Authentication failed'));
      expect(doc.entries.last.text, 'Connection interrupted');
    },
  );

  test(
    'native tool events pair by call ID and plain agent text stays readable',
    () {
      final doc = normalizeTranscript([
        part(1, 'Ik controleer het bestand.'),
        part(2, {
          'id': 'native-read',
          'name': 'Read',
          'input': {'path': 'main.dart'},
        }, kind: 'TOOL_CALL'),
        part(3, {
          'call_id': 'native-read',
          'content': 'file contents',
        }, kind: 'TOOL_OUTPUT'),
      ]);
      expect(doc.entries, hasLength(2));
      expect(doc.entries.first.text, 'Ik controleer het bestand.');
      expect(doc.entries.last.text, 'Bestand lezen');
      expect(doc.entries.last.output, 'file contents');
      expect(doc.entries.last.status, TranscriptEntryStatus.completed);
    },
  );
}
