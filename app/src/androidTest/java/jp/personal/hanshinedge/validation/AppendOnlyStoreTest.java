package jp.personal.hanshinedge.validation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import jp.personal.storage.audit.AppendOnlyStore;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.nio.file.Files;
import java.io.File;
import static org.junit.Assert.*;
@RunWith(AndroidJUnit4.class)
public class AppendOnlyStoreTest {
 private Context c(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}
 interface Checked{void run()throws Exception;}
 private void fails(String why,Checked run)throws Exception{try{run.run();fail(why);}catch(Exception e){assertTrue(e.toString(),e.toString().contains(why));}}
 @Test public void immutableDuplicateLinkedAppendAndFailedWriteReopen()throws Exception{
  c().deleteDatabase("generic-audit.db");AppendOnlyStore db=new AppendOnlyStore(c(),"generic-audit.db",4*1024*1024);
  JSONObject first=db.append("ROOT","TEST_ROOT",null,"{\"synthetic\":true}","GENESIS");String h=first.getString("head");
  assertEquals("DUPLICATE",db.append("ROOT","TEST_ROOT",null,"{\"synthetic\":true}","GENESIS").getString("result"));
  fails("AUDIT_KEY_CONFLICT",()->db.append("ROOT","TEST_ROOT",null,"{}",h));
  fails("AUDIT_HEAD_CONFLICT",()->db.append("RECORD","OTHER",null,"{}","GENESIS"));
  fails("AUDIT_PARENT_MISSING",()->db.append("RECORD","OTHER","MISSING","{}",h));
  db.append("RECORD","TEST_CHILD","TEST_ROOT","{\"synthetic\":true,\"unicode\":\"テスト😀\"}",h);
  fails("AUDIT_IMMUTABLE",()->db.getWritableDatabase().execSQL("UPDATE entries SET payload='{}' WHERE seq=1"));
  fails("AUDIT_IMMUTABLE",()->db.getWritableDatabase().execSQL("DELETE FROM entries WHERE seq=1"));
  db.getWritableDatabase().execSQL("CREATE TRIGGER reject_test AFTER INSERT ON entries WHEN NEW.row_key='REJECT_TEST' BEGIN SELECT RAISE(ABORT,'TEST_ONLY_FAILURE'); END");
  String current=db.verify().getString("head");fails("TEST_ONLY_FAILURE",()->db.append("RECORD","REJECT_TEST","TEST_ROOT","{}",current));assertFalse(db.getWritableDatabase().inTransaction());
  db.close();JSONObject reopened=db.verify();assertEquals(2,reopened.getInt("count"));assertEquals(current,reopened.getString("head"));assertEquals(2,db.page(0).length());assertTrue(db.verify().getBoolean("verified"));db.close();
 }
 @Test public void migrationQuotaAndExplicitRollback()throws Exception{
  c().deleteDatabase("generic-migrate.db");SQLiteDatabase raw=c().openOrCreateDatabase("generic-migrate.db",0,null);AppendOnlyStore.createV1(raw);raw.setVersion(1);raw.close();
  AppendOnlyStore db=new AppendOnlyStore(c(),"generic-migrate.db",4*1024*1024);assertEquals(2,db.verify().getInt("schema_version"));
  SQLiteDatabase live=db.getWritableDatabase();live.beginTransaction();try{live.execSQL("INSERT INTO entries VALUES(1,'TEST','TEMP',NULL,'{}','GENESIS','uncommitted','TEST_ONLY')");try(Cursor row=live.rawQuery("SELECT COUNT(*) FROM entries",null)){assertTrue(row.moveToFirst());assertEquals(1,row.getInt(0));}}finally{live.endTransaction();}
  db.close();assertEquals(0,db.verify().getInt("count"));db.close();
  c().deleteDatabase("generic-quota.db");AppendOnlyStore tiny=new AppendOnlyStore(c(),"generic-quota.db",65536);fails("AUDIT_QUOTA",()->tiny.append("ROOT","TEST",null,"{}","GENESIS"));assertFalse(tiny.getWritableDatabase().inTransaction());tiny.close();assertEquals(0,tiny.verify().getInt("count"));tiny.close();
 }
 @Test public void preserveCorruptionAndDetectTampering()throws Exception{
  c().deleteDatabase("generic-tamper.db");AppendOnlyStore db=new AppendOnlyStore(c(),"generic-tamper.db",4*1024*1024);db.append("ROOT","TEST",null,"{}","GENESIS");
  db.getWritableDatabase().execSQL("DROP TRIGGER immutable_update");db.getWritableDatabase().execSQL("UPDATE entries SET payload='{\"tampered\":true}'");fails("AUDIT_CHAIN_HASH",()->db.verify());db.close();
  c().deleteDatabase("generic-corrupt.db");AppendOnlyStore corrupt=new AppendOnlyStore(c(),"generic-corrupt.db",4*1024*1024);corrupt.verify();corrupt.close();File path=c().getDatabasePath("generic-corrupt.db");byte[] evidence="SYNTHETIC_CORRUPTION".getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(path.toPath(),evidence);
  try{corrupt.verify();fail("must detect corruption");}catch(Exception expected){assertArrayEquals(evidence,Files.readAllBytes(path.toPath()));}
 }
}
