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
            assertEquals("NOT_AVAILABLE",p.getString("F0_STATUS"));
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
}
