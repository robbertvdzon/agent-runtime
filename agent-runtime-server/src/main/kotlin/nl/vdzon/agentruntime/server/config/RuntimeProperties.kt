package nl.vdzon.agentruntime.server.config

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties

enum class RuntimeEnvironment { LOCAL, ACCEPTANCE, PRODUCTION }

@ConfigurationProperties("agent-runtime")
data class RuntimeProperties(
    var environment: RuntimeEnvironment = RuntimeEnvironment.LOCAL,
    var productFactoryToken: String = "local-product-factory-token",
    var softwareFactoryToken: String = "local-software-factory-token",
    var hkhAutopilotToken: String = "local-hkh-autopilot-token",
    var hkhToken: String = "local-hkh-token",
    var pvddToken: String = "local-pvdd-token",
    var personalNewsFeedToken: String = "local-personal-news-feed-token",
    /** Comma-separated tenant=token entries, so a new consumer does not require a code change. */
    var additionalConsumerTokens: String = "",
    var workerToken: String = "local-worker-token",
    var testControlToken: String = "",
    var workerApiEnabled: Boolean = true,
    var adminToken: String = "local-admin-token",
    var googleClientId: String = "",
    var adminEmails: String = "",
    var sessionSigningSecret: String = "local-session-signing-secret",
    var leaseSeconds: Long = 120,
    var recoverySeconds: Long = 1800,
    var maxAttempts: Int = 3,
    var defaultPriority: Int = 50,
    var maxOutputAttempts: Int = 3,
    var productFactoryEnvironmentPrefixes: String = "PF,HKH,HKH_AUTOPILOT,PERSONAL_FEED,ROBBERTS_ASSISTENT,SF",
    var softwareFactoryEnvironmentPrefixes: String = "SF,PF,HKH,HKH_AUTOPILOT,PERSONAL_FEED,ROBBERTS_ASSISTENT",
    var hkhAutopilotEnvironmentPrefixes: String = "HKH_AUTOPILOT",
    var hkhEnvironmentPrefixes: String = "HKH",
    var pvddEnvironmentPrefixes: String = "PVDD",
    var productFactoryProviders: String = "CODEX,CLAUDE,MOCKED",
    var softwareFactoryProviders: String = "CODEX,CLAUDE,MOCKED",
    var hkhAutopilotProviders: String = "CODEX,CLAUDE,MOCKED",
    var hkhProviders: String = "CODEX,CLAUDE,MOCKED",
    var pvddProviders: String = "CODEX",
    var productFactoryModels: String = "*",
    var softwareFactoryModels: String = "*",
    var softwareFactoryRepositoryAliases: String = "software-factory,agent-runtime,test-repository,pvdd,hkh,hkh-autopilot,product-factory,personal-news-feed,robberts-assistent",
    var hkhAutopilotModels: String = "*",
    var hkhModels: String = "*",
    var pvddModels: String = "gpt-5.6-sol",
    var personalNewsFeedEnvironmentPrefixes: String = "PERSONAL_FEED",
    var personalNewsFeedProviders: String = "CODEX,CLAUDE,MOCKED",
    var personalNewsFeedModels: String = "*",
    var inputAttachmentMaxBytes: Long = 2L * 1024 * 1024,
    var jobInputAttachmentMaxBytes: Long = 10L * 1024 * 1024,
    var artifactMaxBytes: Long = 5L * 1024 * 1024,
    var jobArtifactMaxBytes: Long = 75L * 1024 * 1024,
    var transcriptMaxBytesPerJob: Long = 10L * 1024 * 1024,
    var objectStorePath: String = "${System.getProperty("java.io.tmpdir")}/agent-runtime-objects",
    var objectMaxBytes: Long = 2L * 1024 * 1024 * 1024,
    var jobInputMaxBytes: Long = 5L * 1024 * 1024 * 1024,
    var jobOutputMaxBytes: Long = 5L * 1024 * 1024 * 1024,
    var objectStoreMinFreeBytes: Long = 0,
    var uploadRetentionHours: Long = 24,
    var failedContentRetentionDays: Long = 7,
    var successfulContentRetentionDays: Long = 30,
    var monitorLogRetentionDays: Long = 14,
    var v2ResultMaxBytes: Long = 1024 * 1024,
    var openAiApiKey: String = "",
    var openAiApiBaseUrl: String = "https://api.openai.com/v1",
    var elevenLabsApiKey: String = "",
    var elevenLabsBaseUrl: String = "https://api.elevenlabs.io",
    var apiExecutorConcurrency: Int = 6,
    var ffmpegBinary: String = "ffmpeg",
) {
    @PostConstruct
    fun validate() {
        if (environment == RuntimeEnvironment.ACCEPTANCE) {
            require(!workerApiEnabled) { "Acceptance must have the worker API disabled." }
            require(allowedProviders("product-factory") == setOf("MOCKED")) { "Acceptance Product Factory may only allow MOCKED." }
            require(allowedProviders("software-factory") == setOf("MOCKED")) { "Acceptance Software Factory may only allow MOCKED." }
            require(allowedProviders("hkh-autopilot") == setOf("MOCKED")) { "Acceptance HKH Autopilot may only allow MOCKED." }
            require(allowedProviders("hkh") == setOf("MOCKED")) { "Acceptance HKH may only allow MOCKED." }
            require(allowedProviders("pvdd") == setOf("MOCKED")) { "Acceptance PvdD may only allow MOCKED." }
            require(allowedProviders("personal-news-feed") == setOf("MOCKED")) { "Acceptance Personal News Feed may only allow MOCKED." }
            require(pvddModels.split(',').map(String::trim).filter(String::isNotBlank).toSet() == setOf("mock-model", "mock")) {
                "Acceptance PvdD may only allow the v1 and v2 mock models."
            }
            require(testControlToken.isNotBlank() && !testControlToken.startsWith("local-") && testControlToken.length >= 24) {
                "Acceptance requires a non-default test-control credential of at least 24 characters."
            }
        }
        if (environment == RuntimeEnvironment.PRODUCTION) {
            val unsafe = listOf(
                productFactoryToken, softwareFactoryToken, hkhAutopilotToken, hkhToken, pvddToken, personalNewsFeedToken,
                workerToken, adminToken, sessionSigningSecret,
            )
                .any { it.isBlank() || it.startsWith("local-") || it.length < 24 }
            require(!unsafe) { "Production requires non-default credentials of at least 24 characters." }
            require(googleClientId.isNotBlank() && allowedAdminEmails().isNotEmpty()) { "Production requires Google client ID and an administrator email allowlist." }
            require("MOCKED" !in allowedProviders("pvdd") && allowedProviders("pvdd").isNotEmpty()) {
                "Production PvdD requires explicitly configured real providers."
            }
            require(pvddModels.isNotBlank() && pvddModels != "*") { "Production PvdD requires explicitly configured models." }
        }
        val bearerTokens = consumerTokens().values.toList() + workerToken + adminToken + listOfNotNull(testControlToken.takeIf(String::isNotBlank))
        require(bearerTokens.size == bearerTokens.distinct().size) { "Bearer credentials must be unique." }
        require(leaseSeconds in 30..900)
        require(recoverySeconds in leaseSeconds..86_400)
        require(maxAttempts in 1..10)
        require(defaultPriority in 0..100)
        require(maxOutputAttempts in 1..3)
        require(inputAttachmentMaxBytes in 1..10L * 1024 * 1024)
        require(jobInputAttachmentMaxBytes in inputAttachmentMaxBytes..50L * 1024 * 1024)
        require(transcriptMaxBytesPerJob in 1L * 1024 * 1024..100L * 1024 * 1024)
        require(objectMaxBytes in 1..2L * 1024 * 1024 * 1024)
        require(jobInputMaxBytes >= objectMaxBytes && jobOutputMaxBytes >= objectMaxBytes)
        require(objectStoreMinFreeBytes >= 0)
        require(v2ResultMaxBytes in 1..5L * 1024 * 1024)
        require(apiExecutorConcurrency in 1..32)
    }

    fun allowedAdminEmails(): Set<String> = adminEmails.split(',').map(String::trim).map(String::lowercase).filter(String::isNotBlank).toSet()

    fun allowedEnvironmentPrefixes(tenantId: String): Set<String> = when (tenantId) {
        "product-factory" -> productFactoryEnvironmentPrefixes
        "software-factory" -> softwareFactoryEnvironmentPrefixes
        "hkh-autopilot" -> hkhAutopilotEnvironmentPrefixes
        "hkh" -> hkhEnvironmentPrefixes
        "pvdd" -> pvddEnvironmentPrefixes
        "personal-news-feed" -> personalNewsFeedEnvironmentPrefixes
        else -> tenantId.uppercase().replace('-', '_')
    }.split(',').map(String::trim).filter(String::isNotBlank).toSet()

    fun allowedProviders(tenantId: String): Set<String> = when (tenantId) {
        "product-factory" -> productFactoryProviders
        "software-factory" -> softwareFactoryProviders
        "hkh-autopilot" -> hkhAutopilotProviders
        "hkh" -> hkhProviders
        "pvdd" -> pvddProviders
        "personal-news-feed" -> personalNewsFeedProviders
        else -> "CODEX,CLAUDE,MOCKED"
    }.split(',').map(String::trim).map(String::uppercase).filter(String::isNotBlank).toSet()

    fun modelAllowed(tenantId: String, model: String): Boolean {
        val configured = when (tenantId) {
            "product-factory" -> productFactoryModels
            "software-factory" -> softwareFactoryModels
            "hkh-autopilot" -> hkhAutopilotModels
            "hkh" -> hkhModels
            "pvdd" -> pvddModels
            "personal-news-feed" -> personalNewsFeedModels
            else -> "*"
        }.split(',').map(String::trim).filter(String::isNotBlank).toSet()
        return "*" in configured || model in configured
    }

    fun allowedRepositoryAliases(tenantId: String): Set<String> = if (tenantId == "software-factory") {
        softwareFactoryRepositoryAliases.split(',').map(String::trim).filter(String::isNotBlank).toSet()
    } else emptySet()

    fun consumerTokens(): Map<String, String> = linkedMapOf(
        "product-factory" to productFactoryToken,
        "software-factory" to softwareFactoryToken,
        "hkh-autopilot" to hkhAutopilotToken,
        "hkh" to hkhToken,
        "pvdd" to pvddToken,
        "personal-news-feed" to personalNewsFeedToken,
    ) + additionalConsumerTokens.split(',').mapNotNull { entry ->
        val separator = entry.indexOf('=')
        if (separator <= 0) null else entry.substring(0, separator).trim().takeIf(String::isNotBlank)?.let {
            it to entry.substring(separator + 1).trim()
        }
    }.toMap()
}
