package jp.personal.hanshinedge.validation;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONObject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

public final class SyntheticHistoricalDb extends SQLiteOpenHelper {
    public static final int SCHEMA = 2;
    private final Context context;
    private final String filename;
    private final long quotaBytes;

    public SyntheticHistoricalDb(Context context, String filename, long quotaBytes) {
        super(
            context,
            filename,
            null,
            SCHEMA,
            db -> { throw new IllegalStateException("SYNTHETIC_DB_CORRUPT_PRESERVED"); }
        );
        this.context = context.getApplicationContext();
        this.filename = filename;
        this.quotaBytes = quotaBytes;
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
    }

    static void createV1(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "race_id TEXT NOT NULL," +
            "version INTEGER NOT NULL," +
            "payload TEXT NOT NULL," +
            "imported_at TEXT NOT NULL," +
            "UNIQUE(race_id,version))");
        db.execSQL("CREATE TABLE participants(" +
            "record_id INTEGER NOT NULL REFERENCES records(id)," +
            "runner_id TEXT NOT NULL," +
            "runner_no INTEGER NOT NULL," +
            "PRIMARY KEY(record_id,runner_no))");
        db.execSQL("CREATE TABLE batches(" +
            "batch_id TEXT PRIMARY KEY," +
            "previous_cursor INTEGER NOT NULL," +
            "next_cursor INTEGER NOT NULL," +
            "imported_at TEXT NOT NULL)");
        db.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY,v TEXT NOT NULL)");
        db.execSQL("CREATE TABLE odds(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "race_id TEXT NOT NULL," +
            "runner_no INTEGER NOT NULL," +
            "odds_kind TEXT NOT NULL," +
            "UNIQUE(race_id,runner_no,odds_kind))");
    }

    static void upgradeV2(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX records_lookup ON records(race_id,version)");
        db.execSQL("CREATE INDEX participants_runner ON participants(runner_id,record_id)");
        db.execSQL("CREATE TABLE prospective_events(" +
            "id INTEGER PRIMARY KEY AUTOINCREMENT," +
            "event_id TEXT UNIQUE NOT NULL," +
            "payload TEXT NOT NULL," +
            "recorded_at TEXT NOT NULL)");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createV1(db);
        upgradeV2(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion != 1 || newVersion != 2) {
            throw new IllegalStateException("UNSUPPORTED_MIGRATION");
        }
        upgradeV2(db);
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("DOWNGRADE_BLOCKED");
    }

    private static void require(boolean ok, String reason) {
        if (!ok) throw new IllegalArgumentException(reason);
    }

    private static String scalar(SQLiteDatabase db, String sql, String... args) {
        try (Cursor c = db.rawQuery(sql, args)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private String meta(SQLiteDatabase db, String key, String fallback) {
        String value = scalar(db, "SELECT v FROM meta WHERE k=?", key);
        return value == null ? fallback : value;
    }

    private static int runnersForRace(int oneBasedRaceIndex) {
        return oneBasedRaceIndex <= 193 ? 14 : 13;
    }

    public synchronized long sizeBytes() {
        File db = context.getDatabasePath(filename);
        return db.length() + new File(db + "-wal").length() + new File(db + "-shm").length();
    }

    private void integrity(SQLiteDatabase db) {
        require("ok".equals(scalar(db, "PRAGMA quick_check")), "INTEGRITY_FAILED");
    }

    public synchronized JSONObject status() throws Exception {
        SQLiteDatabase db = getReadableDatabase();
        integrity(db);
        return new JSONObject()
            .put("schema_version", db.getVersion())
            .put("races", Long.parseLong(scalar(db, "SELECT COUNT(DISTINCT race_id) FROM records")))
            .put("versions", Long.parseLong(scalar(db, "SELECT COUNT(*) FROM records")))
            .put("odds", Long.parseLong(scalar(db, "SELECT COUNT(*) FROM odds")))
            .put("participants_v0", Long.parseLong(scalar(
                db,
                "SELECT COUNT(*) FROM participants p JOIN records r ON p.record_id=r.id WHERE r.version=0"
            )))
            .put("cursor", Integer.parseInt(meta(db, "cursor", "0")))
            .put("bytes", sizeBytes())
            .put("quota_bytes", quotaBytes)
            .put("process_death_recovery", "PROCESS_DEATH_RECOVERY_NOT_FULLY_SIMULATED");
    }

    public synchronized JSONObject importSynthetic(
        String batchId,
        int previousCursor,
        int nextCursor,
        int recordCount,
        int oddsCount,
        boolean corrections,
        int failAtRecord
    ) throws Exception {
        require(batchId.matches("[A-Z0-9_-]{3,64}"), "BATCH_ID");
        require(recordCount >= 0 && recordCount <= 308, "RECORD_COUNT");
        require(oddsCount >= 0 && oddsCount <= 16, "ODDS_COUNT");

        SQLiteDatabase db = getWritableDatabase();
        integrity(db);

        String prior = scalar(db, "SELECT batch_id FROM batches WHERE batch_id=?", batchId);
        if (prior != null) {
            return status()
                .put("result", "DUPLICATE")
                .put("inserted_records", 0)
                .put("corrected_records", 0);
        }

        int currentCursor = Integer.parseInt(meta(db, "cursor", "0"));
        require(previousCursor == currentCursor, "CURSOR_GAP");
        require(nextCursor == previousCursor + recordCount, "CURSOR_RECORD_COUNT");

        long estimated = 65536L + recordCount * 4096L;
        if (!corrections) estimated += 4197L * 96L + oddsCount * 256L;
        require(sizeBytes() + estimated <= quotaBytes, "STORAGE_QUOTA");

        int inserted = 0;
        int corrected = 0;
        String importedAt = Instant.now().toString();

        db.beginTransaction();
        try {
            for (int i = 1; i <= recordCount; i++) {
                String raceId = String.format(java.util.Locale.ROOT, "SYN-R-%04d", i);
                int version = corrections ? 1 : 0;

                String existing = scalar(
                    db,
                    "SELECT payload FROM records WHERE race_id=? AND version=?",
                    raceId,
                    Integer.toString(version)
                );
                require(existing == null, "VERSION_CONFLICT");

                String last = scalar(db, "SELECT MAX(version) FROM records WHERE race_id=?", raceId);
                if (corrections) {
                    require(last != null && Integer.parseInt(last) == 0, "CORRECTION_BASE_MISSING");
                } else {
                    require(last == null, "INITIAL_ALREADY_EXISTS");
                }

                String payload = new JSONObject()
                    .put("race_id", raceId)
                    .put("version", version)
                    .put("synthetic", true)
                    .put("post_race_odds_available", i <= 16)
                    .toString();

                db.execSQL(
                    "INSERT INTO records(race_id,version,payload,imported_at) VALUES(?,?,?,?)",
                    new Object[]{raceId, version, payload, importedAt}
                );

                long recordId = Long.parseLong(scalar(db, "SELECT last_insert_rowid()"));
                int runners = runnersForRace(i);
                for (int r = 1; r <= runners; r++) {
                    String runnerId = String.format(
                        java.util.Locale.ROOT,
                        "SYN-H-%04d-%02d",
                        i,
                        r
                    );
                    db.execSQL(
                        "INSERT INTO participants(record_id,runner_id,runner_no) VALUES(?,?,?)",
                        new Object[]{recordId, runnerId, r}
                    );
                }

                if (corrections) corrected++; else inserted++;

                if (failAtRecord > 0 && i == failAtRecord) {
                    throw new IllegalArgumentException("RECORD_CHECKSUM");
                }
            }

            for (int i = 1; i <= oddsCount; i++) {
                String raceId = String.format(java.util.Locale.ROOT, "SYN-R-%04d", i);
                require(
                    scalar(db, "SELECT 1 FROM records WHERE race_id=?", raceId) != null,
                    "ODDS_RACE_MISSING"
                );
                db.execSQL(
                    "INSERT OR IGNORE INTO odds(race_id,runner_no,odds_kind) VALUES(?,?,?)",
                    new Object[]{raceId, 1, "POST_RACE_REPORTED"}
                );
            }

            db.execSQL(
                "INSERT INTO batches(batch_id,previous_cursor,next_cursor,imported_at) VALUES(?,?,?,?)",
                new Object[]{batchId, previousCursor, nextCursor, importedAt}
            );
            db.execSQL(
                "INSERT OR REPLACE INTO meta(k,v) VALUES('cursor',?)",
                new Object[]{Integer.toString(nextCursor)}
            );

            require(sizeBytes() <= quotaBytes, "STORAGE_QUOTA");
            integrity(db);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        return status()
            .put("result", "IMPORTED")
            .put("inserted_records", inserted)
            .put("corrected_records", corrected);
    }

    public synchronized JSONObject lookupForPrediction(String raceId) throws Exception {
        SQLiteDatabase db = getReadableDatabase();
        integrity(db);
        String payload = scalar(
            db,
            "SELECT payload FROM records WHERE race_id=? ORDER BY version DESC LIMIT 1",
            raceId
        );
        require(payload != null, "RACE_NOT_FOUND");
        JSONObject result = new JSONObject(payload);
        result.remove("post_race_odds_available");
        result.put("odds_excluded_from_prediction_lookup", true);
        return result;
    }

    public synchronized JSONObject backup() throws Exception {
        SQLiteDatabase db = getWritableDatabase();
        integrity(db);
        try (Cursor c = db.rawQuery("PRAGMA wal_checkpoint(FULL)", null)) {
            require(c.moveToFirst() && c.getInt(0) == 0, "BACKUP_CHECKPOINT_BUSY");
        }
        close();

        File source = context.getDatabasePath(filename);
        File target = new File(source + ".backup");
        File temp = new File(source + ".backup.tmp");
        Files.copy(source.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        validateFile(temp);
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        );
        return new JSONObject().put("result", "BACKED_UP").put("bytes", target.length());
    }

    private static void validateFile(File file) {
        SQLiteDatabase db = SQLiteDatabase.openDatabase(
            file.toString(),
            null,
            SQLiteDatabase.OPEN_READONLY,
            corrupt -> { throw new IllegalStateException("BACKUP_CORRUPT"); }
        );
        try {
            require(db.getVersion() == SCHEMA, "RESTORE_SCHEMA");
            require("ok".equals(scalar(db, "PRAGMA integrity_check")), "RESTORE_CORRUPT");
            for (String table : new String[]{
                "records", "participants", "batches", "meta", "odds", "prospective_events"
            }) {
                require(
                    scalar(db, "SELECT name FROM sqlite_master WHERE name=?", table) != null,
                    "RESTORE_TABLES"
                );
            }
        } finally {
            db.close();
        }
    }

    public synchronized JSONObject restore() throws Exception {
        File target = context.getDatabasePath(filename);
        File backup = new File(target + ".backup");
        validateFile(backup);
        require(backup.length() < quotaBytes, "STORAGE_QUOTA");

        File temp = new File(target + ".restore.tmp");
        Files.copy(backup.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING);
        validateFile(temp);

        close();
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
        );
        Files.deleteIfExists(new File(target + "-wal").toPath());
        Files.deleteIfExists(new File(target + "-shm").toPath());
        return status().put("result", "RESTORED");
    }
}
