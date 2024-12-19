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
import com.android.systemui.shade.data.repository.fakeShadeRepository
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.statusbar.notification.data.repository.FakeHeadsUpRowRepository
import com.android.systemui.statusbar.notification.data.repository.notificationsKeyguardViewStateRepository
import com.android.systemui.statusbar.notification.domain.model.TopPinnedState
import com.android.systemui.statusbar.notification.headsup.PinnedStatus
import com.android.systemui.statusbar.notification.stack.data.repository.headsUpNotificationRepository
import com.android.systemui.statusbar.notification.stack.domain.interactor.headsUpNotificationInteractor
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
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
                FakeKeyguardTransitionRepository(initInLockscreen = false, testScope = testScope)
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
    fun hasPinnedRows_rowGetsPinnedNormally_true() =
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

            // WHEN a row gets pinned normally
            rows[0].pinnedStatus.value = PinnedStatus.PinnedBySystem
            runCurrent()

            // THEN hasPinnedRows updates to true
            assertThat(hasPinnedRows).isTrue()
        }

    @Test
    fun hasPinnedRows_rowGetsPinnedByUser_true() =
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

            // WHEN a row gets pinned due to a chip tap
            rows[0].pinnedStatus.value = PinnedStatus.PinnedByUser
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
            rows[0].pinnedStatus.value = PinnedStatus.NotPinned
            runCurrent()

            // THEN hasPinnedRows updates to false
            assertThat(hasPinnedRows).isFalse()
        }

    @Test
    fun activeRows_noRows_isEmpty() =
        testScope.runTest {
            val activeHeadsUpRows by collectLastValue(underTest.activeHeadsUpRowKeys)

            assertThat(activeHeadsUpRows).isEmpty()
        }

    @Test
    fun pinnedRows_noRows_isEmpty() =
        testScope.runTest {
            val pinnedHeadsUpRows by collectLastValue(underTest.pinnedHeadsUpRowKeys)

            assertThat(pinnedHeadsUpRows).isEmpty()
        }

    @Test
    fun pinnedRows_noPinnedRows_isEmpty() =
        testScope.runTest {
            val pinnedHeadsUpRows by collectLastValue(underTest.pinnedHeadsUpRowKeys)
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
    fun activeRows_noPinnedRows_containsAllRows() =
        testScope.runTest {
            val activeHeadsUpRows by collectLastValue(underTest.activeHeadsUpRowKeys)
            // WHEN no rows are pinned
            val rows =
                arrayListOf(
                    fakeHeadsUpRowRepository("key 0"),
                    fakeHeadsUpRowRepository("key 1"),
                    fakeHeadsUpRowRepository("key 2"),
                )
            headsUpRepository.setNotifications(rows)
            runCurrent()

            // THEN all rows are present
            assertThat(activeHeadsUpRows).containsExactly(rows[0], rows[1], rows[2])
        }

    @Test
    fun pinnedRows_hasPinnedRows_containsPinnedRows() =
        testScope.runTest {
            val pinnedHeadsUpRows by collectLastValue(underTest.pinnedHeadsUpRowKeys)
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
    fun pinnedRows_hasPinnedRows_containsAllRows() =
        testScope.runTest {
            val activeHeadsUpRows by collectLastValue(underTest.activeHeadsUpRowKeys)
            // WHEN no rows are pinned
            val rows =
                arrayListOf(
                    fakeHeadsUpRowRepository("key 0", isPinned = true),
                    fakeHeadsUpRowRepository("key 1", isPinned = true),
                    fakeHeadsUpRowRepository("key 2"),
                )
            headsUpRepository.setNotifications(rows)
            runCurrent()

            // THEN all rows are present
            assertThat(activeHeadsUpRows).containsExactly(rows[0], rows[1], rows[2])
        }

    @Test
    fun pinnedRows_rowGetsPinned_containsPinnedRows() =
        testScope.runTest {
            val pinnedHeadsUpRows by collectLastValue(underTest.pinnedHeadsUpRowKeys)
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
            rows[2].pinnedStatus.value = PinnedStatus.PinnedBySystem
            runCurrent()

            // THEN no rows are filtered
            assertThat(pinnedHeadsUpRows).containsExactly(rows[0], rows[1], rows[2])
        }

    @Test
    fun activeRows_rowGetsPinned_containsAllRows() =
        testScope.runTest {
            val activeHeadsUpRows by collectLastValue(underTest.activeHeadsUpRowKeys)
            // GIVEN some rows are pinned
            val rows =
                arrayListOf(
                    fakeHeadsUpRowRepository("key 0", isPinned = true),
                    fakeHeadsUpRowRepository("key 1", isPinned = true),
                    fakeHeadsUpRowRepository("key 2"),
                )
            headsUpRepository.setNotifications(rows)
            runCurrent()

            // THEN all rows are present
            assertThat(activeHeadsUpRows).containsExactly(rows[0], rows[1], rows[2])

            // WHEN all rows gets pinned
            rows[2].pinnedStatus.value = PinnedStatus.PinnedBySystem
            runCurrent()

            // THEN no change
            assertThat(activeHeadsUpRows).containsExactly(rows[0], rows[1], rows[2])
        }

    @Test
    fun pinnedRows_allRowsPinned_containsAllRows() =
        testScope.runTest {
            val pinnedHeadsUpRows by collectLastValue(underTest.pinnedHeadsUpRowKeys)
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
    fun activeRows_allRowsPinned_containsAllRows() =
        testScope.runTest {
            val activeHeadsUpRows by collectLastValue(underTest.activeHeadsUpRowKeys)
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
            assertThat(activeHeadsUpRows).containsExactly(rows[0], rows[1], rows[2])
        }

    @Test
    fun pinnedRows_rowGetsUnPinned_containsPinnedRows() =
        testScope.runTest {
            val pinnedHeadsUpRows by collectLastValue(underTest.pinnedHeadsUpRowKeys)
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
            rows[0].pinnedStatus.value = PinnedStatus.NotPinned
            runCurrent()

            // THEN the unpinned row is filtered
            assertThat(pinnedHeadsUpRows).containsExactly(rows[1], rows[2])
        }

    @Test
    fun activeRows_rowGetsUnPinned_containsAllRows() =
        testScope.runTest {
            val activeHeadsUpRows by collectLastValue(underTest.activeHeadsUpRowKeys)
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
            rows[0].pinnedStatus.value = PinnedStatus.NotPinned
            runCurrent()

            // THEN all rows are still present
            assertThat(activeHeadsUpRows).containsExactly(rows[0], rows[1], rows[2])
        }

    @Test
    fun pinnedRows_rowGetsPinnedAndUnPinned_containsTheSameInstance() =
        testScope.runTest {
            val pinnedHeadsUpRows by collectLastValue(underTest.pinnedHeadsUpRowKeys)

            val rows =
                arrayListOf(
                    fakeHeadsUpRowRepository("key 0"),
                    fakeHeadsUpRowRepository("key 1"),
                    fakeHeadsUpRowRepository("key 2"),
                )
            headsUpRepository.setNotifications(rows)
            runCurrent()

            rows[0].pinnedStatus.value = PinnedStatus.PinnedBySystem
            runCurrent()
            assertThat(pinnedHeadsUpRows).containsExactly(rows[0])

            rows[0].pinnedStatus.value = PinnedStatus.NotPinned
            runCurrent()
            assertThat(pinnedHeadsUpRows).isEmpty()

            rows[0].pinnedStatus.value = PinnedStatus.PinnedBySystem
            runCurrent()
            assertThat(pinnedHeadsUpRows).containsExactly(rows[0])
        }

    @Test
    fun statusBarHeadsUpState_pinnedBySystem() =
        testScope.runTest {
            val state by collectLastValue(underTest.statusBarHeadsUpState)
            val status by collectLastValue(underTest.statusBarHeadsUpStatus)

            headsUpRepository.setNotifications(
                FakeHeadsUpRowRepository(key = "key 0", pinnedStatus = PinnedStatus.PinnedBySystem)
            )
            runCurrent()

            assertThat(state).isEqualTo(TopPinnedState.Pinned("key 0", PinnedStatus.PinnedBySystem))
            assertThat(status).isEqualTo(PinnedStatus.PinnedBySystem)
        }

    @Test
    fun statusBarHeadsUpState_pinnedByUser() =
        testScope.runTest {
            val state by collectLastValue(underTest.statusBarHeadsUpState)
            val status by collectLastValue(underTest.statusBarHeadsUpStatus)

            headsUpRepository.setNotifications(
                FakeHeadsUpRowRepository(key = "key 0", pinnedStatus = PinnedStatus.PinnedByUser)
            )
            runCurrent()

            assertThat(state).isEqualTo(TopPinnedState.Pinned("key 0", PinnedStatus.PinnedByUser))
            assertThat(status).isEqualTo(PinnedStatus.PinnedByUser)
        }

    @Test
    fun statusBarHeadsUpState_withoutPinnedNotifications_notPinned() =
        testScope.runTest {
            val state by collectLastValue(underTest.statusBarHeadsUpState)
            val status by collectLastValue(underTest.statusBarHeadsUpStatus)

            headsUpRepository.setNotifications(
                FakeHeadsUpRowRepository(key = "key 0", PinnedStatus.NotPinned)
            )
            runCurrent()

            assertThat(state).isEqualTo(TopPinnedState.NothingPinned)
            assertThat(status).isEqualTo(PinnedStatus.NotPinned)
        }

    @Test
    fun statusBarHeadsUpState_whenShadeExpanded_false() =
        testScope.runTest {
            val state by collectLastValue(underTest.statusBarHeadsUpState)
            val status by collectLastValue(underTest.statusBarHeadsUpStatus)

            // WHEN a row is pinned
            headsUpRepository.setNotifications(fakeHeadsUpRowRepository("key 0", isPinned = true))
            runCurrent()
            // AND the shade is expanded
            shadeTestUtil.setShadeExpansion(1.0f)
            // Needed if SceneContainer flag is off: `ShadeTestUtil.setShadeExpansion(1f)`
            // incorrectly causes `ShadeInteractor.isShadeFullyCollapsed` to emit `true`, when it
            // should emit `false`.
            kosmos.fakeShadeRepository.setLegacyShadeExpansion(1.0f)

            assertThat(state).isEqualTo(TopPinnedState.NothingPinned)
            assertThat(status!!.isPinned).isFalse()
        }

    @Test
    fun statusBarHeadsUpState_notificationsAreHidden_false() =
        testScope.runTest {
            val state by collectLastValue(underTest.statusBarHeadsUpState)
            val status by collectLastValue(underTest.statusBarHeadsUpStatus)

            // WHEN a row is pinned
            headsUpRepository.setNotifications(fakeHeadsUpRowRepository("key 0", isPinned = true))
            runCurrent()
            // AND the notifications are hidden
            keyguardViewStateRepository.areNotificationsFullyHidden.value = true

            assertThat(state).isEqualTo(TopPinnedState.NothingPinned)
            assertThat(status!!.isPinned).isFalse()
        }

    @Test
    fun statusBarHeadsUpState_onLockScreen_false() =
        testScope.runTest {
            val state by collectLastValue(underTest.statusBarHeadsUpState)
            val status by collectLastValue(underTest.statusBarHeadsUpStatus)

            // WHEN a row is pinned
            headsUpRepository.setNotifications(fakeHeadsUpRowRepository("key 0", isPinned = true))
            runCurrent()
            // AND the lock screen is shown
            keyguardTransitionRepository.emitInitialStepsFromOff(
                to = KeyguardState.LOCKSCREEN,
                testSetup = true,
            )

            assertThat(state).isEqualTo(TopPinnedState.NothingPinned)
            assertThat(status!!.isPinned).isFalse()
        }

    @Test
    fun statusBarHeadsUpState_onByPassLockScreen_true() =
        testScope.runTest {
            val state by collectLastValue(underTest.statusBarHeadsUpState)
            val status by collectLastValue(underTest.statusBarHeadsUpStatus)

            // WHEN a row is pinned
            headsUpRepository.setNotifications(fakeHeadsUpRowRepository("key 0", isPinned = true))
            runCurrent()
            // AND the lock screen is shown
            keyguardTransitionRepository.emitInitialStepsFromOff(
                to = KeyguardState.LOCKSCREEN,
                testSetup = true,
            )
            // AND bypass is enabled
            faceAuthRepository.isBypassEnabled.value = true

            assertThat(state).isInstanceOf(TopPinnedState.Pinned::class.java)
            assertThat(status!!.isPinned).isTrue()
        }

    @Test
    fun statusBarHeadsUpState_onByPassLockScreen_withoutNotifications_false() =
        testScope.runTest {
            val state by collectLastValue(underTest.statusBarHeadsUpState)
            val status by collectLastValue(underTest.statusBarHeadsUpStatus)

            // WHEN no pinned rows
            // AND the lock screen is shown
            keyguardTransitionRepository.emitInitialStepsFromOff(
                to = KeyguardState.LOCKSCREEN,
                testSetup = true,
            )
            // AND bypass is enabled
            faceAuthRepository.isBypassEnabled.value = true

            assertThat(state).isEqualTo(TopPinnedState.NothingPinned)
            assertThat(status!!.isPinned).isFalse()
        }

    private fun fakeHeadsUpRowRepository(key: String, isPinned: Boolean = false) =
        FakeHeadsUpRowRepository(key = key, isPinned = isPinned)
}
