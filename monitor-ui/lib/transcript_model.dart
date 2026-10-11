import 'dart:convert';

enum TranscriptEntryKind { assistant, tool, error, system }

enum TranscriptEntryStatus { running, completed, failed }

/// A provider-independent, visible message or tool action.
class TranscriptEntry {
  const TranscriptEntry({
    required this.id,
    required this.attemptId,
    required this.kind,
    required this.text,
    required this.status,
    this.toolName,
    this.input,
    this.output,
  });

  final String id;
  final String attemptId;
  final TranscriptEntryKind kind;
  final String text;
  final String? toolName;
  final String? input;
  final String? output;
  final TranscriptEntryStatus status;

  bool get isError => status == TranscriptEntryStatus.failed;
}

/// Raw data remains available even when its provider format is not understood.
class TranscriptTechnicalEntry {
  const TranscriptTechnicalEntry({
    required this.raw,
    required this.text,
    required this.reason,
  });

  final Map<String, dynamic> raw;
  final String text;
  final String reason;
}

class TranscriptDocument {
  const TranscriptDocument({
    required this.entries,
    required this.technicalEntries,
  });

  final List<TranscriptEntry> entries;
  final List<TranscriptTechnicalEntry> technicalEntries;
}

/// Normalize the accumulated pages, not only the newest page. Rebuilding from
/// the ordered raw parts makes polling, replay, and incomplete chunks identical.
TranscriptDocument normalizeTranscript(Iterable<Map<String, dynamic>> parts) =>
    _TranscriptNormalizer().normalize(parts);

class _Entry {
  _Entry({
    required this.id,
    required this.attemptId,
    required this.kind,
    this.text = '',
    this.toolName,
    this.status = TranscriptEntryStatus.running,
  });

  final String id;
  final String attemptId;
  final TranscriptEntryKind kind;
  String text;
  String? toolName;
  String? input;
  String? output;
  TranscriptEntryStatus status;

  TranscriptEntry freeze() => TranscriptEntry(
    id: id,
    attemptId: attemptId,
    kind: kind,
    text: text,
    toolName: toolName,
    input: input,
    output: output,
    status: status,
  );
}

class _Raw {
  _Raw(this.part, this.index);
  final Map<String, dynamic> part;
  final int index;
  String reason = 'Technische gebeurtenis';
  String get text => _string(part['text']);
  String get attemptId => _string(part['attemptId']);
  String get id => _string(part['partId'], 'part-$index');
  String get streamId => _string(part['streamId']);
  bool get isFinal => part['final'] != false;
  int get sequence => _integer(part['sequence']) ?? index;
}

class _PendingLine {
  final text = StringBuffer();
  final parts = <_Raw>[];
}

class _ClaudeMessage {
  _ClaudeMessage(this.id, this.lane);
  final String id;
  final String lane;
  String get key => '$lane/$id';
  final blocks = <int, _Entry>{};
  final hiddenBlocks = <int>{};
  final partialInputs = <int, String>{};
  int? activeBlock;

  int get nextBlock {
    final indices = [...blocks.keys, ...hiddenBlocks];
    return indices.isEmpty ? 0 : indices.reduce((a, b) => a > b ? a : b) + 1;
  }
}

class _Scope {
  _Scope(this.key, this.attemptId);
  final String key;
  final String attemptId;
  final messages = <String, _ClaudeMessage>{};
  final currentMessages = <String, String>{};
  final tools = <String, _Entry>{};
  final codexItems = <String, _Entry>{};
  final nativeStreams = <String, _Entry>{};
  final visible = <_Entry>[];
  int anonymous = 0;

  String nextId(String kind) => '$kind-${anonymous++}';
}

class _TranscriptNormalizer {
  final _entries = <_Entry>[];
  final _scopes = <String, _Scope>{};
  final _legacyRounds = <String, int>{};
  final _pending = <String, _PendingLine>{};

