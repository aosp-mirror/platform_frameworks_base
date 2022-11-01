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
 * limitations under the License
 */

package com.android.systemui.keyguard.domain.interactor

import android.view.View
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.statusbar.phone.KeyguardBouncer
import com.android.systemui.util.ListenerSet
import javax.inject.Inject

/** Interactor to add and remove callbacks for the bouncer. */
@SysUISingleton
class BouncerCallbackInteractor @Inject constructor() {
    private var resetCallbacks = ListenerSet<KeyguardBouncer.KeyguardResetCallback>()
    private var expansionCallbacks = ArrayList<KeyguardBouncer.BouncerExpansionCallback>()
    /** Add a KeyguardResetCallback. */
    fun addKeyguardResetCallback(callback: KeyguardBouncer.KeyguardResetCallback) {
        resetCallbacks.addIfAbsent(callback)
    }

    /** Remove a KeyguardResetCallback. */
    fun removeKeyguardResetCallback(callback: KeyguardBouncer.KeyguardResetCallback) {
        resetCallbacks.remove(callback)
    }

    /** Adds a callback to listen to bouncer expansion updates. */
    fun addBouncerExpansionCallback(callback: KeyguardBouncer.BouncerExpansionCallback) {
        if (!expansionCallbacks.contains(callback)) {
            expansionCallbacks.add(callback)
        }
    }

    /**
     * Removes a previously added callback. If the callback was never added, this method does
     * nothing.
     */
    fun removeBouncerExpansionCallback(callback: KeyguardBouncer.BouncerExpansionCallback) {
        expansionCallbacks.remove(callback)
    }

    /** Propagate fully shown to bouncer expansion callbacks. */
    fun dispatchFullyShown() {
        for (callback in expansionCallbacks) {
            callback.onFullyShown()
        }
    }

    /** Propagate starting to hide to bouncer expansion callbacks. */
    fun dispatchStartingToHide() {
        for (callback in expansionCallbacks) {
            callback.onStartingToHide()
        }
    }

    /** Propagate starting to show to bouncer expansion callbacks. */
    fun dispatchStartingToShow() {
        for (callback in expansionCallbacks) {
            callback.onStartingToShow()
        }
    }

    /** Propagate fully hidden to bouncer expansion callbacks. */
    fun dispatchFullyHidden() {
        for (callback in expansionCallbacks) {
            callback.onFullyHidden()
        }
    }

    /** Propagate expansion changes to bouncer expansion callbacks. */
    fun dispatchExpansionChanged(expansion: Float) {
        for (callback in expansionCallbacks) {
            callback.onExpansionChanged(expansion)
        }
    }
    /** Propagate visibility changes to bouncer expansion callbacks. */
    fun dispatchVisibilityChanged(visibility: Int) {
        for (callback in expansionCallbacks) {
            callback.onVisibilityChanged(visibility == View.VISIBLE)
        }
    }

    /** Propagate keyguard reset. */
    fun dispatchReset() {
        for (callback in resetCallbacks) {
            callback.onKeyguardReset()
        }
    }
}
