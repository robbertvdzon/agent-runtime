package nl.vdzon.agentruntime.worker

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink

data class RepositoryVerificationConfig(
    val version: Int,
    val commands: List<RepositoryVerificationCommand>,
)

data class RepositoryVerificationCommand(
    val id: String,
    val pathPrefixes: List<String> = emptyList(),
    val agentRunnable: Boolean = true,
    val argv: List<String>,
    val workingDirectory: String = ".",
    val timeoutSeconds: Long,
)

sealed interface VerificationConfigLoad {
    data class Loaded(val config: RepositoryVerificationConfig) : VerificationConfigLoad
    data object Missing : VerificationConfigLoad
    data class Invalid(val reason: String) : VerificationConfigLoad
}

class V2VerificationSupport {
    private val yaml = ObjectMapper(YAMLFactory())
        .registerModule(KotlinModule.Builder().build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    fun load(workspace: Path): VerificationConfigLoad {
        val path = workspace.resolve(".factory/verification.yaml")
        if (!path.exists(LinkOption.NOFOLLOW_LINKS)) return VerificationConfigLoad.Missing
        if (path.isSymbolicLink() || !path.isRegularFile(LinkOption.NOFOLLOW_LINKS)) {
            return VerificationConfigLoad.Invalid(".factory/verification.yaml must be a regular file.")
        }
        if (path.fileSize() > MAX_CONFIG_BYTES) return VerificationConfigLoad.Invalid("Verification configuration exceeds 100 KiB.")
        val config = runCatching { yaml.readValue(path.toFile(), RepositoryVerificationConfig::class.java) }
            .getOrElse { return VerificationConfigLoad.Invalid("Verification configuration is not valid version 1 YAML.") }
        return validate(config, workspace)?.let(VerificationConfigLoad::Invalid) ?: VerificationConfigLoad.Loaded(config)
    }

    fun selectedCommands(config: RepositoryVerificationConfig, changedPaths: Set<String>?): List<RepositoryVerificationCommand> =
        config.commands.filter { command ->
            command.agentRunnable && (changedPaths == null || command.pathPrefixes.isEmpty() || changedPaths.any { changed -> command.pathPrefixes.any { prefix -> matches(changed, prefix) } })
        }

    fun commandWorkingDirectory(workspace: Path, command: RepositoryVerificationCommand): Path {
        val relative = Path.of(command.workingDirectory)
        val candidate = workspace.resolve(relative).normalize()
        if (!candidate.startsWith(workspace.normalize()) || !candidate.isDirectory(LinkOption.NOFOLLOW_LINKS) || candidate.isSymbolicLink()) {
            throw JobFailure("VERIFICATION_CONFIG_INVALID", "Verification workingDirectory is unavailable or unsafe.", false)
        }
        val realWorkspace = workspace.toRealPath()
        val realCandidate = candidate.toRealPath()
        if (!realCandidate.startsWith(realWorkspace)) throw JobFailure("VERIFICATION_CONFIG_INVALID", "Verification workingDirectory escapes the worktree.", false)
        return candidate
    }

    private fun validate(config: RepositoryVerificationConfig, workspace: Path): String? {
        if (config.version != 1) return "Only verification configuration version 1 is supported."
        if (config.commands.isEmpty() || config.commands.size > MAX_COMMANDS) return "Verification configuration must contain 1 to $MAX_COMMANDS commands."
        if (config.commands.map { it.id }.distinct().size != config.commands.size) return "Verification command IDs must be unique."
        config.commands.forEach { command ->
            if (!ID.matches(command.id)) return "Verification command id is invalid."
            if (command.argv.isEmpty() || command.argv.size > MAX_ARGV || command.argv.any { invalidArgument(it) }) return "Verification argv is invalid or too large."
            if (command.timeoutSeconds !in 1..MAX_COMMAND_TIMEOUT_SECONDS) return "Verification timeoutSeconds is outside the allowed range."
            if (!safeRelative(command.workingDirectory)) return "Verification workingDirectory must be a safe relative path."
            if (command.pathPrefixes.size > MAX_PREFIXES || command.pathPrefixes.any { !safeRelative(it.removeSuffix("/")) }) return "Verification pathPrefixes contain an unsafe path."
            runCatching { commandWorkingDirectory(workspace, command) }.getOrElse { return "Verification workingDirectory is unavailable or unsafe." }
        }
        return null
    }

    private fun invalidArgument(value: String): Boolean = value.isBlank() || value.length > MAX_ARGUMENT_LENGTH || value.any { it == '\u0000' || it == '\n' || it == '\r' }

    private fun safeRelative(value: String): Boolean {
        if (value.isBlank() || value.indexOf('\u0000') >= 0) return false
        val path = runCatching { Path.of(value) }.getOrNull() ?: return false
        return !path.isAbsolute && path.none { it.toString() == ".." }
    }

    private fun matches(changed: String, configuredPrefix: String): Boolean {
        val prefix = configuredPrefix.removePrefix("./").removeSuffix("/")
        return prefix.isEmpty() || prefix == "." || changed == prefix || changed.startsWith("$prefix/")
    }

    companion object {
        private const val MAX_CONFIG_BYTES = 100L * 1024
        private const val MAX_COMMANDS = 32
        private const val MAX_ARGV = 128
        private const val MAX_ARGUMENT_LENGTH = 1_000
        private const val MAX_PREFIXES = 100
        private const val MAX_COMMAND_TIMEOUT_SECONDS = 7_200L
        private val ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
    }
}
