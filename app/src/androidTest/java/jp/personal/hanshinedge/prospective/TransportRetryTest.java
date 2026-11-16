package jp.personal.hanshinedge.prospective;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TransportRetryTest {
    private JraSource.Page page() throws Exception {return new JraSource.Page("TEST_ONLY",new JSONObject().put("test_only",true));}

    @Test public void dnsFailureThenSuccessUsesTwoAttemptsAndRetainsFailure() throws Exception {
        AtomicInteger n=new AtomicInteger();List<Long> delays=new ArrayList<>();
        JraSource.Page p=JraSource.fetchWithTransport("D","TEST_ONLY",()->{if(n.incrementAndGet()==1)throw new UnknownHostException("TEST_ONLY");return page();},delays::add);
        assertEquals(2,n.get());assertEquals(Arrays.asList(2000L),delays);
        assertEquals(2,p.receipt.getInt("transport_attempts"));assertEquals("PASS",p.receipt.getString("final_transport_status"));
        assertEquals("UnknownHostException",p.receipt.getJSONArray("transport_errors").getString(0));
    }

    @Test public void threeDnsFailuresEndSourceUnavailableWithNoFakePage() throws Exception {
        AtomicInteger n=new AtomicInteger();List<Long> delays=new ArrayList<>();
        try { JraSource.fetchWithTransport("D","TEST_ONLY",()->{n.incrementAndGet();throw new UnknownHostException("TEST_ONLY");},delays::add);fail("exhaustion"); }
        catch(TransportRetry.Failure e) {
            assertEquals(3,n.get());assertEquals(Arrays.asList(2000L,5000L),delays);
            assertEquals("SOURCE_UNAVAILABLE",e.evidence.getString("final_transport_status"));
            assertEquals(3,e.evidence.getJSONArray("transport_errors").length());
            assertTrue(e.getCause() instanceof UnknownHostException);
        }
    }

    @Test public void http4xxAndParserFailuresDoNotRetry() throws Exception {
        for(Exception failure:new Exception[]{new IOException("HTTP_404"),new IOException("HTTP_503"),new javax.net.ssl.SSLHandshakeException("TEST_ONLY_TLS_FAILURE"),new IllegalStateException("PARSER_FAILURE")}) {
            AtomicInteger n=new AtomicInteger();List<Long> delays=new ArrayList<>();
            try { JraSource.fetchWithTransport("D","TEST_ONLY",()->{n.incrementAndGet();throw failure;},delays::add);fail("non retryable"); }
            catch(TransportRetry.Failure e) {
                assertEquals(1,n.get());assertTrue(delays.isEmpty());
                assertEquals("NON_RETRYABLE_FAILURE",e.evidence.getString("final_transport_status"));
            }
        }
    }

    @Test public void successAndExhaustionEvidenceRemainInReportTrail() throws Exception {
        AtomicInteger n=new AtomicInteger();
        JraSource.fetchWithTransport("D","TEST_ONLY_EVIDENCE",()->{if(n.incrementAndGet()<3)throw new UnknownHostException("TEST_ONLY");return page();},ms->{});
        JSONArray trail=JraSource.transportAudit();JSONObject proof=trail.getJSONObject(trail.length()-1).getJSONObject("transport");
        assertEquals(3,proof.getInt("transport_attempts"));assertEquals(2,proof.getJSONArray("transport_errors").length());
        assertEquals("PASS",proof.getString("final_transport_status"));
        try{JraSource.fetchWithTransport("D","TEST_ONLY_EXHAUST",()->{throw new UnknownHostException("TEST_ONLY");},ms->{});fail("exhaustion");}
        catch(TransportRetry.Failure expected){trail=JraSource.transportAudit();JSONObject last=trail.getJSONObject(trail.length()-1);
            assertEquals("TEST_ONLY_EXHAUST",last.getString("source_record_id"));
            assertEquals("SOURCE_UNAVAILABLE",last.getJSONObject("transport").getString("final_transport_status"));}
    }
}
