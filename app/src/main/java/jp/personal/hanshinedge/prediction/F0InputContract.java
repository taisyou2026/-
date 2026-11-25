package jp.personal.hanshinedge.prediction;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validates externally supplied Fundamental (F0) values before they may enter
 * the frozen MF0 calculation. This class does not generate F0.
 */
public final class F0InputContract {
    public static final String SCHEMA = "JRA_EDGE_F0_INPUT_CONTRACT-v1";
    public static final String FEATURE_REGISTRY_SHA256 =
            "fe8bf5a85fb5a5ca0fafb5684d052500453da59746b5774e4632e0171d03370c";
    public static final int DECISION_OFFSET_SECONDS = 300;

    private F0InputContract() {}

    public static final class Validation {
        public final boolean valid;
        public final String reason;
        public final Map<Integer, Double> f0;
        public final JSONObject provenance;

        private Validation(boolean valid, String reason, Map<Integer, Double> f0, JSONObject provenance) {
            this.valid = valid;
            this.reason = reason;
            this.f0 = f0;
            this.provenance = provenance;
        }
    }

    private static Validation fail(String reason) {
        return new Validation(false, reason, new LinkedHashMap<>(), null);
    }

    private static boolean sha256(String value) {
        return value != null && value.matches("[a-fA-F0-9]{64}");
    }

    private static long stamp(String value) {
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
            return OffsetDateTime.parse(value).toInstant().toEpochMilli();
        }
    }

    public static boolean hasAnyF0(JSONObject race) {
        JSONArray runners = race == null ? null : race.optJSONArray("runners");
        if (runners == null) return false;
        for (int i = 0; i < runners.length(); i++) {
            JSONObject runner = runners.optJSONObject(i);
            if (runner != null && runner.has("f0") && !runner.isNull("f0")) return true;
        }
        return false;
    }

    public static Validation validate(JSONObject race, long decisionAtMs) {
        try {
            if (race == null) return fail("F0_RACE_REQUIRED");
            JSONObject p = race.optJSONObject("f0_provenance");
            if (p == null) return fail("F0_PROVENANCE_REQUIRED");
            if (!SCHEMA.equals(p.optString("schema"))) return fail("F0_PROVENANCE_SCHEMA_INVALID");
            if (!FEATURE_REGISTRY_SHA256.equalsIgnoreCase(p.optString("feature_registry_sha256")))
                return fail("F0_FEATURE_REGISTRY_MISMATCH");
            if (!sha256(p.optString("evidence_sha256"))) return fail("F0_EVIDENCE_HASH_INVALID");
            if (!sha256(p.optString("source_raw_sha256"))) return fail("F0_SOURCE_HASH_INVALID");
            if (p.optString("f0_model_id").trim().isEmpty()) return fail("F0_MODEL_ID_REQUIRED");
            if (p.optBoolean("market_derived", true)) return fail("F0_MARKET_DERIVED_FORBIDDEN");
            if (p.optBoolean("upstream_model_output", true)) return fail("F0_UPSTREAM_MODEL_OUTPUT_FORBIDDEN");
            if (p.optBoolean("post_race_data_used", true)) return fail("F0_POST_RACE_DATA_FORBIDDEN");
            if (!p.optBoolean("champion_allowed", false)) return fail("F0_CHAMPION_NOT_ALLOWED");
            if (!"PREVIOUS_DATE_ONLY".equals(p.optString("historical_cutoff")))
                return fail("F0_HISTORICAL_CUTOFF_INVALID");

            String asOfText = p.optString("as_of");
            if (asOfText.isEmpty()) return fail("F0_ASOF_REQUIRED");
            long asOf = stamp(asOfText);
            long post = stamp(race.getString("scheduled_start"));
            long t5 = post - DECISION_OFFSET_SECONDS * 1000L;
            if (asOf > t5) return fail("F0_AFTER_T5_CUTOFF");
            if (asOf > decisionAtMs) return fail("F0_FUTURE_INPUT");

            JSONArray runners = race.optJSONArray("runners");
            if (runners == null) return fail("F0_RUNNERS_REQUIRED");
            LinkedHashMap<Integer, Double> values = new LinkedHashMap<>();
            int active = 0;
            double sum = 0;
            for (int i = 0; i < runners.length(); i++) {
                JSONObject runner = runners.getJSONObject(i);
                if (runner.optBoolean("scratched")) continue;
                active++;
                int no = runner.optInt("horse_no", -1);
                if (no < 1 || no > 18 || values.containsKey(no)) return fail("F0_RUNNER_IDENTITY_INVALID");
                if (!runner.has("f0") || runner.isNull("f0")) return fail("F0_NOT_READY");
                double f0 = runner.getDouble("f0");
                if (!Double.isFinite(f0) || f0 < 0) return fail("F0_VALUE_INVALID");
                values.put(no, f0);
                sum += f0;
            }
            if (active < 2 || active > 18 || values.size() != active) return fail("F0_RUNNER_COVERAGE_INVALID");
            if (!(sum > 0)) return fail("F0_PROBABILITY_INVALID");

            return new Validation(true, "F0_READY", values, new JSONObject(p.toString()));
        } catch (Exception e) {
            return fail("F0_PROVENANCE_INVALID");
        }
    }
}