  TranscriptDocument normalize(Iterable<Map<String, dynamic>> parts) {
    final seen = <String>{};
    final raw = <_Raw>[];
    var index = 0;
    for (final part in parts) {
      final item = _Raw(part, index++);
      // Poll retries may include a previously received part. Missing IDs are
      // deliberately not deduplicated because identical text can be legitimate.
      if (part['partId'] != null &&
          !seen.add('${item.attemptId}:${part['partId']}')) {
        continue;
      }
      raw.add(item);
    }
    raw.sort((a, b) {
      final sequence = a.sequence.compareTo(b.sequence);
      return sequence != 0 ? sequence : a.index.compareTo(b.index);
    });
    for (final item in raw) {
      try {
        _consume(item);
      } on Object {
        // An unexpected provider shape must not hide other messages or abort
        // polling. Keep the original item available for inspection.
        item.reason =
            'Onverwachte providergegevens; oorspronkelijke gegevens bewaard';
      }
    }
    return TranscriptDocument(
      entries: List.unmodifiable(
        _entries
            .where(
              (e) =>
                  e.kind != TranscriptEntryKind.assistant ||
                  e.text.trim().isNotEmpty,
            )
            .map((e) => e.freeze()),
      ),
      technicalEntries: List.unmodifiable(
        raw.map(
          (r) => TranscriptTechnicalEntry(
            raw: Map.unmodifiable(r.part),
            text: r.text,
            reason: r.reason,
          ),
        ),
      ),
    );
  }

  _Scope _scope(_Raw raw, {String? providerRound}) {
    final round =
        providerRound ?? 'legacy-${_legacyRounds[raw.attemptId] ?? 0}';
    final key = '${raw.attemptId}/$round';
    return _scopes.putIfAbsent(key, () => _Scope(key, raw.attemptId));
  }

  void _consume(_Raw raw) {
    final kind = _string(raw.part['kind'], 'AGENT_TEXT');
    if (kind == 'SYSTEM') {
      raw.reason = 'Runtime- en opstartinformatie';
      return;
    }
    final providerStream = raw.streamId.split(':');
    if (providerStream.length >= 3 &&
        providerStream.first == 'provider-event') {
      final key = '${raw.attemptId}/${raw.streamId}';
      final pending = _pending.putIfAbsent(key, _PendingLine.new);
      pending.text.write(raw.text);
      pending.parts.add(raw);
      raw.reason = 'Onvolledige providergebeurtenis; wacht op resterende delen';
      if (!raw.isFinal) return;
      _pending.remove(key);
      final scope = _scope(raw, providerRound: providerStream[1]);
      final reason = _providerText(pending.text.toString(), raw, scope);
      for (final part in pending.parts) {
        part.reason = reason;
      }
      return;
    }
    if (kind == 'REASONING_SUMMARY') {
      raw.reason = 'Zichtbare samenvatting van de agent';
      _nativeText(raw, _scope(raw));
      return;
    }
    if (kind == 'TOOL_CALL' || kind == 'TOOL_OUTPUT') {
      raw.reason = 'Toolaanroep of tooluitvoer';
      _nativeTool(raw, _scope(raw), kind);
      return;
    }
    if (kind != 'AGENT_TEXT') {
      raw.reason = 'Onbekend gebeurtenistype; oorspronkelijke gegevens bewaard';
      return;
    }
    final text = raw.text.trimLeft();
    if (text.startsWith('{') || text.startsWith('[')) {
      // Legacy provider logs did not identify individual lines or rounds.
      final value = _decodeObject(text);
      if (value != null && _startsSession(value)) {
        _legacyRounds.update(raw.attemptId, (r) => r + 1, ifAbsent: () => 1);
      }
      raw.reason = _providerText(raw.text, raw, _scope(raw), decoded: value);
    } else if (_isRuntimeLog(text)) {
      raw.reason = 'Runtime- en opstartinformatie';
    } else {
      raw.reason = 'Zichtbaar bericht van de agent';
      _nativeText(raw, _scope(raw));
    }
  }

