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

package com.android.systemui.shade.display

import android.view.Display
import android.view.Display.DEFAULT_DISPLAY
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.display.data.repository.DisplayRepository
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Returns an external display if one exists, otherwise the default display.
 *
 * If there are multiple external displays, the one with minimum display ID is returned.
 */
@SysUISingleton
class AnyExternalShadeDisplayPolicy
@Inject
constructor(displayRepository: DisplayRepository, @Background bgScope: CoroutineScope) :
    ShadeDisplayPolicy {
    override val name: String
        get() = "any_external_display"

    override val displayId: StateFlow<Int> =
        displayRepository.displays
            .map { displays ->
                displays
                    .filter { it.displayId != DEFAULT_DISPLAY && it.type in ALLOWED_DISPLAY_TYPES }
                    .minOfOrNull { it.displayId } ?: DEFAULT_DISPLAY
            }
            .stateIn(bgScope, SharingStarted.WhileSubscribed(), DEFAULT_DISPLAY)

    private companion object {
        val ALLOWED_DISPLAY_TYPES =
            setOf(Display.TYPE_EXTERNAL, Display.TYPE_OVERLAY, Display.TYPE_WIFI)
    }
}
