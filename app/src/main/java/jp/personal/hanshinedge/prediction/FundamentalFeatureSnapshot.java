package jp.personal.hanshinedge.prediction;

import org.json.JSONObject;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic, fail-closed Fundamental feature snapshot.
 * It validates only provenance/as-of/data-integrity rules; it never generates F0.
 */
public final class FundamentalFeatureSnapshot {
    public static final String SCHEMA = "JRA_EDGE_FUNDAMENTAL_FEATURE_SNAPSHOT-v1";
    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");

    public static final class Snapshot {
        public final boolean valid;
        public final String reason;
        public final String raceId;
        public final int horseNo;
        public final String registryVersion;
        public final String registrySha256;
        public final long asOfMs;
        public final String evidenceSha256;
        public final JSONObject payload;

        private Snapshot(boolean valid, String reason, String raceId, int horseNo,
                         String registryVersion, String registrySha256, long asOfMs,
                         String evidenceSha256, JSONObject payload) {
            this.valid = valid;
            this.reason = reason;
            this.raceId = raceId;
            this.horseNo = horseNo;
            this.registryVersion = registryVersion;
            this.registrySha256 = registrySha256;
            this.asOfMs = asOfMs;
            this.evidenceSha256 = evidenceSha256;
            this.payload = payload;
        }
    }

    private FundamentalFeatureSnapshot() {}

    private static Snapshot fail(String reason, String raceId, int horseNo,
                                 FundamentalFeatureRegistry registry) {
        return new Snapshot(false, reason, raceId, horseNo,
                registry == null ? null : registry.version(),
                registry == null ? null : registry.sha256(),
                0L, null, null);
    }

