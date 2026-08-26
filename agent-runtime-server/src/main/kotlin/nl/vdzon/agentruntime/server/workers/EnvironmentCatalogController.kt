package nl.vdzon.agentruntime.server.workers

import jakarta.servlet.http.HttpServletRequest
import nl.vdzon.agentruntime.contracts.EnvironmentKeyView
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.ApiSecurity
import nl.vdzon.agentruntime.server.config.PrincipalRole
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/v1/environment-keys")
class EnvironmentCatalogController(
    private val workers: WorkerStore,
    private val properties: RuntimeProperties,
) {
    @GetMapping
    fun list(@RequestParam(required = false) project: String?, request: HttpServletRequest): List<EnvironmentKeyView> {
        val identity = ApiSecurity.identity(request)
        if (identity.role !in setOf(PrincipalRole.CONSUMER, PrincipalRole.ADMIN)) {
            throw ApiException("FORBIDDEN", "Consumer or administrator required.", HttpStatus.FORBIDDEN)
        }
        val visiblePrefixes = identity.tenantId?.let(properties::allowedEnvironmentPrefixes)
        val views = workers.listWorkers()
        return views.flatMap { worker -> worker.availableEnvironmentKeys.map { it to worker } }
            .filter { (name, _) -> project == null || name.substringBefore("__") == project }
            .filter { (name, _) -> visiblePrefixes == null || name.substringBefore("__") in visiblePrefixes }
            .groupBy({ it.first }, { it.second })
            .map { (name, matching) ->
                val online = matching.count { it.status == "ONLINE" }
                EnvironmentKeyView(
                    name, name.substringBefore("__"), online > 0, online,
                    matching.maxOfOrNull { it.lastHeartbeatAt } ?: Instant.EPOCH,
                )
            }
            .sortedBy { it.name }
    }
}
