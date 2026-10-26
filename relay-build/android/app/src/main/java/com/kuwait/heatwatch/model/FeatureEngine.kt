package com.kuwait.heatwatch.model

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * Feature engineering — direct ports of the project's Python reference scripts.
 * Verified byte-for-byte against compute_ct_kalman.py and compute_rothfusz.py.
 */

/** Stateful Extended Kalman Filter estimating core temperature from HR.
 *  Port of compute_ct_kalman.py. State (x, v) persists across steps, so ONE
 *  instance must be kept per monitoring session and stepped once per 10-min
 *  bucket (see Bucketer / design spec §6.1). */
class CoreTempKalman(ctStart: Double = 37.0) {
    private val a = 1.0
    private val gamma = 0.022 * 0.022
    private val b0 = -7887.1
    private val b1 = 384.4286
    private val b2 = -4.5714
    private val sigma = 18.88 * 18.88
    private var x = ctStart
    private var v = 0.0

    fun reset(ctStart: Double = 37.0) { x = ctStart; v = 0.0 }

    /** One filter step for one (bucketed) HR value; returns CT in °C, 2 dp. */
    fun step(hr: Double): Double {
        val xp = a * x
        val vp = a * a * v + gamma
        val cvc = 2.0 * b2 * xp + b1
        val k = (vp * cvc) / ((cvc * cvc) * vp + sigma)
        val hx = b2 * xp * xp + b1 * xp + b0
        x = xp + k * (hr - hx)
        v = (1.0 - k * cvc) * vp
        return round2(x)
    }
}

/** NOAA Rothfusz heat index. Port of compute_rothfusz.py (applied unconditionally,
 *  incl. the low-RH and high-RH adjustments). T in °C, RH in %. Returns °C, 2 dp. */
object HeatIndex {
    fun rothfusz(tempC: Double, rh: Double): Double {
        val t = tempC * 1.8 + 32.0
        var hi = -42.379 + 2.04901523 * t + 10.14333127 * rh -
                0.22475541 * t * rh - 0.00683783 * t * t - 0.05481717 * rh * rh +
                0.00122874 * t * t * rh + 0.00085282 * t * rh * rh -
                0.00000199 * t * t * rh * rh
        if (rh < 13 && t in 80.0..112.0)
            hi -= ((13 - rh) / 4.0) * sqrt((17 - abs(t - 95.0)) / 17.0)
        else if (rh > 85 && t in 80.0..87.0)
            hi += ((rh - 85) / 10.0) * ((87 - t) / 5.0)
        return round2((hi - 32.0) * 5.0 / 9.0)
    }
}

/** One averaged 10-minute record produced by the Bucketer. */
data class MinuteRow(val ts: String, val hr: Double, val temp: Double, val hum: Double)
data class TenMinRecord(val ts: String, val hr: Double, val temp: Double, val hum: Double)

/** 10-minute bucketing (design spec §6.1, Option A). Live watch data is 1-min;
 *  training used 10-min averages, so 1-min rows are averaged into 10-min buckets
 *  and the Kalman filter is stepped once per closed bucket. Timestamps of the
 *  form "yyyy-MM-dd HH:mm:ss" are floored to a 10-min boundary; unparseable
 *  timestamps fall back to grouping every 10 rows. */
class Bucketer {
    private var key: Long? = null
    private var n = 0
    private var hr = 0.0; private var temp = 0.0; private var hum = 0.0
    private var lastTs = ""
    private var fallback = 0L

    fun reset() { key = null; n = 0; hr = 0.0; temp = 0.0; hum = 0.0; lastTs = ""; fallback = 0 }

    /** Feed one 1-min row; returns a completed 10-min record when a bucket closes. */
    fun feed(row: MinuteRow): TenMinRecord? {
        val k = bucketKey(row.ts) ?: (fallback / 10)
        fallback++
        var done: TenMinRecord? = null
        if (key != null && k != key) { done = close(); reset() }
        if (key == null) key = k
        n++; hr += row.hr; temp += row.temp; hum += row.hum; lastTs = row.ts
        return done
    }

    fun flush(): TenMinRecord? = close().also { reset() }

    private fun close(): TenMinRecord? =
        if (n == 0) null
        else TenMinRecord(lastTs, hr / n, temp / n, hum / n)

    private fun bucketKey(ts: String): Long? {
        // ts like "2026-05-12 00:10:00" -> unique key per (date, hour, 10-min block)
        return try {
            val parts = ts.trim().split(" ")
            val (y, mo, d) = parts[0].split("-").map { it.toInt() }
            val time = parts[1].split(":").map { it.toInt() }
            val hh = time[0]; val block = time[1] / 10
            (((y.toLong() * 12 + mo) * 31 + d) * 24 + hh) * 6 + block
        } catch (e: Exception) { null }
    }
}

internal fun round2(x: Double): Double = Math.round(x * 100.0) / 100.0
