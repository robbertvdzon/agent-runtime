package nl.vdzon.agentruntime.worker

import nl.vdzon.agentruntime.contracts.v2.HeartbeatResponse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException
import java.time.Duration
import java.time.Instant

class HeartbeatWithGraceTest {
    private val ok = HeartbeatResponse(accepted = true, cancelRequested = false, fenced = false, leaseUntil = null)

    @Test
    fun `een tijdelijke verbindingsstoring wordt overbrugd zonder de poging af te breken`() {
        var attempts = 0
        val slept = mutableListOf<Long>()

        val response = heartbeatWithGrace(Duration.ofMinutes(5), now = { Instant.EPOCH }, sleep = slept::add) {
            attempts++
            if (attempts < 3) throw IOException("/192.168.178.144:56285: GOAWAY received")
            ok
        }

        assertThat(response).isEqualTo(ok)
        assertThat(attempts).isEqualTo(3)
        assertThat(slept).containsExactly(2_000L, 2_000L)
    }

    @Test
    fun `een 503 van de router tijdens een server-herstart telt als storing`() {
        var attempts = 0

        val response = heartbeatWithGrace(Duration.ofMinutes(5), now = { Instant.EPOCH }, sleep = {}) {
            attempts++
            if (attempts == 1) throw RuntimeHttpException(503, "Application is not available")
            ok
        }

        assertThat(response).isEqualTo(ok)
    }

    @Test
    fun `een 4xx-antwoord gaat direct door, de server heeft de poging afgewezen`() {
        assertThatThrownBy {
            heartbeatWithGrace(Duration.ofMinutes(5), now = { Instant.EPOCH }, sleep = { error("mag niet slapen") }) {
                throw RuntimeHttpException(409, "attempt fenced")
            }
        }.isInstanceOf(RuntimeHttpException::class.java)
    }

    @Test
    fun `na de grace-periode wordt de storing alsnog een fout`() {
        var clock = Instant.EPOCH
        var attempts = 0

        assertThatThrownBy {
            heartbeatWithGrace(Duration.ofSeconds(10), now = { clock }, sleep = { clock = clock.plusSeconds(4) }) {
                attempts++
                throw IOException("GOAWAY received")
            }
        }.isInstanceOf(IOException::class.java).hasMessageContaining("Heartbeat unavailable for 10s")
        assertThat(attempts).isEqualTo(4)
    }
}
