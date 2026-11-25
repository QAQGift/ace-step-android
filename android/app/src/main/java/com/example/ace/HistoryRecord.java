package com.example.ace;

import org.json.JSONException;
import org.json.JSONObject;

/** One generation record persisted as JSON. */
public class HistoryRecord {
    public long id;              // unique id = start timestamp (ms)
    public String caption;       // style / prompt
    public String lyrics;
    public int duration;         // requested seconds
    public long startMs;         // generation start (epoch ms)
    public long endMs;           // generation finish (epoch ms)
    public long totalMs;         // total wall time
    public long lmMs;            // LM planning stage time
    public long synthMs;         // DiT + VAE stage time
    public long fileSize;        // output wav size in bytes
    public String outputPath;
    public long seed;             // -1 = random
    public boolean flashAttn;     // A/B state at generation time
    public String vaeFile;        // decoder used (stock vs ScragVAE)
    public boolean keepCaption;   // use_cot_caption = !keepCaption
    public String plannerCaption; // caption after LM rewrite
    public String plannerMeta;    // bpm/key/timesig/language from planner
    public int    codesCount;     // number of 5Hz audio codes from LM
    public boolean bf16Lm;        // planner LM precision used
    public boolean success;
    public String error;

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("caption", caption == null ? "" : caption);
        o.put("lyrics", lyrics == null ? "" : lyrics);
        o.put("duration", duration);
        o.put("startMs", startMs);
        o.put("endMs", endMs);
        o.put("totalMs", totalMs);
        o.put("lmMs", lmMs);
        o.put("synthMs", synthMs);
        o.put("fileSize", fileSize);
        o.put("outputPath", outputPath == null ? "" : outputPath);
        o.put("seed", seed);
        o.put("flashAttn", flashAttn);
        o.put("vaeFile", vaeFile == null ? "" : vaeFile);
        o.put("keepCaption", keepCaption);
        o.put("plannerCaption", plannerCaption == null ? "" : plannerCaption);
        o.put("plannerMeta", plannerMeta == null ? "" : plannerMeta);
        o.put("codesCount", codesCount);
        o.put("bf16Lm", bf16Lm);
        o.put("success", success);
        o.put("error", error == null ? "" : error);
        return o;
    }

    public static HistoryRecord fromJson(JSONObject o) {
        HistoryRecord r = new HistoryRecord();
        try {
            r.id = o.optLong("id");
            r.caption = o.optString("caption", "");
            r.lyrics = o.optString("lyrics", "");
            r.duration = o.optInt("duration", 0);
            r.startMs = o.optLong("startMs");
            r.endMs = o.optLong("endMs");
            r.totalMs = o.optLong("totalMs");
            r.lmMs = o.optLong("lmMs");
            r.synthMs = o.optLong("synthMs");
            r.fileSize = o.optLong("fileSize");
            r.outputPath = o.optString("outputPath", "");
            r.seed = o.optLong("seed", -1);
            r.flashAttn = o.optBoolean("flashAttn", true);
            r.vaeFile = o.optString("vaeFile", "");
            r.keepCaption = o.optBoolean("keepCaption", true);
            r.plannerCaption = o.optString("plannerCaption", "");
            r.plannerMeta = o.optString("plannerMeta", "");
            r.codesCount = o.optInt("codesCount", 0);
            r.bf16Lm = o.optBoolean("bf16Lm", false);
            r.success = o.optBoolean("success", true);
            r.error = o.optString("error", "");
        } catch (Exception e) {
            return null;
        }
        return r;
    }
}
