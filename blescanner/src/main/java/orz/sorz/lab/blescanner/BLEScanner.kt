package orz.sorz.lab.blescanner

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat.*
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.callbacks.onCancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.sendBlocking
import org.jetbrains.anko.AnkoLogger
import org.jetbrains.anko.debug
import org.jetbrains.anko.warn


private const val REQUEST_ENABLE_BT = 32001
private const val REQUEST_LOCATION_SERVICE = 32002
private const val PERMISSION_REQUEST_FINE_LOCATION = 32003

class BLEScanner(
    private val context: Context,
    lifecycleOwner: LifecycleOwner
): LifecycleObserver, AnkoLogger {
    private var locationServiceEnabled: CompletableDeferred<Boolean>? = null
    private var locationPermissionGranted: CompletableDeferred<Boolean>? = null
    private var bluetoothEnabled: CompletableDeferred<Boolean>? = null

    private val bluetoothAdapter by lazy(LazyThreadSafetyMode.NONE) {
        getSystemService(context, BluetoothManager::class.java)!!.adapter
    }
    private val BluetoothAdapter.isDisabled: Boolean
        get() = !isEnabled
    private val bluetoothLeScanner by lazy(LazyThreadSafetyMode.NONE) {
        bluetoothAdapter.bluetoothLeScanner
    }
    private val lifecycle = lifecycleOwner.lifecycle.apply {
        addObserver(this@BLEScanner)
    }
    private val seenBleDevices = hashSetOf<BluetoothDevice>()
    private lateinit var queuedDevice: Channel<BluetoothDevice>


    suspend fun initialize(activity: Activity): Boolean {
        locationServiceEnabled = CompletableDeferred()
        locationPermissionGranted = CompletableDeferred()
        bluetoothEnabled = CompletableDeferred()

        fun done(result: Boolean = false): Boolean {
            locationServiceEnabled = null
            locationPermissionGranted = null
            bluetoothEnabled = null
            return result
        }

        // Ensure location service is enabled
        if (!isLocationServiceEnabled()) {
            val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            MaterialDialog(activity).show {
                title(R.string.ble_location_service_required_title)
                message(R.string.ble_location_service_required_content)
                positiveButton {
                    startActivityForResult(activity, intent, REQUEST_LOCATION_SERVICE, null)
                }
                onCancel { locationServiceEnabled?.complete(false) }
            }
            if (locationServiceEnabled?.await() == false) return done()
        }

        // Get ACCESS_FINE_LOCATION permission before LE scanning
        if (checkSelfPermission(activity, ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            // Show dialog if necessary
            if (shouldShowRequestPermissionRationale(activity, ACCESS_FINE_LOCATION)) {
                MaterialDialog(activity).show {
                    title(R.string.ble_location_permission_required_title)
                    message(R.string.ble_location_permission_required_message)
                    positiveButton(android.R.string.ok)
                }.awaitDismiss()
            }
            // Request permission
            requestPermissions(activity, arrayOf(ACCESS_FINE_LOCATION), PERMISSION_REQUEST_FINE_LOCATION)
            if (locationPermissionGranted?.await() == false) return done()
        }

        // Enable BLE
        bluetoothAdapter.takeIf { it.isDisabled }?.apply {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(activity, enableBtIntent, REQUEST_ENABLE_BT, null)
            if (bluetoothEnabled?.await() == false) return done()
        }

        return done(true)
    }

    fun onRequestPermissionsResult(
        requestCode: Int,
        grantResults: IntArray
    ): Boolean {
        if (requestCode == PERMISSION_REQUEST_FINE_LOCATION) {
            locationPermissionGranted?.run {
                complete(grantResults.contains(PackageManager.PERMISSION_GRANTED))
                return true
            }
        }
        return false
    }

    fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
        when (requestCode) {
            REQUEST_ENABLE_BT -> bluetoothEnabled?.run {
                complete(resultCode == Activity.RESULT_OK)
                return true
            }
            REQUEST_LOCATION_SERVICE -> locationServiceEnabled?.run {
                complete(isLocationServiceEnabled())
                return true
            }
        }
        return false
    }

    private fun isLocationServiceEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ContextCompat.getSystemService(context, LocationManager::class.java)
                ?.isLocationEnabled ?: false
        } else {
            @Suppress("DEPRECATION")
            Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.LOCATION_MODE
            ) != Settings.Secure.LOCATION_MODE_OFF
        }
    }

    private fun onNewDeviceFound(device: BluetoothDevice) {
        if (queuedDevice.isClosedForReceive) {
            stopScan()
            return
        }
        if (!seenBleDevices.contains(device)) {
            seenBleDevices.add(device)
            queuedDevice.sendBlocking(device)
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            debug("LE batch scan result $results")
            results.forEach { onNewDeviceFound(it.device) }
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            debug("LE scan result $callbackType $result")
            onNewDeviceFound(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            warn("LE scan failed $errorCode")
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                queuedDevice.close(ScanFailException(errorCode))
        }
    }

    fun startScan(filters: List<ScanFilter>, settings: ScanSettings): Channel<BluetoothDevice> {
        debug { "Start LE scanning" }
        queuedDevice = Channel()
        bluetoothLeScanner.startScan(filters, settings, leScanCallback)
        return queuedDevice
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun stopScan() {
        debug("Stop LE scanning")
        bluetoothLeScanner.stopScan(leScanCallback)
        clearSeenDevices()
        if (::queuedDevice.isInitialized)
            queuedDevice.close()
    }

    fun clearSeenDevices() {
        seenBleDevices.clear()
    }
}