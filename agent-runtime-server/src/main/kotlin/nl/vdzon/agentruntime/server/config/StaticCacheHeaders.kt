package nl.vdzon.agentruntime.server.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class StaticCacheHeaders : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI.startsWith("/v1/") || request.requestURI.startsWith("/actuator/") || request.requestURI == "/healthz"

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val path = request.requestURI
        response.setHeader(
            "Cache-Control",
            if (path == "/" || path.endsWith("/index.html") || path.endsWith("/version.json") || path.endsWith(".js"))
                "no-store"
            else "public, max-age=31536000, immutable",
        )
        chain.doFilter(request, response)
    }
}
