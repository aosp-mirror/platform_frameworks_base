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

package com.android.systemui.unfold.updates

import android.content.Context
import android.os.RemoteException
import android.view.IRotationWatcher
import android.view.IWindowManager
import android.view.Surface.Rotation
import com.android.systemui.unfold.dagger.UnfoldMain
import com.android.systemui.unfold.util.CallbackController
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Allows to subscribe to rotation changes.
 *
 * This is needed as rotation updates from [IWindowManager] are received in a binder thread, while
 * most of the times we want them in the main one. Updates are provided for the display associated
 * to [context].
 */
class RotationChangeProvider
@Inject
constructor(
    private val windowManagerInterface: IWindowManager,
    private val context: Context,
    @UnfoldMain private val mainExecutor: Executor,
) : CallbackController<RotationChangeProvider.RotationListener> {

    private val listeners = mutableListOf<RotationListener>()

    private val rotationWatcher = RotationWatcher()

    override fun addCallback(listener: RotationListener) {
        mainExecutor.execute {
            if (listeners.isEmpty()) {
                subscribeToRotation()
            }
            listeners += listener
        }
    }

    override fun removeCallback(listener: RotationListener) {
        mainExecutor.execute {
            listeners -= listener
            if (listeners.isEmpty()) {
                unsubscribeToRotation()
            }
        }
    }

    private fun subscribeToRotation() {
        try {
            windowManagerInterface.watchRotation(rotationWatcher, context.displayId)
        } catch (e: RemoteException) {
            throw e.rethrowFromSystemServer()
        }
    }

    private fun unsubscribeToRotation() {
        try {
            windowManagerInterface.removeRotationWatcher(rotationWatcher)
        } catch (e: RemoteException) {
            throw e.rethrowFromSystemServer()
        }
    }

    /** Gets notified of rotation changes. */
    fun interface RotationListener {
        /** Called once rotation changes. */
        fun onRotationChanged(@Rotation newRotation: Int)
    }

    private inner class RotationWatcher : IRotationWatcher.Stub() {
        override fun onRotationChanged(rotation: Int) {
            mainExecutor.execute { listeners.forEach { it.onRotationChanged(rotation) } }
        }
    }
}
