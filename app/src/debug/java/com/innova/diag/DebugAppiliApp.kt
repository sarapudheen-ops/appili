package com.innova.diag

import android.app.Application
import com.innova.diag.platform.demo.DemoByteTransport
import com.innova.diag.platform.demo.DemoMode

/**
 * Debug entry point: installs the scripted adapter so the three screens can be exercised in an
 * emulator, with no Bluetooth hardware and no vehicle.
 *
 * This class exists only in the `debug` source set. A release build has no `DemoByteTransport` and
 * no `DebugAppiliApp`, so the scripted path is not present at all.
 */
class DebugAppiliApp : Application() {

    override fun onCreate() {
        super.onCreate()
        DemoMode.install { DemoByteTransport() }
    }
}
