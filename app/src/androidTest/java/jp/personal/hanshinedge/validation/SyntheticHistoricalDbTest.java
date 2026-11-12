package jp.personal.hanshinedge.validation;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SyntheticHistoricalDbTest {
    private static final long LARGE_QUOTA = 64L * 1024L * 1024L;

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private SyntheticHistoricalDb fresh(String name, long quota) {
        context().deleteDatabase(name);
        new File(context().getDatabasePath(name) + ".backup").delete();
        new File(context().getDatabasePath(name) + ".backup.tmp").delete();
        new File(context().getDatabasePath(name) + ".restore.tmp").delete();
        return new SyntheticHistoricalDb(context(), name, quota);
    }

    private interface Checked {
        void run() throws Exception;
    }

    private void fails(String expected, Checked checked) throws Exception {
        try {
            checked.run();
            fail("expected " + expected);
        } catch (Exception e) {
            assertTrue(e.toString(), e.toString().contains(expected));
        }
    }

    @Test
    public void importDuplicateCorrectionRollbackReopenBackupAndOddsExclusion() throws Exception {
        SyntheticHistoricalDb db = fresh("synthetic-v43.db", LARGE_QUOTA);

        JSONObject initial = db.importSynthetic(
            "INITIAL_308",
            0,
            308,
            308,
            16,
            false,
            0
        );
        assertEquals(308, initial.getInt("inserted_records"));
        assertEquals("DUPLICATE", db.importSynthetic(
            "INITIAL_308", 0, 308, 308, 16, false, 0
        ).getString("result"));

        JSONObject baseline = db.status();
        assertEquals(308, baseline.getInt("races"));
        assertEquals(308, baseline.getInt("versions"));
        assertEquals(16, baseline.getInt("odds"));
        assertEquals(4197, baseline.getInt("participants_v0"));
        assertEquals(308, baseline.getInt("cursor"));

        fails("RECORD_CHECKSUM", () -> db.importSynthetic(
            "CORRECTION_FAIL",
            308,
            384,
            76,
            0,
            true,
            5
        ));
        assertFalse(db.getWritableDatabase().inTransaction());
        db.close();

        JSONObject unchanged = db.status();
        assertEquals(308, unchanged.getInt("races"));
        assertEquals(308, unchanged.getInt("versions"));
        assertEquals(16, unchanged.getInt("odds"));
        assertEquals(4197, unchanged.getInt("participants_v0"));
        assertEquals(308, unchanged.getInt("cursor"));

        JSONObject correction = db.importSynthetic(
            "CORRECTION_76",
            308,
            384,
            76,
            0,
            true,
            0
        );
        assertEquals(76, correction.getInt("corrected_records"));
        assertEquals("DUPLICATE", db.importSynthetic(
            "CORRECTION_76", 308, 384, 76, 0, true, 0
        ).getString("result"));

        JSONObject ready = db.status();
        assertEquals(308, ready.getInt("races"));
        assertEquals(384, ready.getInt("versions"));
        assertEquals(16, ready.getInt("odds"));
        assertEquals(4197, ready.getInt("participants_v0"));
        assertEquals(384, ready.getInt("cursor"));
        assertEquals(
            "PROCESS_DEATH_RECOVERY_NOT_FULLY_SIMULATED",
            ready.getString("process_death_recovery")
        );

        JSONObject prediction = db.lookupForPrediction("SYN-R-0001");
        assertTrue(prediction.getBoolean("odds_excluded_from_prediction_lookup"));
        assertFalse(prediction.has("post_race_odds_available"));
        assertFalse(prediction.has("odds"));

        db.backup();
        JSONObject restored = db.restore();
        assertEquals(308, restored.getInt("races"));
        assertEquals(384, restored.getInt("versions"));
        assertEquals(16, restored.getInt("odds"));

        File reportDir = new File(context().getFilesDir(), "validation");
        assertTrue(reportDir.isDirectory() || reportDir.mkdirs());
        JSONObject report = db.status()
            .put("test", "SYNTHETIC_SCALE_IMPORT_REOPEN_PASS")
            .put("synthetic_races", 308)
            .put("synthetic_runners", 4197)
            .put("normalization_corrections", 76)
            .put("post_race_odds", 16)
            .put("historical_odds_T5_exclusion", "PASS")
            .put("failed_import_rollback_reopen", "PASS")
            .put("duplicate_delta", "PASS");
        Files.write(
            new File(reportDir, "public-harness-report.json").toPath(),
            report.toString(2).getBytes(StandardCharsets.UTF_8)
        );
        db.close();
    }

    @Test
    public void atomicRollbackInterruptedRecoveryAndQuota() throws Exception {
        SyntheticHistoricalDb db = fresh("atomic-test.db", LARGE_QUOTA);
        db.importSynthetic("INITIAL_308", 0, 308, 308, 16, false, 0);
        db.close();

        SQLiteDatabase raw = SQLiteDatabase.openDatabase(
            context().getDatabasePath("atomic-test.db").toString(),
            null,
            SQLiteDatabase.OPEN_READWRITE
        );
        try {
            raw.beginTransaction();
            try {
                raw.execSQL("INSERT INTO meta(k,v) VALUES('uncommitted','not-visible')");
                try (android.database.Cursor c = raw.rawQuery(
                    "SELECT v FROM meta WHERE k='uncommitted'",
                    null
                )) {
                    assertTrue(c.moveToFirst());
                    assertEquals("not-visible", c.getString(0));
                }
            } finally {
                raw.endTransaction();
            }
            assertFalse(raw.inTransaction());
        } finally {
            raw.close();
        }

        JSONObject reopened = db.status();
        assertEquals(308, reopened.getInt("races"));
        assertEquals(308, reopened.getInt("versions"));
        assertEquals(308, reopened.getInt("cursor"));
        try (android.database.Cursor c = db.getReadableDatabase().rawQuery(
            "SELECT v FROM meta WHERE k='uncommitted'",
            null
        )) {
            assertEquals(0, c.getCount());
        }
        db.close();

        SyntheticHistoricalDb tiny = fresh("quota-test.db", 65536L);
        fails("STORAGE_QUOTA", () -> tiny.importSynthetic(
            "INITIAL_308", 0, 308, 308, 16, false, 0
        ));
        assertFalse(tiny.getWritableDatabase().inTransaction());
        tiny.close();

        JSONObject afterQuota = tiny.status();
        assertEquals(0, afterQuota.getInt("races"));
        assertEquals(0, afterQuota.getInt("versions"));
        assertEquals(0, afterQuota.getInt("odds"));
        assertEquals(0, afterQuota.getInt("cursor"));
        tiny.close();
    }

    @Test
    public void migrationAndCorruptionPreservation() throws Exception {
        context().deleteDatabase("migration-test.db");
        SQLiteDatabase raw = context().openOrCreateDatabase("migration-test.db", 0, null);
        SyntheticHistoricalDb.createV1(raw);
        raw.execSQL("INSERT INTO meta(k,v) VALUES('legacy-proof','preserved')");
        raw.setVersion(1);
        raw.close();

        SyntheticHistoricalDb migrated = new SyntheticHistoricalDb(
            context(),
            "migration-test.db",
            LARGE_QUOTA
        );
        assertEquals(2, migrated.status().getInt("schema_version"));
        try (android.database.Cursor c = migrated.getReadableDatabase().rawQuery(
            "SELECT v FROM meta WHERE k='legacy-proof'",
            null
        )) {
            assertTrue(c.moveToFirst());
            assertEquals("preserved", c.getString(0));
        }
        migrated.close();

        SyntheticHistoricalDb corrupt = fresh("corrupt-test.db", LARGE_QUOTA);
        corrupt.status();
        corrupt.close();

        File file = context().getDatabasePath("corrupt-test.db");
        byte[] evidence = "invalid SQLite evidence".getBytes(StandardCharsets.UTF_8);
        Files.write(file.toPath(), evidence);

        try {
            corrupt.status();
            fail("corruption must block");
        } catch (Exception expected) {
            assertTrue(file.exists());
            assertArrayEquals(evidence, Files.readAllBytes(file.toPath()));
        }

        SyntheticHistoricalDb backup = fresh("backup-test.db", LARGE_QUOTA);
        backup.status();
        backup.backup();
        File badBackup = new File(context().getDatabasePath("backup-test.db") + ".backup");
        Files.write(badBackup.toPath(), "bad backup".getBytes(StandardCharsets.UTF_8));
        try {
            backup.restore();
            fail("bad backup must be rejected");
        } catch (Exception expected) {
            JSONObject live = backup.status();
            assertEquals(0, live.getInt("races"));
            assertEquals(0, live.getInt("versions"));
        }
        backup.close();
    }
}
