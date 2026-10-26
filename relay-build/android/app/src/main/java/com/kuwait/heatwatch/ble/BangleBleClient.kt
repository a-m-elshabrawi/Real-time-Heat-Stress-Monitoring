package com.kuwait.heatwatch.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.util.LinkedList
import java.util.UUID

/**
 * Bangle.js 2 BLE client — native equivalent of downloader.html / watch_interface.html.
 *
 * Transport: Nordic UART Service (NUS) used as an Espruino REPL. On connect we
 * enable TX notifications and write a one-line Espruino snippet to RX that reads
 * `upload.log` and streams it back framed as BEGIN: / DATA:<base64> / END:.
 * Incoming DATA chunks are base64-decoded, reassembled into newline-delimited
 * JSON log lines, and handed to [Listener.onLogLine].
 *
 * NOTE (design spec §6A, Option 1): the snippet keeps only the last N minutes
 * (default 60) of upload.log — a rolling tail — instead of the whole file. It
 * reads COOPERATIVELY (setTimeout yields between reads) so the watch stays
 * responsive and the BLE link isn't dropped. For fully continuous monitoring,
 * add reconnect + periodic re-fetch (and optionally a byte-offset seek).
 */
class BangleBleClient(private val ctx: Context) {

    interface Listener {
        fun onStatus(msg: String)
        fun onConnected()
        fun onLogLine(jsonLine: String)   // one decoded JSON line from upload.log
        fun onTransferComplete()
        fun onDisconnected()
        fun onError(msg: String)
    }

    var listener: Listener? = null

    // Every status/error also goes to Logcat (tag "HeatWatchBLE") so it can be read back
    // at your own pace — the on-screen status label only ever shows the latest one and
    // updates too fast to read live once the watch starts responding.
    private fun logStatus(msg: String) { Log.d(TAG, msg); listener?.onStatus(msg) }
    private fun logError(msg: String) { Log.e(TAG, msg); listener?.onError(msg) }

    private var gatt: BluetoothGatt? = null
    private var rx: BluetoothGattCharacteristic? = null   // phone -> watch (write)
    private var tx: BluetoothGattCharacteristic? = null   // watch -> phone (notify)

    // Framing / reassembly buffers
    private val protoBuf = StringBuilder()   // raw NUS text (protocol lines)
    private val lineBuf = StringBuilder()    // decoded log content
    private var receiving = false

