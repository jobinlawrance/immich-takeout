package wtf.jobin

import java.sql.DriverManager

class MigrationStateDb(dbPath: String = "migration_state.duckdb") {
    private val connection: java.sql.Connection

    init {
        // Explicitly load class for GraalVM native image safety
        Class.forName("org.duckdb.DuckDBDriver")
        connection = DriverManager.getConnection("jdbc:duckdb:$dbPath")

        connection.createStatement().execute("""
            CREATE TABLE IF NOT EXISTS local_takeout (
                file_path VARCHAR PRIMARY KEY,
                file_name VARCHAR,
                file_created_at VARCHAR,
                latitude DOUBLE,
                longitude DOUBLE,
                sha256 VARCHAR,
                file_size UBIGINT,
                album_name VARCHAR,
                json_sidecar_path VARCHAR,
                status VARCHAR DEFAULT 'PENDING'
            );

            CREATE TABLE IF NOT EXISTS server_immich (
                id VARCHAR PRIMARY KEY,
                checksum VARCHAR,
                original_file_name VARCHAR
            );
        """)
    }

    fun registerLocalFile(
        path: String,
        name: String,
        sha256: String,
        size: Long,
        album: String?,
        jsonPath: String?,
        fileCreatedAt: String?,
        latitude: Double?,
        longitude: Double?
    ) {
        val pstmt = connection.prepareStatement("""
            INSERT OR REPLACE INTO local_takeout 
            (file_path, file_name, file_created_at, latitude, longitude, sha256, file_size, album_name, json_sidecar_path)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)
        pstmt.setString(1, path)
        pstmt.setString(2, name)
        pstmt.setString(3, fileCreatedAt)

        if (latitude != null) pstmt.setDouble(4, latitude) else pstmt.setNull(4, java.sql.Types.DOUBLE)
        if (longitude != null) pstmt.setDouble(5, longitude) else pstmt.setNull(5, java.sql.Types.DOUBLE)

        pstmt.setString(6, sha256)
        pstmt.setLong(7, size)
        pstmt.setString(8, album)
        pstmt.setString(9, jsonPath)
        pstmt.executeUpdate()
    }

    fun registerServerAsset(id: String, checksum: String?, fileName: String) {
        val pstmt = connection.prepareStatement("""
            INSERT OR REPLACE INTO server_immich (id, checksum, original_file_name)
            VALUES (?, ?, ?)
        """)
        pstmt.setString(1, id)
        pstmt.setString(2, checksum ?: "")
        pstmt.setString(3, fileName)
        pstmt.executeUpdate()
    }

    fun updateStatus(sha256: String, status: String) {
        val pstmt = connection.prepareStatement("UPDATE local_takeout SET status = ? WHERE sha256 = ?")
        pstmt.setString(1, status)
        pstmt.setString(2, sha256)
        pstmt.executeUpdate()
    }

    fun getUnsyncedFiles(): List<TakeoutRecord> {
        val query = """
            SELECT l.* FROM local_takeout l
            LEFT JOIN server_immich s ON l.sha256 = s.checksum
            WHERE s.checksum IS NULL AND l.status = 'PENDING'
        """
        val rs = connection.createStatement().executeQuery(query)
        val list = mutableListOf<TakeoutRecord>()
        while (rs.next()) {
            list.add(TakeoutRecord(
                filePath = rs.getString("file_path"),
                fileName = rs.getString("file_name"),
                sha256 = rs.getString("sha256"),
                albumName = rs.getString("album_name"),
                jsonSidecarPath = rs.getString("json_sidecar_path"),
                fileCreatedAt = rs.getString("file_created_at"),
                latitude = rs.getObject("latitude")?.let { (it as Number).toDouble() },
                longitude = rs.getObject("longitude")?.let { (it as Number).toDouble() }
            ))
        }
        return list
    }

    fun close() {
        connection.close()
    }
}