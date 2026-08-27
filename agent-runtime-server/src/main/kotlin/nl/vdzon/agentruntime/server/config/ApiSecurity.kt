package nl.vdzon.agentruntime.server.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

enum class PrincipalRole { CONSUMER, WORKER, ADMIN }
data class RequestIdentity(val role: PrincipalRole, val tenantId: String? = null)

@Component
class ApiSecurity(private val properties: RuntimeProperties, private val adminAuth: AdminAuthService) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        val path = request.requestURI
        return (!path.startsWith("/v1/") && !path.startsWith("/actuator/")) ||
            path.startsWith("/actuator/health") || path.startsWith("/v1/auth/")
    }

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.requestURI
        if (!properties.workerApiEnabled && (path == "/v1/workers" || path.startsWith("/v1/workers/"))) {
            response.status = 404
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write("{\"code\":\"NOT_FOUND\",\"message\":\"Not found.\"}")
            return
        }
        val token = request.getHeader("Authorization")?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }
        val identity = when {
            secureEquals(token, properties.productFactoryToken) -> RequestIdentity(PrincipalRole.CONSUMER, "product-factory")
            secureEquals(token, properties.softwareFactoryToken) -> RequestIdentity(PrincipalRole.CONSUMER, "software-factory")
            secureEquals(token, properties.hkhAutopilotToken) -> RequestIdentity(PrincipalRole.CONSUMER, "hkh-autopilot")
            secureEquals(token, properties.hkhToken) -> RequestIdentity(PrincipalRole.CONSUMER, "hkh")
            secureEquals(token, properties.workerToken) -> RequestIdentity(PrincipalRole.WORKER)
            secureEquals(token, properties.adminToken) -> RequestIdentity(PrincipalRole.ADMIN)
            token != null && adminAuth.verifySession(token) != null -> RequestIdentity(PrincipalRole.ADMIN)
            else -> null
        }
        if (identity == null) {
            response.status = 401
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.writer.write("{\"code\":\"UNAUTHORIZED\",\"message\":\"A valid bearer token is required.\"}")
            return
        }
        request.setAttribute(IDENTITY_ATTRIBUTE, identity)
        chain.doFilter(request, response)
    }

    private fun secureEquals(candidate: String?, expected: String): Boolean = candidate != null &&
        MessageDigest.isEqual(candidate.toByteArray(), expected.toByteArray())

    companion object {
        const val IDENTITY_ATTRIBUTE = "agentRuntimeIdentity"
        fun identity(request: HttpServletRequest): RequestIdentity =
            request.getAttribute(IDENTITY_ATTRIBUTE) as? RequestIdentity ?: error("Missing authenticated identity")
    }
}
