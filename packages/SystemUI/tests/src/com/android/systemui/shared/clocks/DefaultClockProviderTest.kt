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

package com.android.systemui.shared.clocks

import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.testing.AndroidTestingRunner
import android.view.LayoutInflater
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertNotNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.junit.MockitoJUnit

@RunWith(AndroidTestingRunner::class)
@SmallTest
class DefaultClockProviderTest : SysuiTestCase() {

    @JvmField @Rule val mockito = MockitoJUnit.rule()

    @Mock private lateinit var mockClockView: AnimatableClockView
    @Mock private lateinit var layoutInflater: LayoutInflater
    @Mock private lateinit var mockClockThumbnail: Drawable
    @Mock private lateinit var resources: Resources
    private lateinit var provider: DefaultClockProvider

    @Before
    fun setUp() {
        whenever(layoutInflater.inflate(R.layout.clock_default_small, null))
            .thenReturn(mockClockView)
        whenever(layoutInflater.inflate(R.layout.clock_default_large, null))
            .thenReturn(mockClockView)
        whenever(resources.getDrawable(R.drawable.clock_default_thumbnail, null))
            .thenReturn(mockClockThumbnail)

        provider = DefaultClockProvider(layoutInflater, resources)
    }

    @Test
    fun providedClocks_matchesFactory() {
        // All providers need to provide clocks & thumbnails for exposed clocks
        for (metadata in provider.getClocks()) {
            assertNotNull(provider.createClock(metadata.clockId))
            assertNotNull(provider.getClockThumbnail(metadata.clockId))
        }
    }

    @Test
    fun defaultClock_alwaysProvided() {
        // Default clock provider must always provide the default clock
        val clock = provider.createClock(DEFAULT_CLOCK_ID)
        assertNotNull(clock)
        assertEquals(clock.smallClock, mockClockView)
        assertEquals(clock.largeClock, mockClockView)
    }
}
