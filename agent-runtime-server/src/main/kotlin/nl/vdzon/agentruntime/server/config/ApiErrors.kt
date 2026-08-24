package nl.vdzon.agentruntime.server.config

import nl.vdzon.agentruntime.contracts.ErrorResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException

class ApiException(val code: String, message: String, val status: HttpStatus = HttpStatus.BAD_REQUEST) : RuntimeException(message)

@RestControllerAdvice
class ApiErrors {
    @ExceptionHandler(ApiException::class)
    fun api(error: ApiException) = ResponseEntity.status(error.status).body(ErrorResponse(error.code, error.message.orEmpty()))

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(error: MethodArgumentNotValidException) = ResponseEntity.badRequest().body(
        ErrorResponse("INVALID_REQUEST", error.bindingResult.fieldErrors.joinToString { "${it.field}: ${it.defaultMessage}" })
    )

    @ExceptionHandler(ResponseStatusException::class)
    fun responseStatus(error: ResponseStatusException) = ResponseEntity.status(error.statusCode)
        .body(ErrorResponse("AUTHENTICATION_FAILED", error.reason ?: "Authentication failed."))

    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception): ResponseEntity<ErrorResponse> = ResponseEntity.internalServerError()
        .body(ErrorResponse("INTERNAL_ERROR", "The request could not be processed safely."))
}
