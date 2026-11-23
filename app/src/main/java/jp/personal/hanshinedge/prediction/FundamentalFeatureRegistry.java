package jp.personal.hanshinedge.prediction;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic contract for a future authoritative Fundamental feature registry.
 * This class defines validation/canonicalization only; it does not invent Champion features.
 */
public final class FundamentalFeatureRegistry {
    public static final String SCHEMA = "JRA_EDGE_FUNDAMENTAL_FEATURE_REGISTRY-v1";
    public static final String TYPE_NUMBER = "NUMBER";
    public static final String TYPE_STRING = "STRING";
    public static final String TYPE_BOOLEAN = "BOOLEAN";
    public static final String ASOF_PREVIOUS_DATE_ONLY = "PREVIOUS_DATE_ONLY";
    public static final String ASOF_AT_OR_BEFORE_T5 = "AT_OR_BEFORE_T5";
    public static final String MISSING_REJECT = "REJECT";
    public static final String MISSING_OMIT = "OMIT";

    public static final class Feature {
        public final String id;
        public final String type;
        public final boolean required;
        public final boolean marketDerived;
        public final String sourceCategory;
        public final String asOfRule;
        public final String missingPolicy;

        private Feature(String id, String type, boolean required, boolean marketDerived,
                        String sourceCategory, String asOfRule, String missingPolicy) {
            this.id = id;
            this.type = type;
            this.required = required;
            this.marketDerived = marketDerived;
            this.sourceCategory = sourceCategory;
            this.asOfRule = asOfRule;
            this.missingPolicy = missingPolicy;
        }
    }

    private final String version;
    private final List<Feature> features;
    private final String canonical;
    private final String sha256;

    private FundamentalFeatureRegistry(String version, List<Feature> features) throws Exception {
        this.version = version;
        this.features = Collections.unmodifiableList(new ArrayList<>(features));
        this.canonical = canonical(version, features);
        this.sha256 = sha256(canonical);
    }

    public static FundamentalFeatureRegistry fromJson(JSONObject manifest) throws Exception {
        if (manifest == null) throw new IllegalArgumentException("FEATURE_REGISTRY_REQUIRED");
        if (!SCHEMA.equals(manifest.optString("schema")))
            throw new IllegalArgumentException("FEATURE_REGISTRY_SCHEMA_INVALID");
        String version = manifest.optString("version").trim();
        if (version.isEmpty()) throw new IllegalArgumentException("FEATURE_REGISTRY_VERSION_REQUIRED");
        JSONArray raw = manifest.optJSONArray("features");
        if (raw == null || raw.length() == 0)
            throw new IllegalArgumentException("FEATURE_REGISTRY_FEATURES_REQUIRED");

        List<Feature> features = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject x = raw.getJSONObject(i);
            String id = x.optString("feature_id").trim();
            String type = x.optString("type").trim().toUpperCase(Locale.ROOT);
            boolean required = x.optBoolean("required", false);
            boolean marketDerived = x.optBoolean("market_derived", true);
            String sourceCategory = x.optString("source_category").trim().toUpperCase(Locale.ROOT);
            String asOfRule = x.optString("as_of_rule").trim().toUpperCase(Locale.ROOT);
            String missingPolicy = x.optString("missing_policy").trim().toUpperCase(Locale.ROOT);

            if (id.isEmpty() || !id.matches("[A-Za-z0-9_.-]+"))
                throw new IllegalArgumentException("FEATURE_ID_INVALID");
            if (!ids.add(id)) throw new IllegalArgumentException("FEATURE_ID_DUPLICATE:" + id);
            if (!TYPE_NUMBER.equals(type) && !TYPE_STRING.equals(type) && !TYPE_BOOLEAN.equals(type))
                throw new IllegalArgumentException("FEATURE_TYPE_INVALID:" + id);
            if (sourceCategory.isEmpty())
                throw new IllegalArgumentException("FEATURE_SOURCE_CATEGORY_REQUIRED:" + id);
            if (!ASOF_PREVIOUS_DATE_ONLY.equals(asOfRule) && !ASOF_AT_OR_BEFORE_T5.equals(asOfRule))
                throw new IllegalArgumentException("FEATURE_ASOF_RULE_INVALID:" + id);
            if (!MISSING_REJECT.equals(missingPolicy) && !MISSING_OMIT.equals(missingPolicy))
                throw new IllegalArgumentException("FEATURE_MISSING_POLICY_INVALID:" + id);
            if (required && !MISSING_REJECT.equals(missingPolicy))
                throw new IllegalArgumentException("REQUIRED_FEATURE_MUST_REJECT_MISSING:" + id);

            features.add(new Feature(id, type, required, marketDerived,
                    sourceCategory, asOfRule, missingPolicy));
        }
        features.sort((a, b) -> a.id.compareTo(b.id));
        return new FundamentalFeatureRegistry(version, features);
    }

    public String version() { return version; }
    public List<Feature> features() { return features; }
    public String canonicalJson() { return canonical; }
    public String sha256() { return sha256; }

    public boolean frozenCompatible() {
        return F0InputContract.FEATURE_REGISTRY_SHA256.equalsIgnoreCase(sha256);
    }

    public String f0SafetyReason() {
        for (Feature f : features) {
            if (f.marketDerived) return "F0_MARKET_DERIVED_FEATURE_FORBIDDEN:" + f.id;
            if ("RESULT".equals(f.sourceCategory) || "PAYOUT".equals(f.sourceCategory)
                    || "POST_RACE_ODDS".equals(f.sourceCategory))
                return "F0_POST_RACE_FEATURE_FORBIDDEN:" + f.id;
        }
        return "PASS";
    }

    private static String canonical(String version, List<Feature> features) {
        StringBuilder b = new StringBuilder();
        b.append("{\"schema\":").append(JSONObject.quote(SCHEMA))
                .append(",\"version\":").append(JSONObject.quote(version))
                .append(",\"features\":[");
        for (int i = 0; i < features.size(); i++) {
            if (i > 0) b.append(',');
            Feature f = features.get(i);
            b.append("{\"feature_id\":").append(JSONObject.quote(f.id))
                    .append(",\"type\":").append(JSONObject.quote(f.type))
                    .append(",\"required\":").append(f.required)
                    .append(",\"market_derived\":").append(f.marketDerived)
                    .append(",\"source_category\":").append(JSONObject.quote(f.sourceCategory))
                    .append(",\"as_of_rule\":").append(JSONObject.quote(f.asOfRule))
                    .append(",\"missing_policy\":").append(JSONObject.quote(f.missingPolicy))
                    .append('}');
        }
        return b.append("]}").toString();
    }

    static String sha256(String raw) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        for (byte x : digest) b.append(String.format(Locale.ROOT, "%02x", x & 0xff));
        return b.toString();
    }
}