    // Write queue (CCCD + code chunks) — one outstanding GATT write at a time
    private val writeQueue = LinkedList<ByteArray>()
    private var writing = false
    private var pendingWrite: ByteArray? = null   // chunk currently in flight — retried, not dropped, on failure
    private var writeRetries = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        logStatus("Connecting to ${device.name ?: device.address}…")
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        mainHandler.removeCallbacksAndMessages(null)
        gatt?.disconnect(); gatt?.close(); gatt = null
        receiving = false; protoBuf.clear(); lineBuf.clear()
        writeQueue.clear(); pendingWrite = null; writing = false; writeRetries = 0
    }

    /** Ask the watch to stream only the last [minutes] of upload.log (log is
     *  1 line/min → last [minutes] lines). The watch reads the file cooperatively
     *  (yielding between reads so the BLE stack stays serviced), keeps a rolling
     *  tail of the last N lines, and sends just those with BEGIN/DATA/END framing.
     *  Prefixed with Ctrl-P (Ctrl-P) to suppress the Espruino REPL echo. */
    @SuppressLint("MissingPermission")
    fun requestLog(fileName: String = "upload.log", minutes: Int = 60) {
        val q = "\""
        val nl = "\\n"                                   // emitted to watch as a newline literal
        val code = "\u0010" +
            "var n=" + jsStr(fileName) +
            ",f=require(${q}Storage${q}).open(n,${q}r${q}),KEEP=" + minutes + ",buf=${q}${q},tail=[];" +
            "if(!f){Bluetooth.println(${q}!ERR:OPEN_FAILED:${q}+n);}else{" +
            "var rd=function(){var s=f.read(256);" +
            "if(s){buf+=s;var i;while((i=buf.indexOf(${q}$nl${q}))>=0){" +
            "tail.push(buf.substr(0,i));buf=buf.substr(i+1);if(tail.length>KEEP)tail.shift();}setTimeout(rd,5);}" +
            "else{if(buf.length){tail.push(buf);if(tail.length>KEEP)tail.shift();}" +
            "var payload=tail.join(${q}$nl${q})+${q}$nl${q};Bluetooth.println(${q}BEGIN:${q}+n);var p=0;" +
            "var snd=function(){if(p>=payload.length){Bluetooth.println(${q}END:${q}+n);return;}" +
            "Bluetooth.println(${q}DATA:${q}+btoa(payload.substr(p,265)));p+=265;setTimeout(snd,30);};snd();}};rd();}\r\n"
            // trailing \r\n, not just \n — Espruino's console submits/executes a typed line
            // on carriage-return; a bare line-feed can leave it sitting unexecuted with no
            // output and no error, which is what we were seeing.
        logStatus("Requesting last $minutes min of $fileName…")
        enqueueChunked(code.toByteArray(Charsets.UTF_8))
    }

    // ── GATT callback ──────────────────────────────────────────────────────
    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> g.requestMtu(247)
                BluetoothProfile.STATE_DISCONNECTED -> { listener?.onDisconnected() }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) { g.discoverServices() }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(NUS_SERVICE)
            if (svc == null) { logError("Nordic UART service not found"); return }
            rx = svc.getCharacteristic(NUS_RX)
            tx = svc.getCharacteristic(NUS_TX)
            if (rx == null || tx == null) { logError("NUS characteristics missing"); return }
            val p = rx!!.properties
            Log.d(TAG, "RX properties=$p (WRITE=${p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0}, " +
                "WRITE_NO_RESPONSE=${p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0})")
            g.setCharacteristicNotification(tx, true)
            val cccd = tx!!.getDescriptor(CCCD)
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)   // completion → onDescriptorWrite
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            listener?.onConnected()   // ready; caller may now requestLog()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writing = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingWrite = null
                writeRetries = 0
                // "Write without response" doesn't wait for the watch to actually process the
                // chunk before this callback fires — it only confirms the phone's Bluetooth
                // radio sent it. Firing the next chunk immediately outruns the watch's own
                // firmware and drops bytes mid-stream (this was corrupting the injected code).
                // A short pause between chunks keeps it from overflowing.
                mainHandler.postDelayed({ pumpWriteQueue() }, CHUNK_PACING_DELAY_MS)
            } else if (writeRetries < MAX_WRITE_RETRIES) {
                // Stack was likely still busy from the previous GATT op (e.g. right after
                // enabling notifications). Back off briefly and resend the SAME chunk —
                // dropping it here corrupts the injected code with no way to detect it.
                writeRetries++
                mainHandler.postDelayed({ pumpWriteQueue() }, WRITE_RETRY_DELAY_MS)
            } else {
                pendingWrite = null
                writeRetries = 0
                logError("BLE write failed (status=$status) after $MAX_WRITE_RETRIES retries")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (c.uuid == NUS_TX) onIncoming(c.value)
        }
    }

    // ── Incoming protocol handling ─────────────────────────────────────────
    private fun onIncoming(bytes: ByteArray) {
        protoBuf.append(String(bytes, Charsets.UTF_8))
        var nl = protoBuf.indexOf("\n")
        while (nl >= 0) {
            val line = protoBuf.substring(0, nl).trim()
            protoBuf.delete(0, nl + 1)
            if (line.isNotEmpty()) handleProtoLine(line)
            nl = protoBuf.indexOf("\n")
        }
    }

    private fun handleProtoLine(line: String) {
        Log.d(TAG, "watch> $line")   // every raw line from the watch, always, regardless of what it is
        when {
            line.startsWith("BEGIN:") -> {
                receiving = true; lineBuf.clear()
                logStatus("Receiving ${line.substring(6)}…")
            }
            line.startsWith("DATA:") && receiving -> {
                val decoded = try {
                    String(Base64.decode(line.substring(5), Base64.DEFAULT), Charsets.UTF_8)
                } catch (e: Exception) { logError("base64 decode error"); return }
                lineBuf.append(decoded)
                var nl = lineBuf.indexOf("\n")
                while (nl >= 0) {
                    val json = lineBuf.substring(0, nl).trim()
                    lineBuf.delete(0, nl + 1)
                    if (json.isNotEmpty()) listener?.onLogLine(json)
                    nl = lineBuf.indexOf("\n")
                }
            }
            line.startsWith("END:") && receiving -> {
                receiving = false
                if (lineBuf.isNotBlank()) listener?.onLogLine(lineBuf.toString().trim())
                lineBuf.clear()
                listener?.onTransferComplete()
            }
            line.startsWith("!ERR:") -> logError("Watch: ${line.substring(5)}")
            else -> logStatus("watch: $line")   // unrecognized REPL output (e.g. a JS error) — already logged above too
        }
    }

    // ── Chunked, serialized writes to RX ───────────────────────────────────
    private fun enqueueChunked(data: ByteArray) {
        val chunk = 20    // small on purpose — the watch's own BLE/REPL input buffer can't
                           // keep up with large write-without-response bursts and silently
                           // drops bytes if fed faster than this (matches Espruino's own
                           // official tooling convention for code injection over BLE)
        var i = 0
        while (i < data.size) {
            val end = minOf(i + chunk, data.size)
            writeQueue.add(data.copyOfRange(i, end)); i = end
        }
        pumpWriteQueue()
    }

    @SuppressLint("MissingPermission")
    private fun pumpWriteQueue() {
        if (writing) return
        val g = gatt ?: return; val c = rx ?: return
        // Resend the same chunk on retry (pendingWrite set); otherwise pull the next one.
        val payload = pendingWrite ?: writeQueue.poll() ?: return
        pendingWrite = payload
        writing = true
        c.value = payload
        // Use "write without response" whenever the characteristic advertises it — Nordic
        // UART RX on this watch only supports that mode; requesting an acknowledged write
        // (WRITE_TYPE_DEFAULT) against it is rejected outright, every time, with
        // GATT_WRONG_STATE (0x82) — that's what was causing the permanent freeze.
        c.writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val queued = g.writeCharacteristic(c)
        if (!queued) {
            // writeCharacteristic() returning false means it was rejected locally (e.g. the
            // phone's own outgoing BLE buffer is full) — no callback will ever arrive for
            // this attempt, so we must retry it ourselves rather than wait indefinitely.
            writing = false
            if (writeRetries < MAX_WRITE_RETRIES) {
                writeRetries++
                mainHandler.postDelayed({ pumpWriteQueue() }, WRITE_RETRY_DELAY_MS)
            } else {
                pendingWrite = null
                writeRetries = 0
                logError("writeCharacteristic() rejected locally after $MAX_WRITE_RETRIES retries")
            }
        }
    }

    private fun jsStr(s: String) = "\"" + s.replace("\"", "\\\"") + "\""

    companion object {
        val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // write
        val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // notify
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val MAX_WRITE_RETRIES = 5
        private const val WRITE_RETRY_DELAY_MS = 150L
        private const val CHUNK_PACING_DELAY_MS = 25L
        private const val TAG = "HeatWatchBLE"
    }
}
