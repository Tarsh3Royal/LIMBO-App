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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    var statusText by remember { mutableStateOf("Waiting for UVC Capture Card...") }
    var bleStatus by remember { mutableStateOf("ESP32: Disconnected") }
    var batteryPct by remember { mutableStateOf("98%") }
    var cpuTemp by remember { mutableStateOf("44°C") }
    var showShutdownDialog by remember { mutableStateOf(false) }

    var activeGatt by remember { mutableStateOf<BluetoothGatt?>(null) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    var cameraDeviceRef by remember { mutableStateOf<CameraDevice?>(null) }

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
            } ?: manager.cameraIdList.lastOrNull()

            if (externalCamId == null) {
                statusText = "No UVC Capture Card Detected"
                return
            }

            val thread = HandlerThread("LimboUVCThread").apply { start() }
            val handler = Handler(thread.looper)

            cameraDeviceRef?.close()
            manager.openCamera(externalCamId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDeviceRef = camera
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
                                statusText = "UVC Stream Config Failed"
                            }
                        },
                        handler
                    )
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    statusText = "Capture Card Disconnected"
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    statusText = "UVC Error: $error"
                }
            }, handler)
        } catch (e: Exception) {
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
            .background(Color.Black)
            .clickable { hudVisible = !hudVisible }
    ) {
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

        AnimatedVisibility(
            visible = hudVisible,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
        ) {
            Surface(
                color = Color(0xCC12141A),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Text(
                        text = ";LIMBO",
                        color = Color(0xFF00E5FF),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp
                    )

                    Text(
                        text = statusText,
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BatteryChargingFull, contentDescription = null, tint = Color(0xFF4CAF50))
                        Text(batteryPct, color = Color.White, fontSize = 14.sp)
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Thermostat, contentDescription = null, tint = Color(0xFFFF9800))
                        Text(cpuTemp, color = Color.White, fontSize = 14.sp)
                    }

                    IconButton(onClick = { textureViewRef?.let { openExternalUvcCamera(it) } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reconnect HDMI", tint = Color.White)
                    }

                    OutlinedButton(onClick = { connectEsp32Ble() }) {
                        Icon(Icons.Default.Bluetooth, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(bleStatus, fontSize = 12.sp)
                    }

                    Button(
                        onClick = { showShutdownDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                    ) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(18.dp))
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
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
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
