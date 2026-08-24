package nl.vdzon.agentruntime.server.config

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.proc.DefaultJWTProcessor
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class GoogleLoginRequest(val idToken: String = "")
data class LoginResponse(val token: String, val username: String)
data class AuthConfigView(val googleClientId: String, val googleEnabled: Boolean)

@Service
class AdminAuthService(private val properties: RuntimeProperties) {
    private val processor by lazy {
        val source = JWKSourceBuilder.create<SecurityContext>(URI.create("https://www.googleapis.com/oauth2/v3/certs").toURL()).build()
        DefaultJWTProcessor<SecurityContext>().apply { jwsKeySelector = JWSVerificationKeySelector(JWSAlgorithm.RS256, source) }
    }

    fun login(idToken: String): LoginResponse {
        if (properties.googleClientId.isBlank()) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val claims = try { processor.process(idToken, null) } catch (_: Exception) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Ongeldig Google ID-token")
        }
        if (properties.googleClientId !in claims.audience.orEmpty() || claims.issuer !in setOf("accounts.google.com", "https://accounts.google.com") || claims.expirationTime?.toInstant()?.isBefore(Instant.now()) != false) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Ongeldige Google tokenclaims")
        }
        val email = claims.getStringClaim("email").orEmpty().trim().lowercase()
        val verified = claims.getBooleanClaim("email_verified") ?: false
        if (!verified || email !in properties.allowedAdminEmails()) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Beheerder niet toegestaan")
        val expires = Instant.now().plusSeconds(30L * 24 * 3600).epochSecond
        return LoginResponse(session(email, expires), email)
    }

    fun verifySession(token: String): String? {
        val raw = runCatching { String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8) }.getOrNull() ?: return null
        val parts = raw.split(':', limit = 3)
        if (parts.size != 3) return null
        val email = parts[0].lowercase()
        val expires = parts[1].toLongOrNull() ?: return null
        val expected = hmac("$email:$expires")
        if (expires < Instant.now().epochSecond || email !in properties.allowedAdminEmails() || !MessageDigest.isEqual(parts[2].toByteArray(), expected.toByteArray())) return null
        return email
    }

    private fun session(email: String, expires: Long): String {
        val value = "$email:$expires"
        return Base64.getUrlEncoder().withoutPadding().encodeToString("$value:${hmac(value)}".toByteArray())
    }

    private fun hmac(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(properties.sessionSigningSecret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

@RestController
@RequestMapping("/v1/auth")
class AdminAuthController(private val properties: RuntimeProperties, private val auth: AdminAuthService) {
    @GetMapping("/config") fun config() = AuthConfigView(properties.googleClientId, properties.googleClientId.isNotBlank())
    @PostMapping("/google") fun google(@RequestBody body: GoogleLoginRequest) = auth.login(body.idToken)
}
