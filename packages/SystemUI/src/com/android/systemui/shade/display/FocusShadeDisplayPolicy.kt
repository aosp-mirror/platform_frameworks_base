/*
 * Copyright (C) 2025 The Android Open Source Project
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

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.display.data.repository.FocusedDisplayRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** Policy that just emits the [FocusedDisplayRepository] display id. */
@SysUISingleton
class FocusShadeDisplayPolicy
@Inject
constructor(private val focusedDisplayRepository: FocusedDisplayRepository) : ShadeDisplayPolicy {
    override val name: String
        get() = "focused_display"

    override val displayId: StateFlow<Int>
        get() = focusedDisplayRepository.focusedDisplayId
}
