package nl.vdzon.agentruntime.contracts

/** Only explicitly provisioned non-production credentials may enter an execution job. */
object ExecutionCredentialPolicy {
    private val allowed = Regex("[A-Z][A-Z0-9_]*__(TEST|ACCEPTANCE|PREVIEW)_[A-Z][A-Z0-9_]*")
    private val forbidden = setOf("PROD", "PRD", "PRODUCTION", "KUBECONFIG", "CLUSTER", "SIGNING", "REMEMBER", "PRIVATE", "WORKER", "GITHUB")

    fun allows(key: String): Boolean = allowed.matches(key) &&
        key.substringAfter("__").split('_').none(forbidden::contains)

    fun requireAllowed(key: String) {
        require(allows(key)) { "Only scoped test/acceptance/preview credentials are allowed: $key" }
    }
}
