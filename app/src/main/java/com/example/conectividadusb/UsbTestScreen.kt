package com.example.conectividadusb

import android.hardware.usb.UsbDevice
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun UsbTestScreen(viewModel: UsbViewModel) {
    val context = LocalContext.current
    val statusText by remember { viewModel.statusText }
    val deviceList = remember { viewModel.deviceList }
    val connectedDevice by remember { viewModel.connectedDevice }
    val messageToSend by remember { viewModel.messageToSend }
    val receivedMessages = remember { viewModel.receivedMessages }
    val selectedImage by remember { viewModel.selectedImage }

    // Lanzador para seleccionar imágenes de la galería
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.handleImageResult(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Conectividad USB",
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
                        .height(300.dp) // Más espacio para mostrar imágenes
                        .padding(vertical = 8.dp)
                ) {
                    items(receivedMessages) { message ->
                        when (message) {
                            is UsbSerialConnection.MessageItem.TextMessage -> {
                                MessageBubble(text = message.text)
                            }
                            is UsbSerialConnection.MessageItem.ImageMessage -> {
                                ImageMessageBubble(bitmap = message.bitmap)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Área para mostrar la imagen seleccionada
                selectedImage?.let { bitmap ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(vertical = 8.dp),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "Imagen seleccionada",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )

                            // Botón para enviar la imagen
                            Button(
                                onClick = { viewModel.sendImage() },
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Enviar imagen"
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Enviar imagen")
                            }

                            // Botón para cancelar la selección
                            Button(
                                onClick = { viewModel.setSelectedImage(null) },
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Cancelar")
                            }
                        }
                    }
                }

                // Área de envío de mensajes
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Botón para seleccionar imagen
                    IconButton(
                        onClick = { imagePicker.launch("image/*") }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Seleccionar imagen",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }

                    // Campo para escribir mensaje
                    OutlinedTextField(
                        value = messageToSend,
                        onValueChange = { viewModel.updateMessageToSend(it) },
                        placeholder = { Text("Escribe tu mensaje") },
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )

                    // Botón para enviar mensaje
                    Button(
                        onClick = { viewModel.sendMessage() },
                        enabled = messageToSend.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Enviar mensaje"
                        )
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
fun MessageBubble(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
fun ImageMessageBubble(bitmap: android.graphics.Bitmap) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(8.dp)
        ) {
            Text("Imagen recibida:")
            Spacer(modifier = Modifier.height(4.dp))
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Imagen recibida",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit
            )
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