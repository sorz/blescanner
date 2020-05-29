package org.sorz.lab.blescanner

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat.*
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.checkSelfPermission
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.afollestad.materialdialogs.MaterialDialog
import com.afollestad.materialdialogs.callbacks.onCancel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.sendBlocking
import mu.KotlinLogging


private const val REQUEST_ENABLE_BT = 32001
private const val REQUEST_LOCATION_SERVICE = 32002
private const val PERMISSION_REQUEST_FINE_LOCATION = 32003

/**
 * Initialize Bluetooth Low Energy and scan BLE devices.
 *
 * Usage:
 *   1. Add [onRequestPermissionsResult] to your [Activity.onRequestPermissionsResult] and
 *   and [onActivityResult] to the [Activity.onActivityResult];
 *   2. Call [initialize] to request permissions and enable Bluetooth. If you already check them
 *   before, step 1 & 2 could be skipped.
 *   3. Call [startScan] to get [Channel] of [BluetoothDevice]. After you find the device, call
 *   [stopScan].
 *
 *   Do not fire a second [startScan] before the last scanning stopped.
 *
 *   Scanning will stop whatever when [Lifecycle] went [Lifecycle.State.DESTROYED].
 */
class BLEScanner(
    private val context: Context,
    lifecycleOwner: LifecycleOwner
): LifecycleObserver {
    private val logger = KotlinLogging.logger {}
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


    /**
     * Request necessary permission and switch on Bluetooth.
     *
     * 1. Ensure location service is enabled. If not, ask user for it and open the system preference.
     * 2. Ensure [ACCESS_FINE_LOCATION] is granted, request it if necessary.
     * 3. Ensure Bluetooth is switched on, request for open if necessary.
     *
     * @param activity If fragment is null, [onRequestPermissionsResult] and [onActivityResult] must
     *   be called on [activity].[Activity.onRequestPermissionsResult] and
     *   [activity].[Activity.onActivityResult], otherwise the method may never return.
     * @param fragment If [onRequestPermissionsResult] and [onActivityResult] is called on fragment
     *   rather than the activity, set the fragment here.
     *
     * @return false if user reject any permission.
     */
    suspend fun initialize(activity: Activity, fragment: Fragment? = null): Boolean {
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
                    if (fragment == null)
                        startActivityForResult(activity, intent, REQUEST_LOCATION_SERVICE, null)
                    else
                        fragment.startActivityForResult(intent, REQUEST_LOCATION_SERVICE)
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
            if (fragment == null)
                requestPermissions(activity, arrayOf(ACCESS_FINE_LOCATION), PERMISSION_REQUEST_FINE_LOCATION)
            else
                fragment.requestPermissions(arrayOf(ACCESS_FINE_LOCATION), PERMISSION_REQUEST_FINE_LOCATION)
            if (locationPermissionGranted?.await() == false) return done()
        }

        // Enable BLE
        bluetoothAdapter.takeIf { it.isDisabled }?.apply {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            if (fragment == null)
                startActivityForResult(activity, enableBtIntent, REQUEST_ENABLE_BT, null)
            else
                fragment.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            if (bluetoothEnabled?.await() == false) return done()
        }

        return done(true)
    }

    /**
     * Call from [Activity.onRequestPermissionsResult].
     * @return true if event is processed.
     */
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

    /**
     * Call from [Activity.onActivityResult].
     * @return true if event is processed.
     */
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
            logger.debug { "LE batch scan result $results" }
            results.forEach { onNewDeviceFound(it.device) }
        }

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            logger.debug { "LE scan result $callbackType $result" }
            onNewDeviceFound(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            logger.warn("LE scan failed $errorCode")
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                queuedDevice.close(ScanFailException(errorCode))
        }
    }

    /**
     * Start scanning. Call [stopScan] to stop.
     *
     * @param filters see [BluetoothLeScanner.startScan]
     * @param settings see [BluetoothLeScanner.startScan]
     * @return [Channel] of result. The same [BluetoothDevice] will never send twice until
     * [clearSeenDevices] is called.
     */
    fun startScan(filters: List<ScanFilter>, settings: ScanSettings): Channel<BluetoothDevice> {
        logger.debug("Start LE scanning")
        queuedDevice = Channel()
        bluetoothLeScanner.startScan(filters, settings, leScanCallback)
        return queuedDevice
    }

    /**
     * Stop scanning. [Channel] returned from [startScan] will be closed.
     * Automatically be called when [Lifecycle] went [Lifecycle.Event.ON_DESTROY].
     */
    @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    fun stopScan() {
        logger.debug("Stop LE scanning")
        bluetoothLeScanner.stopScan(leScanCallback)
        clearSeenDevices()
        if (::queuedDevice.isInitialized)
            queuedDevice.close()
    }

    fun clearSeenDevices() {
        seenBleDevices.clear()
    }
}
