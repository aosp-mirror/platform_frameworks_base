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

package com.android.systemui.bouncer.ui.viewmodel

import com.android.systemui.bouncer.domain.interactor.BouncerInteractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class AuthMethodBouncerViewModel(
    /**
     * Whether user input is enabled.
     *
     * If `false`, user input should be completely ignored in the UI as the user is "locked out" of
     * being able to attempt to unlock the device.
     */
    val isInputEnabled: StateFlow<Boolean>,
    private val interactor: BouncerInteractor,
) {

    private val _animateFailure = MutableStateFlow(false)
    /**
     * Whether a failure animation should be shown. Once consumed, the UI must call
     * [onFailureAnimationShown] to consume this state.
     */
    val animateFailure: StateFlow<Boolean> = _animateFailure.asStateFlow()

    /** Whether the input method editor (for example, the software keyboard) is visible. */
    private var isImeVisible: Boolean = false

    /**
     * Notifies that the failure animation has been shown. This should be called to consume a `true`
     * value in [animateFailure].
     */
    fun onFailureAnimationShown() {
        _animateFailure.value = false
    }

    /**
     * Notifies that the input method editor (for example, the software keyboard) has been shown or
     * hidden.
     */
    fun onImeVisibilityChanged(isVisible: Boolean) {
        if (isImeVisible && !isVisible) {
            // The IME has gone from visible to invisible, dismiss the bouncer.
            interactor.hide(
                loggingReason = "IME hidden",
            )
        }

        isImeVisible = isVisible
    }

    /** Ask the UI to show the failure animation. */
    protected fun showFailureAnimation() {
        _animateFailure.value = true
    }
}
