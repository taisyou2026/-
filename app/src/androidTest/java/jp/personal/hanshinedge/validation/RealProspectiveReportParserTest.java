package jp.personal.hanshinedge.validation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import jp.personal.hanshinedge.prospective.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RealProspectiveReportParserTest {
    /** In-memory policy golden only. Never saved as a live observation or sent to a source. */
    static JSONObject golden() throws Exception {
        long now=System.currentTimeMillis(),start=now+1200000;
        JSONObject race=ProspectiveRuntimeTest.race(start),reg;
        String id=ProspectiveRuntime.canonical(race),prefix="test-report-policy";
        ProspectiveRuntimeTest.clear(prefix);
        JSONArray stages=new JSONArray();
        try(ProspectiveRuntime r=new ProspectiveRuntime(ProspectiveRuntimeTest.c(),prefix)) {
            reg=r.register(race,ProspectiveRuntimeTest.receipt(now,1000),now,1000);
            for(int k=0;k<4;k++) {
                long at=start-ProspectiveRuntime.TARGET[k]*1000L;
                JSONObject proof=ProspectiveRuntimeTest.receipt(at,1000).put("raw_size",123)
                    .put("source_uri","https://www.jra.go.jp/JRADB/accessD.html").put("http_date","TEST_ONLY_HTTP_DATE");
                stages.put(r.capture(id,ProspectiveRuntime.STAGE[k],race,proof,at,1000).getJSONObject("snapshot"));
            }
        }
        // The positive verifier case is an in-memory synthetic proof; persisted rows remain test_only.
        reg.put("test_only",false);
        for(int k=0;k<4;k++) stages.getJSONObject(k).put("test_only",false);
        return new JSONObject().put("registration",reg).put("milestones",stages);
    }

    static JSONObject report(JSONArray races) throws Exception {
        return new JSONObject().put("schema",RealProspectiveReport.SCHEMA).put("races",races)
            .put("gates",RealProspectiveReport.gates(races)).put("formal_buy_count",0)
            .put("REAL_MONEY","BLOCKED").put("AUTO_REAL_BET","DISABLED");
    }

    @Test public void recomputesSameRaceR3AndNeverCallsOneRaceR4() throws Exception {
        JSONArray races=new JSONArray().put(golden());
        JSONObject report=report(races);
        assertEquals("PASS",RealProspectiveReport.parse(report.toString()).getJSONObject("gates").getString("REAL_GATE_R3"));
        assertEquals("NOT_MET",report.getJSONObject("gates").getString("REAL_GATE_R4"));
        races.getJSONObject(0).getJSONArray("milestones").getJSONObject(3).put("race_id","OTHER_RACE");
        assertEquals("NOT_MET",RealProspectiveReport.gates(races).getString("REAL_GATE_R3"));
        try { RealProspectiveReport.parse(report.toString()); fail("forged PASS"); }
        catch(IllegalArgumentException expected) { assertEquals("REPORT_GATE_MISMATCH",expected.getMessage()); }
    }

    @Test public void missingClockRawRosterOrToleranceCannotPass() throws Exception {
        for(String missing:new String[]{"clock_verified","source_raw_sha256","roster_identity_valid","http_date","raw_size"}) {
            JSONObject race=golden(); race.getJSONArray("milestones").getJSONObject(2).remove(missing);
            assertEquals(missing,"NOT_MET",RealProspectiveReport.gates(new JSONArray().put(race)).getString("REAL_GATE_R3"));
        }
        JSONObject race=golden(); race.getJSONArray("milestones").getJSONObject(0).put("tolerance_seconds",181);
        assertEquals("NOT_MET",RealProspectiveReport.gates(new JSONArray().put(race)).getString("REAL_GATE_R3"));
        race=golden(); race.getJSONObject("registration").put("test_only",true);
        assertEquals("NOT_MET",RealProspectiveReport.gates(new JSONArray().put(race)).getString("REAL_GATE_R1"));
    }

    @Test public void duplicateRaceAndAlteredMoneyPolicyAreRejected() throws Exception {
        JSONObject race=golden();
        try { RealProspectiveReport.gates(new JSONArray().put(race).put(race)); fail("duplicate race"); }
        catch(IllegalArgumentException expected) { assertEquals("DUPLICATE_REPORT_RACE",expected.getMessage()); }
        JSONObject report=report(new JSONArray()).put("REAL_MONEY","ENABLED");
        try { RealProspectiveReport.parse(report.toString()); fail("money policy"); }
        catch(IllegalArgumentException expected) { assertEquals("REPORT_POLICY_INVALID",expected.getMessage()); }
    }
}
