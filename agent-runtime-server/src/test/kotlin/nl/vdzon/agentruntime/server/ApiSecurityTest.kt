package nl.vdzon.agentruntime.server

import nl.vdzon.agentruntime.server.config.AdminAuthService
import nl.vdzon.agentruntime.server.config.ApiSecurity
import nl.vdzon.agentruntime.server.config.RuntimeEnvironment
import nl.vdzon.agentruntime.server.config.RuntimeProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ApiSecurityTest {
    @Test
    fun `production hides v2 test control before authentication`() {
        val filter = ApiSecurity(RuntimeProperties(environment = RuntimeEnvironment.PRODUCTION), mock(AdminAuthService::class.java))
        val request = MockHttpServletRequest("GET", "/v2/test-control/mocks")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, mock(jakarta.servlet.FilterChain::class.java))

        assertThat(response.status).isEqualTo(404)
        assertThat(response.contentAsString).contains("NOT_FOUND")
    }
}
