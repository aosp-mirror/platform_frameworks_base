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

package com.android.systemui.statusbar.pipeline.shared.domain.interactor

import android.app.StatusBarManager.DISABLE2_NONE
import android.app.StatusBarManager.DISABLE_CLOCK
import android.app.StatusBarManager.DISABLE_NONE
import android.app.StatusBarManager.DISABLE_NOTIFICATION_ICONS
import android.app.StatusBarManager.DISABLE_SYSTEM_INFO
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.disableflags.data.model.DisableFlagsModel
import com.android.systemui.statusbar.disableflags.data.repository.fakeDisableFlagsRepository
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.coroutines.test.runTest

@SmallTest
class CollapsedStatusBarInteractorTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope
    val disableFlagsRepo = kosmos.fakeDisableFlagsRepository

    val underTest = kosmos.collapsedStatusBarInteractor

    @Test
    fun visibilityViaDisableFlags_allDisabled() =
        testScope.runTest {
            val latest by collectLastValue(underTest.visibilityViaDisableFlags)

            disableFlagsRepo.disableFlags.value =
                DisableFlagsModel(
                    DISABLE_CLOCK or DISABLE_NOTIFICATION_ICONS or DISABLE_SYSTEM_INFO,
                    DISABLE2_NONE,
                    animate = false,
                )

            assertThat(latest!!.isClockAllowed).isFalse()
            assertThat(latest!!.areNotificationIconsAllowed).isFalse()
            assertThat(latest!!.isSystemInfoAllowed).isFalse()
        }

    @Test
    fun visibilityViaDisableFlags_allEnabled() =
        testScope.runTest {
            val latest by collectLastValue(underTest.visibilityViaDisableFlags)

            disableFlagsRepo.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE, animate = false)

            assertThat(latest!!.isClockAllowed).isTrue()
            assertThat(latest!!.areNotificationIconsAllowed).isTrue()
            assertThat(latest!!.isSystemInfoAllowed).isTrue()
        }

    @Test
    fun visibilityViaDisableFlags_animateFalse() =
        testScope.runTest {
            val latest by collectLastValue(underTest.visibilityViaDisableFlags)

            disableFlagsRepo.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE, animate = false)

            assertThat(latest!!.animate).isFalse()
        }

    @Test
    fun visibilityViaDisableFlags_animateTrue() =
        testScope.runTest {
            val latest by collectLastValue(underTest.visibilityViaDisableFlags)

            disableFlagsRepo.disableFlags.value =
                DisableFlagsModel(DISABLE_NONE, DISABLE2_NONE, animate = true)

            assertThat(latest!!.animate).isTrue()
        }
}
