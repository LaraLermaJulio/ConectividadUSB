package com.example.conectividadusb


import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log
import java.nio.charset.Charset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

class UsbSerialConnection(
    private val usbManager: UsbManager
) {
    private val TAG = "UsbSerialConnection"

    private var deviceConnection: UsbDeviceConnection? = null
    private var usbInterface: UsbInterface? = null
    private var endpointIn: UsbEndpoint? = null
    private var endpointOut: UsbEndpoint? = null

    private val _connectionStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val _receivedMessages = MutableStateFlow<List<String>>(emptyList())
    val receivedMessages: StateFlow<List<String>> = _receivedMessages

    sealed class ConnectionStatus {
        object Disconnected : ConnectionStatus()
        object Connected : ConnectionStatus()
        data class Error(val message: String) : ConnectionStatus()
    }

    fun connect(device: UsbDevice): Boolean {
        try {
            // Obtener conexión con el dispositivo
            val connection = usbManager.openDevice(device)
                ?: return handleError("No se pudo abrir la conexión con el dispositivo")

            // Buscar una interfaz apropiada
            val usbInterface = findAppropriateInterface(device)
                ?: return handleError("No se encontró una interfaz adecuada")

            // Reclamar la interfaz
            if (!connection.claimInterface(usbInterface, true)) {
                connection.close()
                return handleError("No se pudo reclamar la interfaz")
            }

            // Buscar los endpoints para enviar y recibir datos
            val (inEndpoint, outEndpoint) = findEndpoints(usbInterface)

            if (inEndpoint == null || outEndpoint == null) {
                connection.releaseInterface(usbInterface)
                connection.close()
                return handleError("No se encontraron endpoints adecuados")
            }

            // Guardar las referencias
            this.deviceConnection = connection
            this.usbInterface = usbInterface
            this.endpointIn = inEndpoint
            this.endpointOut = outEndpoint

            _connectionStatus.value = ConnectionStatus.Connected
            Log.d(TAG, "Conexión USB establecida con éxito")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error al conectar: ${e.message}", e)
            return handleError("Error al conectar: ${e.message}")
        }
    }

    private fun findAppropriateInterface(device: UsbDevice): UsbInterface? {
        // Buscar la primera interfaz disponible
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            if (usbInterface.endpointCount >= 2) {
                return usbInterface
            }
        }
        return null
    }

    private fun findEndpoints(usbInterface: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
        var inEndpoint: UsbEndpoint? = null
        var outEndpoint: UsbEndpoint? = null

        for (i in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(i)

            when (endpoint.direction) {
                UsbConstants.USB_DIR_IN -> inEndpoint = endpoint
                UsbConstants.USB_DIR_OUT -> outEndpoint = endpoint
            }
        }

        return Pair(inEndpoint, outEndpoint)
    }

    private fun handleError(message: String): Boolean {
        Log.e(TAG, message)
        _connectionStatus.value = ConnectionStatus.Error(message)
        return false
    }

    fun disconnect() {
        deviceConnection?.let { connection ->
            usbInterface?.let { usbInterface ->
                try {
                    connection.releaseInterface(usbInterface)
                } catch (e: Exception) {
                    Log.e(TAG, "Error al liberar la interfaz: ${e.message}", e)
                }
            }
            try {
                connection.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error al cerrar la conexión: ${e.message}", e)
            }
        }

        deviceConnection = null
        usbInterface = null
        endpointIn = null
        endpointOut = null

        _connectionStatus.value = ConnectionStatus.Disconnected
        Log.d(TAG, "Dispositivo USB desconectado")
    }

    fun sendText(text: String): Boolean {
        val connection = deviceConnection ?: return false
        val endpoint = endpointOut ?: return false

        try {
            val bytes = text.toByteArray(Charset.forName("UTF-8"))
            val sent = connection.bulkTransfer(endpoint, bytes, bytes.size, TIMEOUT)

            if (sent < 0) {
                Log.e(TAG, "Error al enviar datos")
                return false
            }

            Log.d(TAG, "Enviados $sent bytes correctamente")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar texto: ${e.message}", e)
            return false
        }
    }

    suspend fun startListening() {
        withContext(Dispatchers.IO) {
            val connection = deviceConnection
            val endpoint = endpointIn

            if (connection == null || endpoint == null) {
                Log.e(TAG, "No se puede iniciar la escucha: conexión no establecida")
                return@withContext
            }

            val buffer = ByteArray(BUFFER_SIZE)

            while (_connectionStatus.value is ConnectionStatus.Connected) {
                try {
                    val bytesRead = connection.bulkTransfer(endpoint, buffer, buffer.size, TIMEOUT)

                    if (bytesRead > 0) {
                        val receivedText = String(buffer, 0, bytesRead, Charset.forName("UTF-8"))
                        Log.d(TAG, "Recibido: $receivedText")

                        _receivedMessages.value = _receivedMessages.value + receivedText
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error al recibir datos: ${e.message}", e)
                    // Pequeña pausa para evitar bucle infinito en caso de error
                    Thread.sleep(500)
                }
            }
        }
    }

    companion object {
        private const val TIMEOUT = 1000 // 1 segundo
        private const val BUFFER_SIZE = 1024 // 1KB
    }
}