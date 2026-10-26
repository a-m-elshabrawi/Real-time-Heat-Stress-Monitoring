package com.kuwait.heatwatch.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Parity of the Kotlin pipeline with the Python reference oracles (expected values from
 *  relay-planning/reference via verify/make_android_test_vectors.py). */
class RubricParityTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test fun rubricMatchesOracleOnRandomInputs() {
        val o = JSONObject(res("rubric_cases.json"))
        val inp = o.getJSONArray("inputs"); val exp = o.getJSONArray("expected")
        var worst = 0.0
        for (i in 0 until inp.length()) {
            val a = inp.getJSONArray(i)
            val d = Math.abs(HsrsRubric.score(a.getDouble(0), a.getDouble(1), a.getDouble(2)) - exp.getDouble(i))
            if (d > worst) worst = d
        }
        println("rubric max |Kotlin - oracle| over ${inp.length()} inputs = $worst")
        assertEquals(0.0, worst, 1e-9)
    }

    @Test fun pipelineMatchesOracleOnRawLogRows() {
        val e = JSONObject(res("expected.json"))
        val ct = e.getJSONArray("ct"); val hi = e.getJSONArray("hi"); val hs = e.getJSONArray("hsrs")
        val p = ScoringPipeline()
        val got = ArrayList<ScoredReading>()
        res("rows.jsonl").lines().filter { it.isNotBlank() }.forEach { p.onLogLine(it)?.let(got::add) }
        p.flush()?.let(got::add)
        assertEquals(ct.length(), got.size)
        var dCt = 0.0; var dHi = 0.0; var dHs = 0.0
        for (i in 0 until got.size) {
            dCt = maxOf(dCt, Math.abs(got[i].coreTemp - ct.getDouble(i)))
            dHi = maxOf(dHi, Math.abs(got[i].heatIndex - hi.getDouble(i)))
            dHs = maxOf(dHs, Math.abs(got[i].hsrs - hs.getDouble(i)))
        }
        println("pipeline over ${got.size} buckets: max|dCT|=$dCt max|dHI|=$dHi max|dHSRS|=$dHs")
        // CT and HI are rounded to 2 dp by design (as in the stored training columns): error <= 0.005
        assertEquals(0.0, dCt, 0.005 + 1e-9); assertEquals(0.0, dHi, 0.005 + 1e-9); assertEquals(0.0, dHs, 0.0051)
    }
}
