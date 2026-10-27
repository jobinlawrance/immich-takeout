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

        // Pass 1: Collect first valid timestamp per album from sidecars or filenames
        val albumTimestampCache = mutableMapOf<String, String>()

        println("Pre-scanning albums for fallback timestamps...")
        fileSystem.listRecursively(root).forEach { path ->
            if (fileSystem.metadata(path).isRegularFile) {
                val fileName = path.name
                if (fileName.endsWith(".json", ignoreCase = true)) return@forEach

                val parentDir = path.parent
                val albumName = parentDir?.name?.takeIf { it != "Takeout" && it != "Google Photos" && !it.isBlank() }
                if (albumName != null && !albumTimestampCache.containsKey(albumName)) {
                    val jsonPath = findJsonSidecar(path, parentDir, fileName)
                    val metadata = parseMetadata(jsonPath, path, skipModTime = true)
                    if (metadata.timestamp != null) {
                        albumTimestampCache[albumName] = metadata.timestamp
                    }
                }
            }
        }

        // Pass 2: Full index scan with album fallback
        var discoveredCount = 0
        fileSystem.listRecursively(root).forEach { path ->
            if (fileSystem.metadata(path).isRegularFile) {
                val fileName = path.name
                if (fileName.endsWith(".json", ignoreCase = true)) return@forEach

                val parentDir = path.parent
                val jsonPath = findJsonSidecar(path, parentDir, fileName)

                // Parse metadata, falling back to album cache if needed
                val albumName = parentDir?.name?.takeIf { it != "Takeout" && it != "Google Photos" && !it.isBlank() }
                val metadata = parseMetadata(jsonPath, path, albumFallback = albumName?.let { albumTimestampCache[it] })

                val sha256 = computeSha256(path)
                val size = fileSystem.metadata(path).size ?: 0L

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

    private fun findJsonSidecar(path: Path, parentDir: Path?, fileName: String): String? {
        if (parentDir == null) return null
        val nameWithoutExt = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")

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

        for (jsonCandidate in potentialJsonNames) {
            val candidatePath = parentDir.resolve(jsonCandidate)
            if (fileSystem.exists(candidatePath)) {
                return candidatePath.toString()
            }
        }
        return null
    }

    private fun parseMetadata(jsonPath: String?, path: Path, skipModTime: Boolean = false, albumFallback: String? = null): TakeoutMetadata {
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
                // Ignore JSON parsing errors
            }
        }

        val fileName = path.name

        // 2. Snapchat epoch-millisecond parser
//        if (fileName.startsWith("Snapchat-", ignoreCase = true)) {
//            val snapchatRegex = Regex("Snapchat-(\\d{13})", RegexOption.IGNORE_CASE)
//            val matchResult = snapchatRegex.find(fileName)
//            if (matchResult != null) {
//                val epochMillis = matchResult.groupValues[1].toLongOrNull()
//                if (epochMillis != null && epochMillis > 0) {
//                    return TakeoutMetadata(timestamp = Instant.ofEpochMilli(epochMillis).toString())
//                }
//            }
//        }

        // 3. Fallback: Extract date from filename patterns
        val filenameDate = extractDateFromFilename(fileName)
        if (filenameDate != null) {
            return TakeoutMetadata(timestamp = filenameDate)
        }

        // 4. Fallback to Sibling Album Timestamp if available
        if (albumFallback != null) {
            return TakeoutMetadata(timestamp = albumFallback)
        }

        if (skipModTime) return TakeoutMetadata(timestamp = null)

        // 5. Final Fallback: File system modification time
        val modMillis = fileSystem.metadata(path).lastModifiedAtMillis ?: System.currentTimeMillis()
        val fallbackInstant = Instant.ofEpochMilli(modMillis).toString()
        return TakeoutMetadata(timestamp = fallbackInstant)
    }

    private fun extractDateFromFilename(fileName: String): String? {
        // Format A: Standard compact YYYYMMDD (e.g., PXL_20240324, IMG_20130914)
        extractStandardDate(fileName)?.let { return it }

        // Format B: Hyphenated or underscored YYYY-MM-DD / YYYY_MM_DD (e.g., 2012-04-13)
        extractSeparatedDate(fileName)?.let { return it }

        return null
    }

    private fun extractStandardDate(fileName: String): String? {
        val regex = Regex("(?<!\\d)(20\\d{2})(0[1-9]|1[0-2])([0-3]\\d)(?!\\d)")
        val match = regex.find(fileName) ?: return null
        return parseDateValues(match.groupValues[1], match.groupValues[2], match.groupValues[3])
    }

    private fun extractSeparatedDate(fileName: String): String? {
        val regex = Regex("(?<!\\d)(20\\d{2})[-_](0[1-9]|1[0-2])[-_]([0-3]\\d)(?!\\d)")
        val match = regex.find(fileName) ?: return null
        return parseDateValues(match.groupValues[1], match.groupValues[2], match.groupValues[3])
    }

    private fun parseDateValues(yearStr: String, monthStr: String, dayStr: String): String? {
        return try {
            val year = yearStr.toInt()
            val month = monthStr.toInt()
            val day = dayStr.toInt()

            if (month !in 1..12 || day !in 1..31) return null

            Instant.parse(String.format("%04d-%02d-%02dT12:00:00Z", year, month, day)).toString()
        } catch (e: Exception) {
            null
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