package com.kuwait.heatwatch.model

/** HSRS risk tiers and abnormal thresholds (mirror the web client / design spec §4). */
enum class RiskTier(val label: String, val min: Double, val max: Double, val colorHex: Long) {
    LOW("Low", 0.0, 3.0, 0xFF3FB950),
    MODERATE("Moderate", 3.0, 5.0, 0xFFD29922),
    HIGH("High", 5.0, 7.0, 0xFFF0883E),
    SEVERE("Severe", 7.0, 10.0, 0xFFF85149);

    companion object {
        fun of(score: Double): RiskTier =
            values().firstOrNull { score >= it.min && score <= it.max } ?: SEVERE
    }
}

object Thresholds {
    const val HR_BPM = 130.0
    const val CORE_TEMP_C = 39.5
    const val HEAT_INDEX_C = 41.0
}

/** Closed-form HSRS rubric (compute_hsrs.py) — the relay's HSRS score. */
object HsrsRubric {
    private fun interp(v: Double, xs: DoubleArray, ys: DoubleArray): Double {
        if (v <= xs.first()) return ys.first()
        if (v >= xs.last()) return ys.last()
        for (i in 0 until xs.size - 1) {
            if (v in xs[i]..xs[i + 1]) {
                val t = (v - xs[i]) / (xs[i + 1] - xs[i])
                return ys[i] + t * (ys[i + 1] - ys[i])
            }
        }
        return ys.last()
    }
    private fun ct(v: Double) = interp(v, doubleArrayOf(36.5,37.5,38.5,40.0,42.0), doubleArrayOf(0.0,2.0,6.0,9.0,10.0))
    private fun hr(v: Double) = interp(v, doubleArrayOf(60.0,100.0,130.0,160.0,200.0), doubleArrayOf(0.0,2.0,5.0,8.0,10.0))
    private fun hi(v: Double) = interp(v, doubleArrayOf(20.0,32.0,41.0,54.0,60.0), doubleArrayOf(0.0,2.0,5.0,9.0,10.0))

    fun score(coreTemp: Double, heartRate: Double, heatIndex: Double): Double =
        0.5 * ct(coreTemp) + 0.3 * hr(heartRate) + 0.2 * hi(heatIndex)
}

/** A fully scored 10-minute reading shown in the UI. */
data class ScoredReading(
    val ts: String,
    val hr: Double,
    val temp: Double,
    val hum: Double,
    val coreTemp: Double,
    val heatIndex: Double,
    val hsrs: Double           // closed-form rubric
) {
    val tier get() = RiskTier.of(hsrs)
    val abnormal get() = hr > Thresholds.HR_BPM || coreTemp > Thresholds.CORE_TEMP_C || heatIndex > Thresholds.HEAT_INDEX_C
}
