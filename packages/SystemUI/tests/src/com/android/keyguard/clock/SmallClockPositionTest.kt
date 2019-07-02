/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.keyguard.clock

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class SmallClockPositionTest : SysuiTestCase() {

    private val statusBarHeight = 100
    private val lockPadding = 15
    private val lockHeight = 35
    private val burnInY = 20

    private lateinit var position: SmallClockPosition

    @Before
    fun setUp() {
        position = SmallClockPosition(statusBarHeight, lockPadding, lockHeight, burnInY)
    }

    @Test
    fun loadResources() {
        // Cover constructor taking Resources object.
        position = SmallClockPosition(context.resources)
        position.setDarkAmount(1f)
        assertThat(position.preferredY).isGreaterThan(0)
    }

    @Test
    fun darkPosition() {
        // GIVEN on AOD
        position.setDarkAmount(1f)
        // THEN Y position is statusBarHeight + lockPadding + burnInY (100 + 15 + 20 = 135)
        assertThat(position.preferredY).isEqualTo(135)
    }

    @Test
    fun lockPosition() {
        // GIVEN on lock screen
        position.setDarkAmount(0f)
        // THEN Y position is statusBarHeight + lockPadding + lockHeight + lockPadding
        // (100 + 15 + 35 + 15 = 165)
        assertThat(position.preferredY).isEqualTo(165)
    }
}