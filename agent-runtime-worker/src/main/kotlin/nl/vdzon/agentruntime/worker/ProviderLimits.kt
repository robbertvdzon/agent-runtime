package nl.vdzon.agentruntime.worker

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.v2.*
import java.time.Instant
import java.util.UUID

/** Read only provider protocol envelopes, never assistant text or tool output. */
internal class ProviderLimitTracker(private val vendor: String, private val mapper: ObjectMapper) {
    private val observations = linkedMapOf<String, ProviderLimitObservation>()

    @Synchronized fun observe(line: String) {
        val event = runCatching { mapper.readTree(line) }.getOrNull() ?: return
        val type = event.path("type").asText()
        val observation = when {
            vendor == "anthropic" && type == "rate_limit_event" -> {
                val info = event.path("rate_limit_info")
                if (info.path("status").asText() != "rejected") return
                val window = info.path("rateLimitType").asText()
                ProviderLimitObservation(UUID.randomUUID().toString(), when (window) {
                    "five_hour" -> ProviderLimitKind.SHORT_WINDOW
                    "seven_day", "seven_day_opus", "seven_day_sonnet" -> ProviderLimitKind.WEEK
                    else -> ProviderLimitKind.UNKNOWN
                }, Instant.now(), resetsAt = info.path("resetsAt").timestamp(),
                    providerCode = window.safeCode(), source = ProviderLimitSource.PROVIDER_STRUCTURED)
            }
            vendor == "mistral" && type == "mistral.limit" -> {
                ProviderLimitObservation(UUID.randomUUID().toString(),
                    runCatching { ProviderLimitKind.valueOf(event.path("kind").asText()) }.getOrDefault(ProviderLimitKind.UNKNOWN),
                    Instant.now(), resetsAt = event.path("resets_at").timestamp(),
                    retryAfterSeconds = event.path("retry_after_seconds").takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()?.takeIf { it in 0..31_536_000 },
                    httpStatus = event.path("http_status").asInt().takeIf { it in 100..599 },
                    providerCode = event.path("provider_code").asText().safeCode(), source = ProviderLimitSource.PROVIDER_ERROR)
            }
            vendor == "openai" && type in setOf("error", "turn.failed") -> error(event.path("error").takeIf { !it.isMissingNode } ?: event)
            vendor == "anthropic" && type == "assistant" && event.path("error").isTextual -> error(event)
            else -> null
        } ?: return
        // Repeated identical provider envelopes (including retry notices) aren't new limits.
        val key = listOf(observation.kind, observation.resetsAt, observation.retryAfterSeconds, observation.httpStatus, observation.providerCode).joinToString("|")
        if (observations.size < 100) observations.putIfAbsent(key, observation)
    }

    private fun error(node: JsonNode): ProviderLimitObservation? {
        val code = node.path("code").asText().ifBlank { node.path("error").asText() }
        // A structured failure message can establish a limit but never its period.
        val message = node.path("message").asText().lowercase()
        if (code !in setOf("rate_limit", "rate_limit_exceeded", "usage_limit_reached", "insufficient_quota", "quota_exceeded") &&
            !message.contains("usage limit") && !message.contains("rate limit") && !message.contains("quota exceeded")) return null
        return ProviderLimitObservation(UUID.randomUUID().toString(), ProviderLimitKind.UNKNOWN, Instant.now(),
            resetsAt = node.path("resets_at").timestamp(),
            retryAfterSeconds = node.path("retry_after_seconds").takeIf { it.isIntegralNumber && it.canConvertToLong() }?.asLong()?.takeIf { it in 0..31_536_000 },
            providerCode = code.safeCode(), source = ProviderLimitSource.PROVIDER_ERROR)
    }

    @Synchronized fun snapshot(): List<ProviderLimitObservation> = observations.values.toList()

    private fun String.safeCode() = takeIf { matches(Regex("[A-Za-z0-9_.:-]{1,120}")) }
    private fun JsonNode.timestamp(): Instant? = runCatching {
        when { isIntegralNumber -> Instant.ofEpochSecond(asLong()); isTextual -> Instant.parse(asText()); else -> null }
    }.getOrNull()?.takeIf { it.epochSecond in 1..Instant.now().plusSeconds(31_536_000).epochSecond }
}

/** Delivery runs outside the provider reader; failure payload replays observations atomically. */
internal class ProviderLimitReporter(private val send: (ProviderLimitObservation) -> Unit) {
    private val delivered = mutableSetOf<String>()
    private val trackers = mutableListOf<ProviderLimitTracker>()
    private var nextDelivery = Instant.MIN

    @Synchronized fun tracker(vendor: String, mapper: ObjectMapper) = ProviderLimitTracker(vendor,mapper).also { trackers += it }
    @Synchronized fun snapshot() = trackers.flatMap { it.snapshot() }.take(100)

    fun flush(force: Boolean = false) {
        if (!force && Instant.now().isBefore(nextDelivery)) return
        for (observation in snapshot()) {
            if (observation.eventId in delivered) continue
            try { send(observation); delivered += observation.eventId }
            catch (_: Exception) { nextDelivery = Instant.now().plusSeconds(5); return }
        }
    }
}
