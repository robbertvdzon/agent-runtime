package nl.vdzon.agentruntime.server.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class StaticCacheHeaders : OncePerRequestFilter() {
    private val contentHashedBundle = Regex("^/main\\.[0-9a-f]+\\.js$")

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI.startsWith("/actuator/") || request.requestURI == "/healthz"

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.requestURI
        response.setHeader(
            "Cache-Control",
            if (path.startsWith("/v1/") || path.startsWith("/v2/"))
                "no-store, private"
            else if (path.endsWith("/flutter_service_worker.js"))
                "no-store"
            else if (contentHashedBundle.matches(path))
                "public, max-age=31536000, immutable"
            else
                "no-cache, must-revalidate",
        )
        chain.doFilter(request, response)
    }
}
