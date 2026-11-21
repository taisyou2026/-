package jp.personal.hanshinedge.validation;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;

import jp.personal.hanshinedge.prediction.F0InputContract;
import jp.personal.hanshinedge.prospective.ProspectiveRuntime;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class F0InputContractTest {
    static Context c(){return ProspectiveRuntimeTest.c();}

    static JSONObject provenance(long start) throws Exception {
        return new JSONObject()
                .put("schema", F0InputContract.SCHEMA)
                .put("feature_registry_sha256", F0InputContract.FEATURE_REGISTRY_SHA256)
                .put("f0_model_id", "TEST_ONLY_F0_MODEL")
                .put("evidence_sha256", String.format("%064x", 10))
                .put("source_raw_sha256", String.format("%064x", 11))
                .put("as_of", Instant.ofEpochMilli(start - 300000).toString())
                .put("market_derived", false)
                .put("upstream_model_output", false)
                .put("post_race_data_used", false)
                .put("champion_allowed", true)
                .put("historical_cutoff", "PREVIOUS_DATE_ONLY");
    }

    static JSONObject withF0(JSONObject raw,long start) throws Exception {
        JSONObject x=new JSONObject(raw.toString());
        JSONArray runners=x.getJSONArray("runners");
        runners.getJSONObject(0).put("f0",9.0);
        runners.getJSONObject(1).put("f0",1.0);
        x.put("f0_provenance",provenance(start));
        return x;
    }

    @Test public void frozenContractAcceptsOnlyPreT5MarketFreeEvidence() throws Exception {
        long start=System.currentTimeMillis()+1200000;
        JSONObject race=withF0(ProspectiveRuntimeTest.race(start),start);
        F0InputContract.Validation ok=F0InputContract.validate(race,start-300000);
        assertTrue(ok.reason,ok.valid);
        assertEquals("F0_READY",ok.reason);
        assertEquals(2,ok.f0.size());

        JSONObject market=new JSONObject(race.toString());
        market.getJSONObject("f0_provenance").put("market_derived",true);
        assertEquals("F0_MARKET_DERIVED_FORBIDDEN",
                F0InputContract.validate(market,start-300000).reason);

        JSONObject late=new JSONObject(race.toString());
        late.getJSONObject("f0_provenance").put("as_of",Instant.ofEpochMilli(start-299999).toString());
        assertEquals("F0_AFTER_T5_CUTOFF",
                F0InputContract.validate(late,start-299999).reason);

        JSONObject wrongRegistry=new JSONObject(race.toString());
        wrongRegistry.getJSONObject("f0_provenance").put("feature_registry_sha256",String.format("%064x",99));
        assertEquals("F0_FEATURE_REGISTRY_MISMATCH",
                F0InputContract.validate(wrongRegistry,start-300000).reason);
    }

    @Test public void t5ShadowUsesCommittedMarketAndNeverCreatesFormalBuy() throws Exception {
        long now=System.currentTimeMillis(),start=now+1200000,mono=1000;
        long t5=start-300000;
        JSONObject raw=ProspectiveRuntimeTest.race(start);
        String id=ProspectiveRuntime.canonical(raw);
        String prefix="test-f0-shadow";
        ProspectiveRuntimeTest.clear(prefix);
        try(ProspectiveRuntime r=new ProspectiveRuntime(c(),prefix)){
            r.register(raw,ProspectiveRuntimeTest.receipt(now,mono),now,mono);
            JSONObject latest=withF0(raw,start);
            r.capture(id,"T-5",latest,ProspectiveRuntimeTest.receipt(t5,mono),t5,mono);
            JSONObject out=r.shadowDecision(id,latest,t5);
            assertEquals("APPENDED",out.getString("result"));
            JSONObject shadow=out.getJSONObject("shadow");
            assertTrue(shadow.getBoolean("model_applied"));
            assertEquals("BUY",shadow.getString("model_action"));
            assertEquals("BUY",shadow.getString("decision_action"));
            assertEquals("SUPPLIED_VALIDATED",shadow.getString("F0_STATUS"));
            assertEquals("NOT_AVAILABLE",shadow.getString("STAKE_RULE"));
            assertEquals("BLOCKED",shadow.getString("FORMAL_PAPER_BUY"));
            assertEquals(F0InputContract.FEATURE_REGISTRY_SHA256,shadow.getString("feature_registry_sha256"));
            assertEquals(2,shadow.getJSONArray("rows").length());
            assertEquals(0,r.runtime.formalBuyStatus().getInt("count"));
            assertEquals(1,r.runtime.shadowStatus().getInt("count"));
            assertEquals("DUPLICATE",r.shadowDecision(id,latest,t5).getString("result"));
            assertEquals(1,r.runtime.shadowStatus().getInt("count"));
        }
    }

    @Test public void missingProvenanceIsStoredAsBlockedShadow() throws Exception {
        long now=System.currentTimeMillis(),start=now+1200000,mono=1000;
        long t5=start-300000;
        JSONObject raw=ProspectiveRuntimeTest.race(start);
        String id=ProspectiveRuntime.canonical(raw);
        String prefix="test-f0-missing-proof";
        ProspectiveRuntimeTest.clear(prefix);
        try(ProspectiveRuntime r=new ProspectiveRuntime(c(),prefix)){
            r.register(raw,ProspectiveRuntimeTest.receipt(now,mono),now,mono);
            JSONObject latest=new JSONObject(raw.toString());
            latest.getJSONArray("runners").getJSONObject(0).put("f0",9.0);
            latest.getJSONArray("runners").getJSONObject(1).put("f0",1.0);
            r.capture(id,"T-5",latest,ProspectiveRuntimeTest.receipt(t5,mono),t5,mono);
            JSONObject shadow=r.shadowDecision(id,latest,t5).getJSONObject("shadow");
            assertFalse(shadow.getBoolean("model_applied"));
            assertEquals("BLOCKED",shadow.getString("decision_action"));
            assertEquals("F0_PROVENANCE_REQUIRED",shadow.getString("reason"));
            assertEquals("REJECTED",shadow.getString("F0_STATUS"));
            assertEquals(0,r.runtime.formalBuyStatus().getInt("count"));
        }
    }
}
