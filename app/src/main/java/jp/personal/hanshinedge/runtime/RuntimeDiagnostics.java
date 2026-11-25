package jp.personal.hanshinedge.runtime;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.PowerManager;
import android.os.StatFs;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import jp.personal.hanshinedge.prospective.BackgroundScheduler;
import jp.personal.hanshinedge.prospective.ProspectiveRuntime;

/**
 * Privacy-minimal physical-device evidence bundle.
 * Does not read IMEI, phone number, accounts, contacts, advertising ID, or location.
 */
public final class RuntimeDiagnostics {
    public static final String SCHEMA = "JRA_EDGE_DEVICE_DIAGNOSTIC-v1";

    private RuntimeDiagnostics() {}

    public static JSONObject collect(Context context, ProspectiveRuntime runtime) throws Exception {
        Context c = context.getApplicationContext();
        PackageInfo pkg = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
        StatFs stat = new StatFs(c.getFilesDir().getAbsolutePath());
        PowerManager power = (PowerManager)c.getSystemService(Context.POWER_SERVICE);

        JSONObject stores = new JSONObject()
                .put("FETCH", runtime.runtime.fetchStatus())
                .put("TIMECHAIN", runtime.runtime.timechainStatus())
                .put("RESULT", runtime.runtime.resultStatus())
                .put("SHADOW", runtime.runtime.shadowStatus())
                .put("FORMAL_BUY", runtime.runtime.formalBuyStatus())
                .put("SETTLEMENT", runtime.runtime.settlementStatus());

        boolean allVerified = true;
        for (String key : new String[]{"FETCH","TIMECHAIN","RESULT","SHADOW","FORMAL_BUY","SETTLEMENT"}) {
            allVerified = allVerified && stores.getJSONObject(key).optBoolean("verified", false);
        }

        JSONArray abis = new JSONArray();
        for (String abi : Build.SUPPORTED_ABIS) abis.put(abi);

        JSONObject device = new JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL)
                .put("android_release", Build.VERSION.RELEASE)
                .put("api_level", Build.VERSION.SDK_INT)
                .put("abis", abis)
                .put("available_storage_bytes", stat.getAvailableBytes())
                .put("battery_optimization_ignored", power != null && power.isIgnoringBatteryOptimizations(c.getPackageName()));

        JSONObject app = new JSONObject()
                .put("package", c.getPackageName())
                .put("version_name", pkg.versionName == null ? JSONObject.NULL : pkg.versionName)
                .put("version_code", Build.VERSION.SDK_INT >= 28 ? pkg.getLongVersionCode() : pkg.versionCode);

        JSONObject safety = new JSONObject()
                .put("REAL_MONEY", "BLOCKED")
                .put("AUTO_REAL_BET", "DISABLED")
                .put("F0_STATUS", "NOT_AVAILABLE")
                .put("STAKE_RULE", ProspectiveRuntime.STAKE_RULE)
                .put("FORMAL_PAPER_BUY", "BLOCKED");

        return new JSONObject()
                .put("schema", SCHEMA)
                .put("generated_at", Instant.now().toString())
                .put("privacy", "NO_IMEI_NO_PHONE_NO_ACCOUNTS_NO_AD_ID_NO_LOCATION")
                .put("device", device)
                .put("app", app)
                .put("database_integrity", allVerified ? "PASS" : "FAIL")
                .put("stores", stores)
                .put("background", BackgroundScheduler.status(c))
                .put("bankroll", runtime.bankroll())
                .put("safety", safety);
    }
}
