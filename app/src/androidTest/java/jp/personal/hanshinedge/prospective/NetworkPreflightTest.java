package jp.personal.hanshinedge.prospective;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.net.*;
import java.time.Instant;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Opt-in diagnostics only. Host/HEAD responses can never become race observations. */
@RunWith(AndroidJUnit4.class)
public class NetworkPreflightTest {
    private static InetAddress[] resolve() throws Exception {
        ExecutorService executor=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"jra-dns-probe");t.setDaemon(true);return t;});
        Future<InetAddress[]> future=executor.submit(()->InetAddress.getAllByName("www.jra.go.jp"));
        try { return future.get(10,TimeUnit.SECONDS); }
        catch(ExecutionException e) { if(e.getCause() instanceof Exception)throw (Exception)e.getCause();throw e; }
        catch(TimeoutException e) { future.cancel(true);throw new SocketTimeoutException("DNS_RESOLVE_TIMEOUT"); }
        finally { executor.shutdownNow(); }
    }

    @Test public void diagnoseEmulatorDnsTlsAndHttpWithoutProspectiveCredit() throws Exception {
        assertEquals("readonly",InstrumentationRegistry.getArguments().getString("network_diagnostics"));
        JSONArray attempts=new JSONArray();
        JSONObject report=new JSONObject().put("scope","ANDROID_EMULATOR_NETWORK_DIAGNOSTIC")
            .put("target","https://www.jra.go.jp/").put("observed_at",Instant.now().toString())
            .put("formal_observation_credit",0).put("REAL_MONEY","BLOCKED").put("attempts",attempts);
        try {
            TransportRetry.Result<JSONObject> result=TransportRetry.run(()->{
                JSONObject a=new JSONObject().put("attempt",attempts.length()+1).put("started_at",Instant.now().toString())
                    .put("DNS_RESOLVE","NOT_VERIFIED").put("HTTPS_CONNECT","NOT_VERIFIED")
                    .put("HTTP_RESPONSE","NOT_VERIFIED").put("HTTP_status",JSONObject.NULL);
                attempts.put(a);
                try {
                    JSONArray addresses=new JSONArray();for(InetAddress address:resolve())addresses.put(address.getHostAddress());
                    a.put("DNS_RESOLVE","PASS").put("resolved_addresses",addresses);
                    javax.net.ssl.HttpsURLConnection c=(javax.net.ssl.HttpsURLConnection)new URL("https://www.jra.go.jp/").openConnection();
                    c.setConnectTimeout(10000);c.setReadTimeout(10000);c.setInstanceFollowRedirects(false);
                    c.setRequestMethod("HEAD");c.setRequestProperty("User-Agent","Mozilla/5.0 JRA-EDGE-NetworkPreflight/1.0");
                    try {
                        int status=c.getResponseCode();
                        a.put("HTTPS_CONNECT","PASS").put("HTTP_RESPONSE","PASS").put("HTTP_status",status)
                            .put("HTTP_Date",c.getHeaderField("Date"));
                        if(status<200||status>=400)throw new IOException("HTTP_"+status);
                        return a;
                    } finally { c.disconnect(); }
                } catch(Exception e) {
                    if(!"PASS".equals(a.optString("DNS_RESOLVE")))a.put("DNS_RESOLVE","FAIL");
                    else if(!"PASS".equals(a.optString("HTTPS_CONNECT")))a.put("HTTPS_CONNECT","FAIL");
                    a.put("error",e.getClass().getSimpleName()+": "+e.getMessage());throw e;
                }
            },Thread::sleep);
            report.put("NetworkPreflight","PASS").put("transport",result.evidence);
        } catch(TransportRetry.Failure e) {
            report.put("NetworkPreflight","FAIL").put("transport",e.evidence).put("error",e.toString());
        }
        JSONObject last=attempts.getJSONObject(attempts.length()-1);
        for(String k:new String[]{"DNS_RESOLVE","HTTPS_CONNECT","HTTP_RESPONSE","HTTP_status"})report.put(k,last.get(k));
        JSONObject transport=report.getJSONObject("transport");
        for(String k:new String[]{"transport_attempts","transport_errors","final_transport_status"})report.put(k,transport.get(k));
        File dir=new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getFilesDir(),"validation/real-prospective");dir.mkdirs();
        File tmp=new File(dir,"network-preflight.json.tmp");
        try(FileOutputStream out=new FileOutputStream(tmp)){out.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));out.getFD().sync();}
        assertTrue(tmp.renameTo(new File(dir,"network-preflight.json")));
        // Shell validates actual PASS after exporting evidence. A green JUnit alone is not a network PASS.
        assertEquals(0,report.getInt("formal_observation_credit"));
    }
}
