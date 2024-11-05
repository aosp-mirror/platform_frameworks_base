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

package com.android.systemui.authentication.shared.model

import android.provider.Settings.Global.ONE_HANDED_KEYGUARD_SIDE_LEFT
import android.provider.Settings.Global.ONE_HANDED_KEYGUARD_SIDE_RIGHT
import com.android.systemui.authentication.shared.model.BouncerInputSide.LEFT
import com.android.systemui.authentication.shared.model.BouncerInputSide.RIGHT

/** Denotes which side of the bouncer the input area appears, applicable to large screen devices. */
enum class BouncerInputSide(val settingValue: Int) {
    LEFT(ONE_HANDED_KEYGUARD_SIDE_LEFT),
    RIGHT(ONE_HANDED_KEYGUARD_SIDE_RIGHT),
}

/** Map the setting value to [BouncerInputSide] enum. */
fun Int.toBouncerInputSide(): BouncerInputSide? {
    return when (this) {
        ONE_HANDED_KEYGUARD_SIDE_LEFT -> LEFT
        ONE_HANDED_KEYGUARD_SIDE_RIGHT -> RIGHT
        else -> null
    }
}
