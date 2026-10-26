package wtf.jobin

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import java.security.MessageDigest
import kotlin.io.path.extension

class TakeoutScanner(private val rootPath: String, private val db: MigrationStateDb) {
    private val fileSystem = FileSystem.SYSTEM

    fun scan() {
        println("Starting Okio directory traversal on: $rootPath")
        val root = rootPath.toPath()

        var discoveredCount = 0

        fileSystem.listRecursively(root).forEach { path ->
            if (fileSystem.metadata(path).isRegularFile) {
                val fileName = path.name
                if (fileName.endsWith(".json", ignoreCase = true)) return@forEach

                val parentDir = path.parent
                val nameWithoutExt = fileName.substringBeforeLast('.')
                val ext = fileName.substringAfterLast('.', "")

                // Google Takeout truncates filenames to 51 chars for sidecars
                val truncatedFileName = if (fileName.length > 51) fileName.take(51) else fileName
                val truncatedBaseName = if (nameWithoutExt.length > 51) nameWithoutExt.take(51) else nameWithoutExt

                val potentialJsonNames = listOf(
                    "$fileName.json",
                    "$nameWithoutExt.json",
                    "$fileName.supplemental-metadata.json",
                    "$nameWithoutExt.supplemental-metadata.json",
                    "$truncatedFileName.json",
                    "$truncatedBaseName.json",
                    "$truncatedFileName.supplemental-metadata.json",
                    "$truncatedBaseName.supplemental-metadata.json",
                    if (ext.isNotEmpty()) "$truncatedBaseName.$ext.json" else null,
                    if (ext.isNotEmpty()) "$truncatedBaseName.$ext.supplemental-metadata.json" else null
                ).filterNotNull().distinct()

                var jsonPath: String? = null
                if (parentDir != null) {
                    for (jsonCandidate in potentialJsonNames) {
                        val candidatePath = parentDir.resolve(jsonCandidate)
                        if (fileSystem.exists(candidatePath)) {
                            jsonPath = candidatePath.toString()
                            break
                        }
                    }
                }

                val sha256 = computeSha256(path)
                val size = fileSystem.metadata(path).size ?: 0L
                val albumName = parentDir?.name?.takeIf { it != "Takeout" && it != "Google Photos" && !it.isBlank() }

                db.registerLocalFile(
                    path = path.toString(),
                    name = fileName,
                    sha256 = sha256,
                    size = size,
                    album = albumName,
                    jsonPath = jsonPath
                )

                discoveredCount++
                if (discoveredCount % 100 == 0) {
                    print("\r[Scanning] Indexed $discoveredCount media files...")
                }
            }
        }
        println("\nCompleted scanning local files into DuckDB state.")
    }

    private fun computeSha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fileSystem.source(path).use { source ->
            source.buffer().use { buffered ->
                val buffer = okio.Buffer()
                while (buffered.read(buffer, 8192) != -1L) {
                    val bytes = buffer.readByteArray()
                    digest.update(bytes)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val Path.nameWithoutExtension: String
    get() = name.substringBeforeLast(".")