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
 * limitations under the License
 */
package com.android.systemui.keyguard.shared.model

/** When canceled, provide different ways to start the next transition. */
enum class TransitionModeOnCanceled {
    /** Proceed from the last value. If canceled at .7, start from .7 and end at 1 */
    LAST_VALUE,
    /** Start over from 0. If canceled at .7, start from 0 and end at 1 */
    RESET,
    /** Reverse the transition. If canceled at .7, start from 1-.7 (0.3) and end at 1 */
    REVERSE
}
