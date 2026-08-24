package nl.vdzon.agentruntime.server

import nl.vdzon.agentruntime.server.config.RuntimeProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(RuntimeProperties::class)
class AgentRuntimeApplication

fun main(args: Array<String>) {
    runApplication<AgentRuntimeApplication>(*args)
}
