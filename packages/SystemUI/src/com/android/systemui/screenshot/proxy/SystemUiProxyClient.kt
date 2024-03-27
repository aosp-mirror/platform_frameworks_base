/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.screenshot.proxy

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import com.android.internal.infra.ServiceConnector
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.screenshot.IOnDoneCallback
import com.android.systemui.screenshot.IScreenshotProxy
import com.android.systemui.screenshot.ScreenshotProxyService
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CompletableDeferred

private const val TAG = "SystemUiProxy"

/** An implementation of [SystemUiProxy] using [ScreenshotProxyService]. */
class SystemUiProxyClient @Inject constructor(@Application context: Context) : SystemUiProxy {
    @SuppressLint("ImplicitSamInstance")
    private val proxyConnector: ServiceConnector<IScreenshotProxy> =
        ServiceConnector.Impl(
            context,
            Intent(context, ScreenshotProxyService::class.java),
            Context.BIND_AUTO_CREATE or Context.BIND_WAIVE_PRIORITY or Context.BIND_NOT_VISIBLE,
            context.userId,
            IScreenshotProxy.Stub::asInterface
        )

    override suspend fun isNotificationShadeExpanded(): Boolean = suspendCoroutine { k ->
        proxyConnector
            .postForResult { it.isNotificationShadeExpanded }
            .whenComplete { expanded, error ->
                error?.also { Log.wtf(TAG, "isNotificationShadeExpanded", it) }
                k.resume(expanded ?: false)
            }
    }

    override suspend fun dismissKeyguard() {
        val completion = CompletableDeferred<Unit>()
        val onDoneBinder =
            object : IOnDoneCallback.Stub() {
                override fun onDone(success: Boolean) {
                    completion.complete(Unit)
                }
            }
        if (proxyConnector.run { it.dismissKeyguard(onDoneBinder) }) {
            completion.await()
        } else {
            Log.wtf(TAG, "Keyguard dismissal request failed")
        }
    }
}
