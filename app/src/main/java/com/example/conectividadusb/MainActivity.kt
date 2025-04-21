// MainActivity.kt
package com.example.usbtest

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private val ACTION_USB_PERMISSION = "com.example.usbtest.USB_PERMISSION"
    private lateinit var usbManager: UsbManager

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                // Permiso concedido, podemos interactuar con el dispositivo
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    refreshDeviceList()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshDeviceList()
                }
            }
        }
    }

    private lateinit var viewModel: UsbViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // Registrar el receptor para eventos USB con el flag apropiado
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        // Usar ContextCompat para registrar el receptor con el flag RECEIVER_NOT_EXPORTED
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            UsbTestTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    viewModel = viewModel(
                        factory = UsbViewModelFactory(usbManager, this)
                    )
                    UsbTestScreen(viewModel)
                }
            }
        }
    }

    private fun refreshDeviceList() {
        if (::viewModel.isInitialized) {
            viewModel.refreshDeviceList()
        }
    }

    fun requestUsbPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }
}

class UsbViewModel(
    private val usbManager: UsbManager,
    private val activity: MainActivity
) : ViewModel() {
    private val _deviceList = mutableStateListOf<UsbDevice>()
    val deviceList: List<UsbDevice> = _deviceList

    private val _statusText = mutableStateOf("Estado: Esperando dispositivos USB...")
    val statusText: State<String> = _statusText

    init {
        refreshDeviceList()
    }

    fun refreshDeviceList() {
        _deviceList.clear()

        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            _statusText.value = "Estado: No hay dispositivos USB conectados"
            return
        }

        _statusText.value = "Estado: ${deviceList.size} dispositivo(s) encontrado(s)"

        _deviceList.addAll(deviceList.values)
    }

    fun requestPermission(device: UsbDevice) {
        activity.requestUsbPermission(device)
    }

    // Función para obtener información formateada del dispositivo
    fun getDeviceInfo(device: UsbDevice): String {
        return """
            Nombre: ${device.deviceName}
            ID Fabricante: ${device.vendorId}
            ID Producto: ${device.productId}
            Clase: ${device.deviceClass}
            Interfaces: ${device.interfaceCount}
        """.trimIndent()
    }
}

class UsbViewModelFactory(
    private val usbManager: UsbManager,
    private val activity: MainActivity
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(UsbViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return UsbViewModel(usbManager, activity) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

@Composable
fun UsbTestScreen(viewModel: UsbViewModel) {
    val context = LocalContext.current
    val statusText by remember { viewModel.statusText }
    val deviceList = remember { viewModel.deviceList }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Prueba de Conectividad USB",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = statusText,
            fontSize = 16.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { viewModel.refreshDeviceList() },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Actualizar lista de dispositivos")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Dispositivos conectados:",
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            modifier = Modifier.weight(1f)
        ) {
            items(deviceList) { device ->
                DeviceItem(
                    device = device,
                    info = viewModel.getDeviceInfo(device),
                    onDeviceClick = { viewModel.requestPermission(device) }
                )
            }
        }
    }
}

@Composable
fun DeviceItem(device: UsbDevice, info: String, onDeviceClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "Dispositivo: ${device.deviceName}",
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(text = info)

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onDeviceClick,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Solicitar Permiso")
            }
        }
    }
}

// Theme.kt
@Composable
fun UsbTestTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(),
        typography = Typography(),
        content = content
    )
}