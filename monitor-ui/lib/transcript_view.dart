import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_markdown_plus/flutter_markdown_plus.dart';
import 'package:url_launcher/url_launcher.dart';

import 'transcript_model.dart';

/// The readable and technical views share the same normalized event stream.
/// Scrolling is deliberately owned by the job page, including live following.
class TranscriptView extends StatefulWidget {
  final TranscriptDocument transcript;
  final String prompt;
  final String agentLabel;
  final String? result;
  final List<Widget> technicalDetails;
  final bool isRunning;
  final Set<String>? activeAttemptIds;
  final ValueChanged<bool>? onTechnicalDetailsChanged;

  const TranscriptView({
    super.key,
    required this.transcript,
    required this.prompt,
    this.agentLabel = 'Agent',
    this.result,
    this.technicalDetails = const [],
    this.isRunning = false,
    this.activeAttemptIds,
    this.onTechnicalDetailsChanged,
  });

  @override
  State<TranscriptView> createState() => _TranscriptViewState();
}

class _TranscriptViewState extends State<TranscriptView> {
  bool _technical = false;
  bool _promptExpanded = false;
  final Set<String> _expanded = {};

  void _selectView(bool technical) {
    if (_technical == technical) return;
    setState(() => _technical = technical);
    widget.onTechnicalDetailsChanged?.call(technical);
  }

