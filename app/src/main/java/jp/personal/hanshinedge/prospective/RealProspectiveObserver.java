package jp.personal.hanshinedge.prospective;

import android.content.Context;
import android.os.SystemClock;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Read-only live observation, using precisely the Android transport and six Runtime stores. */
public final class RealProspectiveObserver {
    private final Context context;
    private final File directory;
    private final JSONArray registrations = new JSONArray(), attempts = new JSONArray();
    private final Set<String> selected = new LinkedHashSet<>();
    private JSONObject discovery = new JSONObject();
    private String status = "STARTING", error = "";
    private final long deadline;

    public RealProspectiveObserver(Context context, File directory) {
        this.context = context; this.directory = directory;
        deadline = System.currentTimeMillis() + 150 * 60 * 1000L;
    }

    /** Deterministic selection from actual preregistrations, not a fixed race/date or weekday. */
    public static List<JSONObject> choose(JSONArray registrations, long now) throws Exception {
        List<JSONObject> candidates = new ArrayList<>();
        for (int i = 0; i < registrations.length(); i++) {
            JSONObject r = registrations.getJSONObject(i);
            long post = ProspectiveRuntime.stamp(r.getString("scheduled_post_time"));
            if (RealProspectiveReport.registrationPass(r) && post - 1200000 > now + 90000
                    && post < now + 130 * 60000L
                    && ProspectiveRuntime.validRoster(r.getJSONObject("race"), r.getJSONObject("race"))) candidates.add(r);
        }
        candidates.sort(Comparator.comparingLong(r -> ProspectiveRuntime.stamp(r.optString("scheduled_post_time"))));
        List<JSONObject> result = new ArrayList<>(); Set<String> venues = new HashSet<>();
        // Diversity first. Hanshin preference only applies to selection, never to the model.
        List<JSONObject> diversity = new ArrayList<>(candidates);
        diversity.sort(Comparator.comparingInt(r -> "09".equals(r.optJSONObject("race").optString("venue_code")) ? 0 : 1));
        for (JSONObject r : diversity) if (venues.add(r.getJSONObject("race").getString("venue_code"))) {
            result.add(r); if (result.size() == 3) return result;
        }
        for (JSONObject r : candidates) if (!result.contains(r)) { result.add(r); if (result.size() == 3) break; }
        return result;
    }

    private static final class Task implements Comparable<Task> {
        final String id, stage; final long at; final int attempt;
        Task(String id, String stage, long at, int attempt) { this.id=id; this.stage=stage; this.at=at; this.attempt=attempt; }
        public int compareTo(Task other) { int c=Long.compare(at,other.at); return c!=0?c:(id+stage).compareTo(other.id+other.stage); }
    }

