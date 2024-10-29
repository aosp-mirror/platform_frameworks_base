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

package com.android.systemui.statusbar.chips.notification.domain.interactor

import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(StatusBarNotifChips.FLAG_NAME)
class StatusBarNotificationChipsInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val testScope = kosmos.testScope

    private val underTest = kosmos.statusBarNotificationChipsInteractor

    @Test
    fun onPromotedNotificationChipTapped_emitsKeys() =
        testScope.runTest {
            val latest by collectValues(underTest.promotedNotificationChipTapEvent)

            underTest.onPromotedNotificationChipTapped("fakeKey")

            assertThat(latest).hasSize(1)
            assertThat(latest[0]).isEqualTo("fakeKey")

            underTest.onPromotedNotificationChipTapped("fakeKey2")

            assertThat(latest).hasSize(2)
            assertThat(latest[1]).isEqualTo("fakeKey2")
        }

    @Test
    fun onPromotedNotificationChipTapped_sameKeyTwice_emitsTwice() =
        testScope.runTest {
            val latest by collectValues(underTest.promotedNotificationChipTapEvent)

            underTest.onPromotedNotificationChipTapped("fakeKey")
            underTest.onPromotedNotificationChipTapped("fakeKey")

            assertThat(latest).hasSize(2)
            assertThat(latest[0]).isEqualTo("fakeKey")
            assertThat(latest[1]).isEqualTo("fakeKey")
        }
}
