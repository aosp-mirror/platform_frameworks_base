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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import com.android.systemui.common.shared.model.SharedNotificationContainerPosition
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.KeyguardRootViewVisibilityState
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

@OptIn(ExperimentalCoroutinesApi::class)
class KeyguardRootViewModel
@Inject
constructor(
    private val keyguardInteractor: KeyguardInteractor,
) {

    data class PreviewMode(val isInPreviewMode: Boolean = false)

    /**
     * Whether this view-model instance is powering the preview experience that renders exclusively
     * in the wallpaper picker application. This should _always_ be `false` for the real lock screen
     * experience.
     */
    private val previewMode = MutableStateFlow(PreviewMode())

    /** Represents the current state of the KeyguardRootView visibility */
    val keyguardRootViewVisibilityState: Flow<KeyguardRootViewVisibilityState> =
        keyguardInteractor.keyguardRootViewVisibilityState

    /** An observable for the alpha level for the entire keyguard root view. */
    val alpha: Flow<Float> =
        previewMode.flatMapLatest {
            if (it.isInPreviewMode) {
                flowOf(1f)
            } else {
                keyguardInteractor.keyguardAlpha.distinctUntilChanged()
            }
        }

    val translationY: Flow<Float> = keyguardInteractor.keyguardTranslationY

    /**
     * Puts this view-model in "preview mode", which means it's being used for UI that is rendering
     * the lock screen preview in wallpaper picker / settings and not the real experience on the
     * lock screen.
     */
    fun enablePreviewMode() {
        val newPreviewMode = PreviewMode(true)
        previewMode.value = newPreviewMode
    }

    fun onSharedNotificationContainerPositionChanged(top: Float, bottom: Float) {
        keyguardInteractor.sharedNotificationContainerPosition.value =
            SharedNotificationContainerPosition(top, bottom)
    }
}
