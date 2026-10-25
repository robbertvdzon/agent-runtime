package nl.vdzon.agentruntime.server.v2

import com.fasterxml.jackson.databind.ObjectMapper
import nl.vdzon.agentruntime.contracts.v2.*
import java.net.http.HttpHeaders
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/** HTTP status proves a block, but a 429 alone does not identify its quota period. */
internal fun providerHttpFailure(provider: String, status: Int, body: String, headers: HttpHeaders, mapper: ObjectMapper): ProviderFailure {
    val error=runCatching { mapper.readTree(body).path("error") }.getOrNull()
    val code=error?.path("code")?.asText()?.takeIf { it.matches(Regex("[A-Za-z0-9_.:-]{1,120}")) }
    val isLimit=status==429 || code in setOf("insufficient_quota","quota_exceeded","rate_limit_exceeded","weekly_quota_exceeded","monthly_quota_exceeded")
    val now=Instant.now()
    val retry=headers.firstValue("Retry-After").orElse(null)?.let { value ->
        value.toLongOrNull()?.takeIf { it in 0..31_536_000 } ?: runCatching {
            Duration.between(now,ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).seconds.coerceAtLeast(0).takeIf { it<=31_536_000 }
        }.getOrNull()
    }
    val kind=when(code) { "weekly_quota_exceeded"->ProviderLimitKind.WEEK;"monthly_quota_exceeded"->ProviderLimitKind.MONTH;else->ProviderLimitKind.UNKNOWN }
    val observation=if(isLimit)ProviderLimitObservation(UUID.randomUUID().toString(),kind,now,retryAfterSeconds=retry,httpStatus=status,providerCode=code,source=ProviderLimitSource.PROVIDER_ERROR) else null
    return ProviderFailure(if(isLimit)"PROVIDER_RATE_LIMIT" else "PROVIDER_ERROR", "$provider returned HTTP $status.",isLimit||status>=500,observation)
}
