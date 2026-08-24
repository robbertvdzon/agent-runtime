package nl.vdzon.agentruntime.server.config

import nl.vdzon.agentruntime.contracts.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.slf4j.LoggerFactory
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.server.ResponseStatusException

class ApiException(val code: String, message: String, val status: HttpStatus = HttpStatus.BAD_REQUEST) : RuntimeException(message)

@RestControllerAdvice
class ApiErrors {
    private val logger = LoggerFactory.getLogger(ApiErrors::class.java)

    @ExceptionHandler(ApiException::class)
    fun api(error: ApiException) = ResponseEntity.status(error.status).body(ErrorResponse(error.code, error.message.orEmpty()))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(error: MethodArgumentNotValidException) = ResponseEntity.badRequest().body(
        ErrorResponse("INVALID_REQUEST", error.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" })
    )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(error: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> = ResponseEntity.badRequest()
        .body(ErrorResponse("INVALID_REQUEST", "The request body is incomplete or invalid."))

    @ExceptionHandler(ResponseStatusException::class)
    fun responseStatus(error: ResponseStatusException) = ResponseEntity.status(error.statusCode)
        .body(ErrorResponse("AUTHENTICATION_FAILED", error.reason ?: "Authentication failed."))

    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception): ResponseEntity<ErrorResponse> {
        logger.error("Unexpected request failure", error)
        return ResponseEntity.internalServerError()
            .body(ErrorResponse("INTERNAL_ERROR", "The request could not be processed safely."))
    }
}
