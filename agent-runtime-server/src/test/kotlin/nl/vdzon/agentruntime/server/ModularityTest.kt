package nl.vdzon.agentruntime.server

import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTest {
    @Test
    fun `server module boundaries are acyclic`() {
        ApplicationModules.of(AgentRuntimeApplication::class.java).verify()
    }
}
