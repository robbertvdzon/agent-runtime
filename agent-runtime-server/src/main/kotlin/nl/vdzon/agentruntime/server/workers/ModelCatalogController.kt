package nl.vdzon.agentruntime.server.workers

import jakarta.servlet.http.HttpServletRequest
import nl.vdzon.agentruntime.contracts.ModelCatalogView
import nl.vdzon.agentruntime.contracts.Provider
import nl.vdzon.agentruntime.server.config.ApiException
import nl.vdzon.agentruntime.server.config.ApiSecurity
import nl.vdzon.agentruntime.server.config.PrincipalRole
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/v1/models")
class ModelCatalogController(private val workers: WorkerStore) {
    @GetMapping
    fun list(@RequestParam(required = false) provider: Provider?, request: HttpServletRequest): List<ModelCatalogView> {
        val identity = ApiSecurity.identity(request)
        if (identity.role !in setOf(PrincipalRole.CONSUMER, PrincipalRole.ADMIN)) {
            throw ApiException("FORBIDDEN", "Consumer or administrator required.", HttpStatus.FORBIDDEN)
        }
        val views = workers.listWorkers()
        return views.flatMap { worker -> worker.advertisedModels.flatMap { (prov, models) -> models.map { Triple(prov, it, worker) } } }
            .filter { (prov, _, _) -> provider == null || prov == provider }
            .groupBy({ (prov, name, _) -> prov to name }, { (_, _, worker) -> worker })
            .map { (key, matching) ->
                val online = matching.count { it.status == "ONLINE" }
                ModelCatalogView(
                    key.first, key.second, online > 0, online,
                    matching.maxOfOrNull { it.lastHeartbeatAt } ?: Instant.EPOCH,
                )
            }
            .sortedWith(compareBy({ it.provider.name }, { it.model }))
    }
}
