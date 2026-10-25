package nl.vdzon.agentruntime.worker

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.v2.*
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import com.fasterxml.jackson.databind.node.TextNode

internal data class TranscriptChunk(
    val text: String,
    val eventId: String,
    val streamId: String,
    val final: Boolean,
    val observedAt: Instant,
)

/** Redact decoded JSON strings, preserving the provider envelope and escaped secret values. */
internal fun redactProviderEvent(line: String, mapper: ObjectMapper): String {
    val parsed = runCatching { mapper.readTree(line) }.getOrNull()
        ?: return redact(line, Int.MAX_VALUE)
    fun clean(node: JsonNode): JsonNode = when {
        node.isTextual -> TextNode(redact(node.asText(), Int.MAX_VALUE))
        node.isArray -> mapper.createArrayNode().also { array -> node.forEach { array.add(clean(it)) } }
        node.isObject -> mapper.createObjectNode().also { result ->
            node.fields().forEach { (key, value) ->
                val sensitive = key.matches(Regex("(?i)token|secret|password|api[_-]?key"))
                result.set<JsonNode>(SecretRedactor.clean(key), if (sensitive && !value.isNull) TextNode("[REDACTED]") else clean(value))
            }
        }
        else -> node
    }
    return mapper.writeValueAsString(clean(parsed))
}

/** Spool complete redacted lines; retries retain the same chunk and idempotency key. */
internal class ExecutionTranscript(path: Path, private val agentRound: Int = 1) : AutoCloseable {
    private val writer = Files.newBufferedWriter(path)
    private val reader = Files.newBufferedReader(path)
    private val pending = ArrayDeque<TranscriptChunk>()
    private var deliveryFailed = false
    @Volatile private var closing = false
    @Volatile private var lastDeliveryNanos = 0L
    private var sender: Thread? = null

    @Synchronized fun append(line: String) {
        writer.appendLine(line)
        writer.flush()
    }

    /** True means the spool was drained; a failed request stays pending for the next call. */
    fun forward(limit: Int = 32, send: (TranscriptChunk) -> Unit): Boolean {
        deliveryFailed = false
        repeat(limit) {
            if (pending.isEmpty()) {
                val line = synchronized(this) { reader.readLine() } ?: return true
                val streamId = "provider-event:$agentRound:${UUID.randomUUID()}"
                val observedAt = Instant.now()
                var offset = 0
                while (offset < line.length) {
                    var end = minOf(offset + 8192, line.length)
                    // UTF-8 serialization must never see half of a surrogate pair.
                    if (end < line.length && line[end - 1].isHighSurrogate() && line[end].isLowSurrogate()) end--
                    pending += TranscriptChunk(line.substring(offset, end), UUID.randomUUID().toString(), streamId, end == line.length, observedAt)
                    offset = end
                }
            }
            val chunk = pending.firstOrNull() ?: return@repeat
            try { send(chunk) }
            catch (_: InterruptedException) { deliveryFailed = true; Thread.currentThread().interrupt(); return false }
            catch (_: Exception) { deliveryFailed = true; return false }
            pending.removeFirst()
            lastDeliveryNanos = System.nanoTime()
        }
        return false
    }

    /** HTTP log delivery has its own thread, so neither usage reads nor heartbeats wait on it. */
    fun startForwarding(send: (TranscriptChunk) -> Unit) {
        check(sender == null)
        sender = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val drained = forward(send = send)
                    if (closing && drained) break
                    if (drained || deliveryFailed) Thread.sleep(250)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }.apply { isDaemon = true; name = "provider-transcript-$agentRound"; start() }
    }

    override fun close() {
        closing = true
        sender?.let {
            val started = System.nanoTime()
            var lastProgress = started
            var observedDelivery = lastDeliveryNanos
            // A healthy backlog gets time to drain; a failed endpoint must not hold the job.
            while (it.isAlive && System.nanoTime() - started < 60_000_000_000L && System.nanoTime() - lastProgress < 5_000_000_000L) {
                it.join(100)
                if (lastDeliveryNanos != observedDelivery) { observedDelivery = lastDeliveryNanos; lastProgress = System.nanoTime() }
            }
            if (it.isAlive) {
                System.err.println("Provider transcript delivery did not drain; the remaining transcript may be incomplete.")
                it.interrupt()
                it.join(1000)
            }
        }
        synchronized(this) { writer.close(); reader.close() }
    }
}

