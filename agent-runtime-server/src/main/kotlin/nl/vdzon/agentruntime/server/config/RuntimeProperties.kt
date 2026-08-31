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
    var workerToken: String = "local-worker-token",
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
    var hkhAutopilotModels: String = "*",
    var hkhModels: String = "*",
    var pvddModels: String = "gpt-5.6-sol",
    var inputAttachmentMaxBytes: Long = 2L * 1024 * 1024,
    var jobInputAttachmentMaxBytes: Long = 10L * 1024 * 1024,
    var artifactMaxBytes: Long = 5L * 1024 * 1024,
    var jobArtifactMaxBytes: Long = 75L * 1024 * 1024,
    var transcriptMaxBytesPerJob: Long = 10L * 1024 * 1024,
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
            require(pvddModels.split(',').map(String::trim).filter(String::isNotBlank).toSet() == setOf("mock-model")) {
                "Acceptance PvdD may only allow mock-model."
            }
        }
        if (environment == RuntimeEnvironment.PRODUCTION) {
            val unsafe = listOf(
                productFactoryToken, softwareFactoryToken, hkhAutopilotToken, hkhToken, pvddToken,
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
        val bearerTokens = listOf(productFactoryToken, softwareFactoryToken, hkhAutopilotToken, hkhToken, pvddToken, workerToken, adminToken)
        require(bearerTokens.size == bearerTokens.distinct().size) { "Bearer credentials must be unique." }
        require(leaseSeconds in 30..900)
        require(recoverySeconds in leaseSeconds..86_400)
        require(maxAttempts in 1..10)
        require(defaultPriority in 0..100)
        require(maxOutputAttempts in 1..3)
        require(inputAttachmentMaxBytes in 1..10L * 1024 * 1024)
        require(jobInputAttachmentMaxBytes in inputAttachmentMaxBytes..50L * 1024 * 1024)
        require(transcriptMaxBytesPerJob in 1L * 1024 * 1024..100L * 1024 * 1024)
    }

    fun allowedAdminEmails(): Set<String> = adminEmails.split(',').map(String::trim).map(String::lowercase).filter(String::isNotBlank).toSet()

    fun allowedEnvironmentPrefixes(tenantId: String): Set<String> = when (tenantId) {
        "product-factory" -> productFactoryEnvironmentPrefixes
        "software-factory" -> softwareFactoryEnvironmentPrefixes
        "hkh-autopilot" -> hkhAutopilotEnvironmentPrefixes
        "hkh" -> hkhEnvironmentPrefixes
        "pvdd" -> pvddEnvironmentPrefixes
        else -> ""
    }.split(',').map(String::trim).filter(String::isNotBlank).toSet()

    fun allowedProviders(tenantId: String): Set<String> = when (tenantId) {
        "product-factory" -> productFactoryProviders
        "software-factory" -> softwareFactoryProviders
        "hkh-autopilot" -> hkhAutopilotProviders
        "hkh" -> hkhProviders
        "pvdd" -> pvddProviders
        else -> ""
    }.split(',').map(String::trim).map(String::uppercase).filter(String::isNotBlank).toSet()

    fun modelAllowed(tenantId: String, model: String): Boolean {
        val configured = when (tenantId) {
            "product-factory" -> productFactoryModels
            "software-factory" -> softwareFactoryModels
            "hkh-autopilot" -> hkhAutopilotModels
            "hkh" -> hkhModels
            "pvdd" -> pvddModels
            else -> ""
        }.split(',').map(String::trim).filter(String::isNotBlank).toSet()
        return "*" in configured || model in configured
    }
}
