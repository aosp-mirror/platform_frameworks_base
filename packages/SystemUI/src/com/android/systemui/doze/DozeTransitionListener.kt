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

package com.android.systemui.doze

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.policy.CallbackController
import javax.inject.Inject

/** Receives doze transition events, and passes those events to registered callbacks. */
@SysUISingleton
class DozeTransitionListener @Inject constructor() :
    DozeMachine.Part, CallbackController<DozeTransitionCallback> {
    val callbacks = mutableSetOf<DozeTransitionCallback>()
    var oldState = DozeMachine.State.UNINITIALIZED
    var newState = DozeMachine.State.UNINITIALIZED

    override fun transitionTo(oldState: DozeMachine.State, newState: DozeMachine.State) {
        this.oldState = oldState
        this.newState = newState
        callbacks.forEach { it.onDozeTransition(oldState, newState) }
    }

    override fun addCallback(callback: DozeTransitionCallback) {
        callbacks.add(callback)
    }

    override fun removeCallback(callback: DozeTransitionCallback) {
        callbacks.remove(callback)
    }
}

interface DozeTransitionCallback {
    fun onDozeTransition(oldState: DozeMachine.State, newState: DozeMachine.State)
}
