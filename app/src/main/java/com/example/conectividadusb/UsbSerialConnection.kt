package com.example.conectividadusb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
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

    private val _receivedMessages = MutableStateFlow<List<MessageItem>>(emptyList())
    val receivedMessages: StateFlow<List<MessageItem>> = _receivedMessages

    sealed class ConnectionStatus {
        object Disconnected : ConnectionStatus()
        object Connected : ConnectionStatus()
        data class Error(val message: String) : ConnectionStatus()
    }

    sealed class MessageItem {
        data class TextMessage(val text: String) : MessageItem()
        data class ImageMessage(val bitmap: Bitmap) : MessageItem()
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
        // Buscar la interfaz apropiada con prioridad a las interfaces de comunicación
        for (i in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(i)
            // Preferir interfaces de clase de comunicación (CDC) o datos
            if (usbInterface.interfaceClass == UsbConstants.USB_CLASS_COMM ||
                usbInterface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA ||
                usbInterface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC) {
                if (usbInterface.endpointCount >= 2) {
                    return usbInterface
                }
            }
        }

        // Si no se encuentra una interfaz preferida, buscar cualquier interfaz válida
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

            if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                when (endpoint.direction) {
                    UsbConstants.USB_DIR_IN -> inEndpoint = endpoint
                    UsbConstants.USB_DIR_OUT -> outEndpoint = endpoint
                }
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
        // Formato: TEXT:mensaje
        val messageWithPrefix = "TEXT:$text"
        return sendData(messageWithPrefix.toByteArray(Charset.forName("UTF-8")))
    }

    fun sendImage(bitmap: Bitmap): Boolean {
        try {
            // Comprimir la imagen
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
            val imageBytes = stream.toByteArray()

            // Convertir a Base64 para transmisión de texto
            val base64Image = Base64.encodeToString(imageBytes, Base64.DEFAULT)

            // Formato: IMG:datos_base64
            val messageWithPrefix = "IMG:$base64Image"
            val bytes = messageWithPrefix.toByteArray(Charset.forName("UTF-8"))

            // Enviar en chunks si es muy grande
            return sendLargeData(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar imagen: ${e.message}", e)
            return false
        }
    }

    private fun sendData(bytes: ByteArray): Boolean {
        val connection = deviceConnection ?: return false
        val endpoint = endpointOut ?: return false

        try {
            val sent = connection.bulkTransfer(endpoint, bytes, bytes.size, TIMEOUT)

            if (sent < 0) {
                Log.e(TAG, "Error al enviar datos")
                return false
            }

            Log.d(TAG, "Enviados $sent bytes correctamente")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error al enviar datos: ${e.message}", e)
            return false
        }
    }

    private fun sendLargeData(bytes: ByteArray): Boolean {
        // Para datos grandes, enviamos en chunks
        val chunkSize = MAX_CHUNK_SIZE
        var offset = 0

        // Primero enviamos el tamaño total a esperar
        val sizeHeader = "SIZE:${bytes.size}"
        if (!sendData(sizeHeader.toByteArray(Charset.forName("UTF-8")))) {
            return false
        }

        // Esperar un poco para que el receptor se prepare
        Thread.sleep(200)

        // Enviar los datos en chunks
        while (offset < bytes.size) {
            val remaining = bytes.size - offset
            val currentChunkSize = remaining.coerceAtMost(chunkSize)

            val chunk = ByteArray(currentChunkSize)
            System.arraycopy(bytes, offset, chunk, 0, currentChunkSize)

            if (!sendData(chunk)) {
                return false
            }

            offset += currentChunkSize
            // Pequeña pausa entre chunks para evitar sobrecargar el buffer
            Thread.sleep(50)
        }

        return true
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
            var accumulatedData = ByteArray(0)
            var expectedSize = -1
            var isReceivingImage = false

            while (_connectionStatus.value is ConnectionStatus.Connected) {
                try {
                    val bytesRead = connection.bulkTransfer(endpoint, buffer, buffer.size, TIMEOUT)

                    if (bytesRead > 0) {
                        val chunk = buffer.copyOfRange(0, bytesRead)

                        // Si estamos en medio de recibir una imagen grande
                        if (expectedSize > 0) {
                            // Añadir este chunk a los datos acumulados
                            accumulatedData = accumulatedData.plus(chunk)

                            // Verificar si hemos recibido todos los datos esperados
                            if (accumulatedData.size >= expectedSize) {
                                if (isReceivingImage) {
                                    processImageData(accumulatedData)
                                } else {
                                    processTextData(String(accumulatedData, Charset.forName("UTF-8")))
                                }
                                // Reiniciar para el próximo mensaje
                                accumulatedData = ByteArray(0)
                                expectedSize = -1
                                isReceivingImage = false
                            }
                        } else {
                            // Nuevo mensaje, verificar el tipo
                            val receivedText = String(chunk, Charset.forName("UTF-8"))

                            when {
                                receivedText.startsWith("SIZE:") -> {
                                    // Es un indicador de tamaño para un mensaje grande
                                    val size = receivedText.substringAfter("SIZE:").toIntOrNull()
                                    if (size != null) {
                                        expectedSize = size
                                        accumulatedData = ByteArray(0)
                                    }
                                }
                                receivedText.startsWith("TEXT:") -> {
                                    // Es un mensaje de texto simple
                                    val textContent = receivedText.substringAfter("TEXT:")
                                    addTextMessage(textContent)
                                }
                                receivedText.startsWith("IMG:") -> {
                                    // Es una imagen en base64
                                    isReceivingImage = true
                                    val base64Content = receivedText.substringAfter("IMG:")
                                    processImageData(base64Content.toByteArray(Charset.forName("UTF-8")))
                                }
                                else -> {
                                    // Mensaje sin formato específico, tratarlo como texto
                                    addTextMessage(receivedText)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error al recibir datos: ${e.message}", e)
                    // Pequeña pausa para evitar bucle infinito en caso de error
                    Thread.sleep(500)
                }
            }
        }
    }

    private fun processTextData(text: String) {
        if (text.startsWith("TEXT:")) {
            addTextMessage(text.substringAfter("TEXT:"))
        } else {
            addTextMessage(text)
        }
    }

    private fun processImageData(data: ByteArray) {
        try {
            val text = String(data, Charset.forName("UTF-8"))
            if (text.startsWith("IMG:")) {
                val base64Image = text.substringAfter("IMG:")
                val imageBytes = Base64.decode(base64Image, Base64.DEFAULT)
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                addImageMessage(bitmap)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al procesar datos de imagen: ${e.message}", e)
        }
    }

    private fun addTextMessage(text: String) {
        _receivedMessages.value = _receivedMessages.value + MessageItem.TextMessage(text)
    }

    private fun addImageMessage(bitmap: Bitmap) {
        _receivedMessages.value = _receivedMessages.value + MessageItem.ImageMessage(bitmap)
    }

    companion object {
        private const val TIMEOUT = 1000 // 1 segundo
        private const val BUFFER_SIZE = 16384 // 16KB
        private const val MAX_CHUNK_SIZE = 16384 // 16KB por chunk
    }
}