    public static Snapshot build(FundamentalFeatureRegistry registry,
                                 String raceId,
                                 int horseNo,
                                 long scheduledStartMs,
                                 JSONObject observedFeatures) {
        try {
            if (registry == null) return fail("FEATURE_REGISTRY_REQUIRED", raceId, horseNo, null);
            String safety = registry.f0SafetyReason();
            if (!"PASS".equals(safety)) return fail(safety, raceId, horseNo, registry);
            if (raceId == null || raceId.trim().isEmpty())
                return fail("FEATURE_RACE_ID_REQUIRED", raceId, horseNo, registry);
            if (horseNo < 1 || horseNo > 18)
                return fail("FEATURE_HORSE_NO_INVALID", raceId, horseNo, registry);
            if (scheduledStartMs <= 0)
                return fail("FEATURE_SCHEDULED_START_INVALID", raceId, horseNo, registry);
            if (observedFeatures == null)
                return fail("FEATURE_VALUES_REQUIRED", raceId, horseNo, registry);

            Map<String, FundamentalFeatureRegistry.Feature> byId = new HashMap<>();
            for (FundamentalFeatureRegistry.Feature f : registry.features()) byId.put(f.id, f);

            Set<String> observedKeys = new HashSet<>();
            java.util.Iterator<String> it = observedFeatures.keys();
            while (it.hasNext()) {
                String key = it.next();
                if (!byId.containsKey(key))
                    return fail("FUNDAMENTAL_UNKNOWN_FEATURE:" + key, raceId, horseNo, registry);
                observedKeys.add(key);
            }

            long maxAsOf = 0L;
            StringBuilder evidence = new StringBuilder();
            evidence.append("{\"schema\":").append(JSONObject.quote(SCHEMA))
                    .append(",\"race_id\":").append(JSONObject.quote(raceId.trim()))
                    .append(",\"horse_no\":").append(horseNo)
                    .append(",\"scheduled_start_ms\":").append(scheduledStartMs)
                    .append(",\"registry_version\":").append(JSONObject.quote(registry.version()))
                    .append(",\"registry_sha256\":").append(JSONObject.quote(registry.sha256()))
                    .append(",\"features\":[");

            boolean first = true;
            JSONObject normalized = new JSONObject();
            for (FundamentalFeatureRegistry.Feature f : registry.features()) {
                if (!observedKeys.contains(f.id)) {
                    if (f.required)
                        return fail("FUNDAMENTAL_REQUIRED_FEATURE_MISSING:" + f.id,
                                raceId, horseNo, registry);
                    continue;
                }
                JSONObject raw = observedFeatures.optJSONObject(f.id);
                if (raw == null)
                    return fail("FUNDAMENTAL_FEATURE_RECORD_INVALID:" + f.id,
                            raceId, horseNo, registry);
                if (!raw.has("value") || raw.isNull("value"))
                    return fail("FUNDAMENTAL_FEATURE_VALUE_MISSING:" + f.id,
                            raceId, horseNo, registry);
                long availableAt = raw.optLong("available_at_ms", 0L);
                if (availableAt <= 0)
                    return fail("FUNDAMENTAL_FEATURE_ASOF_MISSING:" + f.id,
                            raceId, horseNo, registry);
                String sourceRawSha = raw.optString("source_raw_sha256").trim().toLowerCase(Locale.ROOT);
                if (!sourceRawSha.matches("[a-f0-9]{64}"))
                    return fail("FUNDAMENTAL_SOURCE_HASH_INVALID:" + f.id,
                            raceId, horseNo, registry);
                String sourceRecordId = raw.optString("source_record_id").trim();
                if (sourceRecordId.isEmpty())
                    return fail("FUNDAMENTAL_SOURCE_RECORD_ID_REQUIRED:" + f.id,
                            raceId, horseNo, registry);

                if (FundamentalFeatureRegistry.ASOF_PREVIOUS_DATE_ONLY.equals(f.asOfRule)) {
                    java.time.LocalDate featureDate = Instant.ofEpochMilli(availableAt).atZone(JST).toLocalDate();
                    java.time.LocalDate raceDate = Instant.ofEpochMilli(scheduledStartMs).atZone(JST).toLocalDate();
                    if (!featureDate.isBefore(raceDate))
                        return fail("FUNDAMENTAL_SAME_DAY_LEAKAGE:" + f.id,
                                raceId, horseNo, registry);
                } else if (FundamentalFeatureRegistry.ASOF_AT_OR_BEFORE_T5.equals(f.asOfRule)) {
                    long cutoff = scheduledStartMs - F0InputContract.DECISION_OFFSET_SECONDS * 1000L;
                    if (availableAt > cutoff)
                        return fail("FUNDAMENTAL_AFTER_T5:" + f.id,
                                raceId, horseNo, registry);
                }

                Object value = raw.get("value");
                if (!valueTypeMatches(f.type, value))
                    return fail("FUNDAMENTAL_FEATURE_TYPE_MISMATCH:" + f.id,
                            raceId, horseNo, registry);

                maxAsOf = Math.max(maxAsOf, availableAt);
                String canonicalValue = canonicalValue(value);
                if (!first) evidence.append(',');
                first = false;
                evidence.append("{\"feature_id\":").append(JSONObject.quote(f.id))
                        .append(",\"type\":").append(JSONObject.quote(f.type))
                        .append(",\"value\":").append(canonicalValue)
                        .append(",\"available_at_ms\":").append(availableAt)
                        .append(",\"source_record_id\":").append(JSONObject.quote(sourceRecordId))
                        .append(",\"source_raw_sha256\":").append(JSONObject.quote(sourceRawSha))
                        .append('}');

                normalized.put(f.id, new JSONObject()
                        .put("value", value)
                        .put("available_at_ms", availableAt)
                        .put("source_record_id", sourceRecordId)
                        .put("source_raw_sha256", sourceRawSha));
            }

            evidence.append("]}");
            String evidenceSha = FundamentalFeatureRegistry.sha256(evidence.toString());
            JSONObject payload = new JSONObject()
                    .put("schema", SCHEMA)
                    .put("race_id", raceId.trim())
                    .put("horse_no", horseNo)
                    .put("scheduled_start_ms", scheduledStartMs)
                    .put("registry_version", registry.version())
                    .put("registry_sha256", registry.sha256())
                    .put("as_of_ms", maxAsOf)
                    .put("evidence_sha256", evidenceSha)
                    .put("features", normalized);

            return new Snapshot(true, "PASS", raceId.trim(), horseNo,
                    registry.version(), registry.sha256(), maxAsOf, evidenceSha, payload);
        } catch (Exception e) {
            return fail("FUNDAMENTAL_SNAPSHOT_INVALID", raceId, horseNo, registry);
        }
    }

    private static boolean valueTypeMatches(String type, Object value) {
        if (FundamentalFeatureRegistry.TYPE_NUMBER.equals(type)) {
            if (!(value instanceof Number)) return false;
            double d = ((Number) value).doubleValue();
            return Double.isFinite(d);
        }
        if (FundamentalFeatureRegistry.TYPE_STRING.equals(type)) return value instanceof String;
        if (FundamentalFeatureRegistry.TYPE_BOOLEAN.equals(type)) return value instanceof Boolean;
        return false;
    }

    private static String canonicalValue(Object value) {
        if (value instanceof Number) {
            return new BigDecimal(value.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Boolean) return Boolean.TRUE.equals(value) ? "true" : "false";
        return JSONObject.quote(String.valueOf(value));
    }
}
