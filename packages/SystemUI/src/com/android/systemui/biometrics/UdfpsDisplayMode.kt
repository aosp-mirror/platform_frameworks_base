package com.android.systemui.biometrics

import android.content.Context
import android.os.RemoteException
import android.os.Trace
import android.util.Log
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.concurrency.Execution
import javax.inject.Inject

private const val TAG = "UdfpsDisplayMode"

/**
 * UdfpsDisplayMode that encapsulates pixel-specific code, such as enabling the high-brightness mode
 * (HBM) in a display-specific way and freezing the display's refresh rate.
 */
@SysUISingleton
class UdfpsDisplayMode
@Inject
constructor(
    private val context: Context,
    private val execution: Execution,
    private val authController: AuthController
) : UdfpsDisplayModeProvider {

    // The request is reset to null after it's processed.
    private var currentRequest: Request? = null

    override fun enable(onEnabled: Runnable?) {
        execution.isMainThread()
        Log.v(TAG, "enable")

        if (currentRequest != null) {
            Log.e(TAG, "enable | already requested")
            return
        }
        if (authController.udfpsHbmListener == null) {
            Log.e(TAG, "enable | mDisplayManagerCallback is null")
            return
        }

        Trace.beginSection("UdfpsDisplayMode.enable")

        // Track this request in one object.
        val request = Request(context.displayId)
        currentRequest = request

        try {
            // This method is a misnomer. It has nothing to do with HBM, its purpose is to set
            // the appropriate display refresh rate.
            authController.udfpsHbmListener!!.onHbmEnabled(request.displayId)
            Log.v(TAG, "enable | requested optimal refresh rate for UDFPS")
        } catch (e: RemoteException) {
            Log.e(TAG, "enable", e)
        }

        onEnabled?.run() ?: Log.w(TAG, "enable | onEnabled is null")
        Trace.endSection()
    }

    override fun disable(onDisabled: Runnable?) {
        execution.isMainThread()
        Log.v(TAG, "disable")

        val request = currentRequest
        if (request == null) {
            Log.w(TAG, "disable | already disabled")
            return
        }

        Trace.beginSection("UdfpsDisplayMode.disable")

        try {
            // Allow DisplayManager to unset the UDFPS refresh rate.
            authController.udfpsHbmListener!!.onHbmDisabled(request.displayId)
            Log.v(TAG, "disable | removed the UDFPS refresh rate request")
        } catch (e: RemoteException) {
            Log.e(TAG, "disable", e)
        }

        currentRequest = null
        onDisabled?.run() ?: Log.w(TAG, "disable | onDisabled is null")
        Trace.endSection()
    }
}

/** Tracks a request to enable the UDFPS mode. */
private data class Request(val displayId: Int)
