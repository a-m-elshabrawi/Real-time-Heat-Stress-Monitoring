/*
 * Bangle.js 2 - Background Health Logger (V4.4-ES5 safe)
 * - Continuous mode, HRM duty cycle 10s ON / 2s OFF
 * - Flush 1 aggregated JSON line per minute
 * - Uses built-in health status
 * - Ignores HRM samples with confidence < 80
 * - HR = confidence-weighted mean over used samples
 * - Logs hr_samples_total / hr_samples_used and hr_unweighted (QA)
 */

(function () {
    // ---------- Utilities ----------
    function pad(n){ return (n<10?"0":"") + n; }
    function formatLocal(ts) {
      var d = (ts && ts.getTime) ? ts : new Date(ts);
      return d.getFullYear()+"-"+pad(d.getMonth()+1)+"-"+pad(d.getDate())+" "+
             pad(d.getHours())+":"+pad(d.getMinutes())+":"+pad(d.getSeconds());
    }
    function mode(arr) {
      if (!arr || !arr.length) return null;
      var counts = {};
      var best = null, bestN = -1, i, v;
      for (i=0;i<arr.length;i++){
        v = arr[i];
        if (v===undefined || v===null) continue;
        counts[v] = (counts[v]||0)+1;
        if (counts[v] > bestN){ bestN = counts[v]; best = v; }
      }
      return best;
    }
  
    // ---------- Weather ----------
    var wCache = null, wCacheAt = 0, wWarned = false;
    function getWeatherNow() {
      if (wCache && (Date.now() - wCacheAt) < 5*60*1000) return wCache;
      var S = require("Storage");
      try {
        var j = S.readJSON("weather.json",1);
        if (!j) {
          var cand = S.list(/weather/i)[0];
          if (cand) j = S.readJSON(cand,1);
        }
        var wx = (j && j.weather) ? j.weather : j;
        if (wx && (wx.temp!==undefined || wx.hum!==undefined || wx.txt || wx.wind!==undefined)) {
          var tsRaw = wx.time || wx.ts || wx.updated;
          if (typeof tsRaw==="number" && tsRaw<1e12) tsRaw *= 1000;
          var tsLocal = (tsRaw!==undefined) ? formatLocal(tsRaw) : formatLocal(Date.now());
  
          var tempC, lo_C, hi_C;
          if (typeof wx.temp==="number") {
            if (wx.temp>100) {
              tempC = wx.temp - 273.15;
              lo_C = (typeof wx.lo==="number") ? (wx.lo - 273.15) : undefined;
              hi_C = (typeof wx.hi==="number") ? (wx.hi - 273.15) : undefined;
            } else {
              tempC = wx.temp; lo_C = wx.lo; hi_C = wx.hi;
            }
          }
  
          wCache = {
            w_temp_c: tempC,
            w_hi_C: hi_C,
            w_lo_C: lo_C,
            w_hum: wx.hum,
            w_rain: wx.rain,
            w_uv: wx.uv,
            w_txt: wx.txt,
            w_wind: wx.wind,
            w_wrose: wx.wrose,
            w_loc: wx.loc,
            w_ts: tsLocal
          };
        } else {
          if (!wWarned) { console.log("Weather JSON missing usable fields"); wWarned = true; }
          wCache = null;
        }
      } catch(e){
        if (!wWarned) { console.log("Weather read error:", e); wWarned = true; }
        wCache = null;
      }
      wCacheAt = Date.now();
      return wCache;
    }
  
    // ---------- Built-in status ----------
    function readBuiltinStatus() {
      var h = Bangle.getHealthStatus("last") || Bangle.getHealthStatus("day") || {};
      if (h.activity !== undefined) return h.activity;
      if (h.act !== undefined) return h.act;
      if (h.movement !== undefined) return h.movement;
      return null;
    }
  
    // ---------- Aggregation (reset every minute) ----------
    var hrWNum, hrWDen, hrSum, hrCount, confSum, tempSum, tempN, statusSamples, stepsStart, hrSamplesTotal, hrSamplesUsed;
  
    function resetAgg() {
      hrWNum = 0; hrWDen = 0;
      hrSum = 0; hrCount = 0;
      confSum = 0;
      tempSum = 0; tempN = 0;
      statusSamples = [];
      hrSamplesTotal = 0; hrSamplesUsed = 0;
      var h = Bangle.getHealthStatus("day") || {};
      stepsStart = h.steps || 0;
    }
  
    // ---------- HRM handler with threshold + weighting ----------
    var CONF_THRESHOLD = 80;
    function onHRM(e) {
      hrSamplesTotal += 1;
      var conf = (e && typeof e.confidence==="number") ? e.confidence : 0;
      var bpm  = (e && typeof e.bpm==="number") ? e.bpm : 0;
  
      if (conf >= CONF_THRESHOLD && bpm > 0) {
        var w = conf;      // weight = confidence (0..100)
        hrWNum += bpm * w;
        hrWDen += w;
  
        hrSum += bpm;
        hrCount += 1;
  
        confSum += conf;
        hrSamplesUsed += 1;
      }
  
      var t = E.getTemperature();
      if (typeof t === "number") { tempSum += t; tempN += 1; }
    }
  
    // ---------- Status sampler (every 2s) ----------
    setInterval(function(){ statusSamples.push(readBuiltinStatus()); }, 2000);
  
    // ---------- Minute flush ----------
    function writeMinuteLine() {
      var h = Bangle.getHealthStatus("day") || {};
      var stepsEnd = h.steps || 0;
  
      var hrWeighted   = (hrWDen > 0) ? Math.round((hrWNum / hrWDen) * 10)/10 : null;
      var hrUnweighted = (hrCount > 0) ? Math.round((hrSum   / hrCount)* 10)/10 : null;
      var confAvg      = (hrCount > 0) ? Math.round((confSum / hrCount)* 10)/10 : 0;
      var tempAvg      = (tempN   > 0) ? Math.round((tempSum / tempN)  *100)/100 : null;
      var statusMajor  = mode(statusSamples);
  
      var entry = {
        ts: formatLocal(Date.now()),
        hr: hrWeighted,
        hr_unweighted: hrUnweighted,
        conf: confAvg,
        temp: tempAvg,
        steps_delta: Math.max(0, stepsEnd - stepsStart),
        status: statusMajor,
        movement: (h.movement !== undefined ? h.movement : undefined),
        hr_samples_total: hrSamplesTotal,
        hr_samples_used: hrSamplesUsed
      };
  
      var w = getWeatherNow();
      if (w) {
        // merge weather fields without Object.assign (ES5-safe)
        for (var k in w) { if (w.hasOwnProperty(k)) entry[k] = w[k]; }
      }
  
      try {
        require("Storage").open("upload.log","a").write(JSON.stringify(entry)+"\n");
      } catch(e){
        console.log("Error writing upload.log:", e);
      }
    }
    setInterval(function(){ writeMinuteLine(); resetAgg(); }, 60000);
  
    // ---------- HRM duty cycle (10s on / 2s off) ----------
    function hrmCycleOn() {
      Bangle.setHRMPower(1);
      setTimeout(hrmCycleOff, 10000);
    }
    function hrmCycleOff() {
      Bangle.setHRMPower(0);
      setTimeout(hrmCycleOn, 2000);
    }
  
    // ---------- Start ----------
    resetAgg();
    Bangle.on("HRM", onHRM);
    hrmCycleOn();
    console.log("Continuous logging started (10s on / 2s off, 1 line/min)");
  })();
  