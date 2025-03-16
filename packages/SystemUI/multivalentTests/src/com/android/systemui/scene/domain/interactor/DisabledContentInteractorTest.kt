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

package com.android.systemui.scene.domain.interactor

import android.app.StatusBarManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.UserAction
import com.android.compose.animation.scene.UserActionResult
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.statusbar.disableflags.data.repository.fakeDisableFlagsRepository
import com.android.systemui.statusbar.disableflags.shared.model.DisableFlagsModel
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class DisabledContentInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos().useUnconfinedTestDispatcher()

    private val underTest = kosmos.disabledContentInteractor

    @Test
    fun isDisabled_notificationsShade() =
        kosmos.runTest {
            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(disable2 = StatusBarManager.DISABLE2_NONE)
            assertThat(underTest.isDisabled(Overlays.NotificationsShade)).isFalse()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(disable2 = StatusBarManager.DISABLE2_NOTIFICATION_SHADE)
            assertThat(underTest.isDisabled(Overlays.NotificationsShade)).isTrue()
        }

    @Test
    fun isDisabled_qsShade() =
        kosmos.runTest {
            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(disable2 = StatusBarManager.DISABLE2_NONE)
            assertThat(underTest.isDisabled(Overlays.QuickSettingsShade)).isFalse()

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(disable2 = StatusBarManager.DISABLE2_QUICK_SETTINGS)
            assertThat(underTest.isDisabled(Overlays.QuickSettingsShade)).isTrue()
        }

    @Test
    fun repeatWhenDisabled() =
        kosmos.runTest {
            var notificationDisabledCount = 0
            applicationCoroutineScope.launch {
                underTest.repeatWhenDisabled(Overlays.NotificationsShade) {
                    notificationDisabledCount++
                }
            }
            var qsDisabledCount = 0
            applicationCoroutineScope.launch {
                underTest.repeatWhenDisabled(Overlays.QuickSettingsShade) { qsDisabledCount++ }
            }

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(disable2 = StatusBarManager.DISABLE2_QUICK_SETTINGS)
            assertThat(notificationDisabledCount).isEqualTo(0)
            assertThat(qsDisabledCount).isEqualTo(1)

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 =
                        StatusBarManager.DISABLE2_NOTIFICATION_SHADE or
                            StatusBarManager.DISABLE2_QUICK_SETTINGS
                )
            assertThat(notificationDisabledCount).isEqualTo(1)
            assertThat(qsDisabledCount).isEqualTo(1)

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(disable2 = StatusBarManager.DISABLE2_NOTIFICATION_SHADE)
            assertThat(notificationDisabledCount).isEqualTo(1)
            assertThat(qsDisabledCount).isEqualTo(1)

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(disable2 = StatusBarManager.DISABLE2_QUICK_SETTINGS)
            assertThat(notificationDisabledCount).isEqualTo(1)
            assertThat(qsDisabledCount).isEqualTo(2)
        }

    @Test
    fun filteredUserActions() =
        kosmos.runTest {
            val map =
                mapOf<UserAction, UserActionResult>(
                    Swipe.Up to UserActionResult.ShowOverlay(Overlays.NotificationsShade),
                    Swipe.Down to UserActionResult.ShowOverlay(Overlays.QuickSettingsShade),
                )
            val unfiltered = MutableStateFlow(map)
            val filtered by collectLastValue(underTest.filteredUserActions(unfiltered))
            assertThat(filtered).isEqualTo(map)

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(disable2 = StatusBarManager.DISABLE2_NOTIFICATION_SHADE)
            assertThat(filtered)
                .isEqualTo(
                    mapOf(Swipe.Down to UserActionResult.ShowOverlay(Overlays.QuickSettingsShade))
                )

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(disable2 = StatusBarManager.DISABLE2_QUICK_SETTINGS)
            assertThat(filtered)
                .isEqualTo(
                    mapOf(Swipe.Up to UserActionResult.ShowOverlay(Overlays.NotificationsShade))
                )

            fakeDisableFlagsRepository.disableFlags.value =
                DisableFlagsModel(
                    disable2 =
                        StatusBarManager.DISABLE2_NOTIFICATION_SHADE or
                            StatusBarManager.DISABLE2_QUICK_SETTINGS
                )
            assertThat(filtered).isEqualTo(emptyMap<UserAction, UserActionResult>())
        }
}
