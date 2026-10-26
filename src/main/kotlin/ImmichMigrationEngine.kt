package wtf.jobin

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

class ImmichMigrationEngine(
    private val serverUrl: String,
    private val apiKey: String,
    private val db: MigrationStateDb,
    private val concurrency: Int = 4
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
        engine {
            requestTimeout = 60_000 // 60 seconds per file upload
        }
    }

    private fun printProgress(completed: Int, total: Int, currentFile: String) {
        val percentage = (completed * 100) / total
        val truncatedName = if (currentFile.length > 30) "${currentFile.take(27)}..." else currentFile.padEnd(30)
        print("\r[Progress] [$completed/$total] ($percentage%) Uploading: $truncatedName")
        System.out.flush() // Crucial: forces the terminal to render the progress line immediately
        if (completed == total) println()
    }

    private val jsonParser = Json { ignoreUnknownKeys = true }
    private val albumCache = mutableMapOf<String, String>() // Album Name -> Immich ID

    suspend fun execute() = coroutineScope {
        syncServerState()

        val unsynced = db.getUnsyncedFiles()
        println("Found ${unsynced.size} items to upload to Immich.")

        val semaphore = Semaphore(concurrency)

        for (record in unsynced) {
            launch(Dispatchers.IO) {
                semaphore.acquire()
                try {
                    uploadAsset(record)
                } catch (e: Exception) {
                    println("Error uploading ${record.fileName}: ${e.message}")
                } finally {
                    semaphore.release()
                }
            }
        }
    }

    private suspend fun syncServerState() {
        println("Syncing existing assets from Immich server...")
        try {
            // Immich requires search parameters or uses /api/search/metadata or paginated assets.
            // If /api/assets returns a search object or requires query params, catch it gracefully
            // so it doesn't crash the migration.
            val response = client.get("$serverUrl/api/assets") {
                header("x-api-key", apiKey)
            }
            if (response.status.isSuccess()) {
                val assets: List<ImmichAssetResponse> = response.body()
                assets.forEach { asset ->
                    db.registerServerAsset(asset.id, asset.checksum, asset.originalFileName ?: "unknown")
                }
            }

            val albumsResp = client.get("$serverUrl/api/albums") {
                header("x-api-key", apiKey)
            }
            if (albumsResp.status.isSuccess()) {
                val albums: List<ImmichAlbumResponse> = albumsResp.body()
                albums.forEach { albumCache[it.albumName] = it.id }
            }

            println("Server state synced successfully.")
        } catch (e: Exception) {
            println("Notice: Skipping full server state pre-load (${e.message}). Proceeding with upload/duplicate check.")
        }
    }

    private suspend fun uploadAsset(record: TakeoutRecord) {
        val file = File(record.filePath)
        if (!file.exists()) return

        // Skip unsupported file extensions like Motion Photo metadata wrappers (.MP)
        if (record.fileName.endsWith(".MP", ignoreCase = true)) {
            return
        }

        // Parse metadata or fallback to file system modification time to satisfy Immich requirements
        val metadata = parseSidecar(record.jsonSidecarPath, file)

        val response = client.submitFormWithBinaryData(
            url = "$serverUrl/api/assets",
            formData = formData {
                append("assetData", file.readBytes(), Headers.build {
                    append(HttpHeaders.ContentType, getMimeType(record.fileName))
                    append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                })
                append("deviceAssetId", "takeout-${record.sha256}")
                append("deviceId", "kotlin-takeout-migrator")

                // Always provide valid ISO 8601 strings to satisfy Immich validation
                metadata.timestamp?.let {
                    append("fileCreatedAt", it)
                    append("fileModifiedAt", it)
                }

                metadata.latitude?.let { append("latitude", it.toString()) }
                metadata.longitude?.let { append("longitude", it.toString()) }
            }
        ) {
            header("x-api-key", apiKey)
        }

        if (response.status.isSuccess()) {
            val responseBody = response.bodyAsText()
            val createdAsset = jsonParser.decodeFromString<ImmichAssetResponse>(responseBody)

            if (record.albumName != null) {
                assignToAlbum(record.albumName, createdAsset.id)
            }
        } else {
            throw Exception("${response.status} - ${response.bodyAsText()}")
        }
    }

    private fun parseSidecar(jsonPath: String?, fallbackFile: File): TakeoutMetadata {
        if (jsonPath != null) {
            try {
                val jsonString = File(jsonPath).readText()
                val sidecar = jsonParser.decodeFromString<TakeoutSidecar>(jsonString)
                val epochSec = sidecar.photoTakenTime?.timestamp?.toLongOrNull()
                if (epochSec != null && epochSec > 0) {
                    val isoTimestamp = Instant.ofEpochSecond(epochSec).toString()
                    // Uncomment this line temporarily if you want to verify it's working:
                    // println("Found sidecar date for ${fallbackFile.name}: $isoTimestamp")
                    return TakeoutMetadata(
                        timestamp = isoTimestamp,
                        latitude = sidecar.geoData?.latitude?.takeIf { it != 0.0 },
                        longitude = sidecar.geoData?.longitude?.takeIf { it != 0.0 }
                    )
                }
            } catch (e: Exception) {
                println("Failed parsing json for ${fallbackFile.name}: ${e.message}")
            }
        }

        // If it reaches here, no valid JSON sidecar was matched!
        // println("WARNING: No sidecar found for ${fallbackFile.name}, using file mod time.")
        val fallbackInstant = Instant.ofEpochMilli(fallbackFile.lastModified()).toString()
        return TakeoutMetadata(timestamp = fallbackInstant)
    }

    private suspend fun assignToAlbum(albumName: String, assetId: String) {
        val albumId = getOrCreateAlbumId(albumName) ?: return
        client.put("$serverUrl/api/albums/$albumId/assets") {
            header("x-api-key", apiKey)
            contentType(ContentType.Application.Json)
            setBody(mapOf("ids" to listOf(assetId)))
        }
    }

    private suspend fun getOrCreateAlbumId(name: String): String? {
        if (albumCache.containsKey(name)) return albumCache[name]

        try {
            val response = client.post("$serverUrl/api/albums") {
                header("x-api-key", apiKey)
                contentType(ContentType.Application.Json)
                setBody(mapOf("albumName" to name))
            }
            if (response.status.isSuccess()) {
                val album = jsonParser.decodeFromString<ImmichAlbumResponse>(response.bodyAsText())
                albumCache[name] = album.id
                return album.id
            }
        } catch (e: Exception) {
            println("Failed to create/fetch album $name: ${e.message}")
        }
        return null
    }

    private val TakeoutRecord.jsonSidecarState: String? get() = this.jsonSidecarPath

    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".jpg", true) || fileName.endsWith(".jpeg", true) -> "image/jpeg"
            fileName.endsWith(".png", true) -> "image/png"
            fileName.endsWith(".mp4", true) -> "video/mp4"
            fileName.endsWith(".mov", true) -> "video/quicktime"
            else -> "application/octet-stream"
        }
    }

    fun close() {
        client.close()
    }
}

