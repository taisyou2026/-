package jp.personal.hanshinedge.validation;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import jp.personal.hanshinedge.runtime.AllVenueRuntime;
import jp.personal.hanshinedge.runtime.CapabilityGate;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AllVenueRuntimeTest {
    private Context c() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

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

    private static String readiness(String raceId) throws Exception {
        return new JSONObject()
                .put("race_id", raceId)
                .put("data_status", "READINESS_ONLY")
                .put("realMoney", "BLOCKED")
                .toString();
    }

    @Test public void capabilityGateIsAllVenueObservationButFrozenBuyOnly() throws Exception {
        CapabilityGate.RaceContext hanshin = new CapabilityGate.RaceContext(
                "H-1","HANSHIN",1,false,true,false,CapabilityGate.FROZEN_MODEL);
        CapabilityGate.Capabilities h = CapabilityGate.evaluate(hanshin);
        assertTrue(h.fetch && h.timechain && h.result && h.shadowHistory && h.flatModel);
        assertTrue(h.formalPaperBuy);
        assertFalse(h.realMoney);

        CapabilityGate.RaceContext tokyo = new CapabilityGate.RaceContext(
                "T-1","TOKYO",12,false,true,false,CapabilityGate.FROZEN_MODEL);
        CapabilityGate.Capabilities t = CapabilityGate.evaluate(tokyo);
        assertTrue(t.fetch && t.timechain && t.result && t.shadowHistory && t.flatModel);
        assertFalse(t.formalPaperBuy);
        assertEquals("OUTSIDE_FROZEN_VENUE", t.formalBlockReason);

        CapabilityGate.RaceContext jump = new CapabilityGate.RaceContext(
                "H-J","HANSHIN",4,true,true,false,CapabilityGate.FROZEN_MODEL);
        CapabilityGate.Capabilities j = CapabilityGate.evaluate(jump);
        assertTrue(j.fetch && j.timechain && j.result && j.shadowHistory);
        assertFalse(j.flatModel);
        assertFalse(j.formalPaperBuy);
        assertEquals("OBSTACLE_MODEL_BLOCKED", j.formalBlockReason);
    }

    @Test public void storesRemainPhysicallySeparatedAndResultReplayCannotCreateBuy() throws Exception {
        String prefix = "runtime-separation";
        clear(prefix);
        AllVenueRuntime r = new AllVenueRuntime(c(), prefix, 4 * 1024 * 1024);
        CapabilityGate.RaceContext fukushima = new CapabilityGate.RaceContext(
                "2026-11-14|FUKUSHIMA|1","FUKUSHIMA",1,false,false,true,CapabilityGate.FROZEN_MODEL);

        String payload = readiness(fukushima.raceId);
        r.appendFetch(fukushima, "FETCH:1", payload, "GENESIS");
        r.appendTimechain(fukushima, "TC:T-5", payload, "GENESIS");
        r.appendResult(fukushima, "RESULT:1", payload, "GENESIS");
        r.appendShadow(fukushima, "SHADOW:1",
                new JSONObject(payload).put("model_applied", false).toString(), "GENESIS");

        assertEquals(1, r.fetchStatus().getInt("count"));
        assertEquals(1, r.timechainStatus().getInt("count"));
        assertEquals(1, r.resultStatus().getInt("count"));
        assertEquals(1, r.shadowStatus().getInt("count"));
        assertEquals(0, r.formalBuyStatus().getInt("count"));
        assertEquals(0, r.settlementStatus().getInt("count"));

        String buy = new JSONObject()
                .put("race_id", fukushima.raceId)
                .put("data_status", "PROSPECTIVE")
                .put("realMoney", "BLOCKED")
                .put("paper", true)
                .put("result_known", false)
                .put("model_tag", CapabilityGate.FROZEN_MODEL)
                .toString();
        fails("FORMAL_BUY_BLOCKED:OUTSIDE_FROZEN_VENUE",
                () -> r.appendFormalPaperBuy(fukushima, "1", buy, "GENESIS"));
        assertEquals(0, r.formalBuyStatus().getInt("count"));
        r.close();
    }

    @Test public void obstacleNeverEntersFlatModelShadow() throws Exception {
        String prefix = "runtime-obstacle";
        clear(prefix);
        AllVenueRuntime r = new AllVenueRuntime(c(), prefix, 4 * 1024 * 1024);
        CapabilityGate.RaceContext jump = new CapabilityGate.RaceContext(
                "JUMP-1","HANSHIN",4,true,true,false,CapabilityGate.FROZEN_MODEL);
        String bad = new JSONObject(readiness(jump.raceId)).put("model_applied", true).toString();
        fails("OBSTACLE_MODEL_BLOCKED", () -> r.appendShadow(jump, "SHADOW:JUMP", bad, "GENESIS"));
        assertEquals(0, r.shadowStatus().getInt("count"));
        r.close();
    }

    @Test public void settlementIsIdempotentAndConflictsFailClosed() throws Exception {
        String prefix = "runtime-settlement";
        clear(prefix);
        AllVenueRuntime r = new AllVenueRuntime(c(), prefix, 4 * 1024 * 1024);
        CapabilityGate.RaceContext pre = new CapabilityGate.RaceContext(
                "HANSHIN-FROZEN-1","HANSHIN",8,false,true,false,CapabilityGate.FROZEN_MODEL);

        String buy = new JSONObject()
                .put("race_id", pre.raceId)
                .put("data_status", "PROSPECTIVE")
                .put("realMoney", "BLOCKED")
                .put("paper", true)
                .put("result_known", false)
                .put("model_tag", CapabilityGate.FROZEN_MODEL)
                .put("stake_yen", 100)
                .toString();
        JSONObject b = r.appendFormalPaperBuy(pre, "ticket-1", buy, "GENESIS");
        assertEquals("APPENDED", b.getString("result"));
        assertEquals(1, r.formalBuyStatus().getInt("count"));

        CapabilityGate.RaceContext post = new CapabilityGate.RaceContext(
                pre.raceId,"HANSHIN",8,false,false,true,CapabilityGate.FROZEN_MODEL);
        String settlement = new JSONObject()
                .put("race_id", post.raceId)
                .put("data_status", "SETTLEMENT")
                .put("realMoney", "BLOCKED")
                .put("payout_yen", 240)
                .toString();

        JSONObject s1 = r.settleOnce(post, "ticket-1", settlement, "GENESIS");
        assertEquals("APPENDED", s1.getString("result"));
        JSONObject s2 = r.settleOnce(post, "ticket-1", settlement, "GENESIS");
        assertEquals("DUPLICATE", s2.getString("result"));
        assertEquals(1, r.settlementStatus().getInt("count"));

        String conflicting = new JSONObject(settlement).put("payout_yen", 999).toString();
        fails("AUDIT_KEY_CONFLICT", () -> r.settleOnce(post, "ticket-1", conflicting, "GENESIS"));
        assertEquals(1, r.settlementStatus().getInt("count"));
        r.close();
    }
}
