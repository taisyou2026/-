package jp.personal.hanshinedge.prospective;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/**
 * Read-only prospective 100-race progress model.
 * Uses only committed audit rows; never creates observations, predictions, buys, or settlements.
 */
public final class ProspectiveProgressReadModel {
    public static final int TARGET_RACES = 100;

    private ProspectiveProgressReadModel() {}

    public static JSONObject collect(ProspectiveRuntime runtime) throws Exception {
        JSONArray registrations = runtime.registrations();
        JSONArray timechain = runtime.rows("TIMECHAIN");
        JSONArray results = runtime.rows("RESULT");
        JSONArray shadows = runtime.rows("SHADOW");
        JSONArray buys = runtime.rows("FORMAL_BUY");
        JSONArray settlements = runtime.rows("SETTLEMENT");

        LinkedHashMap<String, JSONObject> races = new LinkedHashMap<>();
        for (int i = 0; i < registrations.length(); i++) {
            JSONObject reg = registrations.getJSONObject(i);
            String id = reg.getString("race_id");
            JSONObject race = reg.optJSONObject("race");
            JSONObject item = new JSONObject()
                    .put("race_id", id)
                    .put("registered_at", reg.optString("registered_at"))
                    .put("scheduled_post_time", reg.optString("scheduled_post_time"))
                    .put("venue", race == null ? JSONObject.NULL : race.optString("venue"))
                    .put("race_no", race == null ? JSONObject.NULL : race.optInt("race_no"))
                    .put("timechain", new JSONObject())
                    .put("result", "WAITING")
                    .put("shadow", "NOT_RECORDED")
                    .put("formal_buy_count", 0)
                    .put("settlement_count", 0);
            races.put(id, item);
        }

        JSONObject stageRecorded = new JSONObject();
        JSONObject stagePass = new JSONObject();
        for (String stage : ProspectiveRuntime.STAGE) {
            stageRecorded.put(stage, 0);
            stagePass.put(stage, 0);
        }

        for (int i = 0; i < timechain.length(); i++) {
            JSONObject payload = new JSONObject(timechain.getJSONObject(i).getString("payload"));
            String id = payload.optString("race_id");
            String stage = payload.optString("milestone");
            if (!races.containsKey(id) || !stageRecorded.has(stage)) continue;
            String status = payload.optString("status", "UNKNOWN");
            races.get(id).getJSONObject("timechain").put(stage, status);
            stageRecorded.put(stage, stageRecorded.getInt(stage) + 1);
            if ("PASS".equals(status)) stagePass.put(stage, stagePass.getInt(stage) + 1);
        }

        Set<String> resultIds = new HashSet<>();
        for (int i = 0; i < results.length(); i++) {
            JSONObject payload = new JSONObject(results.getJSONObject(i).getString("payload"));
            String id = payload.optString("race_id");
            if (races.containsKey(id)) {
                races.get(id).put("result", "SAVED");
                resultIds.add(id);
            }
        }

        for (int i = 0; i < shadows.length(); i++) {
            JSONObject payload = new JSONObject(shadows.getJSONObject(i).getString("payload"));
            String id = payload.optString("race_id");
            if (races.containsKey(id)) races.get(id).put("shadow", "RECORDED");
        }

        for (int i = 0; i < buys.length(); i++) {
            JSONObject payload = new JSONObject(buys.getJSONObject(i).getString("payload"));
            String id = payload.optString("race_id");
            if (races.containsKey(id)) {
                JSONObject item = races.get(id);
                item.put("formal_buy_count", item.getInt("formal_buy_count") + 1);
            }
        }

        for (int i = 0; i < settlements.length(); i++) {
            JSONObject payload = new JSONObject(settlements.getJSONObject(i).getString("payload"));
            String id = payload.optString("race_id");
            if (races.containsKey(id)) {
                JSONObject item = races.get(id);
                item.put("settlement_count", item.getInt("settlement_count") + 1);
            }
        }

        int allFourRecorded = 0;
        int allFourPass = 0;
        int fullyObserved = 0;
        JSONArray raceRows = new JSONArray();
        for (JSONObject item : races.values()) {
            JSONObject stages = item.getJSONObject("timechain");
            boolean recorded = true;
            boolean pass = true;
            for (String stage : ProspectiveRuntime.STAGE) {
                if (!stages.has(stage)) {
                    stages.put(stage, "WAITING");
                    recorded = false;
                    pass = false;
                } else if (!"PASS".equals(stages.optString(stage))) {
                    pass = false;
                }
            }
            if (recorded) allFourRecorded++;
            if (pass) allFourPass++;
            boolean resultSaved = "SAVED".equals(item.optString("result"));
            if (recorded && resultSaved) fullyObserved++;
            item.put("timechain_all_four_recorded", recorded)
                    .put("timechain_all_four_pass", pass)
                    .put("fully_observed", recorded && resultSaved);
            raceRows.put(item);
        }

        int registered = registrations.length();
        double progressPct = Math.min(100.0, fullyObserved * 100.0 / TARGET_RACES);
        String status = fullyObserved >= TARGET_RACES ? "COMPLETE" : registered > 0 ? "IN_PROGRESS" : "NOT_STARTED";

        return new JSONObject()
                .put("schema", "JRA_EDGE_PROSPECTIVE_100R_PROGRESS-v1")
                .put("target_races", TARGET_RACES)
                .put("status", status)
                .put("registered_races", registered)
                .put("timechain_all_four_recorded", allFourRecorded)
                .put("timechain_all_four_pass", allFourPass)
                .put("result_linked_races", resultIds.size())
                .put("fully_observed_races", fullyObserved)
                .put("progress_percent", progressPct)
                .put("stage_recorded", stageRecorded)
                .put("stage_pass", stagePass)
                .put("shadow_rows", shadows.length())
                .put("formal_buy_rows", buys.length())
                .put("settlement_rows", settlements.length())
                .put("F0_STATUS", "NOT_AVAILABLE")
                .put("STAKE_RULE", ProspectiveRuntime.STAKE_RULE)
                .put("FORMAL_PAPER_BUY", "BLOCKED")
                .put("REAL_MONEY", "BLOCKED")
                .put("AUTO_REAL_BET", "DISABLED")
                .put("races", raceRows);
    }
}
