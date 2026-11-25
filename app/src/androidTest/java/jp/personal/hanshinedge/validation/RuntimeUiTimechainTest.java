package jp.personal.hanshinedge.validation;

import android.content.Intent;
import android.view.*;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import jp.personal.hanshinedge.ui.MainActivity;
import jp.personal.hanshinedge.prospective.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RuntimeUiTimechainTest {
    private String text(View view) {
        StringBuilder s=new StringBuilder(); if(view instanceof TextView)s.append(((TextView)view).getText());
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)s.append(text(((ViewGroup)view).getChildAt(i)));
        return s.toString();
    }
    @Test public void componentRendersActualSQLiteStatusTimestampAndCoverage() throws Exception {
        String prefix="test-ui-timechain";ProspectiveRuntimeTest.clear(prefix);
        long now=System.currentTimeMillis();JSONObject race=ProspectiveRuntimeTest.race(now+1200000);String id=ProspectiveRuntime.canonical(race);
        JSONArray stages;
        try(ProspectiveRuntime r=new ProspectiveRuntime(ProspectiveRuntimeTest.c(),prefix)) {
            r.register(race,ProspectiveRuntimeTest.receipt(now,1000),now,1000);
            r.capture(id,"T-20",race,ProspectiveRuntimeTest.receipt(now,1000),now,1000);
            stages=TimechainReadModel.stages(r,id);
        }
        android.app.Instrumentation instr=InstrumentationRegistry.getInstrumentation();
        MainActivity a=(MainActivity)instr.startActivitySync(new Intent(instr.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try { instr.runOnMainSync(()->{try{a.showTimechainEvidence(stages);}catch(Exception e){throw new RuntimeException(e);}});
            final String[] s={null};instr.runOnMainSync(()->s[0]=text(a.getWindow().getDecorView()));
            assertTrue(s[0].contains("T-20 PASS"));assertTrue(s[0].contains(stages.getJSONObject(0).getString("observed_at")));
            assertTrue(s[0].contains("2/2 odds"));assertTrue(s[0].contains("T-10 WAITING"));assertTrue(s[0].contains("REAL MONEY: BLOCKED"));
        } finally { instr.runOnMainSync(a::finish); }
    }
}