  void _toggle(String id) => setState(() {
    if (!_expanded.add(id)) _expanded.remove(id);
  });

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: Alignment.topCenter,
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 800),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Wrap(
              spacing: 8,
              children: [
                _ViewTab(
                  label: 'Gesprek',
                  selected: !_technical,
                  onPressed: () => _selectView(false),
                ),
                _ViewTab(
                  label: 'Technische details',
                  selected: _technical,
                  onPressed: () => _selectView(true),
                ),
              ],
            ),
            const Divider(height: 1),
            const SizedBox(height: 24),
            SelectionArea(
              child: _technical
                  ? _buildTechnical(context)
                  : _buildConversation(context),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildConversation(BuildContext context) {
    final entries = widget.transcript.entries;
    final promptLines = const LineSplitter().convert(widget.prompt);
    final longPrompt = widget.prompt.length > 700 || promptLines.length > 12;
    final incomplete =
        !widget.isRunning &&
        widget.transcript.technicalEntries.any(
          (entry) => entry.reason.contains('wacht op resterende delen'),
        );
    final result = widget.result?.trim() ?? '';
    final showResult =
        result.isNotEmpty &&
        !entries.any(
          (entry) =>
              entry.kind == TranscriptEntryKind.assistant &&
              _sameResult(entry.text, result),
        );
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (widget.prompt.isNotEmpty) ...[
          Align(
            alignment: Alignment.centerRight,
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 620),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Text(
                    'Opdracht',
                    style: Theme.of(context).textTheme.labelMedium,
                  ),
                  const SizedBox(height: 6),
                  Container(
                    padding: const EdgeInsets.all(16),
                    decoration: const BoxDecoration(
                      color: Color(0xffedf5f0),
                      borderRadius: BorderRadius.only(
                        topLeft: Radius.circular(16),
                        topRight: Radius.circular(16),
                        bottomLeft: Radius.circular(16),
                        bottomRight: Radius.circular(4),
                      ),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        _ReadableText(
                          longPrompt && !_promptExpanded
                              ? _promptPreview(widget.prompt, promptLines)
                              : widget.prompt,
                        ),
                        if (longPrompt) ...[
                          const SizedBox(height: 8),
                          Align(
                            alignment: Alignment.centerLeft,
                            child: TextButton(
                              onPressed: () => setState(
                                () => _promptExpanded = !_promptExpanded,
                              ),
                              child: Text(
                                _promptExpanded
                                    ? 'Opdracht inklappen'
                                    : 'Volledige opdracht tonen',
                              ),
                            ),
                          ),
                        ],
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 28),
        ],
        if (entries.isNotEmpty) ...[
          Row(
            children: [
              Icon(
                Icons.auto_awesome_outlined,
                size: 20,
                color: Theme.of(context).colorScheme.primary,
              ),
              const SizedBox(width: 8),
              Flexible(
                child: Text(
                  widget.agentLabel,
                  style: Theme.of(context).textTheme.titleSmall,
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          for (final entry in entries)
            Padding(
              key: ValueKey('entry:${entry.id}'),
              padding: EdgeInsets.only(
                bottom: entry.kind == TranscriptEntryKind.tool ? 6 : 20,
              ),
              child: switch (entry.kind) {
                TranscriptEntryKind.tool => _ToolStep(
                  entry: entry,
                  isRunning:
                      widget.isRunning &&
                      (widget.activeAttemptIds == null ||
                          widget.activeAttemptIds!.contains(entry.attemptId)),
                  expanded: _expanded.contains(entry.id),
                  onToggle: () => _toggle(entry.id),
                ),
                TranscriptEntryKind.error => _ErrorMessage(entry.text),
                TranscriptEntryKind.system => Container(
                  padding: const EdgeInsets.symmetric(vertical: 12),
                  decoration: const BoxDecoration(
                    border: Border(
                      bottom: BorderSide(color: Color(0xffdfe7e2)),
                    ),
                  ),
                  child: Text(
                    entry.text,
                    style: Theme.of(context).textTheme.labelLarge,
                  ),
                ),
                TranscriptEntryKind.assistant => _ReadableText(entry.text),
              },
            ),
        ] else
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 16),
            child: Text(
              widget.isRunning
                  ? 'De agent is bezig. Nieuwe berichten verschijnen hier.'
                  : incomplete
                  ? 'Het transcript is onvolledig ontvangen. Bekijk de oorspronkelijke uitvoer bij Technische details.'
                  : 'Er zijn geen leesbare agentberichten in dit transcript. De oorspronkelijke uitvoer staat bij Technische details.',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
          ),
        if (incomplete && entries.isNotEmpty)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 12),
            child: Text(
              'Een deel van het transcript is onvolledig ontvangen. Bekijk de oorspronkelijke uitvoer bij Technische details.',
              style: Theme.of(context).textTheme.bodyMedium,
            ),
          ),
        if (showResult) ...[
          const Divider(height: 32),
          TranscriptResult(result: result),
        ],
        if (widget.isRunning) ...[
          const SizedBox(height: 12),
          Row(
            children: [
              Icon(
                Icons.circle,
                size: 7,
                color: Theme.of(context).colorScheme.primary,
              ),
              const SizedBox(width: 8),
              const Expanded(child: Text('De agent is bezig…')),
            ],
          ),
        ],
      ],
    );
  }

  Widget _buildTechnical(BuildContext context) {
    final entries = widget.transcript.technicalEntries;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Text(
          'Opstartinformatie, taakgegevens en oorspronkelijke events blijven hier beschikbaar.',
        ),
        const SizedBox(height: 16),
        for (final detail in widget.technicalDetails) ...[
          detail,
          const SizedBox(height: 16),
        ],
        Text(
          'Oorspronkelijke events · ${entries.length}',
          style: Theme.of(context).textTheme.titleSmall,
        ),
        const SizedBox(height: 8),
        for (var index = 0; index < entries.length; index++)
          _Disclosure(
            key: ValueKey('raw:$index'),
            title:
                'Event ${index + 1} · ${!widget.isRunning && entries[index].reason.contains('wacht op resterende delen') ? 'Onvolledig ontvangen' : entries[index].reason}',
            expanded: _expanded.contains('raw:$index'),
            onToggle: () => _toggle('raw:$index'),
            child: _CodeText(
              const JsonEncoder.withIndent('  ').convert(entries[index].raw),
            ),
          ),
        if (entries.isEmpty)
          const Text('Er zijn nog geen transcript-events ontvangen.'),
      ],
    );
  }
}

