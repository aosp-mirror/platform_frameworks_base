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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.stack

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.assertDoesNotLogWtf
import com.android.systemui.log.assertLogsWtf
import kotlin.math.log2
import kotlin.math.sqrt
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class ViewStateTest : SysuiTestCase() {
    private val viewState = ViewState()

    @Suppress("DIVISION_BY_ZERO")
    @Test
    fun testWtfs() {
        // Setting valid values doesn't cause any wtfs.
        assertDoesNotLogWtf {
            viewState.alpha = 0.1f
            viewState.xTranslation = 0f
            viewState.yTranslation = 10f
            viewState.zTranslation = 20f
            viewState.scaleX = 0.5f
            viewState.scaleY = 0.25f
        }

        // Setting NaN values leads to wtfs being logged, and the value not being changed.
        assertLogsWtf { viewState.alpha = 0.0f / 0.0f }
        Assert.assertEquals(viewState.alpha, 0.1f)

        assertLogsWtf { viewState.xTranslation = Float.NaN }
        Assert.assertEquals(viewState.xTranslation, 0f)

        assertLogsWtf { viewState.yTranslation = log2(-10.0).toFloat() }
        Assert.assertEquals(viewState.yTranslation, 10f)

        assertLogsWtf { viewState.zTranslation = sqrt(-1.0).toFloat() }
        Assert.assertEquals(viewState.zTranslation, 20f)

        assertLogsWtf { viewState.scaleX = Float.POSITIVE_INFINITY + Float.NEGATIVE_INFINITY }
        Assert.assertEquals(viewState.scaleX, 0.5f)

        assertLogsWtf { viewState.scaleY = Float.POSITIVE_INFINITY * 0 }
        Assert.assertEquals(viewState.scaleY, 0.25f)
    }
}
