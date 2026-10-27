package wtf.jobin

import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import java.security.MessageDigest
import java.time.Instant

class TakeoutScanner(private val rootPath: String, private val db: MigrationStateDb) {
    private val fileSystem = FileSystem.SYSTEM
    private val jsonParser = Json { ignoreUnknownKeys = true }

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

                // Parse metadata during indexing (Sidecar -> Filename Regex -> Fallback Mod Time)
                val metadata = parseMetadata(jsonPath, path)

                val sha256 = computeSha256(path)
                val size = fileSystem.metadata(path).size ?: 0L
                val albumName = parentDir?.name?.takeIf { it != "Takeout" && it != "Google Photos" && !it.isBlank() }

                // Register file with pre-computed metadata columns in DuckDB
                db.registerLocalFile(
                    path = path.toString(),
                    name = fileName,
                    sha256 = sha256,
                    size = size,
                    album = albumName,
                    jsonPath = jsonPath,
                    fileCreatedAt = metadata.timestamp,
                    latitude = metadata.latitude,
                    longitude = metadata.longitude
                )

                discoveredCount++
                if (discoveredCount % 100 == 0) {
                    print("\r[Scanning] Indexed $discoveredCount media files...")
                }
            }
        }
        println("\nCompleted scanning local files into DuckDB state.")
    }

    private fun parseMetadata(jsonPath: String?, path: Path): TakeoutMetadata {
        // 1. Try reading from JSON sidecar first
        if (jsonPath != null) {
            try {
                val jsonString = fileSystem.read(jsonPath.toPath()) { readUtf8() }
                val sidecar = jsonParser.decodeFromString<TakeoutSidecar>(jsonString)
                val epochSec = sidecar.photoTakenTime?.timestamp?.toLongOrNull()
                if (epochSec != null && epochSec > 0) {
                    val isoTimestamp = Instant.ofEpochSecond(epochSec).toString()
                    return TakeoutMetadata(
                        timestamp = isoTimestamp,
                        latitude = sidecar.geoData?.latitude?.takeIf { it != 0.0 },
                        longitude = sidecar.geoData?.longitude?.takeIf { it != 0.0 }
                    )
                }
            } catch (e: Exception) {
                // Ignore JSON parsing errors and move to filename fallback
            }
        }

        // 2. Fallback: Extract date from filename patterns (e.g., IMG-20160911-WA0003.jpg)
        val fileName = path.name
        val filenameDate = extractDateFromFilename(fileName)
        if (filenameDate != null) {
            return TakeoutMetadata(timestamp = filenameDate)
        }

        // 3. Final Fallback: File system modification time
        val modMillis = fileSystem.metadata(path).lastModifiedAtMillis ?: System.currentTimeMillis()
        val fallbackInstant = Instant.ofEpochMilli(modMillis).toString()
        return TakeoutMetadata(timestamp = fallbackInstant)
    }

    private fun extractDateFromFilename(fileName: String): String? {
        val regex = Regex("(?:20\\d{2})(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])")
        val match = regex.find(fileName)?.value ?: return null

        try {
            val year = match.substring(0, 4).toInt()
            val month = match.substring(4, 6).toInt()
            val day = match.substring(6, 8).toInt()

            return Instant.parse(String.format("%04d-%02d-%02dT12:00:00Z", year, month, day)).toString()
        } catch (e: Exception) {
            return null
        }
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