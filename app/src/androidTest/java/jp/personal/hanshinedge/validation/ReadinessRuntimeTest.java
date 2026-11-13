package jp.personal.hanshinedge.validation;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import jp.personal.storage.audit.ReadinessRuntime;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class ReadinessRuntimeTest {
 private Context c(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
 interface Checked{void run()throws Exception;}
 private void fails(String why,Checked action)throws Exception{try{action.run();fail(why);}catch(Exception e){assertTrue(e.toString(),e.toString().contains(why));}}
 @Test public void nativeIsolationAndStartBlock()throws Exception{
  c().deleteDatabase("formal_test.db");c().deleteDatabase("readiness_test.db");ReadinessRuntime runtime=new ReadinessRuntime(c(),"formal_test.db","readiness_test.db",16*1024*1024);
  String head="GENESIS";for(int i=0;i<100;i++)head=runtime.append("SYNTHETIC_"+i,"{\"data_status\":\"READINESS_ONLY\",\"realMoney\":\"BLOCKED\",\"synthetic\":true}",head).getString("head");
  assertEquals(100,runtime.readinessStatus().getInt("count"));assertEquals(0,runtime.formalStatus().getInt("count"));
  fails("REAL_START_BLOCKED",()->runtime.appendFormal("{}"));fails("REAL_START_BLOCKED",()->ReadinessRuntime.blockFormalStart());assertEquals(0,runtime.formalStatus().getInt("count"));runtime.close();
  ReadinessRuntime reopened=new ReadinessRuntime(c(),"formal_test.db","readiness_test.db",16*1024*1024);assertEquals(100,reopened.readinessStatus().getInt("count"));assertEquals(head,reopened.readinessStatus().getString("head"));assertEquals(0,reopened.formalStatus().getInt("count"));assertTrue(reopened.readinessStatus().getBoolean("verified"));
  File out=new File(c().getFilesDir(),"validation");assertTrue(out.isDirectory()||out.mkdirs());JSONObject report=new JSONObject().put("formal_count",0).put("readiness_count",100).put("native_start_block","PASS").put("reopen_chain","PASS").put("data_status","READINESS_ONLY").put("process_death_recovery","PROCESS_DEATH_RECOVERY_NOT_FULLY_SIMULATED");Files.write(new File(out,"native-readiness-runtime.json").toPath(),report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));reopened.close();
 }
 @Test public void requireReadinessEnvelope()throws Exception{
  c().deleteDatabase("envelope_formal.db");c().deleteDatabase("envelope_readiness.db");ReadinessRuntime r=new ReadinessRuntime(c(),"envelope_formal.db","envelope_readiness.db",1024*1024);
  fails("READINESS_ONLY_REQUIRED",()->r.append("MISSING","{}","GENESIS"));fails("READINESS_ONLY_REQUIRED",()->r.append("WRONG_STATUS","{\"data_status\":\"REAL\",\"realMoney\":\"BLOCKED\"}","GENESIS"));fails("READINESS_ONLY_REQUIRED",()->r.append("WRONG_MONEY","{\"data_status\":\"READINESS_ONLY\",\"realMoney\":\"ENABLED\"}","GENESIS"));assertEquals(0,r.readinessStatus().getInt("count"));assertEquals(0,r.formalStatus().getInt("count"));r.close();
 }
 @Test public void trustedReadinessCheckpointRejectsTruncation()throws Exception{
  c().deleteDatabase("anchor_formal.db");c().deleteDatabase("anchor_readiness.db");ReadinessRuntime r=new ReadinessRuntime(c(),"anchor_formal.db","anchor_readiness.db",16*1024*1024);String head="GENESIS",payload="{\"data_status\":\"READINESS_ONLY\",\"realMoney\":\"BLOCKED\"}";
  for(int i=1;i<=80;i++)head=r.append("SYN_"+i,payload,head).getString("head");r.close();File db=c().getDatabasePath("anchor_readiness.db"),prefix=new File(db+".prefix80");Files.copy(db.toPath(),prefix.toPath(),StandardCopyOption.REPLACE_EXISTING);
  for(int i=81;i<=100;i++)head=r.append("SYN_"+i,payload,head).getString("head");JSONObject trusted=r.checkpoint();assertEquals(100,r.verifyCheckpoint(trusted).getInt("count"));r.close();Files.copy(prefix.toPath(),db.toPath(),StandardCopyOption.REPLACE_EXISTING);assertEquals(80,r.readinessStatus().getInt("count"));fails("AUDIT_CHECKPOINT_MISMATCH",()->r.verifyCheckpoint(trusted));assertEquals(0,r.formalStatus().getInt("count"));r.close();
 }
}
