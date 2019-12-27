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
import android.service.controls.IControlsProvider
import android.util.Log

class ControlsProviderServiceWrapper(val service: IControlsProvider) {
    companion object {
        private const val TAG = "ControlsProviderServiceWrapper"
    }

    private fun callThroughService(block: () -> Unit): Boolean {
        try {
            block()
            return true
        } catch (ex: Exception) {
            Log.d(TAG, "Caught exception from ControlsProviderService", ex)
            return false
        }
    }

    fun load(): Boolean {
        return callThroughService {
            service.load()
        }
    }

    fun subscribe(controlIds: List<String>): Boolean {
        return callThroughService {
            service.subscribe(controlIds)
        }
    }

    fun unsubscribe(): Boolean {
        return callThroughService {
            service.unsubscribe()
        }
    }

    fun onAction(controlId: String, action: ControlAction): Boolean {
        return callThroughService {
            service.onAction(controlId, action)
        }
    }
}