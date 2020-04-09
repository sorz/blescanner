# BLE Scanner

Android library that facilitate Bluetooth Low Energy devices scanning w/ Kotlin coroutines APIs.

This library helps you:
- Define `BLUETOOTH`, `BLUETOOTH_ADMIN`, and `ACCESS_FINE_LOCATION` permission on the manifest;
- Check & request to open location service;
- Check & request `ACCESS_FINE_LOCATION` permission;
- Check & request to enable Bluetooth;
- Scanning BLE devices.

All are done with Kotlin's coroutines (suspend fun), and lifecycle aware.
No more messy callbacks.


See [sorz/gattkt](https://github.com/sorz/gattkt) for the library that play
GATT server with Kotlin coroutines.

## Install

- Add [JitPack](https://jitpack.io/) to your build file.
- Add `implementation 'com.github.sorz:blescanner:{VERSION}`

## Usage
- Create `BLEScanner` instance with `Context` and `LifecycleOwner`
- Add `BLEScanner.onRequestPermissionsResult()` call in your `Activity.onRequestPermissionsResult()`
  or `Fragment.onRequestPermissionsResult()`
- Add `BLEScanner.onActivityResult()` call in your `Activity.onActivityResult()`
  or `Fragment.onActivityResult()`
- Call `BLEScanner.initialize()` before `BLEScanner.startScan()`

`BLEScanner.initialize()` will return `false` if user reject any necessary permission. 

## Example

```kotlin
class MainActivity : AppCompatActivity() {
    private val bleScanner = BLEScanner(this, this)

    override fun onCreate(savedInstanceState: Bundle?) {
        // ...
        lifecycleScope.launchWhenCreated {
            // If you are in a fragment, pass both the activity and
            // the fragment to initialize().
            if (bleScanner.initialize(this@MainActivity)) {
                val filters = listOf(/* ... */)
                val settings = ScanSettings.Builder().build()
                for device in bleScanner.startScan(filters, settings) {
                    // ... device found
                    bleScanner.stopScan()
                }
            } else {
                // ... permission denied
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (!bleScanner.onRequestPermissionsResult(requestCode, grantResults))
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!bleScanner.onActivityResult(requestCode, resultCode))
            super.onActivityResult(requestCode, resultCode, data)
    }
}
```
