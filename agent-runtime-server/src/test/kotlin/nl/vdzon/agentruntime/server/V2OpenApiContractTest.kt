package nl.vdzon.agentruntime.server

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

class V2OpenApiContractTest {
    @Test fun `published contract parses without duplicate keys and exposes capacity test fields`() {
        val options=LoaderOptions().apply { setAllowDuplicateKeys(false) }
        val spec=javaClass.getResourceAsStream("/openapi/agent-runtime-v2.yaml")!!.use {
            Yaml(SafeConstructor(options)).load<Map<String,Any>>(it)
        }
        val schemas=(spec.getValue("components") as Map<*,*>)["schemas"] as Map<*,*>
        for ((name,property) in mapOf("JobView" to "retryNotBefore", "JobEventView" to "providerLimit",
            "WorkerRegistrationRequest" to "providerConcurrencyLimits", "CreateMockFixtureRequest" to "providerLimits")) {
            val properties=(schemas[name] as Map<*,*>)["properties"] as Map<*,*>
            assertThat(properties.containsKey(property)).describedAs("%s.%s",name,property).isTrue()
        }
        fun checkRefs(node: Any?) {
            when(node) {
                is Map<*,*> -> node.forEach { (key,value) ->
                    if(key=="\$ref") {
                        val resolved=(value as String).removePrefix("#/").split('/').fold(spec as Any?) { current,part -> (current as? Map<*,*>)?.get(part) }
                        assertThat(resolved).describedAs(value).isNotNull()
                    } else checkRefs(value)
                }
                is List<*> -> node.forEach(::checkRefs)
            }
        }
        checkRefs(spec)
    }
}
