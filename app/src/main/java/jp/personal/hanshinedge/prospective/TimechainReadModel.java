package jp.personal.hanshinedge.prospective;

import org.json.*;

/** A read model of existing Runtime rows, never an alternative observation store. */
public final class TimechainReadModel {
    private TimechainReadModel() {}

    public static JSONArray stages(ProspectiveRuntime runtime, String raceId) throws Exception {
        JSONObject registration = runtime.registration(raceId);
        JSONArray out = new JSONArray();
        for (String stage : ProspectiveRuntime.STAGE) {
            JSONObject row = runtime.find("TIMECHAIN", "TC:" + raceId + ":" + stage);
            JSONObject value = row == null
                    ? new JSONObject().put("race_id", raceId).put("milestone", stage)
                        .put("status", registration == null ? "UNAVAILABLE" : "WAITING")
                        .put("error_code", registration == null ? "NOT_PROSPECTIVE" : JSONObject.NULL)
                        .put("observed_at", JSONObject.NULL).put("runner_count", JSONObject.NULL)
                        .put("odds_count", JSONObject.NULL)
                    : new JSONObject(row.getString("payload"));
            out.put(value);
        }
        return out;
    }

    public static String render(JSONArray stages) throws Exception {
        StringBuilder text = new StringBuilder("TIMECHAIN\n");
        for (int i = 0; i < stages.length(); i++) {
            JSONObject r = stages.getJSONObject(i);
            text.append(r.getString("milestone")).append(' ').append(r.getString("status"));
            if (!r.isNull("observed_at") && r.has("observed_at")) text.append('\n').append(r.getString("observed_at"));
            if (!r.isNull("odds_count") && r.has("odds_count") && !r.isNull("runner_count")) {
                text.append('\n').append(r.getInt("odds_count")).append('/').append(r.getInt("runner_count")).append(" odds");
            }
            if (r.has("error_code") && !r.isNull("error_code")) text.append(" · ").append(r.getString("error_code"));
            text.append('\n');
        }
        return text.toString();
    }
}
