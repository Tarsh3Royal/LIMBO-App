package com.tarsh.limbo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import java.util.UUID

val LIMBO_SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
val LIMBO_CMD_CHAR_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LimboConsoleScreen()
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

@SuppressLint("MissingPermission")
@Composable
fun LimboConsoleScreen() {
    val context = LocalContext.current
    var hudVisible by remember { mutableStateOf(true) }
    var isHdmiActive by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf("Waiting for HDMI Signal...") }
    var bleStatus by remember { mutableStateOf("ESP32: Disconnected") }
    var batteryPct by remember { mutableStateOf("98%") }
    var cpuTemp by remember { mutableStateOf("44°C") }
    var showShutdownDialog by remember { mutableStateOf(false) }
    var cursorVisible by remember { mutableStateOf(true) }

    var activeGatt by remember { mutableStateOf<BluetoothGatt?>(null) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var cameraDeviceRef by remember { mutableStateOf<CameraDevice?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(550)
            cursorVisible = !cursorVisible
        }
    }

    val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    var permissionsGranted by remember {
        mutableStateOf(
            requiredPermissions.all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        permissionsGranted = perms.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    fun openExternalUvcCamera(textureView: TextureView) {
        if (!permissionsGranted) return
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val externalCamId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_EXTERNAL
            }

            if (externalCamId == null) {
                isHdmiActive = false
                statusText = "No UVC Capture Card Detected"
                return
            }

            val thread = HandlerThread("LimboUVCThread").apply { start() }
            val handler = Handler(thread.looper)

            cameraDeviceRef?.close()
            manager.openCamera(externalCamId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDeviceRef = camera
                    isHdmiActive = true
                    statusText = "HDMI LINK ACTIVE (1080p)"
                    val surfaceTexture = textureView.surfaceTexture ?: return
                    surfaceTexture.setDefaultBufferSize(1920, 1080)
                    val previewSurface = Surface(surfaceTexture)

                    val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface)
                    }
                    camera.createCaptureSession(
                        listOf(previewSurface),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                session.setRepeatingRequest(captureRequest.build(), null, handler)
                            }
                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                isHdmiActive = false
                                statusText = "UVC Stream Config Failed"
                            }
                        },
                        handler
                    )
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    isHdmiActive = false
                    statusText = "Capture Card Disconnected"
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    isHdmiActive = false
                    statusText = "UVC Error: $error"
                }
            }, handler)
        } catch (e: Exception) {
            isHdmiActive = false
            statusText = "Error: ${e.message}"
        }
    }

    fun connectEsp32Ble() {
        if (!permissionsGranted) return
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = btManager.adapter?.bluetoothLeScanner ?: return
        bleStatus = "Scanning for LIMBO-ESP32..."

        scanner.startScan(object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.name == "LIMBO-ESP32") {
                    scanner.stopScan(this)
                    bleStatus = "Connecting..."
                    result.device.connectGatt(context, false, object : BluetoothGattCallback() {
                        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                            if (newState == BluetoothProfile.STATE_CONNECTED) {
                                activeGatt = gatt
                                bleStatus = "ESP32: Connected"
                                gatt.discoverServices()
                            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                activeGatt = null
                                bleStatus = "ESP32: Disconnected"
                            }
                        }
                    })
                }
            }
        })
    }

    fun sendEsp32Command(cmd: String) {
        val gatt = activeGatt ?: return
        val service = gatt.getService(LIMBO_SERVICE_UUID) ?: return
        val char = service.getCharacteristic(LIMBO_CMD_CHAR_UUID) ?: return
        char.value = cmd.toByteArray(Charsets.UTF_8)
        gatt.writeCharacteristic(char)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D12))
            .clickable { hudVisible = !hudVisible }
    ) {
        // Live Raspberry Pi HDMI Video Layer
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    textureViewRef = this
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                            openExternalUvcCamera(this@apply)
                        }
                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            }
        )

        // Raspberry Pi OS / Linux Console Standby Screen (hides automatically when HDMI is live)
        AnimatedVisibility(
            visible = !isHdmiActive,
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Color(0xFF1A101B), Color(0xFF0B0E14))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    color = Color(0xE610141C),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, Color(0xFF2D3648)),
                    modifier = Modifier.widthIn(min = 480.dp, max = 660.dp)
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Fake Linux Terminal Titlebar
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1B202C))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Terminal,
                                    contentDescription = null,
                                    tint = Color(0xFFC51A4A),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "pi@limbo-4b: ~ (HDMI-A-1)",
                                    color = Color(0xFFE2E8F0),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(10.dp).background(Color(0xFF4CAF50), CircleShape))
                                Box(Modifier.size(10.dp).background(Color(0xFFFFB300), CircleShape))
                                Box(Modifier.size(10.dp).background(Color(0xFFC51A4A), CircleShape))
                            }
                        }

                        // Linux Boot / Signal Standby Log
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "LIMBO PORTABLE WORKSTATION // RASPBERRY PI 4B",
                                color = Color(0xFFC51A4A),
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "[  OK  ] Started UVC Display Bridge (1920x1080 @ 60Hz)",
                                color = Color(0xFF4CAF50),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "[  OK  ] Loaded BLE Power Controller (ESP32-S3 Zero)",
                                color = Color(0xFF4CAF50),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "[ WAIT ] $statusText",
                                color = Color(0xFFFFB74D),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "pi@limbo:~$ connect --input usb-c-uvc${if (cursorVisible) " _" else ""}",
                                color = Color(0xFF00E5FF),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { textureViewRef?.let { openExternalUvcCamera(it) } },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFC51A4A),
                                        contentColor = Color.White
                                    )
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Re-scan HDMI", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                }
                                OutlinedButton(onClick = { connectEsp32Ble() }) {
                                    Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Pair ESP32-S3", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Top Floating Raspberry Pi Style Telemetry & Control Bar
        AnimatedVisibility(
            visible = hudVisible,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
        ) {
            Surface(
                color = Color(0xD9121620),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Color(0xFF2A3242)),
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (isHdmiActive) Color(0xFF4CAF50) else Color(0xFFC51A4A),
                                    CircleShape
                                )
                        )
                        Text(
                            text = "LIMBO",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 17.sp
                        )
                    }

                    Text(
                        text = statusText,
                        color = Color(0xFFB0BEC5),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = Color(0xFF4CAF50))
                        Text(batteryPct, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Thermostat, contentDescription = null, tint = Color(0xFFFF9800))
                        Text(cpuTemp, color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }

                    IconButton(onClick = { textureViewRef?.let { openExternalUvcCamera(it) } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reconnect HDMI", tint = Color.White)
                    }

                    OutlinedButton(onClick = { connectEsp32Ble() }) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(bleStatus, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                    }

                    Button(
                        onClick = { showShutdownDialog = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFC51A4A),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("SAFE SHUTDOWN", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        if (showShutdownDialog) {
            AlertDialog(
                onDismissRequest = { showShutdownDialog = false },
                title = { Text("Initiate Limbo Safe Shutdown?") },
                text = {
                    Text("This will instruct the ESP32-S3 Zero to safely shut down the Raspberry Pi 4 OS and cut MOSFET battery power after 15 seconds.")
                },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFC51A4A),
                            contentColor = Color.White
                        ),
                        onClick = {
                            sendEsp32Command("SHUTDOWN")
                            statusText = "SHUTTING DOWN PI 4..."
                            showShutdownDialog = false
                        }
                    ) {
                        Text("Shut Down Now")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showShutdownDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
