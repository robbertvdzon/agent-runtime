package nl.vdzon.agentruntime.contracts

/** Non-production credentials plus one explicitly approved, application-enforced read-only capability. */
object ExecutionCredentialPolicy {
    private val allowed = Regex("[A-Z][A-Z0-9_]*__(TEST|ACCEPTANCE|PREVIEW)_[A-Z][A-Z0-9_]*")
    private val forbidden = setOf("PROD", "PRD", "PRODUCTION", "KUBECONFIG", "CLUSTER", "SIGNING", "REMEMBER", "PRIVATE", "WORKER", "GITHUB")

    const val PVDD_PRODUCTION_READ_ONLY = "PVDD__PRODUCTION_READ_ONLY_TOKEN"

    fun allows(key: String): Boolean = key == PVDD_PRODUCTION_READ_ONLY || allowsNonProduction(key)

    fun allowsNonProduction(key: String): Boolean = allowed.matches(key) &&
        key.substringAfter("__").split('_').none(forbidden::contains)

    fun requireAllowed(key: String) {
        require(allows(key)) { "Credential is outside the execution allowlist: $key" }
    }
}
