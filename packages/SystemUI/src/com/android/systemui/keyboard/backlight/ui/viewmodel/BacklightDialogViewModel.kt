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

package com.android.systemui.keyboard.backlight.ui.viewmodel

import android.view.accessibility.AccessibilityManager
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyboard.backlight.domain.interactor.KeyboardBacklightInteractor
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

/**
 * Responsible for dialog visibility and content - emits [BacklightDialogContentViewModel] if dialog
 * should be shown and hidden otherwise
 */
@SysUISingleton
class BacklightDialogViewModel
@Inject
constructor(
    interactor: KeyboardBacklightInteractor,
    private val accessibilityManagerWrapper: AccessibilityManagerWrapper,
) {

    private val timeoutMillis: Long
        get() =
            accessibilityManagerWrapper
                .getRecommendedTimeoutMillis(
                    DEFAULT_DIALOG_TIMEOUT_MILLIS,
                    AccessibilityManager.FLAG_CONTENT_ICONS
                )
                .toLong()

    val dialogContent: Flow<BacklightDialogContentViewModel?> =
        interactor.backlight
            .filterNotNull()
            .map { BacklightDialogContentViewModel(it.level, it.maxLevel) }
            .timeout(timeoutMillis, emitAfterTimeout = null)

    private fun <T> Flow<T>.timeout(timeoutMillis: Long, emitAfterTimeout: T): Flow<T> {
        return flatMapLatest {
            flow {
                emit(it)
                delay(timeoutMillis)
                emit(emitAfterTimeout)
            }
        }
    }

    private companion object {
        const val DEFAULT_DIALOG_TIMEOUT_MILLIS = 3000
    }
}