  String _providerText(
    String text,
    _Raw raw,
    _Scope scope, {
    Map<String, dynamic>? decoded,
  }) {
    if (_isRuntimeLog(text.trimLeft())) return 'Runtime- en opstartinformatie';
    final value = decoded ?? _decodeObject(text);
    if (value == null) {
      // Provider streams are machine output. Never promote malformed JSON or
      // unclassified process output to an apparent assistant message.
      return 'Niet leesbaar als providergebeurtenis (mogelijk afgekapt); oorspronkelijke gegevens bewaard';
    }
    final type = _string(value['type']);
    if (type == 'stream_event') return _claudeStream(value, scope);
    if (type == 'assistant') {
      _claudeAssistant(value, scope);
      return 'Claude-bericht';
    }
    if (type == 'user') {
      final message = _map(value['message']);
      for (final block in _maps(message['content'])) {
        if (block['type'] != 'tool_result') continue;
        _toolResult(
          scope,
          block,
          lane: _string(value['parent_tool_use_id'], 'main'),
        );
      }
      return 'Claude-tooluitvoer';
    }
    if (type == 'result') {
      final failed =
          value['is_error'] == true ||
          _string(value['subtype']).startsWith('error');
      final result = _string(value['result']);
      if (failed) {
        final detail = _render(value['errors'] ?? value['error']);
        _error(
          scope,
          'result-${raw.id}',
          result.isNotEmpty
              ? result
              : (detail.isNotEmpty
                    ? detail
                    : 'De agent is met een fout gestopt.'),
        );
      } else if (result.trim().isNotEmpty &&
          !_hasAssistantText(scope, result)) {
        _add(
          scope,
          _Entry(
            id: '${scope.key}/result-${raw.id}',
            attemptId: raw.attemptId,
            kind: TranscriptEntryKind.assistant,
            text: result,
            status: TranscriptEntryStatus.completed,
          ),
        );
      }
      return failed ? 'Claude-foutmelding' : 'Claude-eindresultaat';
    }
    if (type == 'system' || type == 'rate_limit_event') {
      return 'Providerstatus en sessiegegevens';
    }
    if (type.startsWith('item.')) return _codexItem(value, scope);
    if (type == 'error' || type == 'turn.failed') {
      final error = value['error'];
      final message = _string(
        value['message'],
        _string(_map(error)['message'], _render(error)),
      );
      _error(
        scope,
        'error-${raw.id}',
        message.isEmpty ? 'De agent is met een fout gestopt.' : message,
      );
      return 'Providerfout';
    }
    if (type == 'thread.started' ||
        type == 'turn.started' ||
        type == 'turn.completed') {
      return 'Providerstatus en sessiegegevens';
    }
    return 'Onbekende providergebeurtenis; oorspronkelijke gegevens bewaard';
  }

  String _claudeStream(Map<String, dynamic> value, _Scope scope) {
    final event = _map(value['event']);
    final type = _string(event['type']);
    final lane = _string(value['parent_tool_use_id'], 'main');
    if (type == 'message_start') {
      final incoming = _map(event['message']);
      final id = _string(incoming['id'], scope.nextId('message'));
      scope.currentMessages[lane] = id;
      scope.messages.putIfAbsent('$lane/$id', () => _ClaudeMessage(id, lane));
      return 'Begin van Claude-bericht';
    }
    if (type == 'error') {
      _error(
        scope,
        scope.nextId('error'),
        _string(
          _map(event['error'])['message'],
          'De agent is met een fout gestopt.',
        ),
      );
      return 'Claude-foutmelding';
    }
    if (type == 'message_delta' || type == 'ping') {
      return 'Providerstatus en sessiegegevens';
    }
    final id = scope.currentMessages.putIfAbsent(
      lane,
      () => scope.nextId('message'),
    );
    final message = scope.messages.putIfAbsent(
      '$lane/$id',
      () => _ClaudeMessage(id, lane),
    );
    if (type == 'message_stop') {
      for (final entry in message.blocks.values) {
        if (entry.kind == TranscriptEntryKind.assistant) {
          entry.status = TranscriptEntryStatus.completed;
        }
      }
      return 'Einde van Claude-bericht';
    }
    final index = _integer(event['index']);
    if (index == null) {
      return 'Onvolledige Claude-streamgebeurtenis; oorspronkelijke gegevens bewaard';
    }
    if (type == 'content_block_start') {
      message.activeBlock = index;
      final block = _map(event['content_block']);
      _claudeBlock(scope, message, index, block, complete: false);
      return _isHiddenBlock(block)
          ? 'Niet-zichtbare providerredenering'
          : 'Begin van bericht of tool';
    }
    if (type == 'content_block_delta') {
      final delta = _map(event['delta']);
      if (delta['type'] == 'thinking_delta' ||
          delta['type'] == 'signature_delta' ||
          message.hiddenBlocks.contains(index)) {
        return 'Niet-zichtbare providerredenering';
      }
      if (delta['type'] == 'text_delta') {
        final entry = message.blocks.putIfAbsent(
          index,
          () => _add(
            scope,
            _Entry(
              id: '${scope.key}/${message.key}/text-$index',
              attemptId: scope.attemptId,
              kind: TranscriptEntryKind.assistant,
            ),
          ),
        );
        entry.text += _string(delta['text']);
        return 'Tekstfragment van Claude-bericht';
      }
      if (delta['type'] == 'input_json_delta') {
        final input =
            (message.partialInputs[index] ?? '') +
            _string(delta['partial_json']);
        message.partialInputs[index] = input;
        message.blocks[index]?.input = input;
        return 'Fragment van toolinvoer';
      }
    }
    if (type == 'content_block_stop') {
      if (message.activeBlock == index) message.activeBlock = null;
      final entry = message.blocks[index];
      if (entry?.kind == TranscriptEntryKind.assistant) {
        entry?.status = TranscriptEntryStatus.completed;
      }
      final partial = message.partialInputs[index];
      if (partial != null && entry != null) entry.input = _prettyJson(partial);
      return 'Einde van berichtfragment';
    }
    return 'Onbekende Claude-streamgebeurtenis; oorspronkelijke gegevens bewaard';
  }

