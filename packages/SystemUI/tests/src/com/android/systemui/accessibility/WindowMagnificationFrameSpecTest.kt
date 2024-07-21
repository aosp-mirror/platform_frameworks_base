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

package com.android.systemui.accessibility

import android.testing.AndroidTestingRunner
import android.util.Size
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.accessibility.WindowMagnificationSettings.MagnificationSize
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class WindowMagnificationFrameSpecTest : SysuiTestCase() {

    @Test
    fun deserializeSpec_validSpec_expectedIndex() {
        val targetIndex = MagnificationSize.LARGE
        val targetSize = Size(100, 200)
        val targetPreference = WindowMagnificationFrameSpec.serialize(targetIndex, targetSize)

        assertThat(WindowMagnificationFrameSpec.deserialize(targetPreference).index)
            .isEqualTo(targetIndex)
    }

    @Test
    fun deserializeSpec_validSpec_expectedSize() {
        val targetIndex = MagnificationSize.LARGE
        val targetSize = Size(100, 200)
        val targetPreference = WindowMagnificationFrameSpec.serialize(targetIndex, targetSize)

        assertThat(WindowMagnificationFrameSpec.deserialize(targetPreference).size)
            .isEqualTo(targetSize)
    }
}
