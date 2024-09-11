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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.statusbar.notification.data.repository.FakeHeadsUpRowRepository
import com.android.systemui.statusbar.notification.data.repository.notificationsKeyguardViewStateRepository
import com.android.systemui.statusbar.notification.stack.data.repository.headsUpNotificationRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.headsUpNotificationInteractor
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
class HeadsUpNotificationInteractorTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            fakeKeyguardTransitionRepository =
                FakeKeyguardTransitionRepository(initInLockscreen = false)
        }
    private val testScope = kosmos.testScope
    private val faceAuthRepository by lazy { kosmos.fakeDeviceEntryFaceAuthRepository }
    private val headsUpRepository by lazy { kosmos.headsUpNotificationRepository }
    private val keyguardTransitionRepository by lazy { kosmos.fakeKeyguardTransitionRepository }
    private val keyguardViewStateRepository by lazy {
        kosmos.notificationsKeyguardViewStateRepository
    }
    private val shadeTestUtil by lazy { kosmos.shadeTestUtil }

    private val underTest = kosmos.headsUpNotificationInteractor

    @Test
    fun hasPinnedRows_emptyList_false() =
        testScope.runTest {
            val hasPinnedRows by collectLastValue(underTest.hasPinnedRows)

            assertThat(hasPinnedRows).isFalse()
        }

    @Test
    fun hasPinnedRows_noPinnedRows_false() =
        testScope.runTest {
            val hasPinnedRows by collectLastValue(underTest.hasPinnedRows)
            // WHEN no pinned rows are set
            headsUpRepository.setNotifications(
                fakeHeadsUpRowRepository("key 0"),
                fakeHeadsUpRowRepository("key 1"),
                fakeHeadsUpRowRepository("key 2"),
            )
            runCurrent()

            // THEN hasPinnedRows is false
            assertThat(hasPinnedRows).isFalse()
        }

    @Test
    fun hasPinnedRows_hasPinnedRows_true() =
        testScope.runTest {
            val hasPinnedRows by collectLastValue(underTest.hasPinnedRows)
            // WHEN a pinned rows is set
            headsUpRepository.setNotifications(
                fakeHeadsUpRowRepository("key 0", isPinned = true),
                fakeHeadsUpRowRepository("key 1"),
                fakeHeadsUpRowRepository("key 2"),
            )
            runCurrent()

            // THEN hasPinnedRows is true
            assertThat(hasPinnedRows).isTrue()
        }

    @Test
    fun hasPinnedRows_rowGetsPinned_true() =
        testScope.runTest {
            val hasPinnedRows by collectLastValue(underTest.hasPinnedRows)
            // GIVEN no rows are pinned
            val rows =
                arrayListOf(
                    fakeHeadsUpRowRepository("key 0"),
                    fakeHeadsUpRowRepository("key 1"),
                    fakeHeadsUpRowRepository("key 2"),
                )
            headsUpRepository.setNotifications(rows)
            runCurrent()

            // WHEN a row gets pinned
            rows[0].isPinned.value = true
            runCurrent()

            // THEN hasPinnedRows updates to true
            assertThat(hasPinnedRows).isTrue()
        }

    @Test
    fun hasPinnedRows_rowGetsUnPinned_false() =
        testScope.runTest {
            val hasPinnedRows by collectLastValue(underTest.hasPinnedRows)
            // GIVEN one row is pinned
            val rows =
                arrayListOf(
                    fakeHeadsUpRowRepository("key 0", isPinned = true),
                    fakeHeadsUpRowRepository("key 1"),
                    fakeHeadsUpRowRepository("key 2"),
                )
            headsUpRepository.setNotifications(rows)
            runCurrent()

            // THEN that row gets unpinned
            rows[0].isPinned.value = false
            runCurrent()

            // THEN hasPinnedRows updates to false
            assertThat(hasPinnedRows).isFalse()
        }

    @Test
    fun pinnedRows_noRows_isEmpty() =
        testScope.runTest {
            val pinnedHeadsUpRows by collectLastValue(underTest.pinnedHeadsUpRows)

            assertThat(pinnedHeadsUpRows).isEmpty()
        }

    @Test
    fun pinnedRows_noPinnedRows_isEmpty() =
        testScope.runTest {
            val pinnedHeadsUpRows by collectLastValue(underTest.pinnedHeadsUpRows)
            // WHEN no rows are pinned
            headsUpRepository.setNotifications(
                fakeHeadsUpRowRepository("key 0"),
                fakeHeadsUpRowRepository("key 1"),
                fakeHeadsUpRowRepository("key 2"),
            )
            runCurrent()

            // THEN all rows are filtered
            assertThat(pinnedHeadsUpRows).isEmpty()
        }

    @Test
    fun pinnedRows_hasPinnedRows_containsPinnedRows() =
        testScope.runTest {
            val pinnedHeadsUpRows by collectLastValue(underTest.pinnedHeadsUpRows)
            // WHEN some rows are pinned
            val rows =
                arrayListOf(
                    fakeHeadsUpRowRepository("key 0", isPinned = true),
                    fakeHeadsUpRowRepository("key 1", isPinned = true),
                    fakeHeadsUpRowRepository("key 2"),
                )
            headsUpRepository.setNotifications(rows)
            runCurrent()

            // THEN the unpinned rows are filtered
            assertThat(pinnedHeadsUpRows).containsExactly(rows[0], rows[1])
        }

    @Test
    fun pinnedRows_rowGetsPinned_containsPinnedRows() =
        testScope.runTest {
            val pinnedHeadsUpRows by collectLastValue(underTest.pinnedHeadsUpRows)
            // GIVEN some rows are pinned
            val rows =
                arrayListOf(
                    fakeHeadsUpRowRepository("key 0", isPinned = true),
                    fakeHeadsUpRowRepository("key 1", isPinned = true),
                    fakeHeadsUpRowRepository("key 2"),
                )
            headsUpRepository.setNotifications(rows)
            runCurrent()

            // WHEN all rows gets pinned
            rows[2].isPinned.value = true
            runCurrent()

            // THEN no rows are filtered
            assertThat(pinnedHeadsUpRows).containsExactly(rows[0], rows[1], rows[2])
        }

    @Test
    fun pinnedRows_allRowsPinned_containsAllRows() =
        testScope.runTest {
            val pinnedHeadsUpRows by collectLastValue(underTest.pinnedHeadsUpRows)
            // WHEN all rows are pinned
            val rows =
                arrayListOf(
                    fakeHeadsUpRowRepository("key 0", isPinned = true),
                    fakeHeadsUpRowRepository("key 1", isPinned = true),
                    fakeHeadsUpRowRepository("key 2", isPinned = true),
                )
            headsUpRepository.setNotifications(rows)
            runCurrent()

            // THEN no rows are filtered
            assertThat(pinnedHeadsUpRows).containsExactly(rows[0], rows[1], rows[2])
        }

    @Test
    fun pinnedRows_rowGetsUnPinned_containsPinnedRows() =
        testScope.runTest {
            val pinnedHeadsUpRows by collectLastValue(underTest.pinnedHeadsUpRows)
            // GIVEN all rows are pinned
            val rows =
                arrayListOf(
                    fakeHeadsUpRowRepository("key 0", isPinned = true),
                    fakeHeadsUpRowRepository("key 1", isPinned = true),
                    fakeHeadsUpRowRepository("key 2", isPinned = true),
                )
            headsUpRepository.setNotifications(rows)
            runCurrent()

            // WHEN a row gets unpinned
            rows[0].isPinned.value = false
            runCurrent()

            // THEN the unpinned row is filtered
            assertThat(pinnedHeadsUpRows).containsExactly(rows[1], rows[2])
        }

    @Test
    fun pinnedRows_rowGetsPinnedAndUnPinned_containsTheSameInstance() =
        testScope.runTest {
            val pinnedHeadsUpRows by collectLastValue(underTest.pinnedHeadsUpRows)

            val rows =
                arrayListOf(
                    fakeHeadsUpRowRepository("key 0"),
                    fakeHeadsUpRowRepository("key 1"),
                    fakeHeadsUpRowRepository("key 2"),
                )
            headsUpRepository.setNotifications(rows)
            runCurrent()

            rows[0].isPinned.value = true
            runCurrent()
            assertThat(pinnedHeadsUpRows).containsExactly(rows[0])

            rows[0].isPinned.value = false
            runCurrent()
            assertThat(pinnedHeadsUpRows).isEmpty()

            rows[0].isPinned.value = true
            runCurrent()
            assertThat(pinnedHeadsUpRows).containsExactly(rows[0])
        }

    @Test
    fun showHeadsUpStatusBar_true() =
        testScope.runTest {
            val showHeadsUpStatusBar by collectLastValue(underTest.showHeadsUpStatusBar)

            // WHEN a row is pinned
            headsUpRepository.setNotifications(fakeHeadsUpRowRepository("key 0", isPinned = true))

            assertThat(showHeadsUpStatusBar).isTrue()
        }

    @Test
    fun showHeadsUpStatusBar_withoutPinnedNotifications_false() =
        testScope.runTest {
            val showHeadsUpStatusBar by collectLastValue(underTest.showHeadsUpStatusBar)

            // WHEN no row is pinned
            headsUpRepository.setNotifications(fakeHeadsUpRowRepository("key 0", isPinned = false))

            assertThat(showHeadsUpStatusBar).isFalse()
        }

    @Test
    fun showHeadsUpStatusBar_whenShadeExpanded_false() =
        testScope.runTest {
            val showHeadsUpStatusBar by collectLastValue(underTest.showHeadsUpStatusBar)

            // WHEN a row is pinned
            headsUpRepository.setNotifications(fakeHeadsUpRowRepository("key 0", isPinned = true))
            // AND the shade is expanded
            shadeTestUtil.setShadeExpansion(1.0f)

            assertThat(showHeadsUpStatusBar).isFalse()
        }

    @Test
    fun showHeadsUpStatusBar_notificationsAreHidden_false() =
        testScope.runTest {
            val showHeadsUpStatusBar by collectLastValue(underTest.showHeadsUpStatusBar)

            // WHEN a row is pinned
            headsUpRepository.setNotifications(fakeHeadsUpRowRepository("key 0", isPinned = true))
            // AND the notifications are hidden
            keyguardViewStateRepository.areNotificationsFullyHidden.value = true

            assertThat(showHeadsUpStatusBar).isFalse()
        }

    @Test
    fun showHeadsUpStatusBar_onLockScreen_false() =
        testScope.runTest {
            val showHeadsUpStatusBar by collectLastValue(underTest.showHeadsUpStatusBar)

            // WHEN a row is pinned
            headsUpRepository.setNotifications(fakeHeadsUpRowRepository("key 0", isPinned = true))
            // AND the lock screen is shown
            keyguardTransitionRepository.emitInitialStepsFromOff(to = KeyguardState.LOCKSCREEN)

            assertThat(showHeadsUpStatusBar).isFalse()
        }

    @Test
    fun showHeadsUpStatusBar_onByPassLockScreen_true() =
        testScope.runTest {
            val showHeadsUpStatusBar by collectLastValue(underTest.showHeadsUpStatusBar)

            // WHEN a row is pinned
            headsUpRepository.setNotifications(fakeHeadsUpRowRepository("key 0", isPinned = true))
            // AND the lock screen is shown
            keyguardTransitionRepository.emitInitialStepsFromOff(to = KeyguardState.LOCKSCREEN)
            // AND bypass is enabled
            faceAuthRepository.isBypassEnabled.value = true

            assertThat(showHeadsUpStatusBar).isTrue()
        }

    @Test
    fun showHeadsUpStatusBar_onByPassLockScreen_withoutNotifications_false() =
        testScope.runTest {
            val showHeadsUpStatusBar by collectLastValue(underTest.showHeadsUpStatusBar)

            // WHEN no pinned rows
            // AND the lock screen is shown
            keyguardTransitionRepository.emitInitialStepsFromOff(to = KeyguardState.LOCKSCREEN)
            // AND bypass is enabled
            faceAuthRepository.isBypassEnabled.value = true

            assertThat(showHeadsUpStatusBar).isFalse()
        }

    private fun fakeHeadsUpRowRepository(key: String, isPinned: Boolean = false) =
        FakeHeadsUpRowRepository(key = key, elementKey = Any()).apply {
            this.isPinned.value = isPinned
        }
}