    public JSONObject run() throws Exception {
        directory.mkdirs();
        try (ProspectiveRuntime runtime = new ProspectiveRuntime(context, "real-prospective-readonly")) {
            write(runtime);
            try {
                discovery = JraSource.discover(context);
                JSONObject clock = ProspectiveRuntime.clock(discovery.getJSONObject("receipt"), System.currentTimeMillis(), SystemClock.elapsedRealtime());
                discovery.put("clock", clock);
                if (!clock.getBoolean("verified")) { status="CLOCK_UNVERIFIED"; return write(runtime); }
                if (discovery.getJSONArray("meetings").length()==0) { status="NO_JRA_MEETING"; return write(runtime); }
                JSONArray catalog = JraSource.today(context, discovery);
                for (int i=0;i<catalog.length();i++) {
                    JSONObject item=catalog.getJSONObject(i);
                    registrations.put(runtime.register(item.getJSONObject("race"), item.getJSONObject("receipt"),
                        System.currentTimeMillis(), SystemClock.elapsedRealtime()));
                    write(runtime); // durable evidence even if the host is interrupted later
                }
                List<JSONObject> chosen=choose(registrations,System.currentTimeMillis());
                PriorityQueue<Task> queue=new PriorityQueue<>();
                for (JSONObject r:chosen) {
                    String id=r.getString("race_id"); selected.add(id); runtime.shadow(id);
                    long post=ProspectiveRuntime.stamp(r.getString("scheduled_post_time"));
                    for (int k=0;k<4;k++) queue.add(new Task(id,ProspectiveRuntime.STAGE[k],post-ProspectiveRuntime.TARGET[k]*1000L,1));
                    queue.add(new Task(id,"RESULT",post+180000,1));
                }
                if (chosen.isEmpty()) { status="NO_ELIGIBLE_PRE_RACE"; return write(runtime); }
                status="OBSERVING"; write(runtime);
                while (!queue.isEmpty()) {
                    Task task=queue.peek();
                    if (System.currentTimeMillis()>=deadline) { status="OBSERVATION_DEADLINE"; break; }
                    long wait=task.at-System.currentTimeMillis();
                    if (wait>0) { Thread.sleep(Math.min(wait,30000)); write(runtime); continue; }
                    queue.remove();
                    JSONObject attempt=new JSONObject().put("race_id",task.id).put("milestone",task.stage)
                        .put("attempt",task.attempt).put("attempted_at",Instant.now().toString());
                    attempts.put(attempt);
                    try {
                        JSONObject[] proof={null};
                        if ("RESULT".equals(task.stage)) {
                            JSONObject outcome=JraSource.outcome(context,runtime.registration(task.id),proof);
                            runtime.result(task.id,outcome,proof[0]); // no BUY / settlement invented
                            attempt.put("status","PASS").put("receipt",proof[0]);
                        } else {
                            JSONObject current=JraSource.latest(context,runtime.registration(task.id),proof);
                            attempt.put("receipt",proof[0]);
                            JSONObject captured=runtime.capture(task.id,task.stage,current,proof[0],System.currentTimeMillis(),SystemClock.elapsedRealtime());
                            attempt.put("status",captured.getJSONObject("snapshot").getString("status"));
                        }
                    } catch (Exception ex) {
                        attempt.put("status","UNAVAILABLE").put("error",ex.toString());
                        int max="RESULT".equals(task.stage)?6:3;
                        if (task.attempt<max && System.currentTimeMillis()+15000<deadline) {
                            queue.add(new Task(task.id,task.stage,System.currentTimeMillis()+("RESULT".equals(task.stage)?120000:15000),task.attempt+1));
                        } else if (!"RESULT".equals(task.stage)) runtime.unavailable(task.id,task.stage,ex.toString(),System.currentTimeMillis());
                    }
                    write(runtime);
                }
                if (queue.isEmpty()) status="OBSERVATION_FINISHED";
            } catch (InterruptedException ex) {
                status="PROCESS_INTERRUPTED"; error=ex.toString(); Thread.currentThread().interrupt();
            } catch (Exception ex) { status="SOURCE_UNAVAILABLE"; error=ex.toString(); }
            return write(runtime);
        }
    }

