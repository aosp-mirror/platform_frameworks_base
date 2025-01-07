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

package com.android.systemui.bouncer.ui.composable

import androidx.annotation.VisibleForTesting
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import com.android.compose.windowsizeclass.LocalWindowSizeClass

/**
 * Returns the [BouncerSceneLayout] that should be used by the bouncer scene. If
 * [isOneHandedModeSupported] is `false`, then [BouncerSceneLayout.BESIDE_USER_SWITCHER] is replaced
 * by [BouncerSceneLayout.STANDARD_BOUNCER].
 */
@Composable
fun calculateLayout(isOneHandedModeSupported: Boolean): BouncerSceneLayout {
    val windowSizeClass = LocalWindowSizeClass.current

    return calculateLayoutInternal(
        width = windowSizeClass.widthSizeClass.toEnum(),
        height = windowSizeClass.heightSizeClass.toEnum(),
        isOneHandedModeSupported = isOneHandedModeSupported,
    )
}

private fun WindowWidthSizeClass.toEnum(): SizeClass {
    return when (this) {
        WindowWidthSizeClass.Compact -> SizeClass.COMPACT
        WindowWidthSizeClass.Medium -> SizeClass.MEDIUM
        WindowWidthSizeClass.Expanded -> SizeClass.EXPANDED
        else -> error("Unsupported WindowWidthSizeClass \"$this\"")
    }
}

private fun WindowHeightSizeClass.toEnum(): SizeClass {
    return when (this) {
        WindowHeightSizeClass.Compact -> SizeClass.COMPACT
        WindowHeightSizeClass.Medium -> SizeClass.MEDIUM
        WindowHeightSizeClass.Expanded -> SizeClass.EXPANDED
        else -> error("Unsupported WindowHeightSizeClass \"$this\"")
    }
}

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
    isOneHandedModeSupported: Boolean,
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
    }.takeIf { it != BouncerSceneLayout.BESIDE_USER_SWITCHER || isOneHandedModeSupported }
        ?: BouncerSceneLayout.STANDARD_BOUNCER
}
