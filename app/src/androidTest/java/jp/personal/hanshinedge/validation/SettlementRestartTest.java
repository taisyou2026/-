package jp.personal.hanshinedge.validation;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import jp.personal.hanshinedge.runtime.AllVenueRuntime;
import jp.personal.hanshinedge.runtime.CapabilityGate;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SettlementRestartTest {
    private Context c(){ return InstrumentationRegistry.getInstrumentation().getTargetContext(); }

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
            c().deleteDatabase(prefix + suffix);
    }

    private static String buyPayload(String raceId) throws Exception {
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

    private static String settlementPayload(String raceId, int payout) throws Exception {
        return new JSONObject()
                .put("race_id", raceId)
                .put("data_status", "SETTLEMENT")
                .put("realMoney", "BLOCKED")
                .put("payout_yen", payout)
                .toString();
    }

    @Test public void duplicateConflictMissingWrongRaceAndRestartStayFailClosed() throws Exception {
        String prefix = "settlement-restart";
        clear(prefix);
        String raceId = "SETTLEMENT-HANSHIN-01";
        String ticket = "ticket-1";
        CapabilityGate.RaceContext pre = new CapabilityGate.RaceContext(
                raceId, "HANSHIN", 8, false, true, false, CapabilityGate.FROZEN_MODEL);
        CapabilityGate.RaceContext post = new CapabilityGate.RaceContext(
                raceId, "HANSHIN", 8, false, false, true, CapabilityGate.FROZEN_MODEL);
        String settlement = settlementPayload(raceId, 240);

        String trustedHead;
        try (AllVenueRuntime runtime = new AllVenueRuntime(c(), prefix, 4 * 1024 * 1024)) {
            JSONObject buy = runtime.appendFormalPaperBuy(pre, ticket, buyPayload(raceId), "GENESIS");
            assertEquals("APPENDED", buy.getString("result"));

            JSONObject first = runtime.settleOnce(post, ticket, settlement, "GENESIS");
            assertEquals("APPENDED", first.getString("result"));
            trustedHead = first.getString("head");
            assertEquals(1, runtime.settlementStatus().getInt("count"));

            JSONObject duplicate = runtime.settleOnce(post, ticket, settlement, trustedHead);
            assertEquals("DUPLICATE", duplicate.getString("result"));
            assertEquals(1, runtime.settlementStatus().getInt("count"));
            assertEquals(trustedHead, runtime.settlementStatus().getString("head"));

            String conflict = settlementPayload(raceId, 999);
            fails("AUDIT_KEY_CONFLICT", () -> runtime.settleOnce(post, ticket, conflict, trustedHead));
            assertEquals(1, runtime.settlementStatus().getInt("count"));

            fails("BUY_NOT_FOUND", () -> runtime.settleOnce(post, "missing-ticket", settlement, trustedHead));

            String wrongRaceId = "SETTLEMENT-HANSHIN-02";
            CapabilityGate.RaceContext wrongRace = new CapabilityGate.RaceContext(
                    wrongRaceId, "HANSHIN", 9, false, false, true, CapabilityGate.FROZEN_MODEL);
            String wrongSettlement = settlementPayload(wrongRaceId, 240);
            fails("BUY_NOT_FOUND", () -> runtime.settleOnce(wrongRace, ticket, wrongSettlement, trustedHead));

            assertTrue(runtime.settlementStatus().getBoolean("verified"));
            assertEquals(trustedHead, runtime.settlementStatus().getString("head"));
        }

        try (AllVenueRuntime reopened = new AllVenueRuntime(c(), prefix, 4 * 1024 * 1024)) {
            JSONObject state = reopened.settlementStatus();
            assertTrue(state.getBoolean("verified"));
            assertEquals(1, state.getInt("count"));
            assertEquals(trustedHead, state.getString("head"));
            assertEquals(1, reopened.formalBuyStatus().getInt("count"));

            JSONObject duplicateAfterRestart = reopened.settleOnce(post, ticket, settlement, trustedHead);
            assertEquals("DUPLICATE", duplicateAfterRestart.getString("result"));
            assertEquals(1, reopened.settlementStatus().getInt("count"));
            assertEquals(trustedHead, reopened.settlementStatus().getString("head"));

            String conflictAfterRestart = settlementPayload(raceId, 777);
            fails("AUDIT_KEY_CONFLICT",
                    () -> reopened.settleOnce(post, ticket, conflictAfterRestart, trustedHead));
            assertEquals(1, reopened.settlementStatus().getInt("count"));

            File out = new File(c().getFilesDir(), "validation");
            assertTrue(out.isDirectory() || out.mkdirs());
            JSONObject report = new JSONObject()
                    .put("schema", "SETTLEMENT_RESTART_REPORT-v1")
                    .put("settlement_count", 1)
                    .put("head_unchanged", true)
                    .put("duplicate_before_restart", "PASS")
                    .put("duplicate_after_restart", "PASS")
                    .put("conflict_isolation", "PASS")
                    .put("missing_buy_block", "PASS")
                    .put("wrong_race_block", "PASS")
                    .put("reopen_hash_chain", "PASS")
                    .put("realMoney", "BLOCKED");
            Files.write(new File(out, "settlement-restart.json").toPath(),
                    report.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
}
