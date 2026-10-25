package nl.vdzon.agentruntime.server

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import nl.vdzon.agentruntime.contracts.v2.ProviderLimitKind
import nl.vdzon.agentruntime.server.v2.providerHttpFailure
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.net.http.HttpHeaders
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class ProviderHttpLimitsTest {
    @Test fun `HTTP rate limit retains retry advice without exposing body or inventing quota period`() {
        val reset=Instant.now().plusSeconds(600)
        for(value in listOf("600",DateTimeFormatter.RFC_1123_DATE_TIME.format(reset.atZone(ZoneOffset.UTC)))) {
            val headers=HttpHeaders.of(mapOf("retry-after" to listOf(value))) { _,_ -> true }
            val failure=providerHttpFailure("Provider",429,"""{"error":{"code":"rate_limit_exceeded","message":"secret-token"}}""",headers,jacksonObjectMapper())
            assertThat(failure.limit!!.kind).isEqualTo(ProviderLimitKind.UNKNOWN)
            assertThat(failure.limit.retryAfterSeconds).isBetween(598L,600L)
            assertThat(failure.message).doesNotContain("secret-token")
        }
    }

    @Test fun `authentication error is not a quota observation`() {
        val failure=providerHttpFailure("Provider",401,"unauthorized",HttpHeaders.of(emptyMap()) { _,_ -> true },jacksonObjectMapper())
        assertThat(failure.limit).isNull()
        assertThat(failure.retryable).isFalse()
    }
}
