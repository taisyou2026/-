package jp.personal.hanshinedge.prospective;

import org.json.*;
import java.time.Instant;
import java.util.*;

/** Fail-closed evidence validation. An artifact's claimed PASS is never trusted. */
public final class RealProspectiveReport {
    public static final String SCHEMA = "REAL_PROSPECTIVE_READONLY-v1";
    private RealProspectiveReport() {}

    public static boolean registrationPass(JSONObject r) {
        try {
            return r.getBoolean("REAL_PROSPECTIVE") && !r.optBoolean("test_only")
                && r.getString("reason").equals("REGISTERED_PRE_RACE")
                && !r.getBoolean("result_known") && r.getJSONObject("clock").getBoolean("verified")
                && r.getJSONObject("race").getBoolean("source_identity_verified")
                && Math.abs(r.getJSONObject("clock").getLong("clock_skew_ms")) <= ProspectiveRuntime.CLOCK_LIMIT_MS
                && r.getString("source_raw_sha256").matches("[a-f0-9]{64}")
                && !r.getString("source_record_id").isEmpty()
                && r.getString("race_id").equals(ProspectiveRuntime.canonical(r.getJSONObject("race")))
                && ProspectiveRuntime.stamp(r.getString("source_retrieved_at")) <= ProspectiveRuntime.stamp(r.getString("registered_at"))
                && ProspectiveRuntime.stamp(r.getString("registered_at")) < ProspectiveRuntime.stamp(r.getString("scheduled_post_time"));
        } catch (Exception e) { return false; }
    }

    public static boolean milestonePass(JSONObject registration, JSONObject s, int index) {
        try {
            if (!registrationPass(registration) || !s.getString("race_id").equals(registration.getString("race_id"))
                    || !s.getString("milestone").equals(ProspectiveRuntime.STAGE[index])
                    || !s.getString("status").equals("PASS") || s.optBoolean("test_only")
                    || !s.getBoolean("REAL_PROSPECTIVE") || !s.getBoolean("clock_verified")
                    || !s.getBoolean("source_identity_verified") || !s.getBoolean("roster_identity_valid")
                    || s.getBoolean("result_known") || s.getBoolean("race_started")
                    || s.getInt("http_status") != 200 || s.getLong("raw_size") <= 0
                    || !s.getString("source_raw_sha256").matches("[a-f0-9]{64}")
                    || s.getString("source_record_id").isEmpty() || s.getString("http_date").isEmpty()
                    || !s.getString("source_uri").startsWith("https://www.jra.go.jp/JRADB/access")
                    || Math.abs(s.getLong("clock_skew_ms")) > ProspectiveRuntime.CLOCK_LIMIT_MS
                    || s.getInt("runner_count") < 2 || s.getInt("runner_count") != s.getInt("odds_count")
                    || s.getDouble("completeness") != 1.0 || !s.getString("realMoney").equals("BLOCKED")) return false;
            long post = ProspectiveRuntime.stamp(registration.getString("scheduled_post_time"));
            long target = post - ProspectiveRuntime.TARGET[index] * 1000L;
            long at = ProspectiveRuntime.stamp(s.getString("observed_at"));
            long trusted = ProspectiveRuntime.stamp(s.getString("trusted_observed_at"));
            long retrieved = ProspectiveRuntime.stamp(s.getString("source_retrieved_at"));
            if (ProspectiveRuntime.stamp(s.getString("scheduled_post_time")) != post
                    || ProspectiveRuntime.stamp(s.getString("target_time")) != target
                    || s.getLong("target_error_ms") != at - target
                    || s.getInt("target_offset") != ProspectiveRuntime.TARGET[index]
                    || s.getInt("tolerance_seconds") != ProspectiveRuntime.TOLERANCE[index]
                    || Math.abs(at - target) > ProspectiveRuntime.TOLERANCE[index] * 1000L
                    || Math.abs(trusted - target) > ProspectiveRuntime.TOLERANCE[index] * 1000L
                    || at >= post || trusted >= post || retrieved > at
                    || at < ProspectiveRuntime.stamp(registration.getString("registered_at"))) return false;
            JSONArray odds = s.getJSONArray("odds");
            if (odds.length() != s.getInt("runner_count")) return false;
            JSONArray roster=s.getJSONArray("roster"), reconstructed=new JSONArray();
            Set<Integer> active=new HashSet<>();
            for(int i=0;i<roster.length();i++) {
                JSONObject h=roster.getJSONObject(i);int no=h.getInt("horse_number");
                boolean scratched=h.getBoolean("scratched");
                if(h.getBoolean("active")==scratched) return false;
                if(!scratched) active.add(no);
                reconstructed.put(new JSONObject().put("horse_no",no).put("scratched",scratched));
            }
            if(!ProspectiveRuntime.validRoster(registration.getJSONObject("race"),new JSONObject().put("runners",reconstructed))) return false;
            Set<Integer> numbers = new HashSet<>();
            double probability = 0;
            for (int i = 0; i < odds.length(); i++) {
                JSONObject h = odds.getJSONObject(i);
                int n = h.getInt("horse_number"); double o = h.getDouble("win_odds"), p = h.getDouble("market_probability");
                if (n < 1 || n > 18 || !numbers.add(n) || !Double.isFinite(o) || o < 1
                        || !Double.isFinite(p) || p <= 0 || p > 1 || h.getInt("market_rank") < 1) return false;
                probability += p;
            }
            return active.equals(numbers) && Math.abs(probability - 1) < 0.000001;
        } catch (Exception e) { return false; }
    }