    private void save(String name, String value) throws Exception {
        File tmp=new File(directory,name+".tmp"),target=new File(directory,name);
        try (FileOutputStream out=new FileOutputStream(tmp)) { out.write(value.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
        if (!tmp.renameTo(target)) throw new IOException("REPORT_ATOMIC_REPLACE_FAILED:"+name);
    }

    private JSONObject write(ProspectiveRuntime runtime) throws Exception {
        JSONArray races=new JSONArray(), result=new JSONArray(), clocks=new JSONArray(), registrationExport=new JSONArray();
        JSONArray[] milestones={new JSONArray(),new JSONArray(),new JSONArray(),new JSONArray()};
        StringBuilder hashes=new StringBuilder();
        for (int i=0;i<registrations.length();i++) {
            JSONObject reg=registrations.getJSONObject(i); String id=reg.getString("race_id");
            JSONObject flat=new JSONObject(reg.toString()),race=reg.getJSONObject("race");
            for(String k:new String[]{"date","venue","venue_code","meeting","day","race_no"}) flat.put(k,race.get(k));
            JSONObject source=reg.optJSONObject("source_receipt");
            if(source!=null) for(String k:new String[]{"raw_size","source_uri","received_at_ms","http_date","http_status"}) flat.put(k,source.opt(k));
            flat.put("raw_sha256",reg.getString("source_raw_sha256")); registrationExport.put(flat);
            JSONArray stages=TimechainReadModel.stages(runtime,id);
            for (int j=0;j<4;j++) {
                JSONObject stage=stages.getJSONObject(j);
                if ("WAITING".equals(stage.optString("status"))) stage.put("status","UNAVAILABLE")
                    .put("error_code",selected.contains(id)?"NOT_YET_OBSERVED":"NOT_SELECTED");
                if (selected.contains(id)) milestones[j].put(stage);
                if (stage.has("source_raw_sha256")&&!stage.isNull("source_raw_sha256"))
                    hashes.append(stage.getString("source_raw_sha256")).append("  ").append(id).append(' ').append(ProspectiveRuntime.STAGE[j]).append('\n');
            }
            JSONObject rr=runtime.find("RESULT","RESULT:"+id);
            JSONObject res=rr==null?new JSONObject().put("race_id",id).put("status","UNAVAILABLE").put("error_code","RESULT_NOT_OBSERVED")
                :new JSONObject(rr.getString("payload")).put("status","PASS");
            if (selected.contains(id)) result.put(res);
            races.put(new JSONObject().put("registration",reg).put("selected",selected.contains(id)).put("milestones",stages).put("result",res));
            clocks.put(new JSONObject().put("race_id",id).put("clock",reg.getJSONObject("clock")));
            hashes.append(reg.getString("source_raw_sha256")).append("  ").append(id).append(" REGISTER\n");
            if (rr!=null) hashes.append(res.getString("result_raw_sha256")).append("  ").append(id).append(" RESULT\n");
        }
        JSONObject report=new JSONObject().put("schema",RealProspectiveReport.SCHEMA).put("status",status).put("error",error)
            .put("updated_at",Instant.now().toString()).put("discovery",discovery).put("races",races)
            .put("selected_race_ids",new JSONArray(selected)).put("selected_count",selected.size())
            .put("selection_warning",selected.size()<3?"FEWER_THAN_THREE_ELIGIBLE":"")
            .put("gates",RealProspectiveReport.gates(races)).put("formal_buy_count",runtime.runtime.formalBuyStatus().getInt("count"))
            .put("F0_STATUS","NOT_AVAILABLE").put("STAKE_RULE","NOT_AVAILABLE").put("model_applied",false)
            .put("reason","F0_NOT_READY").put("FORMAL_PAPER_BUY","BLOCKED").put("REAL_MONEY","BLOCKED")
            .put("AUTO_REAL_BET","DISABLED").put("ANDROID_PHYSICAL_DEVICE","NOT_TESTED")
            .put("bankroll",runtime.bankroll()).put("raw_html_saved",false)
            .put("PROCESS_DEATH_RECOVERY","PROCESS_DEATH_RECOVERY_NOT_FULLY_SIMULATED");
        JSONObject receipt=discovery.optJSONObject("receipt");
        if(receipt!=null) hashes.append(receipt.optString("raw_sha256")).append("  OFFICIAL_DISCOVERY\n");
        RealProspectiveReport.parse(report.toString());
        save("real-prospective-summary.json",report.toString(2));
        save("real-prospective-registration.json",registrationExport.toString(2));
        for(int j=0;j<4;j++) save("timechain-"+ProspectiveRuntime.STAGE[j].replace("-","")+".json",milestones[j].toString(2));
        save("result.json",result.toString(2)); save("raw-sha256.txt",hashes.toString());
        save("clock-report.json",new JSONObject().put("limit_ms",ProspectiveRuntime.CLOCK_LIMIT_MS).put("discovery",discovery.opt("clock")).put("registrations",clocks).toString(2));
        save("attempts.json",attempts.toString(2));
        return report;
    }
}
