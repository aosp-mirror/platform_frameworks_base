/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.service.controls.actions.ControlAction
import android.service.controls.IControlsActionCallback
import android.service.controls.IControlsProvider
import android.service.controls.IControlsSubscriber
import android.service.controls.IControlsSubscription
import android.service.controls.actions.ControlActionWrapper
import android.util.Log

/**
 * Wrapper for the service calls.
 *
 * Calling all [IControlsProvider] methods through here will wrap them in a try/catch block.
 */
class ServiceWrapper(val service: IControlsProvider) {
    companion object {
        private const val TAG = "ServiceWrapper"
    }

    private inline fun callThroughService(block: () -> Unit): Boolean {
        try {
            block()
            return true
        } catch (ex: Exception) {
            Log.e(TAG, "Caught exception from ControlsProviderService", ex)
            return false
        }
    }

    fun load(subscriber: IControlsSubscriber): Boolean {
        return callThroughService {
            service.load(subscriber)
        }
    }

    fun loadSuggested(subscriber: IControlsSubscriber): Boolean {
        return callThroughService {
            service.loadSuggested(subscriber)
        }
    }

    fun subscribe(controlIds: List<String>, subscriber: IControlsSubscriber): Boolean {
        return callThroughService {
            service.subscribe(controlIds, subscriber)
        }
    }

    fun request(subscription: IControlsSubscription, num: Long): Boolean {
        return callThroughService {
            subscription.request(num)
        }
    }

    fun cancel(subscription: IControlsSubscription): Boolean {
        return callThroughService {
            subscription.cancel()
        }
    }

    fun action(
        controlId: String,
        action: ControlAction,
        cb: IControlsActionCallback
    ): Boolean {
        return callThroughService {
            service.action(controlId, ControlActionWrapper(action), cb)
        }
    }
}
