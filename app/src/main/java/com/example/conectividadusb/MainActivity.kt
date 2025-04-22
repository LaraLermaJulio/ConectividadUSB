
package com.example.conectividadusb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
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
    private var selectedDevice: UsbDevice? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                // Permiso concedido, conectamos con el dispositivo
                                selectedDevice = it
                                if (::viewModel.isInitialized) {
                                    viewModel.connectToDevice(it)
                                }
                            }
                        } else {
                            // Permiso denegado
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    refreshDeviceList()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (device != null && device == selectedDevice) {
                        if (::viewModel.isInitialized) {
                            viewModel.disconnectDevice()
                        }
                        selectedDevice = null
                    }

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
        if (::viewModel.isInitialized) {
            viewModel.disconnectDevice()
        }
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
    val connectedDevice by remember { viewModel.connectedDevice }
    val messageToSend by remember { viewModel.messageToSend }
    val receivedMessages = remember { viewModel.receivedMessages }

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

        if (connectedDevice != null) {
            // Si hay un dispositivo conectado, mostrar la interfaz de chat
            Column {
                Text(
                    text = "Conectado a: ${connectedDevice?.deviceName}",
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { viewModel.disconnectDevice() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Desconectar")
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Área de mensajes recibidos
                Text(
                    text = "Mensajes recibidos:",
                    fontWeight = FontWeight.Bold
                )

                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(vertical = 8.dp)
                ) {
                    items(receivedMessages) { message ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Text(
                                text = message,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Área de envío de mensajes
                Text(
                    text = "Enviar mensaje:",
                    fontWeight = FontWeight.Bold
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = messageToSend,
                        onValueChange = { viewModel.updateMessageToSend(it) },
                        placeholder = { Text("Escribe tu mensaje") },
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 8.dp)
                    )

                    Button(
                        onClick = { viewModel.sendMessage() },
                        enabled = messageToSend.isNotBlank()
                    ) {
                        Text("Enviar")
                    }
                }
            }
        } else {
            // Si no hay dispositivo conectado, mostrar la lista de dispositivos disponibles
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
                Text("Conectar")
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