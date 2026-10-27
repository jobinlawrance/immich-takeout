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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
            requestTimeout = 60_000
        }
    }

    private val jsonParser = Json { ignoreUnknownKeys = true }
    private val albumCache = mutableMapOf<String, String>()

    private val startTime = System.currentTimeMillis()
    private val totalBytesUploaded = AtomicLong(0L)

    private fun printProgress(completed: Int, total: Int, currentFile: String, fileSize: Long) {
        val currentBytes = totalBytesUploaded.addAndGet(fileSize)
        val elapsedSecs = (System.currentTimeMillis() - startTime) / 1000.0
        val mbps = if (elapsedSecs > 0) (currentBytes.toDouble() / (1024 * 1024)) / elapsedSecs else 0.0

        val percentage = if (total > 0) (completed * 100) / total else 0
        val truncatedName = if (currentFile.length > 28) "${currentFile.take(25)}..." else currentFile.padEnd(28)

        print(String.format("\r[Progress] [%d/%d] (%d%%) | %.2f MB/s | %s", completed, total, percentage, mbps, truncatedName))
        System.out.flush()
        if (completed == total) {
            println()
        }
    }

    suspend fun execute() = coroutineScope {
        syncServerState()

        val unsynced = db.getUnsyncedFiles()
        val totalItems = unsynced.size
        println("Found $totalItems items to upload to Immich.")

//        val semaphore = Semaphore(concurrency)
//        val completedCount = AtomicInteger(0)
//
//        unsynced.forEach { record ->
//            launch(Dispatchers.IO) {
//                semaphore.acquire()
//                val file = File(record.filePath)
//                val fileSize = if (file.exists()) file.length() else 0L
//                try {
//                    uploadAsset(record, file)
//                    db.updateStatus(record.sha256, "COMPLETED")
//                } catch (e: Exception) {
//                    db.updateStatus(record.sha256, "FAILED")
//                    println("\nError uploading ${record.fileName}: ${e.message}")
//                } finally {
//                    val currentCount = completedCount.incrementAndGet()
//                    printProgress(currentCount, totalItems, record.fileName, fileSize)
//                    semaphore.release()
//                }
//            }
//        }
    }

    private suspend fun syncServerState() {
        println("Syncing existing assets from Immich server...")
        try {
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

    private suspend fun uploadAsset(record: TakeoutRecord, file: File) {
        if (!file.exists()) return

        if (record.fileName.endsWith(".MP", ignoreCase = true)) {
            return
        }

        // Directly reading pre-computed metadata columns from DuckDB record
        val response = client.submitFormWithBinaryData(
            url = "$serverUrl/api/assets",
            formData = formData {
                append("assetData", file.readBytes(), Headers.build {
                    append(HttpHeaders.ContentType, getMimeType(record.fileName))
                    append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                })
                append("deviceAssetId", "takeout-${record.sha256}")
                append("deviceId", "kotlin-takeout-migrator")

                // Read timestamp directly from record column populated during indexing
                record.fileCreatedAt?.let {
                    append("fileCreatedAt", it)
                    append("fileModifiedAt", it)
                }

                record.latitude?.let { append("latitude", it.toString()) }
                record.longitude?.let { append("longitude", it.toString()) }
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
            throw Exception("${response.status}: ${response.bodyAsText()}")
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