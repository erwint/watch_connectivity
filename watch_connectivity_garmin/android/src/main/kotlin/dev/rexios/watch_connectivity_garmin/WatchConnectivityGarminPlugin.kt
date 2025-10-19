package dev.rexios.watch_connectivity_garmin

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQApplicationInfoListener
import com.garmin.android.connectiq.ConnectIQ.IQConnectType
import com.garmin.android.connectiq.ConnectIQ.IQOpenApplicationListener
import com.garmin.android.connectiq.ConnectIQ.IQOpenApplicationStatus
import com.garmin.android.connectiq.ConnectIQ.IQSdkErrorStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread


/** WatchConnectivityGarminPlugin */
class WatchConnectivityGarminPlugin : FlutterPlugin, MethodCallHandler {
    /// The MethodChannel that will the communication between Flutter and native Android
    ///
    /// This local reference serves to register the plugin with the Flutter Engine and unregister it
    /// when the Flutter Engine is detached from the Activity
    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var connectIQ: ConnectIQ
    private lateinit var iqApp: IQApp

    companion object {
        private const val TAG = "WatchConnectivityGarmin"
    }

    override fun onAttachedToEngine(flutterPluginBinding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(flutterPluginBinding.binaryMessenger, "watch_connectivity_garmin")
        channel.setMethodCallHandler(this)

        context = flutterPluginBinding.applicationContext

        packageManager = context.packageManager
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel.setMethodCallHandler(null)
        connectIQ.shutdown(context)
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            // Getters
            "isSupported" -> isSupported(result)
            "isPaired" -> isPaired(result)
            "isReachable" -> isReachable(result)

            // Methods
            "initialize" -> initialize(call, result)
            "sendMessage" -> sendMessage(call, result)
            "openApplication" -> openApplication(result)

            // Not implemented
            else -> result.notImplemented()
        }
    }

    private fun initialize(call: MethodCall, result: Result) {
        val applicationId = call.argument<String>("applicationId")!!
        iqApp = IQApp(applicationId)

        val connectTypeString = call.argument<String>("connectType")!!
        val connectType = IQConnectType.valueOf(connectTypeString)
        connectIQ = ConnectIQ.getInstance(context, connectType)
        connectIQ.initialize(
            context,
            call.argument<Boolean>("autoUI")!!,
            object : ConnectIQListener {
                override fun onSdkReady() {
                    listenForMessages()
                    result.success(null)
                }

                override fun onInitializeError(status: IQSdkErrorStatus?) {
                    result.error(status.toString(), "Unable to initialize Garmin SDK", null)
                }

                override fun onSdkShutDown() {}
            },
        )

        if (connectType == IQConnectType.TETHERED) {
            val adbPort = call.argument<Int>("adbPort")!!
            connectIQ.adbPort = adbPort
        }
    }

    private fun listenForMessages() {
        val devices = connectIQ.knownDevices ?: listOf()

        for (device in devices) {
            connectIQ.registerForDeviceEvents(device) { _, status ->
                processDeviceStatus(device, status)
            }
        }

        for (device in connectIQ.connectedDevices ?: listOf()) {
            processDeviceStatus(device, IQDevice.IQDeviceStatus.CONNECTED)
        }
    }

    private fun processDeviceStatus(device: IQDevice, status: IQDevice.IQDeviceStatus) {
        if (status == IQDevice.IQDeviceStatus.CONNECTED) {
            connectIQ.registerForAppEvents(device, iqApp) { _, _, data, status ->
                if (status != ConnectIQ.IQMessageStatus.SUCCESS) return@registerForAppEvents
                for (datum in data) {
                    channel.invokeMethod("didReceiveMessage", datum)
                }
            }
        } else {
            connectIQ.unregisterForApplicationEvents(device, iqApp)
        }
    }

    private fun getApplicationForDevice(device: IQDevice): IQApp? {
        var installedApp: IQApp? = null
        val latch = CountDownLatch(1)
        connectIQ.getApplicationInfo(
            iqApp.applicationId,
            device,
            object : IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp?) {
                    installedApp = app
                    latch.countDown()
                }

                override fun onApplicationNotInstalled(p0: String?) {
                    latch.countDown()
                }
            }
        )
        latch.await()
        return installedApp
    }

    private fun isSupported(result: Result) {
        val apps = packageManager.getInstalledApplications(0)
        val wearableAppInstalled =
            apps.any { it.packageName == "com.garmin.android.apps.connectmobile" }
        result.success(wearableAppInstalled)
    }

    private fun isPaired(result: Result) {
        result.success(connectIQ.knownDevices?.isNotEmpty() ?: false)
    }

    private fun isReachable(result: Result) {
        thread {
            for (device in connectIQ.connectedDevices ?: listOf()) {
                val installedApp = getApplicationForDevice(device)
                if (installedApp != null) {
                    result.success(true)
                    return@thread
                }
            }
            result.success(false)
        }
    }


    private fun sendMessage(call: MethodCall, result: Result) {
        val devices = connectIQ.connectedDevices ?: listOf()

        thread {
            val latch = CountDownLatch(devices.count())
            val errors = mutableListOf<ConnectIQ.IQMessageStatus>()
            for (device in devices) {
                connectIQ.sendMessage(device, iqApp, call.arguments) { _, _, status ->
                    if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                        errors.add(status)
                    }

                    latch.countDown()
                }
            }

            latch.await()
            if (errors.isNotEmpty()) {
                result.error(errors.toString(), "Unable to send message", null)
            } else {
                result.success(null)
            }
        }
    }

    private fun openApplication(result: Result) {
        try {
            // Verify SDK is actually ready by checking if we can get devices
            val devices: List<IQDevice>?
            try {
                devices = connectIQ.knownDevices
            } catch (e: Exception) {
                Log.w(TAG, "SDK not ready, can't get devices yet: ${e.message}")
                result.error("SDK_NOT_READY", "ConnectIQ SDK is still initializing", null)
                return
            }

            if (devices == null || devices.isEmpty()) {
                Log.w(TAG, "No known devices")
                result.error("NO_DEVICE", "No connected device found", null)
                return
            }

            // Log all available devices
            Log.d(TAG, "Found ${devices.size} known device(s):")
            devices.forEachIndexed { index, d ->
                Log.d(TAG, "  [$index] ${d.friendlyName} (${d.deviceIdentifier}) - status: ${d.status}")
            }

            // Find best device with priority:
            // 1. CONNECTED status (best)
            // 2. NOT_CONNECTED status
            // 3. Fall back to first device
            val device = devices.firstOrNull {
                it.status == IQDevice.IQDeviceStatus.CONNECTED
            } ?: devices.firstOrNull {
                it.status == IQDevice.IQDeviceStatus.NOT_CONNECTED
            } ?: devices[0]

            Log.d(TAG, "Selected device: ${device.friendlyName} (${device.deviceIdentifier})")
            Log.d(TAG, "Device status: ${device.status}")
            Log.d(TAG, "Attempting to open app ${iqApp.applicationId} on this device")

            // Call openApplication
            try {
                connectIQ.openApplication(
                    device,
                    iqApp,
                    object : IQOpenApplicationListener {
                        override fun onOpenApplicationResponse(
                            device: IQDevice,
                            app: IQApp,
                            status: IQOpenApplicationStatus
                        ) {
                            Log.d(TAG, "openApplication callback received! status: $status")

                            when (status) {
                                IQOpenApplicationStatus.APP_IS_ALREADY_RUNNING -> {
                                    Log.d(TAG, "App is already running")
                                    result.success(true)
                                }
                                IQOpenApplicationStatus.PROMPT_SHOWN_ON_DEVICE -> {
                                    Log.d(TAG, "Prompt shown on device")
                                    result.success(true)
                                }
                                IQOpenApplicationStatus.PROMPT_NOT_SHOWN_ON_DEVICE -> {
                                    Log.w(TAG, "Prompt not shown on device")
                                    result.success(false)
                                }
                                IQOpenApplicationStatus.APP_IS_NOT_INSTALLED -> {
                                    Log.w(TAG, "App not installed on device (expected in simulator)")
                                    result.success(false)
                                }
                                else -> {
                                    Log.w(TAG, "Unknown openApplication status: $status")
                                    result.success(false)
                                }
                            }
                        }
                    }
                )
                Log.d(TAG, "openApplication() call completed (waiting for callback...)")
            } catch (e: Exception) {
                Log.e(TAG, "Exception calling openApplication: ${e.message}", e)
                result.error("OPEN_APP_EXCEPTION", e.message, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening watch app", e)
            result.error("OPEN_APP_ERROR", e.message, null)
        }
    }
}
