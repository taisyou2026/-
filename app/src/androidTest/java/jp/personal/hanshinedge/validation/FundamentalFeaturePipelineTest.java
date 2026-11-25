package jp.personal.hanshinedge.validation;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import jp.personal.hanshinedge.prediction.F0Builder;
import jp.personal.hanshinedge.prediction.FundamentalFeatureRegistry;
import jp.personal.hanshinedge.prediction.FundamentalFeatureSnapshot;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class FundamentalFeaturePipelineTest {
    private static long raceStart() {
        return ZonedDateTime.of(2026, 11, 30, 15, 0, 0, 0,
                ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli();
    }

    private static JSONObject feature(String id, String type, boolean required,
                                      boolean marketDerived, String sourceCategory,
                                      String asOfRule, String missingPolicy) throws Exception {
        return new JSONObject()
                .put("feature_id", id)
                .put("type", type)
                .put("required", required)
                .put("market_derived", marketDerived)
                .put("source_category", sourceCategory)
                .put("as_of_rule", asOfRule)
                .put("missing_policy", missingPolicy);
    }

    private static JSONObject manifest(JSONObject... features) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject f : features) a.put(f);
        return new JSONObject()
                .put("schema", FundamentalFeatureRegistry.SCHEMA)
                .put("version", "TEST_ONLY-v1")
                .put("features", a);
    }

    private static JSONObject observed(Object value, long availableAt, int hashSeed) throws Exception {
        return new JSONObject()
                .put("value", value)
                .put("available_at_ms", availableAt)
                .put("source_record_id", "TEST_SOURCE_" + hashSeed)
                .put("source_raw_sha256", String.format("%064x", hashSeed));
    }

    private static FundamentalFeatureRegistry safeRegistry() throws Exception {
        return FundamentalFeatureRegistry.fromJson(manifest(
                feature("carried_weight", "NUMBER", true, false, "ENTRY_HISTORY",
                        "PREVIOUS_DATE_ONLY", "REJECT"),
                feature("days_since_last_race", "NUMBER", true, false, "RACE_HISTORY",
                        "PREVIOUS_DATE_ONLY", "REJECT")
        ));
    }

    @Test public void registryAndSnapshotHashesAreDeterministic() throws Exception {
        JSONObject a = feature("carried_weight", "NUMBER", true, false, "ENTRY_HISTORY",
                "PREVIOUS_DATE_ONLY", "REJECT");
        JSONObject b = feature("days_since_last_race", "NUMBER", true, false, "RACE_HISTORY",
                "PREVIOUS_DATE_ONLY", "REJECT");

        FundamentalFeatureRegistry r1 = FundamentalFeatureRegistry.fromJson(manifest(a, b));
        FundamentalFeatureRegistry r2 = FundamentalFeatureRegistry.fromJson(manifest(b, a));
        assertEquals(r1.canonicalJson(), r2.canonicalJson());
        assertEquals(r1.sha256(), r2.sha256());
        assertEquals("PASS", r1.f0SafetyReason());

        long start = raceStart(), prior = start - 2L * 24 * 60 * 60 * 1000;
        JSONObject v1 = new JSONObject()
                .put("carried_weight", observed(57.0, prior, 1))
                .put("days_since_last_race", observed(28, prior, 2));
        JSONObject v2 = new JSONObject()
                .put("days_since_last_race", observed(28, prior, 2))
                .put("carried_weight", observed(57.0, prior, 1));

        FundamentalFeatureSnapshot.Snapshot s1 =
                FundamentalFeatureSnapshot.build(r1, "TEST-RACE", 1, start, v1);
        FundamentalFeatureSnapshot.Snapshot s2 =
                FundamentalFeatureSnapshot.build(r2, "TEST-RACE", 1, start, v2);
        assertTrue(s1.reason, s1.valid);
        assertTrue(s2.reason, s2.valid);
        assertEquals(s1.evidenceSha256, s2.evidenceSha256);
        assertEquals(r1.sha256(), s1.registrySha256);
        assertFalse("Synthetic registry must not impersonate frozen registry", r1.frozenCompatible());
    }

    @Test public void marketAndPostRaceFeaturesFailClosed() throws Exception {
        long start = raceStart(), prior = start - 2L * 24 * 60 * 60 * 1000;

        FundamentalFeatureRegistry market = FundamentalFeatureRegistry.fromJson(manifest(
                feature("market_signal", "NUMBER", true, true, "MARKET",
                        "AT_OR_BEFORE_T5", "REJECT")));
        JSONObject marketValues = new JSONObject().put("market_signal", observed(1.0, prior, 3));
        FundamentalFeatureSnapshot.Snapshot marketSnapshot =
                FundamentalFeatureSnapshot.build(market, "TEST-RACE", 1, start, marketValues);
        assertFalse(marketSnapshot.valid);
        assertEquals("F0_MARKET_DERIVED_FEATURE_FORBIDDEN:market_signal", marketSnapshot.reason);

        FundamentalFeatureRegistry result = FundamentalFeatureRegistry.fromJson(manifest(
                feature("finish_position", "NUMBER", true, false, "RESULT",
                        "PREVIOUS_DATE_ONLY", "REJECT")));
        JSONObject resultValues = new JSONObject().put("finish_position", observed(1, prior, 4));
        FundamentalFeatureSnapshot.Snapshot resultSnapshot =
                FundamentalFeatureSnapshot.build(result, "TEST-RACE", 1, start, resultValues);
        assertFalse(resultSnapshot.valid);
        assertEquals("F0_POST_RACE_FEATURE_FORBIDDEN:finish_position", resultSnapshot.reason);
    }

    @Test public void asOfRulesRejectSameDayAndAfterT5Leakage() throws Exception {
        long start = raceStart();

        FundamentalFeatureRegistry previousDate = FundamentalFeatureRegistry.fromJson(manifest(
                feature("recent_form", "NUMBER", true, false, "RACE_HISTORY",
                        "PREVIOUS_DATE_ONLY", "REJECT")));
        long sameDay = start - 60L * 60 * 1000;
        FundamentalFeatureSnapshot.Snapshot sameDaySnapshot =
                FundamentalFeatureSnapshot.build(previousDate, "TEST-RACE", 1, start,
                        new JSONObject().put("recent_form", observed(0.7, sameDay, 5)));
        assertFalse(sameDaySnapshot.valid);
        assertEquals("FUNDAMENTAL_SAME_DAY_LEAKAGE:recent_form", sameDaySnapshot.reason);

        FundamentalFeatureRegistry t5 = FundamentalFeatureRegistry.fromJson(manifest(
                feature("pre_race_non_market", "NUMBER", true, false, "PRE_RACE_FUNDAMENTAL",
                        "AT_OR_BEFORE_T5", "REJECT")));
        FundamentalFeatureSnapshot.Snapshot late =
                FundamentalFeatureSnapshot.build(t5, "TEST-RACE", 1, start,
                        new JSONObject().put("pre_race_non_market",
                                observed(0.2, start - 299999, 6)));
        assertFalse(late.valid);
        assertEquals("FUNDAMENTAL_AFTER_T5:pre_race_non_market", late.reason);
    }

    @Test public void missingUnknownAndBadProvenanceFailClosed() throws Exception {
        long start = raceStart(), prior = start - 2L * 24 * 60 * 60 * 1000;
        FundamentalFeatureRegistry registry = safeRegistry();

        FundamentalFeatureSnapshot.Snapshot missing =
                FundamentalFeatureSnapshot.build(registry, "TEST-RACE", 1, start,
                        new JSONObject().put("carried_weight", observed(57.0, prior, 7)));
        assertFalse(missing.valid);
        assertEquals("FUNDAMENTAL_REQUIRED_FEATURE_MISSING:days_since_last_race", missing.reason);

        JSONObject unknownValues = new JSONObject()
                .put("carried_weight", observed(57.0, prior, 8))
                .put("days_since_last_race", observed(28, prior, 9))
                .put("odds", observed(2.1, prior, 10));
        FundamentalFeatureSnapshot.Snapshot unknown =
                FundamentalFeatureSnapshot.build(registry, "TEST-RACE", 1, start, unknownValues);
        assertFalse(unknown.valid);
        assertEquals("FUNDAMENTAL_UNKNOWN_FEATURE:odds", unknown.reason);

        JSONObject bad = observed(57.0, prior, 11).put("source_raw_sha256", "bad");
        JSONObject badValues = new JSONObject()
                .put("carried_weight", bad)
                .put("days_since_last_race", observed(28, prior, 12));
        FundamentalFeatureSnapshot.Snapshot badProof =
                FundamentalFeatureSnapshot.build(registry, "TEST-RACE", 1, start, badValues);
        assertFalse(badProof.valid);
        assertEquals("FUNDAMENTAL_SOURCE_HASH_INVALID:carried_weight", badProof.reason);
    }

    @Test public void unavailableBuilderNeverSynthesizesF0() throws Exception {
        long start = raceStart(), prior = start - 2L * 24 * 60 * 60 * 1000;
        FundamentalFeatureRegistry registry = safeRegistry();
        JSONObject values = new JSONObject()
                .put("carried_weight", observed(57.0, prior, 13))
                .put("days_since_last_race", observed(28, prior, 14));
        FundamentalFeatureSnapshot.Snapshot snapshot =
                FundamentalFeatureSnapshot.build(registry, "TEST-RACE", 1, start, values);
        assertTrue(snapshot.valid);

        F0Builder.Result result = F0Builder.unavailable().build(snapshot);
        assertEquals("BLOCKED", result.status);
        assertEquals("F0_MODEL_NOT_AVAILABLE", result.reason);
        assertEquals("NOT_AVAILABLE", result.modelId);
        assertNull(result.f0);
        assertEquals(snapshot.evidenceSha256, result.evidenceSha256);

        JSONObject readiness = F0Builder.readiness();
        assertEquals("READY_MODEL_BLOCKED", readiness.getString("F0_BUILDER_STATUS"));
        assertEquals("NOT_AVAILABLE", readiness.getString("F0_MODEL_STATUS"));
        assertEquals("REFERENCE_HASH_ONLY", readiness.getString("FEATURE_REGISTRY_STATUS"));
        assertFalse(readiness.getBoolean("fake_f0_generated"));
    }
}
