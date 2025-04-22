package com.example.conectividadusb


import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class UsbViewModel(
    private val usbManager: UsbManager,
    private val activity: MainActivity
) : ViewModel() {
    private val _deviceList = mutableStateListOf<UsbDevice>()
    val deviceList: List<UsbDevice> = _deviceList

    private val _statusText = mutableStateOf("Estado: Esperando dispositivos USB...")
    val statusText: State<String> = _statusText

    private val _messageToSend = mutableStateOf("")
    val messageToSend: State<String> = _messageToSend

    private val _receivedMessages = mutableStateListOf<String>()
    val receivedMessages: List<String> = _receivedMessages

    private val _connectedDevice = mutableStateOf<UsbDevice?>(null)
    val connectedDevice: State<UsbDevice?> = _connectedDevice

    private var usbSerialConnection: UsbSerialConnection? = null

    init {
        refreshDeviceList()
        usbSerialConnection = UsbSerialConnection(usbManager)

        // Observar el estado de la conexión
        viewModelScope.launch {
            usbSerialConnection?.connectionStatus?.collectLatest { status ->
                when (status) {
                    is UsbSerialConnection.ConnectionStatus.Connected -> {
                        _statusText.value = "Estado: Conectado al dispositivo"
                    }
                    is UsbSerialConnection.ConnectionStatus.Disconnected -> {
                        _statusText.value = "Estado: Desconectado"
                        _connectedDevice.value = null
                    }
                    is UsbSerialConnection.ConnectionStatus.Error -> {
                        _statusText.value = "Error: ${status.message}"
                        _connectedDevice.value = null
                    }
                }
            }
        }

        // Observar los mensajes recibidos
        viewModelScope.launch {
            usbSerialConnection?.receivedMessages?.collectLatest { messages ->
                _receivedMessages.clear()
                _receivedMessages.addAll(messages)
            }
        }
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

    fun connectToDevice(device: UsbDevice) {
        usbSerialConnection?.let { connection ->
            if (connection.connect(device)) {
                _connectedDevice.value = device

                // Iniciar escucha de mensajes
                viewModelScope.launch {
                    connection.startListening()
                }
            }
        }
    }

    fun disconnectDevice() {
        usbSerialConnection?.disconnect()
        _connectedDevice.value = null
    }

    fun updateMessageToSend(message: String) {
        _messageToSend.value = message
    }

    fun sendMessage(): Boolean {
        val message = _messageToSend.value
        if (message.isBlank()) return false

        val success = usbSerialConnection?.sendText(message) ?: false
        if (success) {
            _messageToSend.value = ""
        }
        return success
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

    override fun onCleared() {
        super.onCleared()
        usbSerialConnection?.disconnect()
    }
}