class _ViewTab extends StatelessWidget {
  final String label;
  final bool selected;
  final VoidCallback onPressed;

  const _ViewTab({
    required this.label,
    required this.selected,
    required this.onPressed,
  });

  @override
  Widget build(BuildContext context) => Semantics(
    selected: selected,
    child: DecoratedBox(
      decoration: BoxDecoration(
        border: Border(
          bottom: BorderSide(
            color: selected
                ? Theme.of(context).colorScheme.primary
                : Colors.transparent,
            width: 2,
          ),
        ),
      ),
      child: TextButton(
        onPressed: onPressed,
        style: TextButton.styleFrom(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 14),
          foregroundColor: selected
              ? Theme.of(context).colorScheme.primary
              : Theme.of(context).colorScheme.onSurfaceVariant,
        ),
        child: Text(
          label,
          style: TextStyle(
            fontWeight: selected ? FontWeight.w600 : FontWeight.normal,
          ),
        ),
      ),
    ),
  );
}

class _ToolStep extends StatelessWidget {
  final TranscriptEntry entry;
  final bool isRunning;
  final bool expanded;
  final VoidCallback onToggle;

  const _ToolStep({
    required this.entry,
    required this.isRunning,
    required this.expanded,
    required this.onToggle,
  });

  @override
  Widget build(BuildContext context) {
    final failed = entry.status == TranscriptEntryStatus.failed;
    final status = switch (entry.status) {
      TranscriptEntryStatus.running => isRunning ? 'Bezig' : 'Niet afgerond',
      TranscriptEntryStatus.completed => 'Afgerond',
      TranscriptEntryStatus.failed => 'Mislukt',
    };
    return Container(
      decoration: BoxDecoration(
        border: Border(
          left: BorderSide(
            color: failed
                ? Theme.of(context).colorScheme.error
                : const Color(0xffd7e3dc),
            width: 2,
          ),
        ),
      ),
      padding: const EdgeInsets.only(left: 8),
      child: _Disclosure(
        title: entry.text.isEmpty ? entry.toolName ?? 'Tool' : entry.text,
        status: status,
        failed: failed,
        expanded: expanded,
        onToggle: onToggle,
        preview: failed && (entry.output?.isNotEmpty ?? false)
            ? Padding(
                padding: const EdgeInsets.only(left: 12, right: 12, bottom: 10),
                child: Text(
                  entry.output!,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: Theme.of(context).colorScheme.error),
                ),
              )
            : null,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (entry.toolName?.isNotEmpty ?? false) ...[
              Text(
                entry.toolName!,
                style: Theme.of(context).textTheme.labelMedium,
              ),
              const SizedBox(height: 12),
            ],
            if (entry.input?.isNotEmpty ?? false) ...[
              Text('Invoer', style: Theme.of(context).textTheme.labelLarge),
              const SizedBox(height: 6),
              _CodeText(_formatJson(entry.input!)),
              const SizedBox(height: 16),
            ],
            Text('Uitvoer', style: Theme.of(context).textTheme.labelLarge),
            const SizedBox(height: 6),
            _CodeText(
              entry.output?.isNotEmpty == true
                  ? _formatJson(entry.output!)
                  : entry.status == TranscriptEntryStatus.running
                  ? isRunning
                        ? 'Wachten op uitvoer…'
                        : 'Geen eindstatus ontvangen.'
                  : 'Geen uitvoer ontvangen.',
            ),
          ],
        ),
      ),
    );
  }
}

class _Disclosure extends StatelessWidget {
  final String title;
  final String? status;
  final bool failed;
  final bool expanded;
  final VoidCallback onToggle;
  final Widget child;
  final Widget? preview;

  const _Disclosure({
    super.key,
    required this.title,
    this.status,
    this.failed = false,
    required this.expanded,
    required this.onToggle,
    required this.child,
    this.preview,
  });

