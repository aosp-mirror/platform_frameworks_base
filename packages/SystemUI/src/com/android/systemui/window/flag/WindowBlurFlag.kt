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

package com.android.systemui.window.flag

import com.android.systemui.Flags

/**
 * Flag that controls whether the background surface is blurred or not while on the
 * lockscreen/shade/bouncer. This makes the background of scrim, bouncer and few other opaque
 * surfaces transparent so that we can see the blur effect on the background surface (wallpaper).
 */
object WindowBlurFlag {
    /** Whether the blur is enabled or not */
    @JvmStatic
    val isEnabled
        // Add flags here that require scrims/background surfaces to be transparent.
        get() = Flags.notificationShadeBlur() || Flags.bouncerUiRevamp()
}
