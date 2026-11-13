package jp.personal.storage.audit;

import android.content.Context;
import org.json.JSONObject;

/** Generic native isolation policy. No source adapter, prediction model or application data. */
public final class ReadinessRuntime implements AutoCloseable {
    private final AppendOnlyStore formal;
    private final AppendOnlyStore readiness;
    public ReadinessRuntime(Context c,String formalName,String readinessName,long quota) {
        if(formalName.equals(readinessName))throw new IllegalArgumentException("DISTINCT_DATABASES_REQUIRED");
        formal=new AppendOnlyStore(c,formalName,quota);
        readiness=new AppendOnlyStore(c,readinessName,quota);
    }
    public static void blockFormalStart(){throw new IllegalStateException("REAL_START_BLOCKED");}
    public static JSONObject appendReadiness(AppendOnlyStore store,String kind,String key,String parent,String payload,String head)throws Exception {
        JSONObject record=new JSONObject(payload);
        if(!"READINESS_ONLY".equals(record.optString("data_status"))||!"BLOCKED".equals(record.optString("realMoney")))throw new IllegalArgumentException("READINESS_ONLY_REQUIRED");
        return store.append(kind,key,parent,payload,head);
    }
    public JSONObject appendFormal(String payload)throws Exception {blockFormalStart();return new JSONObject();}
    public JSONObject append(String key,String payload,String head)throws Exception {return appendReadiness(readiness,"RECORD",key,null,payload,head);}
    public JSONObject formalStatus()throws Exception{return formal.verify();}
    public JSONObject readinessStatus()throws Exception{return readiness.verify();}
    public JSONObject checkpoint()throws Exception{return readiness.checkpoint();}
    public JSONObject verifyCheckpoint(JSONObject external)throws Exception{return readiness.verifyCheckpoint(external);}
    @Override public void close(){formal.close();readiness.close();}
}
