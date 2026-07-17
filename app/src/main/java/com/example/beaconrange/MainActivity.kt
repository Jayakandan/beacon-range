package com.example.beaconrange

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

// ---------------------------------------------------------------------------
// DISTANCE MATH  (same formula as your Kotlin code)
//
//   measuredAt1m = beaconTxPowerDbm - loss1m        (e.g. 0 - 59 = -59 dBm)
//   power        = (|rssi| - |measuredAt1m|) / (envFactor * 2.0)
//   distance     = 10 ^ power  *  refDistance       (metres)
//   feet         = distance * 3.28084
//
// envFactor is your configTxPower (20). beaconTxPower is the -4..+4 radio power.
// ---------------------------------------------------------------------------
object Range {
    const val M_TO_FT = 3.28084

    // Convert the beacon's radio Tx power (dBm, e.g. -4..+4) into the expected
    // RSSI at 1 metre. loss1m is the ~1 m path loss (default 59 dB).
    fun measuredAt1m(beaconTxDbm: Double, loss1m: Double): Double = beaconTxDbm - loss1m

    fun distanceMeters(rssi: Int, measuredAt1m: Double, envFactor: Double, refDistance: Double): Double {
        if (rssi >= 0) return 999.0
        val power = (abs(rssi) - abs(measuredAt1m)) / (envFactor * 2.0)
        return 10.0.pow(power) * refDistance
    }

    // The RSSI (dBm) at which a given distance (feet) is reached.
    // A beacon validates when its rssi >= this value (closer / stronger).
    fun rssiForFeet(feet: Double, measuredAt1m: Double, envFactor: Double, refDistance: Double): Int {
        val meters = feet / M_TO_FT
        if (meters <= 0.0) return -abs(measuredAt1m).toInt()
        val absRssi = abs(measuredAt1m) + envFactor * 2.0 * log10(meters / refDistance)
        return -absRssi.toInt()
    }
}

data class Beacon(val address: String, val name: String?, val rssi: Int, val seen: Long)

class MainActivity : ComponentActivity() {

    private var scanner: BluetoothLeScanner? = null
    private val beacons = mutableStateMapOf<String, Beacon>()
    private var scanning by mutableStateOf(false)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val addr = result.device?.address ?: return
            val name = try { result.device.name } catch (e: SecurityException) { null }
            beacons[addr] = Beacon(addr, name, result.rssi, System.currentTimeMillis())
        }
        override fun onScanFailed(errorCode: Int) { scanning = false }
    }

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted -> if (granted.values.all { it }) startScan() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bt = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        scanner = bt.adapter?.bluetoothLeScanner
        setContent {
            MaterialTheme {
                BeaconScreen(
                    beacons = beacons,
                    scanning = scanning,
                    onToggle = { if (scanning) stopScan() else ensurePermsThenScan() }
                )
            }
        }
    }

    private fun requiredPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun ensurePermsThenScan() {
        val missing = requiredPerms().any {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) permLauncher.launch(requiredPerms()) else startScan()
    }

    @SuppressLint("MissingPermission")
    private fun startScan() {
        val s = scanner ?: return
        beacons.clear()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        s.startScan(null, settings, scanCallback)
        scanning = true
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
    }
}

// ---------------------------------------------------------------------------
// UI
// ---------------------------------------------------------------------------
@Composable
fun BeaconScreen(
    beacons: Map<String, Beacon>,
    scanning: Boolean,
    onToggle: () -> Unit
) {
    var beaconTx    by remember { mutableStateOf("0") }    // radio Tx power dBm (-4..+4)
    var loss1m      by remember { mutableStateOf("59") }   // ~1 m path loss (dB)
    var envFactor   by remember { mutableStateOf("20") }   // your configTxPower
    var refDistance by remember { mutableStateOf("1.0") }  // configDistance (m)
    var validateFt  by remember { mutableStateOf("6.0") }  // validate within this many feet

    val tx    = beaconTx.toDoubleOrNull() ?: 0.0
    val loss  = loss1m.toDoubleOrNull() ?: 59.0
    val env   = envFactor.toDoubleOrNull() ?: 20.0
    val refD  = refDistance.toDoubleOrNull() ?: 1.0
    val vFt   = validateFt.toDoubleOrNull() ?: 6.0

    val measured   = Range.measuredAt1m(tx, loss)               // e.g. -59 dBm
    val cutoffRssi = Range.rssiForFeet(vFt, measured, env, refD)

    val list = beacons.values.sortedByDescending { it.rssi }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Beacon Range Scanner", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))

        Row {
            NumField("Beacon Tx (dBm)", beaconTx, { beaconTx = it }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            NumField("Loss @1m (dB)", loss1m, { loss1m = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            NumField("Env factor", envFactor, { envFactor = it }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            NumField("Ref dist (m)", refDistance, { refDistance = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            NumField("Validate (ft)", validateFt, { validateFt = it }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Spacer(Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))
        Surface(color = Color(0xFFEFF4FF), shape = MaterialTheme.shapes.small) {
            Column(Modifier.padding(10.dp)) {
                Text("Measured @1m = ${measured.toInt()} dBm  (Tx ${tx.toInt()} - loss ${loss.toInt()})")
                Text(
                    "Validates up to $vFt ft  ->  RSSI \u2265 $cutoffRssi dBm",
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(if (scanning) "STOP SCAN" else "START SCAN")
        }

        Spacer(Modifier.height(8.dp))
        Text("Found ${list.size} device(s)", color = Color.Gray, fontSize = 13.sp)
        Divider(Modifier.padding(vertical = 6.dp))

        LazyColumn {
            items(list, key = { it.address }) { b ->
                val dM  = Range.distanceMeters(b.rssi, measured, env, refD)
                val dFt = dM * Range.M_TO_FT
                val ok  = dFt <= vFt
                BeaconRow(b, dM, dFt, ok)
                Divider()
            }
        }
    }
}

@Composable
fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}

@Composable
fun BeaconRow(b: Beacon, meters: Double, feet: Double, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(b.name ?: "(unknown)", fontWeight = FontWeight.Medium)
            Text(b.address, fontSize = 12.sp, color = Color.Gray)
            Text("RSSI ${b.rssi} dBm", fontSize = 12.sp, color = Color.Gray)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("%.2f m".format(meters), fontWeight = FontWeight.Bold)
            Text("%.1f ft".format(feet), fontSize = 13.sp)
            Text(
                if (ok) "VALIDATE" else "OUT",
                color = if (ok) Color(0xFF1B7F2E) else Color(0xFFB00020),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End
            )
        }
    }
}
