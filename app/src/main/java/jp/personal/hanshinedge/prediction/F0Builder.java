package jp.personal.hanshinedge.prediction;

import org.json.JSONObject;

/**
 * Boundary for a future authoritative Fundamental-to-F0 model.
 * Current repository state intentionally exposes an unavailable implementation
 * because no authoritative Champion artifact/coefficients are present.
 */
public interface F0Builder {
    String MODEL_STATUS = "NOT_AVAILABLE";
    String FEATURE_REGISTRY_STATUS = "REFERENCE_HASH_ONLY";
    String BOUNDARY_STATUS = "READY_MODEL_BLOCKED";

    final class Result {
        public final String status;
        public final String reason;
        public final String modelId;
        public final Double f0;
        public final String featureRegistrySha256;
        public final String evidenceSha256;

        public Result(String status, String reason, String modelId, Double f0,
                      String featureRegistrySha256, String evidenceSha256) {
            this.status = status;
            this.reason = reason;
            this.modelId = modelId;
            this.f0 = f0;
            this.featureRegistrySha256 = featureRegistrySha256;
            this.evidenceSha256 = evidenceSha256;
        }
    }

    String modelId();
    Result build(FundamentalFeatureSnapshot.Snapshot snapshot);

    static F0Builder unavailable() {
        return new F0Builder() {
            @Override public String modelId() { return "NOT_AVAILABLE"; }

            @Override public Result build(FundamentalFeatureSnapshot.Snapshot snapshot) {
                if (snapshot == null || !snapshot.valid) {
                    String reason = snapshot == null ? "FEATURE_SNAPSHOT_REQUIRED" : snapshot.reason;
                    return new Result("BLOCKED", "FEATURE_SNAPSHOT_REJECTED:" + reason,
                            modelId(), null,
                            snapshot == null ? null : snapshot.registrySha256,
                            snapshot == null ? null : snapshot.evidenceSha256);
                }
                return new Result("BLOCKED", "F0_MODEL_NOT_AVAILABLE",
                        modelId(), null, snapshot.registrySha256, snapshot.evidenceSha256);
            }
        };
    }

    static JSONObject readiness() throws Exception {
        return new JSONObject()
                .put("F0_BUILDER_STATUS", BOUNDARY_STATUS)
                .put("F0_MODEL_STATUS", MODEL_STATUS)
                .put("FEATURE_REGISTRY_STATUS", FEATURE_REGISTRY_STATUS)
                .put("feature_registry_reference_sha256", F0InputContract.FEATURE_REGISTRY_SHA256)
                .put("block_reason", "F0_MODEL_NOT_AVAILABLE")
                .put("fake_f0_generated", false);
    }
}
