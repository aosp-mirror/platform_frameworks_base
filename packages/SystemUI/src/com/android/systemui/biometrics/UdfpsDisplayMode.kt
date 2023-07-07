/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.biometrics

import android.content.Context
import android.os.RemoteException
import android.os.Trace
import com.android.systemui.util.concurrency.Execution

private const val TAG = "UdfpsDisplayMode"

/**
 * UdfpsDisplayMode configures the display for optimal UDFPS operation. For example, sets the
 * display refresh rate that's optimal for UDFPS.
 */
class UdfpsDisplayMode
constructor(
    private val context: Context,
    private val execution: Execution,
    private val authController: AuthController,
    private val logger: UdfpsLogger
) : UdfpsDisplayModeProvider {

    // The request is reset to null after it's processed.
    private var currentRequest: Request? = null

    override fun enable(onEnabled: Runnable?) {
        execution.isMainThread()
        logger.v(TAG, "enable")

        if (currentRequest != null) {
            logger.e(TAG, "enable | already requested")
            return
        }
        if (authController.udfpsRefreshRateCallback == null) {
            logger.e(TAG, "enable | mDisplayManagerCallback is null")
            return
        }

        Trace.beginSection("UdfpsDisplayMode.enable")

        // Track this request in one object.
        val request = Request(context.displayId)
        currentRequest = request

        try {
            // This method is a misnomer. It has nothing to do with HBM, its purpose is to set
            // the appropriate display refresh rate.
            authController.udfpsRefreshRateCallback!!.onRequestEnabled(request.displayId)
            logger.v(TAG, "enable | requested optimal refresh rate for UDFPS")
        } catch (e: RemoteException) {
            logger.e(TAG, "enable", e)
        }

        onEnabled?.run() ?: logger.w(TAG, "enable | onEnabled is null")
        Trace.endSection()
    }

    override fun disable(onDisabled: Runnable?) {
        execution.isMainThread()
        logger.v(TAG, "disable")

        val request = currentRequest
        if (request == null) {
            logger.w(TAG, "disable | already disabled")
            return
        }

        Trace.beginSection("UdfpsDisplayMode.disable")

        try {
            // Allow DisplayManager to unset the UDFPS refresh rate.
            authController.udfpsRefreshRateCallback!!.onRequestDisabled(request.displayId)
            logger.v(TAG, "disable | removed the UDFPS refresh rate request")
        } catch (e: RemoteException) {
            logger.e(TAG, "disable", e)
        }

        currentRequest = null
        onDisabled?.run() ?: logger.w(TAG, "disable | onDisabled is null")
        Trace.endSection()
    }
}

/** Tracks a request to enable the UDFPS mode. */
private data class Request(val displayId: Int)