  void _claudeAssistant(Map<String, dynamic> value, _Scope scope) {
    final snapshot = _map(value['message']);
    final lane = _string(value['parent_tool_use_id'], 'main');
    final id = _string(
      snapshot['id'],
      scope.currentMessages[lane] ?? scope.nextId('message'),
    );
    scope.currentMessages[lane] = id;
    final message = scope.messages.putIfAbsent(
      '$lane/$id',
      () => _ClaudeMessage(id, lane),
    );
    final content = snapshot['content'];
    final blocks = content is String
        ? <Map<String, dynamic>>[
            {'type': 'text', 'text': content},
          ]
        : _maps(content).toList();
    final usedIndices = <int>{};
    for (var i = 0; i < blocks.length; i++) {
      final block = blocks[i];
      // Claude emits one AssistantMessage per completed content block, all
      // sharing a message ID. Its content[0] is NOT stream block index 0.
      // See https://code.claude.com/docs/en/agent-sdk/streaming-output.
      if (_isHiddenBlock(block)) continue;
      final index = _snapshotIndex(
        scope,
        message,
        block,
        usedIndices,
        preferredIndex: blocks.length > 1 ? i : message.activeBlock,
      );
      usedIndices.add(index);
      _claudeBlock(scope, message, index, block, complete: true);
    }
  }

  int _snapshotIndex(
    _Scope scope,
    _ClaudeMessage message,
    Map<String, dynamic> block,
    Set<int> used, {
    int? preferredIndex,
  }) {
    final type = _string(block['type']);
    if (type == 'tool_use' || type == 'server_tool_use') {
      final tool = scope.tools['${message.lane}/${block['id']}'];
      if (tool != null) {
        for (final entry in message.blocks.entries) {
          if (identical(entry.value, tool)) return entry.key;
        }
      }
    }
    if (type == 'text') {
      if (preferredIndex != null &&
          !used.contains(preferredIndex) &&
          message.blocks[preferredIndex]?.kind ==
              TranscriptEntryKind.assistant) {
        return preferredIndex;
      }
      final text = _string(block['text']);
      for (final entry in message.blocks.entries.toList().reversed) {
        if (!used.contains(entry.key) &&
            entry.value.kind == TranscriptEntryKind.assistant &&
            (entry.value.text == text ||
                (entry.value.status == TranscriptEntryStatus.running &&
                    text.startsWith(entry.value.text)))) {
          return entry.key;
        }
      }
    }
    if (preferredIndex != null &&
        !used.contains(preferredIndex) &&
        !message.blocks.containsKey(preferredIndex) &&
        !message.hiddenBlocks.contains(preferredIndex)) {
      return preferredIndex;
    }
    return message.nextBlock;
  }

