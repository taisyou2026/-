package jp.personal.hanshinedge.validation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import jp.personal.hanshinedge.prospective.*;
import java.io.File;
import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Opt-in, long-running official observation, not part of the synthetic regression suite. */
@RunWith(AndroidJUnit4.class)
public class RealProspectiveObservationTest {
    @Test public void observeActualOfficialDateWithoutRetroactiveCredit() throws Exception {
        assertEquals("observe",InstrumentationRegistry.getArguments().getString("observation_mode"));
        android.content.Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject r=new RealProspectiveObserver(c,new File(c.getFilesDir(),"validation/real-prospective")).run();
        JSONArray transport=JraSource.transportAudit(),errors=new JSONArray();int attempts=0;String finalStatus="NOT_VERIFIED";
        for(int i=0;i<transport.length();i++){JSONObject proof=transport.getJSONObject(i).getJSONObject("transport");attempts+=proof.getInt("transport_attempts");JSONArray failed=proof.getJSONArray("transport_errors");for(int j=0;j<failed.length();j++)errors.put(failed.get(j));finalStatus=proof.getString("final_transport_status");}
        r.put("transport_log",transport).put("transport_attempts",attempts).put("transport_errors",errors).put("final_transport_status",finalStatus).put("transport_scope","ALL_JRA_SOURCE_REQUESTS_IN_OBSERVER_PROCESS");
        JSONObject discovery=r.getJSONObject("discovery");r.put("meetings",discovery.has("meetings")?discovery.getJSONArray("meetings").length():"UNKNOWN");
        File dir=new File(c.getFilesDir(),"validation/real-prospective"),tmp=new File(dir,"real-prospective-summary.json.transport.tmp");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(tmp)){out.write(r.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
        assertTrue(tmp.renameTo(new File(dir,"real-prospective-summary.json")));
        RealProspectiveReport.parse(r.toString());
        assertEquals(0,r.getInt("formal_buy_count"));assertEquals("BLOCKED",r.getString("REAL_MONEY"));
        // The shell explicitly checks R3/result after exporting all failure evidence.
    }
}