  @override
  Widget build(BuildContext context) {
    final colors = Theme.of(context).colorScheme;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Semantics(
          button: true,
          expanded: expanded,
          child: InkWell(
            borderRadius: BorderRadius.circular(8),
            onTap: onToggle,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 12),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Icon(
                    expanded ? Icons.expand_more : Icons.chevron_right,
                    size: 20,
                    color: colors.onSurfaceVariant,
                  ),
                  const SizedBox(width: 6),
                  Expanded(
                    child: Wrap(
                      spacing: 12,
                      runSpacing: 4,
                      crossAxisAlignment: WrapCrossAlignment.center,
                      children: [
                        Text(title),
                        if (status != null)
                          Text(
                            status!,
                            style: TextStyle(
                              color: failed ? colors.error : colors.primary,
                              fontSize: 12,
                              fontWeight: FontWeight.w500,
                            ),
                          ),
                      ],
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
        ?preview,
        if (expanded)
          Container(
            margin: const EdgeInsets.only(left: 6, bottom: 12),
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              color: colors.surface,
              border: Border.all(color: const Color(0xffdfe7e2)),
              borderRadius: BorderRadius.circular(8),
            ),
            child: child,
          ),
      ],
    );
  }
}

class _ErrorMessage extends StatelessWidget {
  final String text;
  const _ErrorMessage(this.text);

  @override
  Widget build(BuildContext context) => Container(
    padding: const EdgeInsets.all(16),
    decoration: BoxDecoration(
      color: Theme.of(context).colorScheme.errorContainer.withValues(alpha: .4),
      borderRadius: BorderRadius.circular(8),
    ),
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text(
          'Fout',
          style: TextStyle(
            fontWeight: FontWeight.w600,
            color: Theme.of(context).colorScheme.error,
          ),
        ),
        const SizedBox(height: 6),
        _ReadableText(text),
      ],
    ),
  );
}

/// Structured output is shown as its actual fields; no summary is invented.
class TranscriptResult extends StatelessWidget {
  final String result;
  const TranscriptResult({super.key, required this.result});

  @override
  Widget build(BuildContext context) => Column(
    crossAxisAlignment: CrossAxisAlignment.stretch,
    children: [
      Text('Resultaat', style: Theme.of(context).textTheme.titleSmall),
      const SizedBox(height: 12),
      _ReadableText(result),
    ],
  );
}

class _ReadableText extends StatelessWidget {
  final String text;
  const _ReadableText(this.text);

  @override
  Widget build(BuildContext context) {
    try {
      final value = jsonDecode(text);
      if (value is Map || value is List) return _StructuredValue(value);
    } on FormatException {
      // Normal conversational text is Markdown, not JSON.
    }
    return _MarkdownText(text);
  }
}

class _StructuredValue extends StatelessWidget {
  final Object? value;
  final int depth;
  const _StructuredValue(this.value, {this.depth = 0});

  @override
  Widget build(BuildContext context) {
    if (depth >= 5) {
      return _CodeText(const JsonEncoder.withIndent('  ').convert(value));
    }
    if (value is Map && (value as Map).isNotEmpty) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (final field in (value as Map).entries)
            Padding(
              padding: const EdgeInsets.only(bottom: 14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Text(
                    field.key.toString(),
                    style: Theme.of(context).textTheme.labelLarge?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
                  ),
                  const SizedBox(height: 4),
                  _StructuredValue(field.value, depth: depth + 1),
                ],
              ),
            ),
        ],
      );
    }
    if (value is List && (value as List).isNotEmpty) {
      return Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          for (final item in value as List)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('•  '),
                  Expanded(child: _StructuredValue(item, depth: depth + 1)),
                ],
              ),
            ),
        ],
      );
    }
    return _MarkdownText(value is String ? value as String : jsonEncode(value));
  }
}

