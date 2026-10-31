package com.innova.diag.platform.demo

import com.innova.diag.core.transport.ByteTransport

/**
 * Hook through which a **debug-only** scripted adapter is made available.
 *
 * The implementation lives in the `debug` source set, so a release APK does not contain a fake
 * transport at all — it is not "disabled at runtime", it is absent. `main` only knows this holder,
 * and the view model still checks `BuildConfig.DEBUG` before asking for one, so a release build
 * cannot obtain a scripted adapter even if something installed one.
 */
object DemoMode {

    private var factory: (() -> ByteTransport)? = null

    /** Called by the debug application class at startup. */
    @Synchronized
    fun install(create: () -> ByteTransport) {
        factory = create
    }

    /** True when this build has a scripted adapter installed. */
    val isAvailable: Boolean
        get() = synchronized(this) { factory != null }

    /** Creates a fresh scripted transport, or `null` when none is installed. */
    fun create(): ByteTransport? = synchronized(this) { factory?.invoke() }
}