    public static JSONObject gates(JSONArray races) throws Exception {
        int registered = 0, r2 = 0, complete = 0;
        Set<String> unique = new HashSet<>();
        for (int i = 0; i < races.length(); i++) {
            JSONObject r = races.getJSONObject(i), reg = r.getJSONObject("registration");
            if (!unique.add(reg.getString("race_id"))) throw new IllegalArgumentException("DUPLICATE_REPORT_RACE");
            boolean ok = registrationPass(reg);
            if (ok) registered++;
            JSONArray stages = r.getJSONArray("milestones");
            if (stages.length() != 4) continue;
            boolean[] pass = new boolean[4];
            for (int j = 0; j < 4; j++) pass[j] = milestonePass(reg, stages.getJSONObject(j), j);
            if (pass[0] && pass[1]) r2++;
            if (pass[0] && pass[1] && pass[2] && pass[3]) complete++;
        }
        return new JSONObject().put("REAL_GATE_R1", registered > 0 ? "PASS" : "NOT_MET")
            .put("REAL_GATE_R2", r2 > 0 ? "PASS" : "NOT_MET")
            .put("REAL_GATE_R3", complete > 0 ? "PASS" : "NOT_MET")
            .put("REAL_GATE_R4", complete >= 3 ? "PASS" : "NOT_MET")
            .put("verified_registrations", registered).put("four_milestone_races", complete);
    }

    public static JSONObject parse(String json) throws Exception {
        JSONObject report = new JSONObject(json);
        if (!SCHEMA.equals(report.getString("schema")) || !"BLOCKED".equals(report.getString("REAL_MONEY"))
                || !"DISABLED".equals(report.getString("AUTO_REAL_BET")) || report.getInt("formal_buy_count") != 0)
            throw new IllegalArgumentException("REPORT_POLICY_INVALID");
        JSONObject computed = gates(report.getJSONArray("races"));
        for (String gate : new String[]{"REAL_GATE_R1", "REAL_GATE_R2", "REAL_GATE_R3", "REAL_GATE_R4"})
            if (!computed.getString(gate).equals(report.getJSONObject("gates").getString(gate)))
                throw new IllegalArgumentException("REPORT_GATE_MISMATCH");
        return report;
    }
}
