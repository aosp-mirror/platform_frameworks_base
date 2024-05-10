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

package com.android.systemui.bouncer.domain.interactor

import android.view.View
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.EXPANSION_HIDDEN
import com.android.systemui.bouncer.shared.constants.KeyguardBouncerConstants.EXPANSION_VISIBLE
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.util.ListenerSet
import javax.inject.Inject

/** Interactor to add and remove callbacks for the bouncer. */
@SysUISingleton
class PrimaryBouncerCallbackInteractor @Inject constructor() {
    private var resetCallbacks = ListenerSet<KeyguardResetCallback>()
    private var expansionCallbacks = ArrayList<PrimaryBouncerExpansionCallback>()

    /** Add a KeyguardResetCallback. */
    fun addKeyguardResetCallback(callback: KeyguardResetCallback) {
        resetCallbacks.addIfAbsent(callback)
    }

    /** Remove a KeyguardResetCallback. */
    fun removeKeyguardResetCallback(callback: KeyguardResetCallback) {
        resetCallbacks.remove(callback)
    }

    /** Adds a callback to listen to bouncer expansion updates. */
    fun addBouncerExpansionCallback(callback: PrimaryBouncerExpansionCallback) {
        if (!expansionCallbacks.contains(callback)) {
            expansionCallbacks.add(callback)
        }
    }

    /**
     * Removes a previously added callback. If the callback was never added, this method does
     * nothing.
     */
    fun removeBouncerExpansionCallback(callback: PrimaryBouncerExpansionCallback) {
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

    /** Callback updated when the primary bouncer's show and hide states change. */
    interface PrimaryBouncerExpansionCallback {
        /**
         * Invoked when the bouncer expansion reaches [EXPANSION_VISIBLE]. This is NOT called each
         * time the bouncer is shown, but rather only when the fully shown amount has changed based
         * on the panel expansion. The bouncer's visibility can still change when the expansion
         * amount hasn't changed. See [PrimaryBouncerInteractor.isFullyShowing] for the checks for
         * the bouncer showing state.
         */
        fun onFullyShown() {}

        /** Invoked when the bouncer is starting to transition to a hidden state. */
        fun onStartingToHide() {}

        /** Invoked when the bouncer is starting to transition to a visible state. */
        fun onStartingToShow() {}

        /** Invoked when the bouncer expansion reaches [EXPANSION_HIDDEN]. */
        fun onFullyHidden() {}

        /**
         * From 0f [EXPANSION_VISIBLE] when fully visible to 1f [EXPANSION_HIDDEN] when fully hidden
         */
        fun onExpansionChanged(bouncerHideAmount: Float) {}

        /**
         * Invoked when visibility of KeyguardBouncer has changed. Note the bouncer expansion can be
         * [EXPANSION_VISIBLE], but the view's visibility can be [View.INVISIBLE].
         */
        fun onVisibilityChanged(isVisible: Boolean) {}
    }

    interface KeyguardResetCallback {
        fun onKeyguardReset()
    }
}