/** One CLI invocation. Snapshots are monotonic; repeated assistant/tool messages are not billed twice. */
internal class ProviderUsageTracker(private val vendor: String, private val mapper: ObjectMapper) {
    private val messages = mutableMapOf<String, MutableMap<UsageMetric, Long>>()
    private val cumulative = mutableMapOf<UsageMetric, Long>()
    private var currentMessage: String? = null
    private var nestedUsage = false
    var providerRequestId: String? = null
        private set
    var complete = false
        private set

    fun observe(line: String) {
        val event = runCatching { mapper.readTree(line) }.getOrNull() ?: return
        if (vendor == "mistral") {
            providerRequestId = event.path("session_id").asText().takeIf { it.isNotBlank() } ?: providerRequestId
            when (event.path("type").asText()) {
                "mistral.usage" -> {
                    val id = event.path("request_id").asText().takeIf { it.isNotBlank() } ?: return
                    val usage = event.path("usage")
                    val cached = usage.count("cached_input_tokens")
                    merge(messages.getOrPut(id) { mutableMapOf() }, mapOf(
                        UsageMetric.INPUT_TOKENS to (usage.count("input_tokens") - cached).coerceAtLeast(0),
                        UsageMetric.CACHED_INPUT_TOKENS to cached,
                        UsageMetric.OUTPUT_TOKENS to usage.count("output_tokens"),
                    ))
                }
                "mistral.completed" -> complete = event.path("complete").asBoolean(false)
            }
            return
        }
        if (vendor == "openai") {
            if (event.path("type").asText() == "turn.completed" && event.path("usage").isObject) {
                val usage = event.path("usage")
                val counts = counts(usage)
                // Codex includes cached tokens in input_tokens; Anthropic reports them separately.
                counts[UsageMetric.INPUT_TOKENS] = (usage.count("input_tokens") - usage.count("cached_input_tokens")).coerceAtLeast(0)
                counts[UsageMetric.CACHED_INPUT_TOKENS] = usage.count("cached_input_tokens")
                merge(cumulative, counts)
                complete = true
            }
            return
        }
        if (!event.path("parent_tool_use_id").isMissingNode && !event.path("parent_tool_use_id").isNull) {
            // Nested agents can use another model; never price those tokens at the parent model's rate.
            nestedUsage = true
            return
        }
        when (event.path("type").asText()) {
            "assistant" -> message(event.path("message"))
            "stream_event" -> {
                val item = event.path("event")
                when (item.path("type").asText()) {
                    "message_start" -> {
                        currentMessage = item.path("message").path("id").asText().takeIf { it.isNotBlank() }
                        message(item.path("message"))
                    }
                    "message_delta" -> currentMessage?.let { id ->
                        if (item.path("usage").has("output_tokens")) {
                            merge(messages.getOrPut(id) { mutableMapOf() }, mapOf(UsageMetric.OUTPUT_TOKENS to item.path("usage").count("output_tokens")))
                        }
                    }
                    "message_stop" -> currentMessage = null
                }
            }
            "result" -> if (event.path("usage").isObject) {
                merge(cumulative, counts(event.path("usage")))
                // Error summaries can omit the final request. Keep the measured lower bound.
                complete = !event.path("is_error").asBoolean(false) && event.path("subtype").asText() == "success"
            }
        }
    }

    fun totals(): Map<UsageMetric, Long> {
        val sum = mutableMapOf<UsageMetric, Long>()
        messages.values.forEach { counts -> counts.forEach { (metric, quantity) -> sum[metric] = Math.addExact(sum[metric] ?: 0, quantity) } }
        merge(sum, cumulative)
        return sum
    }

    fun isComplete() = complete && !nestedUsage

    private fun message(message: JsonNode) {
        val id = message.path("id").asText().takeIf { it.isNotBlank() } ?: return
        if (!message.path("usage").isObject) return
        val counts = counts(message.path("usage"))
        // Assistant output_tokens can be a placeholder. Streaming deltas and result carry the count.
        counts.remove(UsageMetric.OUTPUT_TOKENS)
        merge(messages.getOrPut(id) { mutableMapOf() }, counts)
    }

