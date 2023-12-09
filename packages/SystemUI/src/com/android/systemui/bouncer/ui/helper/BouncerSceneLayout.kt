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

package com.android.systemui.bouncer.ui.helper

import androidx.annotation.VisibleForTesting

/** Enumerates all known adaptive layout configurations. */
enum class BouncerSceneLayout {
    /** The default UI with the bouncer laid out normally. */
    STANDARD_BOUNCER,
    /** The bouncer is displayed vertically stacked with the user switcher. */
    BELOW_USER_SWITCHER,
    /** The bouncer is displayed side-by-side with the user switcher or an empty space. */
    BESIDE_USER_SWITCHER,
    /** The bouncer is split in two with both sides shown side-by-side. */
    SPLIT_BOUNCER,
}

/** Enumerates the supported window size classes. */
enum class SizeClass {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

/**
 * Internal version of `calculateLayout` in the System UI Compose library, extracted here to allow
 * for testing that's not dependent on Compose.
 */
@VisibleForTesting
fun calculateLayoutInternal(
    width: SizeClass,
    height: SizeClass,
    isSideBySideSupported: Boolean,
): BouncerSceneLayout {
    return when (height) {
        SizeClass.COMPACT -> BouncerSceneLayout.SPLIT_BOUNCER
        SizeClass.MEDIUM ->
            when (width) {
                SizeClass.COMPACT -> BouncerSceneLayout.STANDARD_BOUNCER
                SizeClass.MEDIUM -> BouncerSceneLayout.STANDARD_BOUNCER
                SizeClass.EXPANDED -> BouncerSceneLayout.BESIDE_USER_SWITCHER
            }
        SizeClass.EXPANDED ->
            when (width) {
                SizeClass.COMPACT -> BouncerSceneLayout.STANDARD_BOUNCER
                SizeClass.MEDIUM -> BouncerSceneLayout.BELOW_USER_SWITCHER
                SizeClass.EXPANDED -> BouncerSceneLayout.BESIDE_USER_SWITCHER
            }
    }.takeIf { it != BouncerSceneLayout.BESIDE_USER_SWITCHER || isSideBySideSupported }
        ?: BouncerSceneLayout.STANDARD_BOUNCER
}
