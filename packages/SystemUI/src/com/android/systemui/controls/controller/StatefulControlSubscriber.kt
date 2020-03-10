/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.controller

import android.os.IBinder
import android.service.controls.Control
import android.service.controls.IControlsSubscriber
import android.service.controls.IControlsSubscription
import android.util.Log
import com.android.systemui.util.concurrency.DelayableExecutor

/**
 * A single subscriber, supporting stateful controls for publishers created by
 * {@link ControlsProviderService#createPublisherFor}. In general, this subscription will remain
 * active until the SysUi chooses to cancel it.
 */
class StatefulControlSubscriber(
    private val controller: ControlsController,
    private val provider: ControlsProviderLifecycleManager,
    private val bgExecutor: DelayableExecutor
) : IControlsSubscriber.Stub() {
    private var subscriptionOpen = false
    private var subscription: IControlsSubscription? = null

    companion object {
        private const val TAG = "StatefulControlSubscriber"
    }

    private fun run(token: IBinder, f: () -> Unit) {
        if (provider.token == token) {
            bgExecutor.execute { f() }
        }
    }

    override fun onSubscribe(token: IBinder, subs: IControlsSubscription) {
        run(token) {
            subscriptionOpen = true
            subscription = subs
            provider.startSubscription(subs)
        }
    }

    override fun onNext(token: IBinder, control: Control) {
        run(token) {
            if (!subscriptionOpen) {
                Log.w(TAG, "Refresh outside of window for token:$token")
            } else {
                controller.refreshStatus(provider.componentName, control)
            }
        }
    }
    override fun onError(token: IBinder, error: String) {
        run(token) {
            if (subscriptionOpen) {
                subscriptionOpen = false
                Log.e(TAG, "onError receive from '${provider.componentName}': $error")
            }
        }
    }

    override fun onComplete(token: IBinder) {
        run(token) {
            if (subscriptionOpen) {
                subscriptionOpen = false
                Log.i(TAG, "onComplete receive from '${provider.componentName}'")
            }
        }
    }

    fun cancel() {
        if (!subscriptionOpen) return
        bgExecutor.execute {
            if (subscriptionOpen) {
                subscriptionOpen = false
                subscription?.let {
                    provider.cancelSubscription(it)
                }
                subscription = null
            }
        }
    }
}
