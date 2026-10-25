package nl.vdzon.agentruntime.worker

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.agentruntime.contracts.v2.*
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProviderLimitsTest {
    private val mapper=jacksonObjectMapper()

    @Test fun `Claude rejection carries explicit window while warnings and assistant text do not establish limits`() {
        val tracker=ProviderLimitTracker("anthropic",mapper)
        tracker.observe("""{"type":"assistant","message":{"content":[{"type":"text","text":"429 weekly quota exceeded"}]}}""")
        tracker.observe("""{"type":"rate_limit_event","rate_limit_info":{"status":"allowed_warning","rateLimitType":"seven_day"}}""")
        assertThat(tracker.snapshot()).isEmpty()
        repeat(2) { tracker.observe("""{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","rateLimitType":"seven_day","resetsAt":1804000000}}""") }
        assertThat(tracker.snapshot()).hasSize(1)
        assertThat(tracker.snapshot().single().kind).isEqualTo(ProviderLimitKind.WEEK)
        assertThat(tracker.snapshot().single().resetsAt!!.epochSecond).isEqualTo(1804000000)
    }

    @Test fun `Codex structured failure proves limit but text cannot prove its period`() {
        val tracker=ProviderLimitTracker("openai",mapper)
        tracker.observe("""{"type":"turn.failed","error":{"message":"You have hit your usage limit; try next week"}}""")
        assertThat(tracker.snapshot().single().kind).isEqualTo(ProviderLimitKind.UNKNOWN)
        assertThat(tracker.snapshot().single().resetsAt).isNull()
        tracker.observe("""{"type":"item.completed","item":{"type":"command_execution","aggregated_output":"rate limit"}}""")
        assertThat(tracker.snapshot()).hasSize(1)
    }

    @Test fun `explicit Codex reset advice is preserved without inferring the limit period`() {
        val tracker=ProviderLimitTracker("openai",mapper)
        tracker.observe("""{"type":"turn.failed","error":{"code":"usage_limit_reached","resets_at":"2026-11-01T00:00:00Z","retry_after_seconds":120}}""")
        assertThat(tracker.snapshot().single().kind).isEqualTo(ProviderLimitKind.UNKNOWN)
        assertThat(tracker.snapshot().single().resetsAt.toString()).isEqualTo("2026-11-01T00:00:00Z")
        assertThat(tracker.snapshot().single().retryAfterSeconds).isEqualTo(120L)
    }

    @Test fun `Mistral sanitized event preserves reset advice but invalid metadata is discarded`() {
        val tracker=ProviderLimitTracker("mistral",mapper)
        tracker.observe("""{"type":"mistral.limit","kind":"MONTH","http_status":429,"retry_after_seconds":120,"resets_at":"2026-11-01T00:00:00Z","provider_code":"monthly_quota_exceeded"}""")
        val limit=tracker.snapshot().single()
        assertThat(limit.kind).isEqualTo(ProviderLimitKind.MONTH)
        assertThat(limit.retryAfterSeconds).isEqualTo(120)
        assertThat(limit.resetsAt.toString()).isEqualTo("2026-11-01T00:00:00Z")
        tracker.observe("""{"type":"mistral.limit","kind":"invented","http_status":0,"retry_after_seconds":-1,"provider_code":"secret token value"}""")
        assertThat(tracker.snapshot().last().providerCode).isNull()
        assertThat(tracker.snapshot().last().kind).isEqualTo(ProviderLimitKind.UNKNOWN)
    }

    @Test fun `delivery retries retain stable observation ID and failure fallback includes delivered observations`() {
        val received=mutableListOf<ProviderLimitObservation>(); var calls=0
        val reporter=ProviderLimitReporter { if (++calls==1)error("Temporary outage");received+=it }
        reporter.tracker("openai",mapper).observe("""{"type":"error","message":"rate limit exceeded"}""")
        reporter.flush(force=true);reporter.flush(force=true);reporter.flush(force=true)
        assertThat(received).hasSize(1)
        assertThat(reporter.snapshot().single().eventId).isEqualTo(received.single().eventId)
    }
}
