package nl.vdzon.agentruntime.worker

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** Never mount personal history, config, hooks, MCP credentials or instruction directories. */
fun isolateProviderCredentials(source: Path, task: Path, engine: String): Path {
    val filename = if (engine == "CODEX") "auth.json" else ".credentials.json"
    require(!Files.isSymbolicLink(source)) { "Provider credential directory must not be a symlink" }
    val input = source.resolve(filename)
    require(Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS)) { "Provider authentication file is unavailable" }
    val directory = task.resolve("provider-auth")
    Files.createDirectories(directory)
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
    val target = directory.resolve(filename)
    require(!Files.isSymbolicLink(target)) { "Provider authentication destination must not be a symlink" }
    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
    Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))
    return directory.toAbsolutePath()
}
