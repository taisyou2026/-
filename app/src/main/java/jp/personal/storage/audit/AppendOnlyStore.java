package jp.personal.storage.audit;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/** Generic append-only, hash-chained audit storage. Contains no application model or dataset. */
public final class AppendOnlyStore extends SQLiteOpenHelper {
    private final Context context;
    private final String filename;
    private final long quota;
    public AppendOnlyStore(Context c,String name,long quotaBytes) {
        super(c,name,null,2,db->{throw new IllegalStateException("AUDIT_CORRUPT_PRESERVED");});
        context=c.getApplicationContext();filename=name;quota=quotaBytes;setWriteAheadLoggingEnabled(true);
    }
    public static void createV1(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE entries(seq INTEGER PRIMARY KEY AUTOINCREMENT,kind TEXT NOT NULL,row_key TEXT UNIQUE NOT NULL,parent_key TEXT,payload TEXT NOT NULL,previous_hash TEXT NOT NULL,hash TEXT NOT NULL,created_at TEXT NOT NULL)");
    }
    private static void v2(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX entries_parent ON entries(parent_key)");
        db.execSQL("CREATE TRIGGER immutable_update BEFORE UPDATE ON entries BEGIN SELECT RAISE(ABORT,'AUDIT_IMMUTABLE'); END");
        db.execSQL("CREATE TRIGGER immutable_delete BEFORE DELETE ON entries BEGIN SELECT RAISE(ABORT,'AUDIT_IMMUTABLE'); END");
    }
    @Override public void onCreate(SQLiteDatabase db){createV1(db);v2(db);}
    @Override public void onUpgrade(SQLiteDatabase db,int old,int next){if(old!=1||next!=2)throw new IllegalStateException("AUDIT_MIGRATION");v2(db);}
    @Override public void onDowngrade(SQLiteDatabase db,int old,int next){throw new IllegalStateException("AUDIT_DOWNGRADE");}
    private static void require(boolean ok,String reason){if(!ok)throw new IllegalArgumentException(reason);}
    public static String sha(String text)throws Exception{
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();
        for(byte b:hash)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();
    }
    private static String hash(long seq,String kind,String key,String parent,String payload,String previous,String at)throws Exception{
        StringBuilder material=new StringBuilder();
        for(String field:new String[]{Long.toString(seq),kind,key,parent,payload,previous,at})material.append(field==null?"-1:":field.length()+":"+field);
        return sha(material.toString());
    }
    private static String scalar(SQLiteDatabase db,String sql,String...args){try(Cursor c=db.rawQuery(sql,args)){return c.moveToFirst()?c.getString(0):null;}}
    public synchronized long bytes(){File f=context.getDatabasePath(filename);return f.length()+new File(f+"-wal").length()+new File(f+"-shm").length();}
    public synchronized JSONObject verify()throws Exception{
        SQLiteDatabase db=getReadableDatabase();require("ok".equals(scalar(db,"PRAGMA quick_check")),"AUDIT_INTEGRITY");String previous="GENESIS";long n=0;
        try(Cursor c=db.rawQuery("SELECT seq,kind,row_key,parent_key,payload,previous_hash,hash,created_at FROM entries ORDER BY seq",null)){
            while(c.moveToNext()){long seq=c.getLong(0);require(seq==++n&&previous.equals(c.getString(5)),"AUDIT_CHAIN_LINK");
                String digest=hash(seq,c.getString(1),c.getString(2),c.isNull(3)?null:c.getString(3),c.getString(4),previous,c.getString(7));require(digest.equals(c.getString(6)),"AUDIT_CHAIN_HASH");previous=digest;}
        }
        return new JSONObject().put("schema_version",db.getVersion()).put("count",n).put("head",previous).put("bytes",bytes()).put("verified",true);
    }
    public synchronized JSONObject checkpoint()throws Exception {
        JSONObject state=verify();return new JSONObject().put("schema","APPEND_ONLY_CHECKPOINT-v1").put("count",state.getLong("count")).put("head",state.getString("head")).put("exported_at",Instant.now().toString());
    }
    public synchronized JSONObject verifyCheckpoint(JSONObject external)throws Exception {
        JSONObject state=verify();require(state.getLong("count")==external.getLong("count")&&state.getString("head").equals(external.getString("head")),"AUDIT_CHECKPOINT_MISMATCH");return state;
    }
    public synchronized JSONObject append(String kind,String key,String parent,String payload,String expectedHead)throws Exception{
        require(kind.matches("[A-Z_]{2,24}")&&key.length()>0&&key.length()<=256,"AUDIT_KEY");require(payload.getBytes(StandardCharsets.UTF_8).length<=262144,"AUDIT_RECORD_LIMIT");new JSONObject(payload);
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            JSONObject state=verify();
            String existing=scalar(db,"SELECT payload FROM entries WHERE row_key=?",key);
            if(existing!=null){require(existing.equals(payload)&&kind.equals(scalar(db,"SELECT kind FROM entries WHERE row_key=?",key))&&java.util.Objects.equals(parent,scalar(db,"SELECT parent_key FROM entries WHERE row_key=?",key)),"AUDIT_KEY_CONFLICT");return state.put("result","DUPLICATE");}
            require(expectedHead.equals(state.getString("head")),"AUDIT_HEAD_CONFLICT");
            if(parent!=null)require(scalar(db,"SELECT 1 FROM entries WHERE row_key=?",parent)!=null,"AUDIT_PARENT_MISSING");
            require(bytes()+payload.getBytes(StandardCharsets.UTF_8).length*4L+65536<=quota,"AUDIT_QUOTA");
            long seq=state.getLong("count")+1;String at=Instant.now().toString(),previous=state.getString("head"),digest=hash(seq,kind,key,parent,payload,previous,at);
            db.execSQL("INSERT INTO entries(seq,kind,row_key,parent_key,payload,previous_hash,hash,created_at) VALUES(?,?,?,?,?,?,?,?)",new Object[]{seq,kind,key,parent,payload,previous,digest,at});
            require(bytes()<=quota,"AUDIT_QUOTA");db.setTransactionSuccessful();return new JSONObject().put("result","APPENDED").put("count",seq).put("head",digest).put("created_at",at);
        }finally{db.endTransaction();}
    }
    public synchronized JSONArray page(long after)throws Exception{
        verify();JSONArray result=new JSONArray();int bytes=0;
        try(Cursor c=getReadableDatabase().rawQuery("SELECT seq,kind,row_key,parent_key,payload,previous_hash,hash,created_at FROM entries WHERE seq>? ORDER BY seq LIMIT 10",new String[]{""+after})){
            while(c.moveToNext()){JSONObject row=new JSONObject().put("seq",c.getLong(0)).put("kind",c.getString(1)).put("key",c.getString(2)).put("parent",c.isNull(3)?JSONObject.NULL:c.getString(3)).put("payload",c.getString(4)).put("previous_hash",c.getString(5)).put("hash",c.getString(6)).put("created_at",c.getString(7));int size=row.toString().getBytes(StandardCharsets.UTF_8).length;if(bytes+size>900000&&result.length()>0)break;bytes+=size;result.put(row);}
        }return result;
    }
}
