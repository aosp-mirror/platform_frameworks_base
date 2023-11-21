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
    STANDARD,
    /** The bouncer is displayed vertically stacked with the user switcher. */
    STACKED,
    /** The bouncer is displayed side-by-side with the user switcher or an empty space. */
    SIDE_BY_SIDE,
    /** The bouncer is split in two with both sides shown side-by-side. */
    SPLIT,
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
        SizeClass.COMPACT -> BouncerSceneLayout.SPLIT
        SizeClass.MEDIUM ->
            when (width) {
                SizeClass.COMPACT -> BouncerSceneLayout.STANDARD
                SizeClass.MEDIUM -> BouncerSceneLayout.STANDARD
                SizeClass.EXPANDED -> BouncerSceneLayout.SIDE_BY_SIDE
            }
        SizeClass.EXPANDED ->
            when (width) {
                SizeClass.COMPACT -> BouncerSceneLayout.STANDARD
                SizeClass.MEDIUM -> BouncerSceneLayout.STACKED
                SizeClass.EXPANDED -> BouncerSceneLayout.SIDE_BY_SIDE
            }
    }.takeIf { it != BouncerSceneLayout.SIDE_BY_SIDE || isSideBySideSupported }
        ?: BouncerSceneLayout.STANDARD
}
