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
            val response: List<ImmichAssetResponse> = client.get("$serverUrl/api/assets") {
                header("x-api-key", apiKey)
            }.body()

            response.forEach { asset ->
                db.registerServerAsset(asset.id, asset.checksum, asset.originalFileName)
            }

            // Populate existing server albums for caching
            val albums: List<ImmichAlbumResponse> = client.get("$serverUrl/api/albums") {
                header("x-api-key", apiKey)
            }.body()
            albums.forEach { albumCache[it.albumName] = it.id }

            println("Server state synced successfully.")
        } catch (e: Exception) {
            println("Warning: Failed to fetch full server state (${e.message}). Proceeding with upload.")
        }
    }

    private suspend fun uploadAsset(record: TakeoutRecord) {
        val file = File(record.filePath)
        if (!file.exists()) return

        val metadata = parseSidecar(record.jsonSidecarState)

        val response = client.submitFormWithBinaryData(
            url = "$serverUrl/api/assets",
            formData = formData {
                append("assetData", file.readBytes(), Headers.build {
                    append(HttpHeaders.ContentType, getMimeType(record.fileName))
                    append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                })
                append("deviceAssetId", "takeout-${record.sha256}")
                append("deviceId", "kotlin-takeout-migrator")
                metadata.timestamp?.let { append("fileCreatedAt", it) }
                metadata.latitude?.let { append("latitude", it.toString()) }
                metadata.longitude?.let { append("longitude", it.toString()) }
            }
        ) {
            header("x-api-key", apiKey)
        }

        if (response.status.isSuccess()) {
            val responseBody = response.bodyAsText()
            val createdAsset = jsonParser.decodeFromString<ImmichAssetResponse>(responseBody)

            // Assign to Album if category exists
            if (record.albumName != null) {
                assignToAlbum(record.albumName, createdAsset.id)
            }
            println("Successfully migrated: ${record.fileName}")
        } else {
            println("Failed upload for ${record.fileName}: ${response.status} - ${response.bodyAsText()}")
        }
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

    private fun parseSidecar(jsonPath: String?): TakeoutMetadata {
        if (jsonPath == null) return TakeoutMetadata()
        return try {
            val jsonString = File(jsonPath).readText()
            val sidecar = jsonParser.decodeFromString<TakeoutSidecar>(jsonString)
            val isoTimestamp = sidecar.photoTakenTime?.timestamp?.toLongOrNull()?.let {
                Instant.ofEpochSecond(it).toString()
            }
            TakeoutMetadata(
                timestamp = isoTimestamp,
                latitude = sidecar.geoData?.latitude?.takeIf { it != 0.0 },
                longitude = sidecar.geoData?.longitude?.takeIf { it != 0.0 }
            )
        } catch (e: Exception) {
            TakeoutMetadata()
        }
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

