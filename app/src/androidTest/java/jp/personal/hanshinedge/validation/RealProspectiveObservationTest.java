package jp.personal.hanshinedge.validation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import jp.personal.hanshinedge.prospective.*;
import java.io.File;
import org.json.JSONObject;
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
        RealProspectiveReport.parse(r.toString());
        assertEquals(0,r.getInt("formal_buy_count"));assertEquals("BLOCKED",r.getString("REAL_MONEY"));
        // The shell explicitly checks R3/result after exporting all failure evidence.
    }
}
