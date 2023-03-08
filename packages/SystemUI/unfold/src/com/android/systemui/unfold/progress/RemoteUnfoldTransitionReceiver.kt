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

package com.android.systemui.unfold.progress

import com.android.systemui.unfold.UnfoldTransitionProgressProvider
import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener
import com.android.systemui.unfold.dagger.UnfoldMain
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Receives unfold events from remote senders (System UI).
 *
 * A binder to an instance to this class (created with [RemoteUnfoldTransitionReceiver.asBinder])
 * should be sent to the remote process providing events.
 */
class RemoteUnfoldTransitionReceiver
@Inject
constructor(@UnfoldMain private val executor: Executor) :
    UnfoldTransitionProgressProvider, IUnfoldTransitionListener.Stub() {

    private val listeners: MutableSet<TransitionProgressListener> = mutableSetOf()

    override fun onTransitionStarted() {
        executor.execute { listeners.forEach { it.onTransitionStarted() } }
    }

    override fun onTransitionProgress(progress: Float) {
        executor.execute { listeners.forEach { it.onTransitionProgress(progress) } }
    }

    override fun onTransitionFinished() {
        executor.execute { listeners.forEach { it.onTransitionFinished() } }
    }

    override fun addCallback(listener: TransitionProgressListener) {
        listeners += listener
    }

    override fun removeCallback(listener: TransitionProgressListener) {
        listeners -= listener
    }

    override fun destroy() {
        listeners.clear()
    }
}
