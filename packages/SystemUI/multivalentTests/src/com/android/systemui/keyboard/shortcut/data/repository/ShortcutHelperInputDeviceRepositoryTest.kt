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

package com.android.systemui.keyboard.shortcut.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyboard.shortcut.shortcutHelperInputDeviceRepository
import com.android.systemui.keyboard.shortcut.shortcutHelperTestHelper
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShortcutHelperInputDeviceRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val testHelper = kosmos.shortcutHelperTestHelper
    private val repo = kosmos.shortcutHelperInputDeviceRepository

    @Test
    fun activeInputDevice_nullByDefault() =
        kosmos.runTest {
            val activeInputDevice by collectLastValue(repo.activeInputDevice)

            assertThat(activeInputDevice).isNull()
        }

    @Test
    fun activeInputDevice_nonNullWhenHelperIsShown() =
        kosmos.runTest {
            val activeInputDevice by collectLastValue(repo.activeInputDevice)

            testHelper.showFromActivity()

            assertThat(activeInputDevice).isNotNull()
        }

    @Test
    fun activeInputDevice_nullWhenHelperIsClosed() =
        kosmos.runTest {
            val activeInputDevice by collectLastValue(repo.activeInputDevice)

            testHelper.showFromActivity()
            testHelper.hideFromActivity()

            assertThat(activeInputDevice).isNull()
        }
}
