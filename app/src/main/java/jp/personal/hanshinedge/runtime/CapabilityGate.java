package jp.personal.hanshinedge.runtime;

import org.json.JSONObject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Capability boundary for the all-venue runtime.
 * Observation is allowed for all ten JRA venues. Formal paper BUY is fail-closed
 * outside the frozen Hanshin flat-race core. Real-money execution is never enabled.
 */
public final class CapabilityGate {
    public static final String FROZEN_MODEL = "HANSHIN-EDGE-MF0-v1.0-FROZEN";
    public static final String REAL_MONEY = "BLOCKED";
    private static final Set<String> VENUES = new HashSet<>(Arrays.asList(
            "SAPPORO","HAKODATE","FUKUSHIMA","NIIGATA","TOKYO",
            "NAKAYAMA","CHUKYO","KYOTO","HANSHIN","KOKURA"));

    private CapabilityGate() {}

    public static final class RaceContext {
        public final String raceId;
        public final String venue;
        public final int raceNo;
        public final boolean obstacle;
        public final boolean prospective;
        public final boolean resultKnown;
        public final String modelTag;

        public RaceContext(String raceId, String venue, int raceNo, boolean obstacle,
                           boolean prospective, boolean resultKnown, String modelTag) {
            if (raceId == null || raceId.trim().isEmpty()) throw new IllegalArgumentException("RACE_ID_REQUIRED");
            String normalized = venue == null ? "" : venue.trim().toUpperCase(Locale.ROOT);
            if (!VENUES.contains(normalized)) throw new IllegalArgumentException("UNSUPPORTED_VENUE");
            if (raceNo < 1 || raceNo > 12) throw new IllegalArgumentException("RACE_NO_RANGE");
            this.raceId = raceId.trim();
            this.venue = normalized;
            this.raceNo = raceNo;
            this.obstacle = obstacle;
            this.prospective = prospective;
            this.resultKnown = resultKnown;
            this.modelTag = modelTag == null ? "" : modelTag;
        }
    }

    public static final class Capabilities {
        public final boolean fetch;
        public final boolean timechain;
        public final boolean result;
        public final boolean shadowHistory;
        public final boolean flatModel;
        public final boolean formalPaperBuy;
        public final boolean realMoney;
        public final String formalBlockReason;

        private Capabilities(boolean flatModel, boolean formalPaperBuy, String reason) {
            this.fetch = true;
            this.timechain = true;
            this.result = true;
            this.shadowHistory = true;
            this.flatModel = flatModel;
            this.formalPaperBuy = formalPaperBuy;
            this.realMoney = false;
            this.formalBlockReason = reason;
        }

        public JSONObject toJson() throws Exception {
            return new JSONObject()
                    .put("fetch", fetch)
                    .put("timechain", timechain)
                    .put("result", result)
                    .put("shadow_history", shadowHistory)
                    .put("flat_model", flatModel)
                    .put("formal_paper_buy", formalPaperBuy)
                    .put("real_money", REAL_MONEY)
                    .put("formal_block_reason", formalBlockReason);
        }
    }

    public static Capabilities evaluate(RaceContext race) {
        if (race.obstacle) return new Capabilities(false, false, "OBSTACLE_MODEL_BLOCKED");
        if (!"HANSHIN".equals(race.venue)) return new Capabilities(true, false, "OUTSIDE_FROZEN_VENUE");
        if (!FROZEN_MODEL.equals(race.modelTag)) return new Capabilities(true, false, "MODEL_TAG_NOT_FROZEN");
        if (!race.prospective) return new Capabilities(true, false, "NOT_PROSPECTIVE");
        if (race.resultKnown) return new Capabilities(true, false, "RESULT_ALREADY_KNOWN");
        return new Capabilities(true, true, "NONE");
    }

    public static void requireFormalPaperBuy(RaceContext race) {
        Capabilities c = evaluate(race);
        if (!c.formalPaperBuy) throw new IllegalStateException("FORMAL_BUY_BLOCKED:" + c.formalBlockReason);
    }
}
