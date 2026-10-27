package wtf.jobin

suspend fun main(args: Array<String>) {
    val takeoutDir = args.getOrNull(0) ?: "./Takeout"
    val immichUrl = args.getOrNull(1) ?: "http://localhost:2283"
    val apiKey = args.getOrNull(2) ?: "YOUR_IMMICH_API_KEY"

    println("=== Google Takeout to Immich Migrator ===")
    val dbPath = "/root/jobin-dump/takeout_migration.duckdb"

    val db = MigrationStateDb("takeout_migration.duckdb")

    // Step 1: Scan local files using Okio and save state to DuckDB
    val scanner = TakeoutScanner(takeoutDir, db)
    scanner.scan()

    // Step 2: Run async migration engine with DuckDB diffing
    val engine = ImmichMigrationEngine(immichUrl, apiKey, db, concurrency = 6)
//    engine.execute()

    engine.close()
    db.close()
    println("=== Migration Pipeline Completed ===")
}
