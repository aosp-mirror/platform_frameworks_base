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
 * limitations under the License.
 */
package com.android.systemui.screenrecord

import androidx.annotation.IntDef
import androidx.annotation.StringRes
import kotlin.annotation.Retention

@Retention(AnnotationRetention.SOURCE)
@IntDef(ENTIRE_SCREEN, SINGLE_APP)
annotation class ScreenShareMode

const val ENTIRE_SCREEN = 0
const val SINGLE_APP = 1

class ScreenShareOption(
    @ScreenShareMode val mode: Int,
    @StringRes val spinnerText: Int,
    @StringRes val warningText: Int
)
