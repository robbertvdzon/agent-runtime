package nl.vdzon.agentruntime.server.v2

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import nl.vdzon.agentruntime.contracts.v2.EnvironmentKeyOptionView
import nl.vdzon.agentruntime.contracts.v2.ExecutionMode
import nl.vdzon.agentruntime.contracts.v2.ExecutionOptionView
import nl.vdzon.agentruntime.contracts.v2.ExecutionSelection
import nl.vdzon.agentruntime.contracts.v2.ExecutorCapability
import nl.vdzon.agentruntime.contracts.v2.TaskType
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.RuntimeEnvironment
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class RegisteredV2Worker(
    val executors: Set<ExecutorCapability>,
    val environmentKeys: Set<String>,
    val lastHeartbeatAt: Instant,
)

@Repository
class V2CatalogStore(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    fun workers(): List<RegisteredV2Worker> = jdbc.query("SELECT executors_json,environment_keys_json,last_heartbeat_at FROM runtime_v2_worker") { rs, _ ->
        RegisteredV2Worker(
            mapper.readValue(rs.getString("executors_json"), mapper.typeFactory.constructCollectionType(Set::class.java, ExecutorCapability::class.java)) as Set<ExecutorCapability>,
            mapper.readValue(rs.getString("environment_keys_json"), mapper.typeFactory.constructCollectionType(Set::class.java, String::class.java)) as Set<String>,
            V2JobStore.instant(rs.getObject("last_heartbeat_at")),
        )
    }
}

@Service
class V2CatalogService(private val properties: RuntimeProperties, private val store: V2CatalogStore) {
    fun executions(tenantId: String, taskType: TaskType): List<ExecutionOptionView> {
        val now = Instant.now()
        val onlineSince = now.minusSeconds(properties.recoverySeconds)
        val workers = store.workers()
        val capabilities = workers.flatMap { worker -> worker.executors.map { worker to it } }
            .filter { (_, capability) -> taskType in capability.taskTypes && allowed(tenantId, capability, taskType) }
        val selections = capabilities.map { (_, capability) ->
            ExecutionSelection(capability.vendorId, capability.model, capability.mode)
        }.toMutableSet()
        if (properties.environment != RuntimeEnvironment.PRODUCTION && allowed(tenantId, MOCK_CAPABILITY, taskType)) {
            selections += ExecutionSelection("mock", "mock", ExecutionMode.MOCK)
        }
        return selections.map { selection ->
            val matches = capabilities.filter { (_, capability) ->
                capability.vendorId == selection.vendorId && capability.model == selection.model && capability.mode == selection.mode
            }
            val online = matches.map { it.first }.distinct().count { !it.lastHeartbeatAt.isBefore(onlineSince) }
            val lastSeen = matches.maxOfOrNull { it.first.lastHeartbeatAt } ?: now
            ExecutionOptionView(selection, setOf(taskType), online > 0 || selection.mode == ExecutionMode.MOCK, if (selection.mode == ExecutionMode.MOCK) 1 else online, lastSeen)
        }.sortedWith(compareBy({ it.execution.vendorId }, { it.execution.model }, { it.execution.mode.name }))
    }

    fun environmentKeys(tenantId: String, projectPrefix: String): List<EnvironmentKeyOptionView> {
        val prefix = projectPrefix.trim().uppercase()
        if (!Regex("[A-Z][A-Z0-9_]*").matches(prefix)) {
            throw ApiException("INVALID_PROJECT_PREFIX", "Project prefix is invalid.", HttpStatus.BAD_REQUEST)
        }
        if (prefix !in properties.allowedEnvironmentPrefixes(tenantId)) {
            throw ApiException("ENVIRONMENT_PREFIX_NOT_ALLOWED", "Project prefix is outside the tenant policy.", HttpStatus.FORBIDDEN)
        }
        val onlineSince = Instant.now().minusSeconds(properties.recoverySeconds)
        val workers = store.workers()
        return workers.flatMap { worker -> worker.environmentKeys.map { worker to it } }
            .filter { (_, name) -> name.substringBefore("__") == prefix }
            .groupBy({ it.second }, { it.first })
            .map { (name, matches) ->
                val online = matches.distinct().count { !it.lastHeartbeatAt.isBefore(onlineSince) }
                EnvironmentKeyOptionView(name, prefix, online > 0, online, matches.maxOf { it.lastHeartbeatAt })
            }.sortedBy { it.name }
    }

    private fun allowed(tenantId: String, capability: ExecutorCapability, taskType: TaskType): Boolean {
        val providerName = when (capability.vendorId) {
            "openai" -> "CODEX"
            "anthropic" -> "CLAUDE"
            "mock" -> "MOCKED"
            else -> capability.vendorId.uppercase()
        }
        if (providerName !in properties.allowedProviders(tenantId) || !properties.modelAllowed(tenantId, capability.model)) return false
        if (capability.mode == ExecutionMode.MOCK) return properties.environment != RuntimeEnvironment.PRODUCTION && capability == MOCK_CAPABILITY
        if (capability.mode != ExecutionMode.SUBSCRIPTION) return false
        return capability.vendorId in setOf("openai", "anthropic") && taskType in setOf(TaskType.STRUCTURED_GENERATION, TaskType.REPOSITORY_AGENT)
    }

    companion object {
        private val MOCK_CAPABILITY = ExecutorCapability("mock", "mock", ExecutionMode.MOCK, setOf(TaskType.STRUCTURED_GENERATION))
    }
}

@RestController
@RequestMapping("/v2")
class V2CatalogController(private val catalogs: V2CatalogService) {
    @GetMapping("/execution-options")
    fun executions(@RequestParam taskType: TaskType, request: HttpServletRequest): List<ExecutionOptionView> =
        catalogs.executions(request.consumerTenant(), taskType)

    @GetMapping("/environment-keys")
    fun environmentKeys(@RequestParam("project") projectPrefix: String, request: HttpServletRequest): List<EnvironmentKeyOptionView> =
        catalogs.environmentKeys(request.consumerTenant(), projectPrefix)
}
