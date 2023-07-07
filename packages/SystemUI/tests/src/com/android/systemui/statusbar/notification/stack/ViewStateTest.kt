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

import android.testing.AndroidTestingRunner
import android.util.Log
import android.util.Log.TerribleFailureHandler
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import kotlin.math.log2
import kotlin.math.sqrt
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class ViewStateTest : SysuiTestCase() {
    private val viewState = ViewState()

    private var wtfHandler: TerribleFailureHandler? = null
    private var wtfCount = 0

    @Suppress("DIVISION_BY_ZERO")
    @Test
    fun testWtfs() {
        interceptWtfs()

        // Setting valid values doesn't cause any wtfs.
        viewState.alpha = 0.1f
        viewState.xTranslation = 0f
        viewState.yTranslation = 10f
        viewState.zTranslation = 20f
        viewState.scaleX = 0.5f
        viewState.scaleY = 0.25f

        expectWtfs(0)

        // Setting NaN values leads to wtfs being logged, and the value not being changed.
        viewState.alpha = 0.0f / 0.0f
        expectWtfs(1)
        Assert.assertEquals(viewState.alpha, 0.1f)

        viewState.xTranslation = Float.NaN
        expectWtfs(2)
        Assert.assertEquals(viewState.xTranslation, 0f)

        viewState.yTranslation = log2(-10.0).toFloat()
        expectWtfs(3)
        Assert.assertEquals(viewState.yTranslation, 10f)

        viewState.zTranslation = sqrt(-1.0).toFloat()
        expectWtfs(4)
        Assert.assertEquals(viewState.zTranslation, 20f)

        viewState.scaleX = Float.POSITIVE_INFINITY + Float.NEGATIVE_INFINITY
        expectWtfs(5)
        Assert.assertEquals(viewState.scaleX, 0.5f)

        viewState.scaleY = Float.POSITIVE_INFINITY * 0
        expectWtfs(6)
        Assert.assertEquals(viewState.scaleY, 0.25f)
    }

    private fun interceptWtfs() {
        wtfCount = 0
        wtfHandler =
            Log.setWtfHandler { _: String?, e: Log.TerribleFailure, _: Boolean ->
                Log.e("ViewStateTest", "Observed WTF: $e")
                wtfCount++
            }
    }

    private fun expectWtfs(expectedWtfCount: Int) {
        Assert.assertNotNull(wtfHandler)
        Assert.assertEquals(expectedWtfCount, wtfCount)
    }
}
