/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.screenshot

import android.content.Context
import android.content.Intent
import com.android.internal.infra.ServiceConnector
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred

open class ScreenshotKeyguardController @Inject constructor(context: Context) {
    private val proxyConnector: ServiceConnector<IScreenshotProxy> =
        ServiceConnector.Impl(
            context,
            Intent(context, ScreenshotProxyService::class.java),
            Context.BIND_AUTO_CREATE or Context.BIND_WAIVE_PRIORITY or Context.BIND_NOT_VISIBLE,
            context.userId,
            IScreenshotProxy.Stub::asInterface
        )

    suspend fun dismiss() {
        val completion = CompletableDeferred<Unit>()
        val onDoneBinder =
            object : IOnDoneCallback.Stub() {
                override fun onDone(success: Boolean) {
                    completion.complete(Unit)
                }
            }
        proxyConnector.post { it.dismissKeyguard(onDoneBinder) }
        completion.await()
    }
}
