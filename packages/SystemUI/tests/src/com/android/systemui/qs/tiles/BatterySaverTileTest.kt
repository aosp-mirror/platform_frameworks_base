/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.qs.tiles

import android.content.Context
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.Dependency
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.QSHost
import com.android.systemui.statusbar.policy.BatteryController
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@RunWithLooper
@SmallTest
class BatterySaverTileTest : SysuiTestCase() {

    companion object {
        private const val USER = 10
    }

    @Mock
    private lateinit var userContext: Context
    @Mock
    private lateinit var qsHost: QSHost
    @Mock
    private lateinit var batteryController: BatteryController
    private lateinit var testableLooper: TestableLooper
    private lateinit var tile: BatterySaverTile

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        mDependency.injectTestDependency(Dependency.BG_LOOPER, testableLooper.looper)
        `when`(qsHost.userContext).thenReturn(userContext)
        `when`(userContext.userId).thenReturn(USER)

        tile = BatterySaverTile(qsHost, batteryController)
    }

    @Test
    fun testSettingWithCorrectUser() {
        assertEquals(USER, tile.mSetting.currentUser)
    }

    @Test
    fun testSettingChangesUser() {
        tile.userSwitch(USER + 1)

        testableLooper.processAllMessages()

        assertEquals(USER + 1, tile.mSetting.currentUser)
    }
}