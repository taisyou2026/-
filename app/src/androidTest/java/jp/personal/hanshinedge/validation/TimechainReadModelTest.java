package jp.personal.hanshinedge.validation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import jp.personal.hanshinedge.prospective.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TimechainReadModelTest {
    @Test public void readsActualRowsWaitingUnavailableAndReopen() throws Exception {
        String prefix="test-readmodel";ProspectiveRuntimeTest.clear(prefix);
        long now=System.currentTimeMillis();JSONObject race=ProspectiveRuntimeTest.race(now+1200000);
        String id=ProspectiveRuntime.canonical(race);
        try(ProspectiveRuntime r=new ProspectiveRuntime(ProspectiveRuntimeTest.c(),prefix)) {
            assertEquals("UNAVAILABLE",TimechainReadModel.stages(r,id).getJSONObject(0).getString("status"));
            r.register(race,ProspectiveRuntimeTest.receipt(now,1000),now,1000);
            assertEquals("WAITING",TimechainReadModel.stages(r,id).getJSONObject(0).getString("status"));
            r.capture(id,"T-20",race,ProspectiveRuntimeTest.receipt(now,1000),now,1000);
            r.unavailable(id,"T-10","HTTP_503",now);
        }
        try(ProspectiveRuntime r=new ProspectiveRuntime(ProspectiveRuntimeTest.c(),prefix)) {
            JSONArray a=TimechainReadModel.stages(r,id);
            assertEquals("PASS",a.getJSONObject(0).getString("status"));
            assertEquals("HTTP_503",a.getJSONObject(1).getString("error_code"));
            assertEquals("WAITING",a.getJSONObject(3).getString("status"));
            assertTrue(TimechainReadModel.render(a).contains("2/2 odds"));
            assertEquals(2,r.runtime.timechainStatus().getInt("count"));
        }
    }

    @Test public void explicitScratchAllowedButMissingOrDuplicateNumbersRejected() throws Exception {
        JSONObject before=ProspectiveRuntimeTest.race(System.currentTimeMillis()+1200000);
        before.getJSONArray("runners").put(new JSONObject().put("horse_no",3).put("win_odds",7.0));
        JSONObject scratch=new JSONObject(before.toString());scratch.getJSONArray("runners").getJSONObject(2).put("scratched",true);
        assertTrue(ProspectiveRuntime.validRoster(before,scratch));
        JSONObject missing=new JSONObject(before.toString());missing.getJSONArray("runners").remove(2);
        assertFalse(ProspectiveRuntime.validRoster(before,missing));
        JSONObject duplicate=new JSONObject(before.toString());duplicate.getJSONArray("runners").getJSONObject(2).put("horse_no",1).put("scratched",true);
        assertFalse(ProspectiveRuntime.validRoster(before,duplicate));
        JSONObject changed=new JSONObject(before.toString());changed.getJSONArray("runners").getJSONObject(2).put("horse_no",4);
        assertFalse(ProspectiveRuntime.validRoster(before,changed));
    }

    @Test public void eachSnapshotRevalidatesSourceIdentityAndRegisteredRoster() throws Exception {
        String prefix="test-snapshot-identity";ProspectiveRuntimeTest.clear(prefix);
        long now=System.currentTimeMillis();JSONObject race=ProspectiveRuntimeTest.race(now+1200000);
        String id=ProspectiveRuntime.canonical(race);
        try(ProspectiveRuntime r=new ProspectiveRuntime(ProspectiveRuntimeTest.c(),prefix)) {
            r.register(race,ProspectiveRuntimeTest.receipt(now,1000),now,1000);
            JSONObject wrong=new JSONObject(race.toString()).put("source_identity_verified",false);
            assertEquals("SNAPSHOT_RACE_MISMATCH",r.capture(id,"T-20",wrong,ProspectiveRuntimeTest.receipt(now,1000),now,1000).getJSONObject("snapshot").getString("status"));
            JSONObject roster=new JSONObject(race.toString());roster.getJSONArray("runners").getJSONObject(0).put("horse_no",3);
            long at=now+600000;
            assertEquals("ROSTER_IDENTITY_INVALID",r.capture(id,"T-10",roster,ProspectiveRuntimeTest.receipt(at,1000),at,1000).getJSONObject("snapshot").getString("status"));
            assertEquals(0,r.runtime.formalBuyStatus().getInt("count"));
        }
    }
}
