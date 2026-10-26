package com.kuwait.heatwatch.model

import org.json.JSONObject

/**
 * End-to-end scoring pipeline for one monitoring session:
 *   1-min JSON line  →  10-min bucket (avg)  →  CT (Kalman) + HI (Rothfusz)
 *                    →  HSRS (closed-form rubric)  →  ScoredReading
 *
 * Stateful: keep one instance per session; call reset() at session start.
 */
class ScoringPipeline {
    private val bucketer = Bucketer()
    private val kalman = CoreTempKalman()

    fun reset() { bucketer.reset(); kalman.reset() }

    /** Parse one raw JSON log line ({ts,hr,temp,w_hum,...}); returns a scored
     *  reading only when a 10-min bucket closes, else null. */
    fun onLogLine(jsonLine: String): ScoredReading? {
        val o = try { JSONObject(jsonLine) } catch (e: Exception) { return null }
        if (o.isNull("hr") || o.isNull("temp") || o.isNull("w_hum")) return null
        val row = MinuteRow(
            ts = o.optString("ts", "—"),
            hr = o.getDouble("hr"),
            temp = o.getDouble("temp"),
            hum = o.getDouble("w_hum")
        )
        return bucketer.feed(row)?.let { score(it) }
    }

    /** Close the final partial bucket at end of transfer. */
    fun flush(): ScoredReading? = bucketer.flush()?.let { score(it) }

    private fun score(r: TenMinRecord): ScoredReading {
        val ct = kalman.step(r.hr)                 // one Kalman step per 10-min bucket
        val hi = HeatIndex.rothfusz(r.temp, r.hum)
        val hsrs = round2(HsrsRubric.score(ct, r.hr, hi))   // HSRS computed directly from the closed-form rubric
        return ScoredReading(r.ts, r.hr, r.temp, r.hum, ct, hi, hsrs)
    }
}
