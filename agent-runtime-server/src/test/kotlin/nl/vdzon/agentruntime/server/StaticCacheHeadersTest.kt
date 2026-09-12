package nl.vdzon.agentruntime.server

import nl.vdzon.agentruntime.server.config.StaticCacheHeaders
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class StaticCacheHeadersTest {
    private val filter = StaticCacheHeaders()

    @Test
    fun `only content hashed bundle is immutable`() {
        assertThat(cacheControl("/main.0123456789ab.js")).isEqualTo("public, max-age=31536000, immutable")
        assertThat(cacheControl("/main.dart.js")).isEqualTo("no-cache, must-revalidate")
        assertThat(cacheControl("/index.html")).isEqualTo("no-cache, must-revalidate")
        assertThat(cacheControl("/assets/FontManifest.json")).isEqualTo("no-cache, must-revalidate")
    }

    @Test
    fun `api and service worker are never stored`() {
        assertThat(cacheControl("/v2/management/consumers")).isEqualTo("no-store, private")
        assertThat(cacheControl("/v1/auth/config")).isEqualTo("no-store, private")
        assertThat(cacheControl("/flutter_service_worker.js")).isEqualTo("no-store")
    }

    private fun cacheControl(path: String): String? {
        val response = MockHttpServletResponse()
        filter.doFilter(MockHttpServletRequest("GET", path), response, MockFilterChain())
        return response.getHeader("Cache-Control")
    }
}
