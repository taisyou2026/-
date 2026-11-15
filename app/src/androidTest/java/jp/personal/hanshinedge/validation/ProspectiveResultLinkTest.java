package jp.personal.hanshinedge.validation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import jp.personal.hanshinedge.prospective.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ProspectiveResultLinkTest {
    static JSONObject outcome(String id) throws Exception {
        return new JSONObject().put("race_id",id).put("source_identity_verified",true)
            .put("finish_position",new JSONArray().put(new JSONObject().put("horse_no",1).put("finish_position",1)))
            .put("winner",new JSONArray().put(1)).put("win_payout",new JSONArray().put(new JSONObject().put("horse_no",1).put("payout_per_100",200)));
    }

    @Test public void sameRaceResultDuplicateReopenAndZeroBuy() throws Exception {
        String prefix="test-result-link";ProspectiveRuntimeTest.clear(prefix);
        long now=System.currentTimeMillis(),post=now+1200000;JSONObject race=ProspectiveRuntimeTest.race(post);
        String id=ProspectiveRuntime.canonical(race);
        try(ProspectiveRuntime r=new ProspectiveRuntime(ProspectiveRuntimeTest.c(),prefix)) {
            r.register(race,ProspectiveRuntimeTest.receipt(now,1000),now,1000);
            assertEquals("APPENDED",r.result(id,outcome(id),ProspectiveRuntimeTest.receipt(post+60000,1000)).getString("result"));
            assertEquals("DUPLICATE",r.result(id,outcome(id),ProspectiveRuntimeTest.receipt(post+60000,1000)).getString("result"));
        }
        try(ProspectiveRuntime r=new ProspectiveRuntime(ProspectiveRuntimeTest.c(),prefix)) {
            assertEquals(1,r.runtime.resultStatus().getInt("count"));
            RuntimeWorker.settleExisting(r,id);
            assertEquals(0,r.runtime.settlementStatus().getInt("count"));
            assertEquals(0,r.runtime.formalBuyStatus().getInt("count"));
            assertEquals(100000,r.bankroll().getLong("current_bankroll"));
        }
    }

    @Test public void wrongRaceAndPreRaceResultAreBlocked() throws Exception {
        String prefix="test-result-block";ProspectiveRuntimeTest.clear(prefix);
        long now=System.currentTimeMillis();JSONObject race=ProspectiveRuntimeTest.race(now+1200000);
        String id=ProspectiveRuntime.canonical(race);
        try(ProspectiveRuntime r=new ProspectiveRuntime(ProspectiveRuntimeTest.c(),prefix)) {
            r.register(race,ProspectiveRuntimeTest.receipt(now,1000),now,1000);
            ProspectiveRuntimeTest.fails("RESULT_RACE_MISMATCH",()->r.result(id,outcome("OTHER"),ProspectiveRuntimeTest.receipt(now+1800000,1000)));
            ProspectiveRuntimeTest.fails("RESULT_BEFORE_RACE",()->r.result(id,outcome(id),ProspectiveRuntimeTest.receipt(now,1000)));
            assertEquals(0,r.runtime.resultStatus().getInt("count"));
        }
    }
}
