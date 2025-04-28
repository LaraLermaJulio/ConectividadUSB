package com.example.usbapc

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.*
import android.os.Bundle
import android.util.Log
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
import com.example.usbapc.ui.theme.USBAPCTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val TAG = "ESP8266USBDisplay"
    private lateinit var usbManager: UsbManager
    private val ACTION_USB_PERMISSION = "com.example.usbapc.USB_PERMISSION"

    // Probables IDs para adaptadores USB-Serial comunes (CH340, CP210x, FTDI, etc.)
    private val COMMON_USB_VENDORS = setOf(
        0x1a86,  // CH340
        0x10c4,  // CP210x
        0x0403,  // FTDI
        0x067b,  // Prolific
    )

    // Baudrate estándar para ESP8266 (generalmente 115200)
    private val ESP8266_BAUDRATE = 115200

    private val _usbDevices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    private val usbDevices: StateFlow<List<UsbDeviceInfo>> = _usbDevices.asStateFlow()

    private val _logMessages = MutableStateFlow<List<String>>(emptyList())
    private val logMessages: StateFlow<List<String>> = _logMessages.asStateFlow()

    private val _messageToSend = MutableStateFlow("Hola desde Android!")
    val messageToSend: StateFlow<String> = _messageToSend.asStateFlow()

    // Guardar referencias a las conexiones activas
    private var activeConnection: UsbDeviceConnection? = null
    private var activeSerialDriver: UsbSerialConnection? = null
    private var lastRequestedDevice: UsbDevice? = null

    // Receptor para manejar eventos USB
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // En el BroadcastReceiver, verifica la parte de recepción de permisos:
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }

                        // Añadir más información de depuración
                        val granted: Boolean = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        addLogMessage("USB PERMISSION RESPONSE - Device: ${device?.deviceName ?: "null"}, Granted: $granted")

                        if (device != null && granted) {
                            connectToESP8266(device)
                        } else {
                            // Mostrar un mensaje más detallado
                            if (device == null) {
                                addLogMessage("ERROR: Dispositivo nulo en respuesta de permiso")
                            } else {
                                addLogMessage("PERMISO DENEGADO para ${device.deviceName}. Intente desconectar y reconectar el dispositivo")
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (device != null) {
                        addLogMessage("Dispositivo USB conectado: ${device.deviceName}")
                        refreshDeviceList()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device: UsbDevice? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                    if (device != null) {
                        addLogMessage("Dispositivo USB desconectado: ${device.deviceName}")
                        closeConnection()
                        refreshDeviceList()
                    }
                }
            }
        }
    }

    // Clase para gestionar la comunicación serial
    inner class UsbSerialConnection(
        private val connection: UsbDeviceConnection,
        private val device: UsbDevice
    ) {
        private var controlInterface: UsbInterface? = null
        private var dataInterface: UsbInterface? = null
        private var inEndpoint: UsbEndpoint? = null
        private var outEndpoint: UsbEndpoint? = null
        private var baudRate = ESP8266_BAUDRATE // Usar 115200 por defecto para ESP8266

        // Constantes para CDC
        private val SET_LINE_CODING = 0x20
        private val GET_LINE_CODING = 0x21
        private val SET_CONTROL_LINE_STATE = 0x22

        fun initialize(): Boolean {
            try {
                // Buscar interfaces de control y datos
                for (i in 0 until device.interfaceCount) {
                    val intf = device.getInterface(i)

                    // Interface de control (CDC)
                    if (intf.interfaceClass == UsbConstants.USB_CLASS_COMM) {
                        controlInterface = intf
                        addLogMessage("Interfaz de control encontrada: #$i")
                    }

                    // Interface de datos (CDC)
                    if (intf.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                        dataInterface = intf
                        addLogMessage("Interfaz de datos encontrada: #$i")

                        // Buscar endpoints
                        for (j in 0 until intf.endpointCount) {
                            val endpoint = intf.getEndpoint(j)

                            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                                    inEndpoint = endpoint
                                    addLogMessage("Endpoint IN encontrado")
                                } else if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                                    outEndpoint = endpoint
                                    addLogMessage("Endpoint OUT encontrado")
                                }
                            }
                        }
                    }
                }

                // Si no encontramos interfaces CDC, probemos con otro enfoque para FTDI/CH340/CP210x
                if (controlInterface == null && dataInterface == null) {
                    addLogMessage("No se encontraron interfaces CDC, probando con enfoque genérico")

                    // Recorremos todas las interfaces disponibles hasta encontrar endpoints válidos
                    for (i in 0 until device.interfaceCount) {
                        val intf = device.getInterface(i)
                        var foundEndpoints = false

                        // Buscar endpoints bulk en esta interfaz
                        for (j in 0 until intf.endpointCount) {
                            val endpoint = intf.getEndpoint(j)

                            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                                if (endpoint.direction == UsbConstants.USB_DIR_IN) {
                                    inEndpoint = endpoint
                                    addLogMessage("Endpoint IN genérico encontrado en interfaz #$i")
                                    foundEndpoints = true
                                } else if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                                    outEndpoint = endpoint
                                    addLogMessage("Endpoint OUT genérico encontrado en interfaz #$i")
                                    foundEndpoints = true
                                }
                            }
                        }

                        // Si encontramos endpoints en esta interfaz, usarla
                        if (foundEndpoints) {
                            dataInterface = intf
                            break
                        }
                    }
                }

                // Reclamar interfaces
                if (controlInterface != null) {
                    if (!connection.claimInterface(controlInterface, true)) {
                        addLogMessage("Error: No se pudo reclamar la interfaz de control")
                        return false
                    }
                }

                if (dataInterface != null) {
                    if (!connection.claimInterface(dataInterface, true)) {
                        addLogMessage("Error: No se pudo reclamar la interfaz de datos")
                        if (controlInterface != null) {
                            connection.releaseInterface(controlInterface)
                        }
                        return false
                    }
                } else {
                    addLogMessage("Error: No se encontró interfaz de datos")
                    return false
                }

                // Intentar configurar el dispositivo según su tipo
                when {
                    isDeviceCH340() -> setupCH340()
                    isDeviceCP210x() -> setupCP210x()
                    isDeviceFTDI() -> setupFTDI()
                    controlInterface != null -> setupCDC()
                    else -> {
                        // Último recurso: intentar una configuración genérica
                        addLogMessage("Aplicando configuración genérica")
                    }
                }

                // Verificación final
                if (outEndpoint == null) {
                    addLogMessage("Error: No se encontró endpoint de salida")
                    releaseInterfaces()
                    return false
                }

                // Enviar un comando de reset al ESP8266
                sendResetSequence()

                addLogMessage("Conexión serial inicializada correctamente a $baudRate baudios")
                return true
            } catch (e: Exception) {
                addLogMessage("Error al inicializar conexión serial: ${e.message}")
                releaseInterfaces()
                return false
            }
        }

        private fun sendResetSequence() {
            try {
                // Enviar secuencia de reset (puede variar según el adaptador)
                write("\r\n".toByteArray())  // Enviar salto de línea para limpiar buffer
                Thread.sleep(100)

                // También podemos enviar comando AT para verificar comunicación
                write("AT\r\n".toByteArray())
            } catch (e: Exception) {
                addLogMessage("Error en secuencia de reset: ${e.message}")
            }
        }

        private fun isDeviceCH340(): Boolean {
            return device.vendorId == 0x1a86
        }

        private fun isDeviceCP210x(): Boolean {
            return device.vendorId == 0x10c4
        }

        private fun isDeviceFTDI(): Boolean {
            return device.vendorId == 0x0403
        }

        private fun setupCDC() {
            // Configuración de línea (baud rate, etc.)
            val lineCoding = ByteArray(7)
            lineCoding[0] = (baudRate and 0xff).toByte()
            lineCoding[1] = ((baudRate shr 8) and 0xff).toByte()
            lineCoding[2] = ((baudRate shr 16) and 0xff).toByte()
            lineCoding[3] = ((baudRate shr 24) and 0xff).toByte()
            lineCoding[4] = 0  // 1 stop bit
            lineCoding[5] = 0  // No parity
            lineCoding[6] = 8  // 8 data bits

            connection.controlTransfer(
                UsbConstants.USB_TYPE_CLASS or UsbConstants.USB_DIR_OUT,
                SET_LINE_CODING,
                0,
                0,
                lineCoding,
                lineCoding.size,
                5000
            )

            // DTR/RTS son importantes para ESP8266
            connection.controlTransfer(
                UsbConstants.USB_TYPE_CLASS or UsbConstants.USB_DIR_OUT,
                SET_CONTROL_LINE_STATE,
                0x03,  // DTR=1, RTS=1
                0,
                null,
                0,
                5000
            )
        }

        private fun setupCH340() {
            // Configuración mejorada para CH340
            addLogMessage("Configurando adaptador CH340 a $baudRate baudios")
            try {
                // Reset
                connection.controlTransfer(0x40, 0x9A, 0x2518, 0x0050, null, 0, 100)

                // Config
                connection.controlTransfer(0x40, 0x9A, 0x12, 0, null, 0, 100)

                // Configurar velocidad (cálculo específico para CH340)
                var factor = 0
                var divisor = 0

                // Cálculos para los baudrates más comunes
                when (baudRate) {
                    9600 -> {
                        factor = 0xd8
                        divisor = 0xfe
                    }
                    19200 -> {
                        factor = 0xd8
                        divisor = 0x7f
                    }
                    38400 -> {
                        factor = 0xd8
                        divisor = 0x3f
                    }
                    57600 -> {
                        factor = 0xc2
                        divisor = 0x29
                    }
                    115200 -> {
                        factor = 0xd8
                        divisor = 0x17
                    }
                    else -> {
                        // Cálculo genérico
                        factor = 0xd8
                        divisor = (0x384000 / baudRate).coerceIn(1, 255)
                    }
                }

                connection.controlTransfer(0x40, 0x9A, 0x1312, factor, null, 0, 100)
                connection.controlTransfer(0x40, 0x9A, 0x0f2c, divisor, null, 0, 100)

                // DTR/RTS control (important for ESP8266)
                connection.controlTransfer(0x40, 0x9A, 0x11, 0x3, null, 0, 100)
            } catch (e: Exception) {
                addLogMessage("Error al configurar CH340: ${e.message}")
            }
        }

        private fun setupCP210x() {
            // Configuración para CP210x
            addLogMessage("Configurando adaptador CP210x a $baudRate baudios")
            try {
                // IFC Enable
                connection.controlTransfer(0x41, 0, 0x0001, 0, null, 0, 100)

                // Baudrate
                val baudBytes = ByteArray(4)
                baudBytes[0] = (baudRate and 0xff).toByte()
                baudBytes[1] = ((baudRate shr 8) and 0xff).toByte()
                baudBytes[2] = ((baudRate shr 16) and 0xff).toByte()
                baudBytes[3] = ((baudRate shr 24) and 0xff).toByte()

                connection.controlTransfer(0x41, 0x1E, 0, 0, baudBytes, 4, 100)

                // Data characteristics (8N1)
                val lineBytes = ByteArray(7)
                lineBytes[0] = 0x80.toByte() // Enable
                lineBytes[1] = 0x00.toByte() // No parity
                lineBytes[6] = 0x08.toByte() // 8 data bits

                connection.controlTransfer(0x41, 0x03, 0, 0, lineBytes, 7, 100)

                // Modem control (DTR/RTS on)
                connection.controlTransfer(0x41, 0x07, 0x0303, 0, null, 0, 100)
            } catch (e: Exception) {
                addLogMessage("Error al configurar CP210x: ${e.message}")
            }
        }

        private fun setupFTDI() {
            // Configuración para FTDI
            addLogMessage("Configurando adaptador FTDI a $baudRate baudios")
            try {
                // Reset
                connection.controlTransfer(0x40, 0, 0, 0, null, 0, 100)

                // Baudrate
                val value = (24000000 / baudRate)
                val index = 0
                connection.controlTransfer(0x40, 3, value, index, null, 0, 100)

                // Data characteristics (8N1)
                connection.controlTransfer(0x40, 4, 8, 0, null, 0, 100)

                // Flow control
                connection.controlTransfer(0x40, 2, 0, 0, null, 0, 100)

                // DTR/RTS
                connection.controlTransfer(0x40, 1, 0x0303, 0, null, 0, 100)
            } catch (e: Exception) {
                addLogMessage("Error al configurar FTDI: ${e.message}")
            }
        }

        fun write(data: ByteArray): Int {
            try {
                if (outEndpoint == null) {
                    addLogMessage("Error: Endpoint de salida no disponible")
                    return -1
                }

                val result = connection.bulkTransfer(outEndpoint, data, data.size, 1000)
                return result
            } catch (e: Exception) {
                addLogMessage("Error al escribir datos: ${e.message}")
                return -1
            }
        }

        fun read(buffer: ByteArray, timeout: Int): Int {
            try {
                if (inEndpoint == null) {
                    return -1
                }

                return connection.bulkTransfer(inEndpoint, buffer, buffer.size, timeout)
            } catch (e: Exception) {
                addLogMessage("Error al leer datos: ${e.message}")
                return -1
            }
        }

        fun setBaudRate(rate: Int) {
            baudRate = rate
            // Reconfigurar según el tipo de dispositivo
            when {
                isDeviceCH340() -> setupCH340()
                isDeviceCP210x() -> setupCP210x()
                isDeviceFTDI() -> setupFTDI()
                controlInterface != null -> setupCDC()
            }
        }

        fun releaseInterfaces() {
            try {
                if (controlInterface != null) {
                    connection.releaseInterface(controlInterface)
                }
                if (dataInterface != null) {
                    connection.releaseInterface(dataInterface)
                }
            } catch (e: Exception) {
                addLogMessage("Error al liberar interfaces: ${e.message}")
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
        addLogMessage("Aplicación iniciada. Buscando adaptadores USB para ESP8266...")

        setContent {
            USBAPCTheme {
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
                        onConnectClick = { device -> requestPermission(device.usbDevice) },
                        onSendMessageClick = { sendMessage(_messageToSend.value) },
                        isConnected = activeConnection != null && activeSerialDriver != null
                    )
                }
            }
        }
    }
    @Composable
    fun MainScreen(
        usbDevices: List<UsbDeviceInfo>,
        logMessages: List<String>,
        messageToSend: String,
        onMessageChange: (String) -> Unit,
        onRefreshClick: () -> Unit,
        onConnectClick: (UsbDeviceInfo) -> Unit,
        onSendMessageClick: () -> Unit,
        isConnected: Boolean
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Título
            Text(
                text = "ESP8266 USB Display",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // Sección de dispositivos USB
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Dispositivos USB (${usbDevices.size})",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Button(onClick = onRefreshClick) {
                            Text("Refrescar")
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (usbDevices.isEmpty()) {
                        Text(
                            text = "No se encontraron dispositivos USB. Conecte un dispositivo y pulse Refrescar.",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        // Lista de dispositivos
                        usbDevices.forEach { device ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = device.name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "VID:${device.vendorId.toString(16)}, PID:${device.productId.toString(16)}, " +
                                                if (device.isLikelyESP) "Probablemente ESP8266" else "Desconocido",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                                Button(
                                    onClick = { onConnectClick(device) },
                                    modifier = Modifier.padding(start = 8.dp)
                                ) {
                                    Text("Conectar")
                                }
                            }
                            Divider()
                        }
                    }
                }
            }

            // Sección para enviar mensajes
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Enviar mensaje a ESP8266",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    TextField(
                        value = messageToSend,
                        onValueChange = onMessageChange,
                        label = { Text("Mensaje") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp)
                    )

                    Button(
                        onClick = onSendMessageClick,
                        enabled = isConnected,
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Enviar")
                    }

                    if (!isConnected) {
                        Text(
                            text = "Conecte un dispositivo para enviar mensajes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Sección de registro de eventos (logs)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Registro de eventos",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(logMessages) { message ->
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                            Divider(thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        closeConnection()
        unregisterReceiver(usbReceiver)
    }

    private fun refreshDeviceList() {
        val deviceList = usbManager.deviceList

        addLogMessage("Encontrados ${deviceList.size} dispositivos USB")

        val usbDeviceInfoList = deviceList.values.map { device ->
            // Detectar adaptadores USB-Serial comunes
            val isLikelyESP8266 = COMMON_USB_VENDORS.contains(device.vendorId) ||
                    device.deviceClass == UsbConstants.USB_CLASS_COMM ||
                    device.deviceClass == UsbConstants.USB_CLASS_CDC_DATA

            UsbDeviceInfo(
                usbDevice = device,
                name = device.deviceName,
                id = device.deviceId,
                vendorId = device.vendorId,
                productId = device.productId,
                deviceClass = device.deviceClass,
                interfaceCount = device.interfaceCount,
                isLikelyESP = isLikelyESP8266
            )
        }

        _usbDevices.update { usbDeviceInfoList }
    }

    private fun requestPermission(device: UsbDevice) {
        if (usbManager.hasPermission(device)) {
            addLogMessage("Permiso ya concedido para ${device.deviceName}, conectando...")
            connectToESP8266(device)
            return
        }
        lastRequestedDevice = device

        val permissionIntent = Intent(ACTION_USB_PERMISSION).apply {
            putExtra(UsbManager.EXTRA_DEVICE, device)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            this,
            device.deviceId,
            permissionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        usbManager.requestPermission(device, pendingIntent)
        addLogMessage("Solicitando permiso para dispositivo: ${device.deviceName}")
    }

    private fun connectToESP8266(device: UsbDevice) {
        try {
            addLogMessage("Intentando conectar a ESP8266 via: ${device.deviceName}")

            // Cerrar cualquier conexión existente
            closeConnection()

            val connection = usbManager.openDevice(device)
            if (connection != null) {
                addLogMessage("Conexión abierta con ${device.deviceName}")

                // Crear gestor de comunicación serial
                val serialDriver = UsbSerialConnection(connection, device)

                if (serialDriver.initialize()) {
                    // Guardar conexión activa
                    activeConnection = connection
                    activeSerialDriver = serialDriver

                    // Iniciar lectura de respuestas
                    startListening()

                    addLogMessage("¡Conexión con ESP8266 establecida correctamente!")

                    // Pequeña pausa para permitir que el ESP8266 se inicialice completamente
                    CoroutineScope(Dispatchers.IO).launch {
                        delay(1000)
                        sendMessage("INIT") // Mensaje de inicialización al ESP8266
                    }
                } else {
                    addLogMessage("Error al inicializar comunicación serial")
                    connection.close()
                }
            } else {
                addLogMessage("Error: No se pudo abrir la conexión con el dispositivo")
            }
        } catch (e: Exception) {
            addLogMessage("Error al conectar: ${e.message}")
            Log.e(TAG, "Error al conectar", e)
        }
    }

    private fun closeConnection() {
        try {
            activeSerialDriver?.releaseInterfaces()
            activeConnection?.close()
            activeConnection = null
            activeSerialDriver = null

            addLogMessage("Conexión cerrada")
        } catch (e: Exception) {
            addLogMessage("Error al cerrar la conexión: ${e.message}")
        }
    }

    private fun startListening() {
        val driver = activeSerialDriver ?: return

        addLogMessage("Iniciando escucha de respuestas del ESP8266...")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val buffer = ByteArray(1024)

                while (activeConnection != null && activeSerialDriver != null) {
                    // Leer datos con timeout corto
                    val readCount = driver.read(buffer, 200)

                    if (readCount > 0) {
                        val message = String(buffer, 0, readCount)
                        addLogMessage("Respuesta del ESP8266: $message")
                    }

                    // Pequeña pausa para no saturar la CPU
                    delay(50)
                }
            } catch (e: Exception) {
                addLogMessage("Error en la escucha: ${e.message}")
            }
        }
    }

    private fun sendMessage(message: String) {
        val driver = activeSerialDriver

        if (driver == null) {
            addLogMessage("Error: No hay una conexión activa para enviar mensajes")
            return
        }

        try {
            // Formato específico para nuestro ESP8266: "MSG:texto"
            val formattedMessage = "MSG:$message"
            val bytes = formattedMessage.toByteArray()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val result = driver.write(bytes)

                    if (result > 0) {
                        // Añadir un salto de línea para que el ESP lo procese mejor
                        driver.write("\r\n".toByteArray())
                        addLogMessage("Mensaje enviado al ESP8266: $formattedMessage ($result bytes)")
                    } else {
                        addLogMessage("Error al enviar mensaje. Código: $result")
                    }
                } catch (e: Exception) {
                    addLogMessage("Error al enviar: ${e.message}")
                }
            }
        } catch (e: Exception) {
            addLogMessage("Error al preparar el mensaje: ${e.message}")
        }
    }

    private fun addLogMessage(message: String) {
        Log.d(TAG, message)
        _logMessages.update { currentMessages ->
            val updatedMessages = currentMessages.toMutableList()
            updatedMessages.add(0, message)
            if (updatedMessages.size > 100) {
                updatedMessages.removeAt(updatedMessages.size - 1)
            }
            updatedMessages
        }
    }

    data class UsbDeviceInfo(
        val usbDevice: UsbDevice,
        val name: String,
        val id: Int,
        val vendorId: Int,
        val productId: Int,
        val deviceClass: Int,
        val interfaceCount: Int,
        val isLikelyESP: Boolean
    )
}