  void _claudeBlock(
    _Scope scope,
    _ClaudeMessage message,
    int index,
    Map<String, dynamic> block, {
    required bool complete,
  }) {
    final type = _string(block['type']);
    if (_isHiddenBlock(block)) {
      message.hiddenBlocks.add(index);
      return;
    }
    if (type == 'text') {
      final existing = message.blocks[index];
      final entry = existing?.kind == TranscriptEntryKind.assistant
          ? existing!
          : _add(
              scope,
              _Entry(
                id: '${scope.key}/${message.key}/text-$index',
                attemptId: scope.attemptId,
                kind: TranscriptEntryKind.assistant,
              ),
            );
      message.blocks[index] = entry;
      // Full assistant snapshots replace deltas instead of appending them.
      entry.text = _string(block['text']);
      entry.status = complete
          ? TranscriptEntryStatus.completed
          : TranscriptEntryStatus.running;
    } else if (type == 'tool_use' || type == 'server_tool_use') {
      final id =
          '${message.lane}/${_string(block['id'], '${message.id}/tool-$index')}';
      final name = _string(block['name'], 'Tool');
      final entry = scope.tools.putIfAbsent(
        id,
        () => _add(
          scope,
          _Entry(
            id: '${scope.key}/tool-$id',
            attemptId: scope.attemptId,
            kind: TranscriptEntryKind.tool,
            toolName: name,
            text: _toolTitle(name),
          ),
        ),
      );
      entry.toolName = name;
      entry.text = _toolTitle(name);
      entry.input = _render(block['input']);
      message.blocks[index] = entry;
    } else if (type.endsWith('_tool_result')) {
      _toolResult(scope, block, lane: message.lane);
    }
  }

  void _toolResult(
    _Scope scope,
    Map<String, dynamic> block, {
    String lane = 'main',
  }) {
    final id =
        '$lane/${_string(block['tool_use_id'], scope.nextId('orphan-tool'))}';
    final entry = scope.tools.putIfAbsent(
      id,
      () => _add(
        scope,
        _Entry(
          id: '${scope.key}/tool-$id',
          attemptId: scope.attemptId,
          kind: TranscriptEntryKind.tool,
          text: 'Tool uitvoeren',
          toolName: 'Tool',
        ),
      ),
    );
    entry.output = _render(block['content']);
    final content = _map(block['content']);
    final failed =
        block['is_error'] == true ||
        _string(content['type']).endsWith('_error');
    entry.status = failed
        ? TranscriptEntryStatus.failed
        : TranscriptEntryStatus.completed;
  }

  String _codexItem(Map<String, dynamic> value, _Scope scope) {
    final item = _map(value['item']);
    final id = _string(item['id']);
    final type = _string(item['type']);
    if (type == 'reasoning') return 'Niet-zichtbare providerredenering';
    if (id.isEmpty) {
      return 'Onvolledige Codex-gebeurtenis; oorspronkelijke gegevens bewaard';
    }
    final completed =
        value['type'] == 'item.completed' || item['status'] == 'completed';
    final failed =
        item['status'] == 'failed' ||
        item['status'] == 'error' ||
        (item['exit_code'] is num && item['exit_code'] != 0) ||
        item['error'] != null;
    final status = failed
        ? TranscriptEntryStatus.failed
        : (completed
              ? TranscriptEntryStatus.completed
              : TranscriptEntryStatus.running);
    if (type == 'agent_message') {
      final entry = scope.codexItems.putIfAbsent(
        id,
        () => _add(
          scope,
          _Entry(
            id: '${scope.key}/item-$id',
            attemptId: scope.attemptId,
            kind: TranscriptEntryKind.assistant,
          ),
        ),
      );
      entry.text = _string(item['text']);
      entry.status = status;
      return 'Codex-bericht';
    }
    if (type == 'error') {
      final entry = scope.codexItems.putIfAbsent(
        id,
        () => _add(
          scope,
          _Entry(
            id: '${scope.key}/item-$id',
            attemptId: scope.attemptId,
            kind: TranscriptEntryKind.error,
          ),
        ),
      );
      entry.text = _string(
        item['message'],
        _string(item['text'], 'De agent is met een fout gestopt.'),
      );
      entry.status = TranscriptEntryStatus.failed;
      return 'Codex-foutmelding';
    }
    if (!const {
      'command_execution',
      'file_change',
      'mcp_tool_call',
      'web_search',
      'todo_list',
      'collab_tool_call',
    }.contains(type)) {
      return 'Onbekende Codex-gebeurtenis; oorspronkelijke gegevens bewaard';
    }
    final name = type == 'mcp_tool_call' ? _string(item['tool'], type) : type;
    final entry = scope.codexItems.putIfAbsent(
      id,
      () => _add(
        scope,
        _Entry(
          id: '${scope.key}/item-$id',
          attemptId: scope.attemptId,
          kind: TranscriptEntryKind.tool,
          toolName: name,
          text: _toolTitle(name),
        ),
      ),
    );
    entry.status = status;
    entry.toolName = name;
    if (type == 'command_execution') {
      entry.input = _string(item['command']);
      final output = _render(item['aggregated_output'] ?? item['output']);
      entry.output = failed && item['exit_code'] != null
          ? '$output${output.isEmpty ? '' : '\n\n'}Exitcode: ${item['exit_code']}'
          : output;
    } else if (type == 'file_change') {
      entry.input = _render(item['changes']);
    } else if (type == 'mcp_tool_call') {
      entry.input = _render(item['arguments']);
      entry.output = _render(item['error'] ?? item['result']);
    } else if (type == 'web_search') {
      entry.input = _string(item['query'], _render(item['action']));
      entry.output = _render(item['results']);
    } else if (type == 'todo_list') {
      entry.input = _render(item['items']);
    } else {
      entry.input = _render(item['prompt'] ?? item['arguments']);
      entry.output = _render(item['agents_states'] ?? item['result']);
    }
    return 'Codex-toolactiviteit';
  }

