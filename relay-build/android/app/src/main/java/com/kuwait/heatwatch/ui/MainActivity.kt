package com.kuwait.heatwatch.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kuwait.heatwatch.ble.BangleBleClient
import com.kuwait.heatwatch.model.RiskTier
import com.kuwait.heatwatch.model.ScoredReading
import com.kuwait.heatwatch.model.ScoringPipeline

class MainActivity : ComponentActivity() {

    private lateinit var pipeline: ScoringPipeline
    private lateinit var ble: BangleBleClient
    private val adapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    // UI state
    private val readings = mutableStateListOf<ScoredReading>()
    private val status = mutableStateOf("Idle")
    private val connected = mutableStateOf(false)

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> if (grants.values.all { it }) startScan() else status.value = "Bluetooth permission denied" }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pipeline = ScoringPipeline()
        ble = BangleBleClient(this).apply { listener = bleListener }
        setContent { HeatWatchScreen(status, connected, readings, onConnect = ::onConnectClicked, onDisconnect = { ble.disconnect() }) }
    }

    private fun onConnectClicked() {
        val needed = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) startScan() else permLauncher.launch(needed.toTypedArray())
    }

    private fun requiredPermissions(): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { status.value = "Bluetooth off"; return }
        status.value = "Scanning for Bangle.js…"
        readings.clear(); pipeline.reset()
        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d: BluetoothDevice = result.device
                val name = d.name ?: return
                if (name.startsWith("Bangle", ignoreCase = true)) {
                    scanner.stopScan(this)
                    ble.connect(d)
                }
            }
            override fun onScanFailed(errorCode: Int) { status.value = "Scan failed ($errorCode)" }
        })
    }

    private val bleListener = object : BangleBleClient.Listener {
        override fun onStatus(msg: String) { runOnUiThread { status.value = msg } }
        override fun onConnected() { runOnUiThread { connected.value = true; status.value = "Connected"; ble.requestLog() } }
        override fun onLogLine(jsonLine: String) {
            val r = pipeline.onLogLine(jsonLine) ?: return
            runOnUiThread { readings.add(r) }
        }
        override fun onTransferComplete() {
            val last = pipeline.flush()
            runOnUiThread {
                if (last != null) readings.add(last)
                status.value = "Done — ${readings.size} records"
            }
        }
        override fun onDisconnected() { runOnUiThread { connected.value = false; status.value = "Disconnected" } }
        override fun onError(msg: String) { runOnUiThread { status.value = "Error: $msg" } }
    }
}

// ── Compose UI ──────────────────────────────────────────────────────────────
private val BG = Color(0xFF0D1117)
private val SURFACE = Color(0xFF161B22)
private val BORDER = Color(0xFF30363D)
private val TEXT = Color(0xFFE6EDF3)
private val MUTED = Color(0xFF8B949E)

@Composable
fun HeatWatchScreen(
    status: State<String>,
    connected: State<Boolean>,
    readings: List<ScoredReading>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF58A6FF), background = BG, surface = SURFACE)) {
        Surface(Modifier.fillMaxSize(), color = BG) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("Heat" + "Watch", color = TEXT, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                Text("Bangle.js 2 · Kuwait University", color = MUTED, fontSize = 12.sp)

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!connected.value)
                        Button(onClick = onConnect) { Text("Connect to Watch") }
                    else
                        OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                    Spacer(Modifier.width(12.dp))
                    Text(status.value, color = MUTED, fontSize = 12.sp)
                }

                Spacer(Modifier.height(16.dp))
                readings.lastOrNull()?.let { LatestCard(it) }

                Spacer(Modifier.height(16.dp))
                Text("Session Log (${readings.size} × 10-min)", color = MUTED, fontSize = 12.sp)
                LazyColumn(Modifier.fillMaxSize()) {
                    items(readings.reversed()) { r -> ReadingRow(r) }
                }
            }
        }
    }
}

@Composable
private fun LatestCard(r: ScoredReading) {
    val tierColor = Color(r.tier.colorHex)
    Surface(color = SURFACE, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(2.dp, tierColor)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("LATEST — ${r.ts}", color = MUTED, fontSize = 10.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("%.2f".format(r.hsrs), color = tierColor, fontWeight = FontWeight.Bold, fontSize = 40.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                Text("/ 10  ·  ${r.tier.label}", color = tierColor, fontSize = 14.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("HR", "%.0f".format(r.hr), "bpm", r.hr > 130)
                Metric("Core T", "%.2f".format(r.coreTemp), "°C", r.coreTemp > 39.5)
                Metric("Heat Idx", "%.1f".format(r.heatIndex), "°C", r.heatIndex > 41)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String, unit: String, abnormal: Boolean) {
    Column {
        Text(label, color = MUTED, fontSize = 10.sp)
        Text(value, color = if (abnormal) Color(0xFFF85149) else TEXT, fontWeight = FontWeight.Bold, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
        Text(unit, color = MUTED, fontSize = 10.sp)
    }
}

@Composable
private fun ReadingRow(r: ScoredReading) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(r.ts, color = MUTED, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        Text("%.2f  %s".format(r.hsrs, r.tier.label), color = Color(r.tier.colorHex), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    }
    Divider(color = BORDER, thickness = 0.5.dp)
}
