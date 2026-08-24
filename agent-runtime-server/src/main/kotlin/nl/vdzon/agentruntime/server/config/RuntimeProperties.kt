package nl.vdzon.agentruntime.server.config

import jakarta.annotation.PostConstruct
import org.springframework.boot.context.properties.ConfigurationProperties

enum class RuntimeEnvironment { LOCAL, ACCEPTANCE, PRODUCTION }

@ConfigurationProperties("agent-runtime")
data class RuntimeProperties(
    var environment: RuntimeEnvironment = RuntimeEnvironment.LOCAL,
    var productFactoryToken: String = "local-product-factory-token",
    var softwareFactoryToken: String = "local-software-factory-token",
    var workerToken: String = "local-worker-token",
    var adminToken: String = "local-admin-token",
    var googleClientId: String = "",
    var adminEmails: String = "",
    var sessionSigningSecret: String = "local-session-signing-secret",
    var leaseSeconds: Long = 120,
    var recoverySeconds: Long = 1800,
    var artifactMaxBytes: Long = 5L * 1024 * 1024,
    var jobArtifactMaxBytes: Long = 25L * 1024 * 1024,
) {
    @PostConstruct
    fun validate() {
        if (environment == RuntimeEnvironment.PRODUCTION) {
            val unsafe = listOf(productFactoryToken, softwareFactoryToken, workerToken, adminToken, sessionSigningSecret)
                .any { it.isBlank() || it.startsWith("local-") || it.length < 24 }
            require(!unsafe) { "Production requires four non-default tokens of at least 24 characters." }
            require(googleClientId.isNotBlank() && allowedAdminEmails().isNotEmpty()) { "Production requires Google client ID and an administrator email allowlist." }
        }
        require(leaseSeconds in 30..900)
        require(recoverySeconds in leaseSeconds..86_400)
    }

    fun allowedAdminEmails(): Set<String> = adminEmails.split(',').map(String::trim).map(String::lowercase).filter(String::isNotBlank).toSet()
}
