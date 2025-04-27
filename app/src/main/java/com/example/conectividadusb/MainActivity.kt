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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.conectividadusb.ui.theme.ConectividadUSBTheme

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
                UsbManager.ACTION_USB_ACCESSORY_ATTACHED -> {
                    // Manejo para cuando el dispositivo actúa como accesorio
                    refreshDeviceList()
                }
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> {
                    // Manejo para cuando se desconecta un accesorio
                    if (::viewModel.isInitialized) {
                        viewModel.disconnectDevice()
                    }
                    selectedDevice = null
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
            addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
            addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
        }

        // Usar ContextCompat para registrar el receptor con el flag RECEIVER_NOT_EXPORTED
        ContextCompat.registerReceiver(
            this,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Procesar el intent si el dispositivo fue iniciado por un evento USB
        val usbDeviceFromIntent = intent?.let {
            if (it.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    it.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    it.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
            } else {
                null
            }
        }

        setContent {
            ConectividadUSBTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    viewModel = viewModel(
                        factory = UsbViewModelFactory(usbManager, this)
                    )
                    UsbTestScreen(viewModel)

                    // Si la app fue iniciada por un evento USB, conectar al dispositivo
                    usbDeviceFromIntent?.let {
                        LaunchedEffect(it) {
                            requestUsbPermission(it)
                        }
                    }
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