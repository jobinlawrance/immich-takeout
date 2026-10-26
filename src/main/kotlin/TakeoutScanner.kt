package wtf.jobin

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import java.security.MessageDigest

class TakeoutScanner(private val rootPath: String, private val db: MigrationStateDb) {
    private val fileSystem = FileSystem.SYSTEM

    fun scan() {
        println("Starting Okio directory traversal on: $rootPath")
        val root = rootPath.toPath()

        fileSystem.listRecursively(root).forEach { path ->
            if (fileSystem.metadata(path).isRegularFile) {
                val fileName = path.name
                if (fileName.endsWith(".json", ignoreCase = true)) return@forEach

                val jsonSidecar = path.parent?.resolve("$fileName.json")
                    ?: path.parent?.resolve("${path.nameWithoutExtension}.json")
                val jsonPath = if (jsonSidecar != null && fileSystem.exists(jsonSidecar)) jsonSidecar.toString() else null

                val sha256 = computeSha256(path)
                val size = fileSystem.metadata(path).size ?: 0L
                val albumName = path.parent?.name.takeIf { it != "Takeout" && it != "Google Photos" && !it.isNullOrBlank() }

                db.registerLocalFile(
                    path = path.toString(),
                    name = fileName,
                    sha256 = sha256,
                    size = size,
                    album = albumName,
                    jsonPath = jsonPath
                )
            }
        }
        println("Completed scanning local files into DuckDB state.")
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