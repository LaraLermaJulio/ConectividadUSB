package com.example.usb


import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MainActivity : ComponentActivity() {
    private lateinit var usbManager: UsbManager
    private val ACTION_USB_PERMISSION = "com.example.usbconnectivitytester.USB_PERMISSION"

    private val _usbDevices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    private val usbDevices: StateFlow<List<UsbDeviceInfo>> = _usbDevices.asStateFlow()

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    private val logMessages: StateFlow<List<String>> = _logMessages.asStateFlow()

    private val _messageToSend = MutableStateFlow("Hola desde USB!")
    val messageToSend: StateFlow<String> = _messageToSend.asStateFlow()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        val granted: Boolean = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                        if (granted && device != null) {
                            addLogMessage("Permiso concedido para dispositivo: ${device.deviceName}")
                            connectToDevice(device)
                        } else {
                            addLogMessage("Permiso denegado para dispositivo")
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        addLogMessage("Dispositivo USB conectado: ${device.deviceName}")
                        refreshDeviceList()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (device != null) {
                        addLogMessage("Dispositivo USB desconectado: ${device.deviceName}")
                        refreshDeviceList()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }

        // Usar ContextCompat.registerReceiver con RECEIVER_NOT_EXPORTED
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        refreshDeviceList()
        addLogMessage("Aplicación iniciada. Buscando dispositivos USB...")

        setContent {
            USBConnectivityTesterTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        usbDevices = usbDevices.collectAsState().value,
                        logMessages = logMessages.collectAsState().value,
                        messageToSend = messageToSend.collectAsState().value,
                        onMessageChange = { _messageToSend.value = it },
                        onRefreshClick = { refreshDeviceList() },
                        onConnectClick = { device -> requestPermission(device.usbDevice) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    private fun refreshDeviceList() {
        val deviceList = usbManager.deviceList
        val usbDeviceInfoList = deviceList.values.map { device ->
            UsbDeviceInfo(
                usbDevice = device,
                name = device.deviceName,
                id = device.deviceId,
                vendorId = device.vendorId,
                productId = device.productId
            )
        }
        _usbDevices.update { usbDeviceInfoList }
        addLogMessage("Se encontraron ${usbDeviceInfoList.size} dispositivos USB")
    }

    private fun requestPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_IMMUTABLE
        )
        usbManager.requestPermission(device, permissionIntent)
        addLogMessage("Solicitando permiso para el dispositivo: ${device.deviceName}")
    }

    private fun connectToDevice(device: UsbDevice) {
        try {
            val connection = usbManager.openDevice(device)
            if (connection != null) {
                addLogMessage("Conexión establecida con: ${device.deviceName}")

                // Buscar endpoints adecuados para enviar datos
                var outEndpoint: UsbEndpoint? = null
                var usbInterface: UsbInterface? = null

                // Buscar una interfaz y endpoint compatible
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)

                    // Buscar un endpoint de salida (OUT)
                    for (j in 0 until intf.endpointCount) {
                        val endpoint = intf.getEndpoint(j)
                        if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                            outEndpoint = endpoint
                            usbInterface = intf
                            break
                        }
                    }

                    if (outEndpoint != null) break
                }

                if (outEndpoint != null && usbInterface != null) {
                    // Reclamar la interfaz
                    connection.claimInterface(usbInterface, true)

                    // Enviar el mensaje
                    val messageBytes = _messageToSend.value.toByteArray()
                    val bytesSent = connection.bulkTransfer(outEndpoint, messageBytes, messageBytes.size, 5000)

                    if (bytesSent > 0) {
                        addLogMessage("Mensaje enviado correctamente ($bytesSent bytes): ${_messageToSend.value}")
                    } else {
                        addLogMessage("Error al enviar el mensaje")
                    }

                    // Liberar la interfaz
                    connection.releaseInterface(usbInterface)
                } else {
                    addLogMessage("No se encontraron endpoints compatibles para enviar mensajes")
                }

                connection.close()
            } else {
                addLogMessage("Error: No se pudo abrir la conexión con el dispositivo")
            }
        } catch (e: Exception) {
            addLogMessage("Error al conectar o enviar mensaje: ${e.message}")
        }
    }

    private fun addLogMessage(message: String) {
        _logMessages.update { currentMessages ->
            val updatedMessages = currentMessages.toMutableList()
            updatedMessages.add(0, message)
            if (updatedMessages.size > 100) {
                updatedMessages.removeAt(updatedMessages.size - 1)
            }
            updatedMessages
        }
    }
}

data class UsbDeviceInfo(
    val usbDevice: UsbDevice,
    val name: String,
    val id: Int,
    val vendorId: Int,
    val productId: Int
)

@Composable
fun MainScreen(
    usbDevices: List<UsbDeviceInfo>,
    logMessages: List<String>,
    messageToSend: String,
    onMessageChange: (String) -> Unit,
    onRefreshClick: () -> Unit,
    onConnectClick: (UsbDeviceInfo) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Probador de Conectividad USB",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        Button(
            onClick = onRefreshClick,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Actualizar Lista de Dispositivos")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Campo para el mensaje a enviar
        OutlinedTextField(
            value = messageToSend,
            onValueChange = onMessageChange,
            label = { Text("Mensaje a enviar") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Dispositivos USB (${usbDevices.size})",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        if (usbDevices.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    text = "No se encontraron dispositivos USB",
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                items(usbDevices) { device ->
                    DeviceItem(
                        device = device,
                        onConnectClick = { onConnectClick(device) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Registro de Actividad",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            items(logMessages) { message ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun DeviceItem(device: UsbDeviceInfo, onConnectClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Nombre: ${device.name}",
                style = MaterialTheme.typography.bodyLarge
            )
            Text(
                text = "ID: ${device.id}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Vendor ID: ${device.vendorId.toString(16)} / Product ID: ${device.productId.toString(16)}",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onConnectClick,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Conectar y Probar")
            }
        }
    }
}

@Composable
fun USBConnectivityTesterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(),
        content = content
    )
}