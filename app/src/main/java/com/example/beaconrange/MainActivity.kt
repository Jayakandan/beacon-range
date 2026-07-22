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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import kotlin.math.pow

// ---------------------------------------------------------------------------
// EXACT production formula:
//   distance = 10 ^ ((|rssi| - |configMeasure|) / (configTxPower * 2.0)) * configDistance
// Validation zones (your config):
//   distance <= configImmediateFeet  -> IMMEDIATE  (VALIDATE)
//   distance <= configNear           -> NEAR
//   else                             -> FAR        (OUT)
// ---------------------------------------------------------------------------
object Range {
    fun distance(rssi: Int, configMeasure: Double, configTxPower: Double, configDistance: Double): Double {
        if (rssi >= 0) return 999.0
        val power = (abs(rssi) - abs(configMeasure)) / (configTxPower * 2.0)
        return 10.0.pow(power) * configDistance
    }
}

enum class Zone { IMMEDIATE, NEAR, FAR }

fun zoneOf(d: Double, immediate: Double, near: Double): Zone =
    when {
        d <= immediate -> Zone.IMMEDIATE
        d <= near      -> Zone.NEAR
        else           -> Zone.FAR
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
                BeaconApp(beacons, scanning) { if (scanning) stopScan() else ensurePermsThenScan() }
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
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
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
@Composable
fun BeaconApp(beacons: Map<String, Beacon>, scanning: Boolean, onToggle: () -> Unit) {
    var configMeasure   by remember { mutableStateOf("-60") }  // measure power
    var configTxPower   by remember { mutableStateOf("20") }   // tx power (factor)
    var configDistance  by remember { mutableStateOf("1.0") }  // reference multiplier
    var configImmediate by remember { mutableStateOf("3.0") }  // immediate zone
    var configNear      by remember { mutableStateOf("6.0") }  // near zone
    var selected        by remember { mutableStateOf<String?>(null) }

    val cm  = configMeasure.toDoubleOrNull() ?: -60.0
    val ctp = configTxPower.toDoubleOrNull() ?: 20.0
    val cd  = configDistance.toDoubleOrNull() ?: 1.0
    val ci  = configImmediate.toDoubleOrNull() ?: 3.0
    val cn  = configNear.toDoubleOrNull() ?: 6.0

    val sel = selected
    if (sel == null) {
        ListScreen(beacons.values.sortedByDescending { it.rssi }, scanning, cm, ctp, cd, ci, cn,
            onToggle) { selected = it }
    } else {
        BackHandler { selected = null }
        val b = beacons[sel]
        if (b == null) selected = null
        else DetailScreen(
            b,
            configMeasure, { configMeasure = it },
            configTxPower, { configTxPower = it },
            configDistance, { configDistance = it },
            configImmediate, { configImmediate = it },
            configNear, { configNear = it },
            onReadMeasure = { configMeasure = b.rssi.toString() },
            cm, ctp, cd, ci, cn
        ) { selected = null }
    }
}

@Composable
fun zoneColor(z: Zone): Color = when (z) {
    Zone.IMMEDIATE -> Color(0xFF1B7F2E)
    Zone.NEAR      -> Color(0xFFB8860B)
    Zone.FAR       -> Color(0xFFB00020)
}

@Composable
fun ListScreen(
    list: List<Beacon>, scanning: Boolean,
    cm: Double, ctp: Double, cd: Double, ci: Double, cn: Double,
    onToggle: () -> Unit, onSelect: (String) -> Unit
) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Beacon Range Scanner", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Tap a device to check the validation flow", color = Color.Gray, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        Button(onClick = onToggle, modifier = Modifier.fillMaxWidth()) {
            Text(if (scanning) "STOP SCAN" else "START SCAN")
        }
        Spacer(Modifier.height(8.dp))
        Text("Found ${list.size} device(s)", color = Color.Gray, fontSize = 13.sp)
        Divider(Modifier.padding(vertical = 6.dp))
        LazyColumn {
            items(list, key = { it.address }) { b ->
                val d = Range.distance(b.rssi, cm, ctp, cd)
                val z = zoneOf(d, ci, cn)
                Row(
                    Modifier.fillMaxWidth().clickable { onSelect(b.address) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(b.name ?: "(unknown)", fontWeight = FontWeight.Medium)
                        Text(b.address, fontSize = 12.sp, color = Color.Gray)
                        Text("RSSI ${b.rssi} dBm", fontSize = 12.sp, color = Color.Gray)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("%.2f".format(d), fontWeight = FontWeight.Bold)
                        Text(z.name, color = zoneColor(z), fontWeight = FontWeight.Bold)
                        Text("tap >", fontSize = 11.sp, color = Color(0xFF3366CC))
                    }
                }
                Divider()
            }
        }
    }
}

