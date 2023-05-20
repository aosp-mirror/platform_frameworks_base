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

import kotlinx.coroutines.flow.StateFlow

sealed interface AuthMethodBouncerViewModel {
    /**
     * Whether user input is enabled.
     *
     * If `false`, user input should be completely ignored in the UI as the user is "locked out" of
     * being able to attempt to unlock the device.
     */
    val isInputEnabled: StateFlow<Boolean>
}