  void _nativeText(_Raw raw, _Scope scope) {
    if (raw.streamId.isEmpty) {
      if (raw.text.trim().isEmpty) return;
      _add(
        scope,
        _Entry(
          id: '${scope.key}/native-${raw.id}',
          attemptId: raw.attemptId,
          kind: TranscriptEntryKind.assistant,
          text: raw.text,
          status: raw.isFinal
              ? TranscriptEntryStatus.completed
              : TranscriptEntryStatus.running,
        ),
      );
      return;
    }
    final key = '${raw.part['kind']}/${raw.streamId}';
    final entry = scope.nativeStreams.putIfAbsent(
      key,
      () => _add(
        scope,
        _Entry(
          id: '${scope.key}/native-$key',
          attemptId: raw.attemptId,
          kind: TranscriptEntryKind.assistant,
        ),
      ),
    );
    // Native public summaries send deltas followed by a complete snapshot.
    entry.text = raw.isFinal ? raw.text : entry.text + raw.text;
    entry.status = raw.isFinal
        ? TranscriptEntryStatus.completed
        : TranscriptEntryStatus.running;
  }

  void _nativeTool(_Raw raw, _Scope scope, String kind) {
    final value = _decodeObject(raw.text) ?? <String, dynamic>{};
    final id = _string(
      value['tool_use_id'] ?? value['call_id'] ?? value['id'],
      raw.streamId,
    );
    _Entry? entry;
    if (id.isNotEmpty) entry = scope.tools[id];
    if (kind == 'TOOL_OUTPUT' && entry == null && id.isEmpty) {
      for (final candidate in scope.tools.values.toList().reversed) {
        if (candidate.status == TranscriptEntryStatus.running) {
          entry = candidate;
          break;
        }
      }
    }
    if (entry == null) {
      final toolId = id.isEmpty ? raw.id : id;
      final name = _string(value['name'] ?? value['tool'], 'Tool');
      entry = _add(
        scope,
        _Entry(
          id: '${scope.key}/native-tool-$toolId',
          attemptId: raw.attemptId,
          kind: TranscriptEntryKind.tool,
          toolName: name,
          text: _toolTitle(name),
        ),
      );
      scope.tools[toolId] = entry;
    }
    if (kind == 'TOOL_CALL') {
      entry.input = value.isEmpty
          ? raw.text
          : _render(value['input'] ?? value['arguments'] ?? value);
    } else {
      entry.output = value.isEmpty
          ? raw.text
          : _render(value['content'] ?? value['output'] ?? value);
      entry.status = value['is_error'] == true || value['error'] != null
          ? TranscriptEntryStatus.failed
          : TranscriptEntryStatus.completed;
    }
  }

  _Entry _add(_Scope scope, _Entry entry) {
    _entries.add(entry);
    scope.visible.add(entry);
    return entry;
  }

  void _error(_Scope scope, String id, String text) {
    if (scope.visible.any(
      (entry) => entry.kind == TranscriptEntryKind.error && entry.text == text,
    )) {
      return;
    }
    _add(
      scope,
      _Entry(
        id: '${scope.key}/$id',
        attemptId: scope.attemptId,
        kind: TranscriptEntryKind.error,
        text: text,
        status: TranscriptEntryStatus.failed,
      ),
    );
  }