    private fun counts(usage: JsonNode) = mutableMapOf(
        UsageMetric.INPUT_TOKENS to usage.count("input_tokens"),
        UsageMetric.CACHED_INPUT_TOKENS to usage.count("cache_read_input_tokens"),
        UsageMetric.CACHE_WRITE_TOKENS to usage.count("cache_creation_input_tokens"),
        UsageMetric.OUTPUT_TOKENS to usage.count("output_tokens"),
    )

    private fun JsonNode.count(name: String): Long = path(name).let {
        if (it.isIntegralNumber && it.canConvertToLong()) it.asLong().coerceAtLeast(0) else 0
    }

    private fun merge(target: MutableMap<UsageMetric, Long>, values: Map<UsageMetric, Long>) {
        values.forEach { (metric, quantity) -> target[metric] = maxOf(target[metric] ?: 0, quantity) }
    }
}

/** Queue deltas before result validation. Failed HTTP deliveries replay the same idempotency key. */
internal class ExecutionUsageReporter(
    private val fencingToken: String,
    private val send: (AppendUsageRequest) -> Unit,
    private val warn: (String) -> Unit = { System.err.println(it) },
) {
    private val pending = ArrayDeque<AppendUsageRequest>()
    private var sequence = 0
    private var allComplete = true
    private var rounds = 0
    private var finishedRounds = 0
    private var nextDelivery = Instant.MIN

    inner class Round(private val tracker: ProviderUsageTracker) {
        private var reported = emptyMap<UsageMetric, Long>()
        private var closed = false

        @Synchronized fun observe(line: String) {
            if (closed) return
            tracker.observe(line)
            val totals = tracker.totals()
            if (totals.isEmpty()) return
            val delta = totals.mapValues { (metric, count) -> count - (reported[metric] ?: 0) }
            if (reported.isEmpty() || delta.values.any { it > 0 }) {
                enqueue(delta, UsageSource.PROVIDER_REPORTED, false, tracker.providerRequestId)
                reported = totals
            }
        }

        @Synchronized fun finish(estimatedInput: Long, estimatedOutput: Long, streamDrained: Boolean = true) {
            if (closed) return
            closed = true
            finishedRounds++
            // Compatibility with older execution images: raw output, including invalid JSON,
            // is sufficient for the same explicitly partial estimate used previously.
            if (streamDrained && reported.isEmpty() && estimatedOutput > 0) {
                enqueue(mapOf(UsageMetric.INPUT_TOKENS to estimatedInput, UsageMetric.OUTPUT_TOKENS to estimatedOutput), UsageSource.WORKER_MEASURED, false)
            }
            allComplete = allComplete && tracker.isComplete() && streamDrained
        }
    }

    fun round(vendor: String, mapper: ObjectMapper): Round {
        rounds++
        return Round(ProviderUsageTracker(vendor, mapper))
    }

    @Synchronized private fun enqueue(counts: Map<UsageMetric, Long>, source: UsageSource, complete: Boolean, providerRequestId: String? = null) {
        val metrics = counts.map { (metric, quantity) -> UsageMetricValue(metric, quantity.toString(), UsageUnit.TOKEN) }
        pending.addLast(AppendUsageRequest(fencingToken, "execution-usage-${++sequence}", Instant.now(), metrics, providerRequestId = providerRequestId, source = source, complete = complete))
    }

    fun finish() {
        if (rounds > 0 && rounds == finishedRounds && allComplete) enqueue(mapOf(UsageMetric.OUTPUT_TOKENS to 0), UsageSource.PROVIDER_REPORTED, true)
        // Bounded retries; accounting must never turn a successful execution into WORKER_ERROR.
        repeat(3) { flush(force = true) }
        synchronized(this) {
            if (pending.isNotEmpty()) warn("Execution usage could not be delivered: ${pending.size} pending events; costs may be incomplete.")
        }
    }

    fun flush(force: Boolean = false) {
        if (!force && Instant.now().isBefore(nextDelivery)) return
        while (true) {
            val event = synchronized(this) { pending.firstOrNull() } ?: return
            try {
                send(event)
                synchronized(this) { pending.removeFirst() }
            } catch (_: Exception) {
                nextDelivery = Instant.now().plusSeconds(5)
                return
            }
        }
    }
}
