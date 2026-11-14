package jp.personal.hanshinedge.validation;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import jp.personal.hanshinedge.runtime.AllVenueRuntime;
import jp.personal.hanshinedge.runtime.CapabilityGate;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RealFixtureReplayTest {
    private Context target(){ return InstrumentationRegistry.getInstrumentation().getTargetContext(); }
    private Context testContext(){ return InstrumentationRegistry.getInstrumentation().getContext(); }

    interface Checked { void run() throws Exception; }

    private void fails(String reason, Checked action) throws Exception {
        try {
            action.run();
            fail(reason);
        } catch (Exception e) {
            assertTrue(e.toString(), e.toString().contains(reason));
        }
    }

    private void clear(String prefix) {
        for (String suffix : new String[]{"-fetch.db","-timechain.db","-result.db","-shadow.db","-formal-buy.db","-settlement.db"})
            target().deleteDatabase(prefix + suffix);
    }

    private JSONObject fixture() throws Exception {
        try (InputStream in = testContext().getAssets().open("jra-real-historical-fixture-20260919-20261114.json");
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) >= 0;) out.write(b, 0, n);
            return new JSONObject(out.toString("UTF-8"));
        }
    }

    private static String venue(String jp) {
        Map<String,String> m = new HashMap<>();
        m.put("札幌","SAPPORO"); m.put("函館","HAKODATE"); m.put("福島","FUKUSHIMA");
        m.put("新潟","NIIGATA"); m.put("東京","TOKYO"); m.put("中山","NAKAYAMA");
        m.put("中京","CHUKYO"); m.put("京都","KYOTO"); m.put("阪神","HANSHIN"); m.put("小倉","KOKURA");
        String v = m.get(jp);
        if (v == null) throw new IllegalArgumentException("VENUE_MAP:" + jp);
        return v;
    }

    private static String readiness(String raceId, boolean modelApplied, String fixtureSha) throws Exception {
        return new JSONObject()
                .put("race_id", raceId)
                .put("data_status", "READINESS_ONLY")
                .put("realMoney", "BLOCKED")
                .put("model_applied", modelApplied)
                .put("fixture_sha256", fixtureSha)
                .toString();
    }

    private static String formalPayload(String raceId) throws Exception {
        return new JSONObject()
                .put("race_id", raceId)
                .put("data_status", "PROSPECTIVE")
                .put("realMoney", "BLOCKED")
                .put("paper", true)
                .put("result_known", false)
                .put("model_tag", CapabilityGate.FROZEN_MODEL)
                .put("stake_yen", 100)
                .toString();
    }

    @Test public void replayAllCommittedRealHistoricalFixturesWithZeroFormalBuys() throws Exception {
        String prefix = "real-fixture-replay";
        clear(prefix);
        JSONObject root = fixture();
        JSONObject summary = root.getJSONObject("summary");
        JSONArray races = root.getJSONArray("races");

        assertFalse(summary.getBoolean("REAL_PROSPECTIVE"));
        assertEquals(0, summary.getInt("PROSPECTIVE_SUCCESS_CREDIT"));
        assertEquals("BLOCKED", summary.getString("realMoney"));
        assertEquals(22, summary.getInt("race_count"));
        assertEquals(307, summary.getInt("active_runner_count"));
        assertEquals(1, summary.getInt("hanshin_flat_races"));
        assertEquals(1, summary.getInt("hanshin_obstacle_races"));
        assertEquals(22, races.length());

        String fixtureSha = "COMMITTED_FIXTURE";
        String fetchHead = "GENESIS", tcHead = "GENESIS", shadowHead = "GENESIS", resultHead = "GENESIS";
        int gateA = 0, gateB = 0, gateC = 0, formalBlocked = 0;
        String firstResultKey = null, firstResultPayload = null;
        CapabilityGate.RaceContext firstResultContext = null;
        CapabilityGate.RaceContext hanshinObstacle = null;

        try (AllVenueRuntime runtime = new AllVenueRuntime(target(), prefix, 16 * 1024 * 1024)) {
            for (int i = 0; i < races.length(); i++) {
                JSONObject race = races.getJSONObject(i);
                String raceId = race.getString("race_id");
                String v = venue(race.getString("venue"));
                boolean obstacle = race.getBoolean("obstacle");
                CapabilityGate.RaceContext ctx = new CapabilityGate.RaceContext(
                        raceId, v, race.getInt("race_no"), obstacle,
                        false, true, CapabilityGate.FROZEN_MODEL);

                String payload = readiness(raceId, !obstacle, fixtureSha);
                fetchHead = runtime.appendFetch(ctx, "FETCH:" + raceId, payload, fetchHead).getString("head");
                tcHead = runtime.appendTimechain(ctx, "TIMECHAIN_REPLAY:" + raceId, payload, tcHead).getString("head");
                shadowHead = runtime.appendShadow(ctx, "SHADOW:" + raceId, payload, shadowHead).getString("head");
                JSONObject resultAppend = runtime.appendResult(ctx, "RESULT:" + raceId, payload, resultHead);
                resultHead = resultAppend.getString("head");

                if (firstResultKey == null) {
                    firstResultKey = "RESULT:" + raceId;
                    firstResultPayload = payload;
                    firstResultContext = ctx;
                }

                CapabilityGate.Capabilities cap = CapabilityGate.evaluate(ctx);
                assertFalse(cap.formalPaperBuy);
                assertFalse(cap.realMoney);

                if ("HANSHIN".equals(v) && obstacle) {
                    gateB++;
                    hanshinObstacle = ctx;
                    assertFalse(cap.flatModel);
                    assertEquals("OBSTACLE_MODEL_BLOCKED", cap.formalBlockReason);
                    fails("FORMAL_BUY_BLOCKED:OBSTACLE_MODEL_BLOCKED",
                            () -> runtime.appendFormalPaperBuy(ctx, "historical-" + raceId, formalPayload(raceId), "GENESIS"));
                } else if ("HANSHIN".equals(v)) {
                    gateC++;
                    assertTrue(cap.flatModel);
                    assertEquals("NOT_PROSPECTIVE", cap.formalBlockReason);
                    fails("FORMAL_BUY_BLOCKED:NOT_PROSPECTIVE",
                            () -> runtime.appendFormalPaperBuy(ctx, "historical-" + raceId, formalPayload(raceId), "GENESIS"));
                } else {
                    gateA++;
                    if (obstacle) {
                        assertEquals("OBSTACLE_MODEL_BLOCKED", cap.formalBlockReason);
                        fails("FORMAL_BUY_BLOCKED:OBSTACLE_MODEL_BLOCKED",
                                () -> runtime.appendFormalPaperBuy(ctx, "historical-" + raceId, formalPayload(raceId), "GENESIS"));
                    } else {
                        assertEquals("OUTSIDE_FROZEN_VENUE", cap.formalBlockReason);
                        fails("FORMAL_BUY_BLOCKED:OUTSIDE_FROZEN_VENUE",
                                () -> runtime.appendFormalPaperBuy(ctx, "historical-" + raceId, formalPayload(raceId), "GENESIS"));
                    }
                }
                formalBlocked++;
            }

            assertEquals(22, runtime.fetchStatus().getInt("count"));
            assertEquals(22, runtime.timechainStatus().getInt("count"));
            assertEquals(22, runtime.shadowStatus().getInt("count"));
            assertEquals(22, runtime.resultStatus().getInt("count"));
            assertEquals(0, runtime.formalBuyStatus().getInt("count"));
            assertEquals(0, runtime.settlementStatus().getInt("count"));
            assertEquals(20, gateA);
            assertEquals(1, gateB);
            assertEquals(1, gateC);
            assertEquals(22, formalBlocked);

            JSONObject duplicate = runtime.appendResult(firstResultContext, firstResultKey, firstResultPayload, resultHead);
            assertEquals("DUPLICATE", duplicate.getString("result"));
            assertEquals(22, runtime.resultStatus().getInt("count"));

            final CapabilityGate.RaceContext obstacleCtx = hanshinObstacle;
            assertNotNull(obstacleCtx);
            String bad = readiness(obstacleCtx.raceId, true, fixtureSha);
            fails("OBSTACLE_MODEL_BLOCKED",
                    () -> runtime.appendShadow(obstacleCtx, "SHADOW:BAD:" + obstacleCtx.raceId, bad, shadowHead));
            assertEquals(22, runtime.shadowStatus().getInt("count"));

            File out = new File(target().getFilesDir(), "validation");
            assertTrue(out.isDirectory() || out.mkdirs());
            JSONObject report = new JSONObject()
                    .put("schema", "REAL_FIXTURE_REPLAY_REPORT-v1")
                    .put("race_count", 22)
                    .put("active_runner_count", 307)
                    .put("gate_A_non_hanshin", gateA)
                    .put("gate_B_hanshin_obstacle", gateB)
                    .put("gate_C_hanshin_flat_historical", gateC)
                    .put("formal_buy_count", 0)
                    .put("historical_formal_buy_blocked", formalBlocked)
                    .put("duplicate_result", "PASS")
                    .put("obstacle_flat_model_block", "PASS")
                    .put("REAL_PROSPECTIVE", false)
                    .put("realMoney", "BLOCKED");
            Files.write(new File(out, "real-fixture-replay.json").toPath(),
                    report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test public void gateDAllowsOnlySyntheticProspectiveHanshinPaperBuy() throws Exception {
        String prefix = "gate-d-prospective";
        clear(prefix);
        try (AllVenueRuntime runtime = new AllVenueRuntime(target(), prefix, 4 * 1024 * 1024)) {
            CapabilityGate.RaceContext ctx = new CapabilityGate.RaceContext(
                    "SYNTHETIC-GATE-D", "HANSHIN", 8, false,
                    true, false, CapabilityGate.FROZEN_MODEL);
            CapabilityGate.Capabilities cap = CapabilityGate.evaluate(ctx);
            assertTrue(cap.formalPaperBuy);
            assertFalse(cap.realMoney);
            assertEquals("BLOCKED", CapabilityGate.REAL_MONEY);
            JSONObject appended = runtime.appendFormalPaperBuy(
                    ctx, "paper-only", formalPayload(ctx.raceId), "GENESIS");
            assertEquals("APPENDED", appended.getString("result"));
            assertEquals(1, runtime.formalBuyStatus().getInt("count"));
            assertEquals(0, runtime.settlementStatus().getInt("count"));
        }
    }
}
