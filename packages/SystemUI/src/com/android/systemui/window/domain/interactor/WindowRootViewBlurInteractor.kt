/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.window.domain.interactor

import android.util.Log
import com.android.systemui.Flags
import com.android.systemui.communal.domain.interactor.CommunalInteractor
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.window.data.repository.WindowRootViewBlurRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Interactor that provides the blur state for the window root view
 * [com.android.systemui.scene.ui.view.WindowRootView]
 */
@SysUISingleton
class WindowRootViewBlurInteractor
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    private val keyguardInteractor: KeyguardInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val communalInteractor: CommunalInteractor,
    private val repository: WindowRootViewBlurRepository,
) {
    private var isBouncerTransitionInProgress: StateFlow<Boolean> =
        if (Flags.bouncerUiRevamp()) {
            keyguardTransitionInteractor
                .transitionValue(PRIMARY_BOUNCER)
                .map { it > 0f }
                .distinctUntilChanged()
                .stateIn(applicationScope, SharingStarted.Eagerly, false)
        } else {
            MutableStateFlow(false)
        }

    /**
     * Invoked by the view after blur of [appliedBlurRadius] was successfully applied on the window
     * root view.
     */
    suspend fun onBlurApplied(appliedBlurRadius: Int) {
        repository.onBlurApplied.emit(appliedBlurRadius)
    }

    /** Radius of blur to be applied on the window root view. */
    val blurRadius: StateFlow<Int> = repository.blurRadius.asStateFlow()

    /** Whether the blur applied is opaque or transparent. */
    val isBlurOpaque: StateFlow<Boolean> = repository.isBlurOpaque.asStateFlow()

    /**
     * Emits the applied blur radius whenever blur is successfully applied to the window root view.
     */
    val onBlurAppliedEvent: Flow<Int> = repository.onBlurApplied

    /**
     * Request to apply blur while on bouncer, this takes precedence over other blurs (from shade).
     */
    fun requestBlurForBouncer(blurRadius: Int) {
        repository.isBlurOpaque.value = false
        repository.blurRadius.value = blurRadius
    }

    /**
     * Request to apply blur while on glanceable hub, this takes precedence over other blurs (from
     * shade) except for bouncer.
     */
    fun requestBlurForGlanceableHub(blurRadius: Int): Boolean {
        if (keyguardInteractor.primaryBouncerShowing.value) {
            return false
        }

        Log.d(TAG, "requestBlurForGlanceableHub for $blurRadius")

        repository.isBlurOpaque.value = false
        repository.blurRadius.value = blurRadius

        return true
    }

    /**
     * Method that requests blur to be applied on window root view. It is applied only when other
     * blurs are not applied.
     *
     * This method is present to temporarily support the blur for notification shade, ideally shade
     * should expose state that is used by this interactor to determine the blur that has to be
     * applied.
     *
     * @return whether the request for blur was processed or not.
     */
    fun requestBlurForShade(blurRadius: Int, opaque: Boolean): Boolean {
        // We need to check either of these because they are two different sources of truth,
        // primaryBouncerShowing changes early to true/false, but blur is
        // coordinated by transition value.
        if (keyguardInteractor.primaryBouncerShowing.value || isBouncerTransitionInProgress.value) {
            return false
        }
        if (communalInteractor.isCommunalBlurring.value) {
            return false
        }
        Log.d(TAG, "requestingBlurForShade for $blurRadius $opaque")
        repository.blurRadius.value = blurRadius
        repository.isBlurOpaque.value = opaque
        return true
    }

    companion object {
        const val TAG = "WindowRootViewBlurInteractor"
    }
}
