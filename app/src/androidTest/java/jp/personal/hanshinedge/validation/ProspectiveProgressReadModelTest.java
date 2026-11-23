package jp.personal.hanshinedge.validation;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import jp.personal.hanshinedge.prospective.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ProspectiveProgressReadModelTest {
    static Context c(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
    static void clear(String p){for(String s:new String[]{"fetch","timechain","result","shadow","formal-buy","settlement"})c().deleteDatabase(p+"-"+s+".db");}

    @Test public void emptyProgressIsNotStarted() throws Exception {
        clear("test-progress-empty");
        try(ProspectiveRuntime r=new ProspectiveRuntime(c(),"test-progress-empty")){
            JSONObject p=ProspectiveProgressReadModel.collect(r);
            assertEquals(100,p.getInt("target_races"));
            assertEquals("NOT_STARTED",p.getString("status"));
            assertEquals(0,p.getInt("registered_races"));
            assertEquals(0,p.getInt("fully_observed_races"));
            assertEquals(0,p.getInt("formal_buy_rows"));
            assertEquals("JRA_EDGE_PROSPECTIVE_100R_PROGRESS-v3",p.getString("schema"));
            assertEquals("NOT_AVAILABLE",p.getString("F0_STATUS"));
            assertEquals("READY_MODEL_BLOCKED",p.getString("F0_BUILDER_STATUS"));
            assertEquals("NOT_AVAILABLE",p.getString("F0_MODEL_STATUS"));
            assertEquals("REFERENCE_HASH_ONLY",p.getString("FEATURE_REGISTRY_STATUS"));
            assertEquals("F0_MODEL_NOT_AVAILABLE",p.getString("F0_BLOCK_REASON"));
            assertFalse(p.getBoolean("fake_f0_generated"));
            assertEquals("NOT_AVAILABLE",p.getString("STAKE_RULE"));
            assertEquals("BLOCKED",p.getString("FORMAL_PAPER_BUY"));
            assertEquals("BLOCKED",p.getString("REAL_MONEY"));
        }
    }

    @Test public void oneFullyObservedRaceAdvancesOnlyOnePercent() throws Exception {
        long now=System.currentTimeMillis(), start=now+1200000, mono=1000;
        JSONObject raw=ProspectiveRuntimeTest.race(start);
        String id=ProspectiveRuntime.canonical(raw);
        clear("test-progress-one");
        try(ProspectiveRuntime r=new ProspectiveRuntime(c(),"test-progress-one")){
            r.register(raw,ProspectiveRuntimeTest.receipt(now,mono),now,mono);
            r.shadow(id);
            for(int i=0;i<4;i++){
                long at=start-ProspectiveRuntime.TARGET[i]*1000L;
                r.capture(id,ProspectiveRuntime.STAGE[i],raw,ProspectiveRuntimeTest.receipt(at,mono),at,mono);
            }
            JSONObject result=new JSONObject()
                    .put("race_id",id)
                    .put("source_identity_verified",true)
                    .put("winner",new JSONArray().put(1))
                    .put("win_payout",new JSONArray().put(new JSONObject().put("horse_no",1).put("payout_per_100",240)));
            r.result(id,result,ProspectiveRuntimeTest.receipt(start+60000,mono));
            JSONObject p=ProspectiveProgressReadModel.collect(r);
            assertEquals("IN_PROGRESS",p.getString("status"));
            assertEquals(1,p.getInt("registered_races"));
            assertEquals(1,p.getInt("timechain_all_four_recorded"));
            assertEquals(1,p.getInt("timechain_all_four_pass"));
            assertEquals(1,p.getInt("result_linked_races"));
            assertEquals(1,p.getInt("fully_observed_races"));
            assertEquals(1.0,p.getDouble("progress_percent"),0.0001);
            assertEquals("ALL_4_TIMECHAIN_PASS_AND_RESULT_SAVED",p.getString("progress_rule"));
            JSONObject row=p.getJSONArray("races").getJSONObject(0);
            assertEquals(1,row.getInt("registration_ordinal"));
            assertEquals("COUNTED",row.getString("observation_state"));
            assertTrue(row.getBoolean("counted_in_100r"));
            assertEquals(0,row.getJSONArray("missing_timechain_stages").length());
            assertEquals(0,row.getJSONArray("failed_timechain_stages").length());
            assertEquals(1,p.getInt("shadow_rows"));
            assertEquals(0,p.getInt("formal_buy_rows"));
            assertEquals(0,p.getInt("settlement_rows"));
            JSONObject recorded=p.getJSONObject("stage_recorded"), pass=p.getJSONObject("stage_pass");
            for(String stage:ProspectiveRuntime.STAGE){
                assertEquals(1,recorded.getInt(stage));
                assertEquals(1,pass.getInt(stage));
            }
        }
    }

    @Test public void failedTimechainDoesNotAdvanceProgress() throws Exception {
        long now=System.currentTimeMillis(), start=now+1200000, mono=1000;
        JSONObject raw=ProspectiveRuntimeTest.race(start);
        String id=ProspectiveRuntime.canonical(raw);
        clear("test-progress-failed-stage");
        try(ProspectiveRuntime r=new ProspectiveRuntime(c(),"test-progress-failed-stage")){
            r.register(raw,ProspectiveRuntimeTest.receipt(now,mono),now,mono);
            r.shadow(id);
            for(int i=0;i<4;i++){
                long at=start-ProspectiveRuntime.TARGET[i]*1000L;
                if("T-5".equals(ProspectiveRuntime.STAGE[i])){
                    r.unavailable(id,ProspectiveRuntime.STAGE[i],"SOURCE_UNAVAILABLE",at);
                }else{
                    r.capture(id,ProspectiveRuntime.STAGE[i],raw,ProspectiveRuntimeTest.receipt(at,mono),at,mono);
                }
            }
            JSONObject result=new JSONObject()
                    .put("race_id",id)
                    .put("source_identity_verified",true)
                    .put("winner",new JSONArray().put(1))
                    .put("win_payout",new JSONArray().put(new JSONObject().put("horse_no",1).put("payout_per_100",240)));
            r.result(id,result,ProspectiveRuntimeTest.receipt(start+60000,mono));

            JSONObject p=ProspectiveProgressReadModel.collect(r);
            assertEquals(1,p.getInt("timechain_all_four_recorded"));
            assertEquals(0,p.getInt("timechain_all_four_pass"));
            assertEquals(1,p.getInt("result_linked_races"));
            assertEquals(0,p.getInt("fully_observed_races"));
            assertEquals(0.0,p.getDouble("progress_percent"),0.0001);

            JSONObject row=p.getJSONArray("races").getJSONObject(0);
            assertEquals("TIMECHAIN_FAILED",row.getString("observation_state"));
            assertFalse(row.getBoolean("counted_in_100r"));
            assertEquals(0,row.getJSONArray("missing_timechain_stages").length());
            JSONArray failed=row.getJSONArray("failed_timechain_stages");
            assertEquals(1,failed.length());
            assertEquals("T-5",failed.getJSONObject(0).getString("stage"));
            assertEquals("UNAVAILABLE",failed.getJSONObject(0).getString("status"));
        }
    }
}
