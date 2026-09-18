package nl.vdzon.agentruntime.worker

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.v2.*
import java.time.Instant
import java.nio.file.Files
import java.nio.file.Path

/** Spool redacted logs so a slow log endpoint cannot block reading provider usage. */
internal class ExecutionTranscript(path: Path) : AutoCloseable {
    private val writer = Files.newBufferedWriter(path)
    private val reader = Files.newBufferedReader(path)
    @Synchronized fun append(line: String) {
        writer.appendLine(line)
        writer.flush()
    }
    fun forward(limit: Int = Int.MAX_VALUE, send: (String) -> Unit) {
        repeat(limit) {
            val line = synchronized(this) { reader.readLine() } ?: return
            try { send(line) } catch (_: Exception) { return }
        }
    }
    @Synchronized override fun close() { writer.close(); reader.close() }
}

/** One CLI invocation. Snapshots are monotonic; repeated assistant/tool messages are not billed twice. */
internal class ProviderUsageTracker(private val vendor: String, private val mapper: ObjectMapper) {
    private val messages = mutableMapOf<String, MutableMap<UsageMetric, Long>>()
    private val cumulative = mutableMapOf<UsageMetric, Long>()
    private var currentMessage: String? = null
    private var nestedUsage = false
    var complete = false
        private set

    fun observe(line: String) {
        val event = runCatching { mapper.readTree(line) }.getOrNull() ?: return
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
                enqueue(delta, UsageSource.PROVIDER_REPORTED, false)
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

    @Synchronized private fun enqueue(counts: Map<UsageMetric, Long>, source: UsageSource, complete: Boolean) {
        val metrics = counts.map { (metric, quantity) -> UsageMetricValue(metric, quantity.toString(), UsageUnit.TOKEN) }
        pending.addLast(AppendUsageRequest(fencingToken, "execution-usage-${++sequence}", Instant.now(), metrics, source = source, complete = complete))
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