@Composable
fun DetailScreen(
    b: Beacon,
    configMeasure: String, onConfigMeasure: (String) -> Unit,
    configTxPower: String, onConfigTxPower: (String) -> Unit,
    configDistance: String, onConfigDistance: (String) -> Unit,
    configImmediate: String, onConfigImmediate: (String) -> Unit,
    configNear: String, onConfigNear: (String) -> Unit,
    onReadMeasure: () -> Unit,
    cm: Double, ctp: Double, cd: Double, ci: Double, cn: Double,
    onBack: () -> Unit
) {
    val d = Range.distance(b.rssi, cm, ctp, cd)
    val z = zoneOf(d, ci, cn)
    val validates = z == Zone.IMMEDIATE

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        TextButton(onClick = onBack) { Text("\u2190 Back to list") }
        Text(b.name ?: "(unknown)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(b.address, color = Color.Gray)
        Text("Live RSSI: ${b.rssi} dBm", fontSize = 13.sp, color = Color.Gray)

        Spacer(Modifier.height(10.dp))
        Surface(color = Color(0xFFF2F2F2), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text("distance", fontSize = 14.sp, color = Color.Gray)
                Text("%.3f".format(d), fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text("ZONE: ${z.name}", color = zoneColor(z), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(if (validates) "TICKET VALIDATES" else "NOT VALIDATED",
                    color = if (validates) Color(0xFF1B7F2E) else Color(0xFFB00020),
                    fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Config \u2014 change any, distance updates", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumField("configMeasure (dBm)", configMeasure, onConfigMeasure, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = onReadMeasure) { Text("READ @1m") }
        }
        Text("Hold phone 1 m from beacon, tap READ to capture ${b.rssi} dBm.",
            fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(8.dp))
        Row {
            NumField("configTxPower", configTxPower, onConfigTxPower, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            NumField("configDistance", configDistance, onConfigDistance, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row {
            NumField("configImmediateFeet", configImmediate, onConfigImmediate, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            NumField("configNear", configNear, onConfigNear, Modifier.weight(1f))
        }

        Spacer(Modifier.height(10.dp))
        Surface(color = Color(0xFFEFF4FF), shape = MaterialTheme.shapes.small) {
            Column(Modifier.padding(10.dp)) {
                Text("Formula", fontWeight = FontWeight.Bold)
                Text("10 ^ ((|${b.rssi}| - |${cm.toInt()}|) / (${ctp.toInt()} * 2)) * ${cd}",
                    fontSize = 12.sp)
                Text("= %.3f".format(d), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text("env: ${ctp * 2.0} (${ctp.toInt()})   measure: ${cm.toInt()}",
                    fontSize = 12.sp, color = Color.Gray)
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("distance per RSSI (validation flow)", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("RSSI", Modifier.weight(1f), fontWeight = FontWeight.Medium)
            Text("distance", Modifier.weight(1f), textAlign = TextAlign.End, fontWeight = FontWeight.Medium)
            Text("zone", Modifier.weight(1f), textAlign = TextAlign.End, fontWeight = FontWeight.Medium)
        }
        Divider()
        for (r in -50 downTo -95 step 5) {
            val dd = Range.distance(r, cm, ctp, cd)
            val zz = zoneOf(dd, ci, cn)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text("$r", Modifier.weight(1f))
                Text("%.2f".format(dd), Modifier.weight(1f), textAlign = TextAlign.End)
                Text(zz.name, Modifier.weight(1f), textAlign = TextAlign.End,
                    color = zoneColor(zz), fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun NumField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier
    )
}
