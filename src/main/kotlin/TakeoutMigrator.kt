package wtf.jobin

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.File
import java.security.MessageDigest
import java.sql.DriverManager
import java.time.Instant

// ==========================================
// DATA MODELS & JSON SCHEMAS
// ==========================================

@Serializable
data class TakeoutSidecar(
    val title: String? = null,
    val description: String? = null,
    val photoTakenTime: PhotoTakenTime? = null,
    val geoData: GeoData? = null
)

@Serializable
data class PhotoTakenTime(
    val timestamp: String,
    val formatted: String? = null
)

@Serializable
data class GeoData(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0
)

data class TakeoutRecord(
    val filePath: String,
    val fileName: String,
    val sha256: String,
    val albumName: String?,
    val jsonSidecarPath: String?
)

data class TakeoutMetadata(
    val timestamp: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
)

@Serializable
data class ImmichAssetResponse(
    val id: String,
    val originalFileName: String,
    val checksum: String? = null
)

@Serializable
data class ImmichAlbumResponse(
    val id: String,
    val albumName: String
)