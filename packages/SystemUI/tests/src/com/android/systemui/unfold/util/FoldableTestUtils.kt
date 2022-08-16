/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.unfold.util

import android.content.Context
import org.junit.Assume.assumeTrue

object FoldableTestUtils {

    /** Finds device state for folded and unfolded. */
    fun findDeviceStates(context: Context): FoldableDeviceStates {
        val foldedDeviceStates: IntArray = context.resources.getIntArray(
            com.android.internal.R.array.config_foldedDeviceStates)
        assumeTrue("Test should be launched on a foldable device",
            foldedDeviceStates.isNotEmpty())

        val folded = foldedDeviceStates.maxOrNull()!!
        val unfolded = folded + 1
        return FoldableDeviceStates(folded = folded, unfolded = unfolded)
    }
}

data class FoldableDeviceStates(val folded: Int, val unfolded: Int)