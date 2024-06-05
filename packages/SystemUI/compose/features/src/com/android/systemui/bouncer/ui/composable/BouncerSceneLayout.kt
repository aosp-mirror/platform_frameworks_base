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

import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import com.android.compose.windowsizeclass.LocalWindowSizeClass
import com.android.systemui.bouncer.ui.helper.BouncerSceneLayout
import com.android.systemui.bouncer.ui.helper.SizeClass
import com.android.systemui.bouncer.ui.helper.calculateLayoutInternal

/**
 * Returns the [BouncerSceneLayout] that should be used by the bouncer scene. If
 * [isSideBySideSupported] is `false`, then [BouncerSceneLayout.BESIDE_USER_SWITCHER] is replaced by
 * [BouncerSceneLayout.STANDARD_BOUNCER].
 */
@Composable
fun calculateLayout(
    isSideBySideSupported: Boolean,
): BouncerSceneLayout {
    val windowSizeClass = LocalWindowSizeClass.current

    return calculateLayoutInternal(
        width = windowSizeClass.widthSizeClass.toEnum(),
        height = windowSizeClass.heightSizeClass.toEnum(),
        isSideBySideSupported = isSideBySideSupported,
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