  bool _hasAssistantText(_Scope scope, String text) {
    final normalized = text.trim();
    if (scope.visible.any(
      (entry) =>
          entry.kind == TranscriptEntryKind.assistant &&
          entry.text.trim() == normalized,
    )) {
      return true;
    }
    return scope.messages.values.any(
      (message) =>
          message.blocks.values
              .where((entry) => entry.kind == TranscriptEntryKind.assistant)
              .map((entry) => entry.text)
              .join('\n')
              .trim() ==
          normalized,
    );
  }
}

bool _startsSession(Map<String, dynamic> value) =>
    value['type'] == 'thread.started' ||
    (value['type'] == 'system' && value['subtype'] == 'init');

bool _isHiddenBlock(Map<String, dynamic> block) => const {
  'thinking',
  'redacted_thinking',
  'reasoning',
}.contains(block['type']);

bool _isRuntimeLog(String text) {
  final lower = text.toLowerCase();
  if (const [
    'digest: sha256:',
    'status: image is up to date',
    'status: downloaded newer image',
    'unable to find image ',
    'docker: ',
    'warning:',
    'error response from daemon:',
    'sha256:',
  ].any(lower.startsWith)) {
    return true;
  }
  final separator = lower.indexOf(': ');
  if (separator < 0) return false;
  final prefix = lower.substring(0, separator);
  final message = lower.substring(separator + 2);
  if (!prefix.contains(' ') && message.startsWith('pulling from ')) return true;
  final isLayerId =
      prefix.length >= 6 &&
      prefix.length <= 64 &&
      prefix.codeUnits.every(
        (char) => (char >= 48 && char <= 57) || (char >= 97 && char <= 102),
      );
  return isLayerId &&
      const [
        'pulling',
        'waiting',
        'downloading',
        'download complete',
        'verifying',
        'extracting',
        'pull complete',
        'already exists',
      ].any(message.startsWith);
}

String _toolTitle(String name) {
  final lower = name.toLowerCase();
  if (lower == 'bash' ||
      lower == 'shell' ||
      lower == 'command_execution' ||
      lower == 'exec_command') {
    return 'Opdracht uitvoeren';
  }
  if (lower == 'read' || lower == 'readfile' || lower == 'read_file') {
    return 'Bestand lezen';
  }
  if (lower == 'edit' ||
      lower == 'write' ||
      lower == 'multiedit' ||
      lower == 'apply_patch' ||
      lower == 'file_change') {
    return 'Bestanden wijzigen';
  }
  if (lower == 'glob' || lower == 'grep' || lower == 'search') {
    return 'Bestanden doorzoeken';
  }
  if (lower == 'websearch' || lower == 'web_search' || lower == 'webfetch') {
    return 'Zoeken op internet';
  }
  if (lower == 'todowrite' || lower == 'todo_list') return 'Plan bijwerken';
  if (lower == 'task' || lower == 'agent' || lower == 'collab_tool_call') {
    return 'Subagent inschakelen';
  }
  return name == 'Tool' ? 'Tool uitvoeren' : name;
}

String _string(dynamic value, [String fallback = '']) =>
    value is String && value.isNotEmpty ? value : fallback;

int? _integer(dynamic value) => value is num
    ? value.toInt()
    : (value is String ? int.tryParse(value) : null);

Map<String, dynamic> _map(dynamic value) =>
    value is Map ? Map<String, dynamic>.from(value) : <String, dynamic>{};

Iterable<Map<String, dynamic>> _maps(dynamic value) => value is List
    ? value.whereType<Map>().map((v) => Map<String, dynamic>.from(v))
    : const Iterable.empty();

Map<String, dynamic>? _decodeObject(String text) {
  try {
    final value = jsonDecode(text);
    return value is Map ? Map<String, dynamic>.from(value) : null;
  } on FormatException {
    return null;
  }
}

String _prettyJson(String text) {
  try {
    return const JsonEncoder.withIndent('  ').convert(jsonDecode(text));
  } on FormatException {
    return text;
  }
}

String _render(dynamic value) {
  if (value == null) return '';
  if (value is String) return value;
  if (value is List &&
      value.every((part) => part is Map && part['type'] == 'text')) {
    return value.map((part) => _string(part['text'])).join('\n');
  }
  return const JsonEncoder.withIndent('  ').convert(value);
}
