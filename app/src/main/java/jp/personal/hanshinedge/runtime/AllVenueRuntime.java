package jp.personal.hanshinedge.runtime;

import android.content.Context;
import jp.personal.storage.audit.AppendOnlyStore;
import org.json.JSONObject;

/**
 * Native all-venue runtime with physically separated audit stores.
 * Fetch, TIMECHAIN, result, shadow history, formal paper BUY and settlement
 * are isolated in different SQLite files. Real-money execution is not present.
 */
public final class AllVenueRuntime implements AutoCloseable {
    private final AppendOnlyStore fetch;
    private final AppendOnlyStore timechain;
    private final AppendOnlyStore result;
    private final AppendOnlyStore shadow;
    private final AppendOnlyStore formalBuy;
    private final AppendOnlyStore settlement;

    public AllVenueRuntime(Context context, String prefix, long quotaPerStore) {
        if (prefix == null || prefix.trim().isEmpty()) throw new IllegalArgumentException("PREFIX_REQUIRED");
        String p = prefix.trim();
        fetch = new AppendOnlyStore(context, p + "-fetch.db", quotaPerStore);
        timechain = new AppendOnlyStore(context, p + "-timechain.db", quotaPerStore);
        result = new AppendOnlyStore(context, p + "-result.db", quotaPerStore);
        shadow = new AppendOnlyStore(context, p + "-shadow.db", quotaPerStore);
        formalBuy = new AppendOnlyStore(context, p + "-formal-buy.db", quotaPerStore);
        settlement = new AppendOnlyStore(context, p + "-settlement.db", quotaPerStore);
    }

    private static JSONObject envelope(String payload, CapabilityGate.RaceContext race, String status) throws Exception {
        JSONObject p = new JSONObject(payload);
        if (!CapabilityGate.REAL_MONEY.equals(p.optString("realMoney")))
            throw new IllegalArgumentException("REAL_MONEY_MUST_BE_BLOCKED");
        if (!race.raceId.equals(p.optString("race_id")))
            throw new IllegalArgumentException("RACE_ID_MISMATCH");
        if (!status.equals(p.optString("data_status")))
            throw new IllegalArgumentException("DATA_STATUS_REQUIRED:" + status);
        return p;
    }

    public JSONObject appendFetch(CapabilityGate.RaceContext race, String key, String payload, String head) throws Exception {
        envelope(payload, race, "READINESS_ONLY");
        return fetch.append("FETCH", key, null, payload, head);
    }

    public JSONObject appendTimechain(CapabilityGate.RaceContext race, String key, String payload, String head) throws Exception {
        envelope(payload, race, "READINESS_ONLY");
        return timechain.append("TIMECHAIN", key, null, payload, head);
    }

    public JSONObject appendResult(CapabilityGate.RaceContext race, String key, String payload, String head) throws Exception {
        envelope(payload, race, "READINESS_ONLY");
        return result.append("RESULT", key, null, payload, head);
    }

    public JSONObject appendShadow(CapabilityGate.RaceContext race, String key, String payload, String head) throws Exception {
        JSONObject p = envelope(payload, race, "READINESS_ONLY");
        if (race.obstacle && p.optBoolean("model_applied", false))
            throw new IllegalArgumentException("OBSTACLE_MODEL_BLOCKED");
        return shadow.append("SHADOW", key, null, payload, head);
    }

    public JSONObject appendFormalPaperBuy(CapabilityGate.RaceContext race, String ticketId,
                                            String payload, String head) throws Exception {
        CapabilityGate.requireFormalPaperBuy(race);
        JSONObject p = envelope(payload, race, "PROSPECTIVE");
        if (!p.optBoolean("paper", false)) throw new IllegalArgumentException("PAPER_BUY_REQUIRED");
        if (p.optBoolean("result_known", true)) throw new IllegalArgumentException("RESULT_ALREADY_KNOWN");
        if (!CapabilityGate.FROZEN_MODEL.equals(p.optString("model_tag")))
            throw new IllegalArgumentException("FROZEN_MODEL_REQUIRED");
        String key = buyKey(race.raceId, ticketId);
        return formalBuy.append("FORMAL_BUY", key, null, payload, head);
    }

    public JSONObject settleOnce(CapabilityGate.RaceContext race, String ticketId,
                                 String payload, String head) throws Exception {
        JSONObject p = envelope(payload, race, "SETTLEMENT");
        if (race.obstacle) throw new IllegalStateException("SETTLEMENT_SCOPE_BLOCKED:OBSTACLE");
        if (!"HANSHIN".equals(race.venue))
            throw new IllegalStateException("SETTLEMENT_SCOPE_BLOCKED:OUTSIDE_FROZEN_VENUE");
        if (!CapabilityGate.FROZEN_MODEL.equals(race.modelTag))
            throw new IllegalStateException("SETTLEMENT_SCOPE_BLOCKED:MODEL_TAG_NOT_FROZEN");
        String buyKey = buyKey(race.raceId, ticketId);
        if (!formalBuy.containsKey(buyKey)) throw new IllegalStateException("BUY_NOT_FOUND");
        String key = "SETTLEMENT:" + race.raceId + ":" + ticketId;
        return settlement.append("SETTLEMENT", key, null, payload, head);
    }

    public static String buyKey(String raceId, String ticketId) {
        if (ticketId == null || ticketId.trim().isEmpty()) throw new IllegalArgumentException("TICKET_ID_REQUIRED");
        return "BUY:" + raceId + ":" + ticketId.trim();
    }

    public JSONObject fetchStatus() throws Exception { return fetch.verify(); }
    public JSONObject timechainStatus() throws Exception { return timechain.verify(); }
    public JSONObject resultStatus() throws Exception { return result.verify(); }
    public JSONObject shadowStatus() throws Exception { return shadow.verify(); }
    public JSONObject formalBuyStatus() throws Exception { return formalBuy.verify(); }
    public JSONObject settlementStatus() throws Exception { return settlement.verify(); }

    public org.json.JSONArray fetchPage(long after)throws Exception{return fetch.page(after);}
    public org.json.JSONArray timechainPage(long after)throws Exception{return timechain.page(after);}
    public org.json.JSONArray resultPage(long after)throws Exception{return result.page(after);}
    public org.json.JSONArray shadowPage(long after)throws Exception{return shadow.page(after);}
    public org.json.JSONArray formalBuyPage(long after)throws Exception{return formalBuy.page(after);}
    public org.json.JSONArray settlementPage(long after)throws Exception{return settlement.page(after);}

    @Override public void close() {
        fetch.close();
        timechain.close();
        result.close();
        shadow.close();
        formalBuy.close();
        settlement.close();
    }
}