class _MarkdownText extends StatelessWidget {
  final String text;
  const _MarkdownText(this.text);

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return MarkdownBody(
      data: text,
      fitContent: false,
      styleSheet: MarkdownStyleSheet.fromTheme(theme).copyWith(
        p: theme.textTheme.bodyLarge?.copyWith(height: 1.6),
        h1: theme.textTheme.titleLarge?.copyWith(fontWeight: FontWeight.w600),
        h2: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
        h3: theme.textTheme.titleSmall?.copyWith(fontWeight: FontWeight.w600),
        code: theme.textTheme.bodyMedium?.copyWith(
          fontFamily: 'monospace',
          backgroundColor: const Color(0xffedf3ef),
        ),
        codeblockDecoration: BoxDecoration(
          color: const Color(0xffedf3ef),
          borderRadius: BorderRadius.circular(8),
        ),
        codeblockPadding: const EdgeInsets.all(12),
        blockSpacing: 12,
        listBulletPadding: const EdgeInsets.only(right: 8),
        tableCellsPadding: const EdgeInsets.all(8),
      ),
      onTapLink: (_, href, _) => _openLink(context, href),
      // Agent-emitted paths and remote images are rendered as explicit links.
      // Authenticated attachments are handled by the job's file collection.
      imageBuilder: (uri, title, alt) => TextButton.icon(
        onPressed: _webUri(uri.toString()) == null
            ? null
            : () => _openLink(context, uri.toString()),
        icon: const Icon(Icons.image_outlined, size: 18),
        label: Text(
          alt?.isNotEmpty == true
              ? alt!
              : title?.isNotEmpty == true
              ? title!
              : uri.toString(),
        ),
      ),
    );
  }
}

class _CodeText extends StatelessWidget {
  final String text;
  const _CodeText(this.text);

  @override
  Widget build(BuildContext context) => Text(
    text,
    style: Theme.of(
      context,
    ).textTheme.bodySmall?.copyWith(fontFamily: 'monospace', height: 1.6),
  );
}

String _formatJson(String text) {
  try {
    return const JsonEncoder.withIndent('  ').convert(jsonDecode(text));
  } on FormatException {
    return text;
  }
}

bool _sameResult(String left, String right) {
  if (left.trim() == right.trim()) return true;
  try {
    return _sameJsonValue(jsonDecode(left), jsonDecode(right));
  } on FormatException {
    return false;
  }
}

bool _sameJsonValue(Object? left, Object? right) {
  if (left is Map && right is Map) {
    return left.length == right.length &&
        left.keys.every(
          (key) =>
              right.containsKey(key) && _sameJsonValue(left[key], right[key]),
        );
  }
  if (left is List && right is List) {
    return left.length == right.length &&
        List.generate(
          left.length,
          (index) => index,
        ).every((index) => _sameJsonValue(left[index], right[index]));
  }
  return left == right;
}

String _promptPreview(String prompt, List<String> lines) {
  var preview = lines.length > 12 ? lines.take(12).join('\n') : prompt;
  if (preview.length > 700) {
    preview = preview.substring(0, 700);
    // End at a word boundary when there is one near the character limit.
    final boundary = preview.lastIndexOf(RegExp(r'\s'));
    if (boundary > 550) preview = preview.substring(0, boundary);
  }
  return '${preview.trimRight()}…';
}

Uri? _webUri(String? href) {
  final uri = href == null ? null : Uri.tryParse(href);
  return uri != null &&
          (uri.scheme == 'https' || uri.scheme == 'http') &&
          uri.host.isNotEmpty
      ? uri
      : null;
}

Future<void> _openLink(BuildContext context, String? href) async {
  final uri = _webUri(href);
  if (uri == null) return;
  try {
    if (await launchUrl(uri, mode: LaunchMode.externalApplication)) return;
  } catch (_) {
    // Keep the transcript usable if the host cannot launch a browser.
  }
  if (context.mounted) {
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(
      const SnackBar(content: Text('De link kon niet worden geopend.')),
    );
  }
}
