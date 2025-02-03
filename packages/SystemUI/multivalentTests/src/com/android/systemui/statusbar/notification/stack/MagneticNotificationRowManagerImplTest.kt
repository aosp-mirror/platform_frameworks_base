/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack

import android.os.testableLooper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlagsClassic
import com.android.systemui.haptics.msdl.fakeMSDLPlayer
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.NotificationTestHelper
import com.android.systemui.statusbar.notification.stack.MagneticNotificationRowManagerImpl.State
import com.android.systemui.testKosmos
import com.google.android.msdl.data.model.MSDLToken
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class MagneticNotificationRowManagerImplTest : SysuiTestCase() {

    private val featureFlags = FakeFeatureFlagsClassic()
    private val kosmos = testKosmos()
    private val childrenNumber = 5
    private val stackScrollLayout = mock<NotificationStackScrollLayout>()
    private val sectionsManager = mock<NotificationSectionsManager>()
    private val swipedMultiplier = 0.5f
    private val msdlPlayer = kosmos.fakeMSDLPlayer

    private val underTest = kosmos.magneticNotificationRowManagerImpl

    private lateinit var notificationTestHelper: NotificationTestHelper
    private lateinit var children: NotificationChildrenContainer

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        notificationTestHelper =
            NotificationTestHelper(mContext, mDependency, kosmos.testableLooper, featureFlags)
        children = notificationTestHelper.createGroup(childrenNumber).childrenContainer
    }

    @Test
    fun setMagneticAndRoundableTargets_onIdle_targetsGetSet() =
        kosmos.testScope.runTest {
            // WHEN the targets are set for a row
            val row = children.attachedChildren[childrenNumber / 2]
            setTargetsForRow(row)

            // THEN the magnetic and roundable targets are defined and the state is TARGETS_SET
            assertThat(underTest.currentState).isEqualTo(State.TARGETS_SET)
            assertThat(underTest.currentMagneticListeners.isNotEmpty()).isTrue()
            assertThat(underTest.currentRoundableTargets).isNotNull()
        }

    @Test
    fun setMagneticRowTranslation_whenTargetsAreSet_startsPulling() =
        kosmos.testScope.runTest {
            // GIVEN targets are set
            val row = children.attachedChildren[childrenNumber / 2]
            setTargetsForRow(row)

            // WHEN setting a translation for the swiped row
            underTest.setMagneticRowTranslation(row, translation = 100f)

            // THEN the state moves to PULLING
            assertThat(underTest.currentState).isEqualTo(State.PULLING)
        }

    @Test
    fun setMagneticRowTranslation_whenIdle_doesNotSetMagneticTranslation() =
        kosmos.testScope.runTest {
            // GIVEN an IDLE state
            // WHEN setting a translation for the swiped row
            val row = children.attachedChildren[childrenNumber / 2]
            underTest.setMagneticRowTranslation(row, translation = 100f)

            // THEN no magnetic translations are set
            val canSetMagneticTranslation =
                underTest.setMagneticRowTranslation(row, translation = 100f)
            assertThat(canSetMagneticTranslation).isFalse()
        }

    @Test
    fun setMagneticRowTranslation_whenRowIsNotSwiped_doesNotSetMagneticTranslation() =
        kosmos.testScope.runTest {
            // GIVEN that targets are set
            val row = children.attachedChildren[childrenNumber / 2]
            setTargetsForRow(row)

            // WHEN setting a translation for a row that is not being swiped
            val differentRow = children.attachedChildren[childrenNumber / 2 - 1]
            val canSetMagneticTranslation =
                underTest.setMagneticRowTranslation(differentRow, translation = 100f)

            // THEN no magnetic translations are set
            assertThat(canSetMagneticTranslation).isFalse()
        }

    @Test
    fun setMagneticRowTranslation_belowThreshold_whilePulling_setsMagneticTranslations() =
        kosmos.testScope.runTest {
            // GIVEN a threshold of 100 px
            val threshold = 100f
            underTest.setSwipeThresholdPx(threshold)

            // GIVEN that targets are set and the rows are being pulled
            val row = children.attachedChildren[childrenNumber / 2]
            setTargetsForRow(row)
            underTest.setMagneticRowTranslation(row, translation = 100f)

            // WHEN setting a translation that will fall below the threshold
            val translation = threshold / swipedMultiplier - 50f
            underTest.setMagneticRowTranslation(row, translation)

            // THEN the targets continue to be pulled and translations are set
            assertThat(underTest.currentState).isEqualTo(State.PULLING)
            assertThat(row.translation).isEqualTo(swipedMultiplier * translation)
        }

    @Test
    fun setMagneticRowTranslation_aboveThreshold_whilePulling_detachesMagneticTargets() =
        kosmos.testScope.runTest {
            // GIVEN a threshold of 100 px
            val threshold = 100f
            underTest.setSwipeThresholdPx(threshold)

            // GIVEN that targets are set and the rows are being pulled
            val row = children.attachedChildren[childrenNumber / 2]
            setTargetsForRow(row)
            underTest.setMagneticRowTranslation(row, translation = 100f)

            // WHEN setting a translation that will fall above the threshold
            val translation = threshold / swipedMultiplier + 50f
            underTest.setMagneticRowTranslation(row, translation)

            // THEN the swiped view detaches and the correct detach haptics play
            assertThat(underTest.currentState).isEqualTo(State.DETACHED)
            assertThat(msdlPlayer.latestTokenPlayed).isEqualTo(MSDLToken.SWIPE_THRESHOLD_INDICATOR)
        }

    @Test
    fun setMagneticRowTranslation_whileDetached_setsTranslationAndStaysDetached() =
        kosmos.testScope.runTest {
            // GIVEN that the swiped view has been detached
            val row = children.attachedChildren[childrenNumber / 2]
            setDetachedState(row)

            // WHEN setting a new translation
            val translation = 300f
            underTest.setMagneticRowTranslation(row, translation)

            // THEN the swiped view continues to be detached
            assertThat(underTest.currentState).isEqualTo(State.DETACHED)
        }

    @Test
    fun onMagneticInteractionEnd_whilePulling_goesToIdle() =
        kosmos.testScope.runTest {
            // GIVEN targets are set
            val row = children.attachedChildren[childrenNumber / 2]
            setTargetsForRow(row)

            // WHEN setting a translation for the swiped row
            underTest.setMagneticRowTranslation(row, translation = 100f)

            // WHEN the interaction ends on the row
            underTest.onMagneticInteractionEnd(row, velocity = null)

            // THEN the state resets
            assertThat(underTest.currentState).isEqualTo(State.IDLE)
        }

    @Test
    fun onMagneticInteractionEnd_whileDetached_goesToIdle() =
        kosmos.testScope.runTest {
            // GIVEN the swiped row is detached
            val row = children.attachedChildren[childrenNumber / 2]
            setDetachedState(row)

            // WHEN the interaction ends on the row
            underTest.onMagneticInteractionEnd(row, velocity = null)

            // THEN the state resets
            assertThat(underTest.currentState).isEqualTo(State.IDLE)
        }

    private fun setDetachedState(row: ExpandableNotificationRow) {
        val threshold = 100f
        underTest.setSwipeThresholdPx(threshold)

        // Set the pulling state
        setTargetsForRow(row)
        underTest.setMagneticRowTranslation(row, translation = 100f)

        // Set a translation that will fall above the threshold
        val translation = threshold / swipedMultiplier + 50f
        underTest.setMagneticRowTranslation(row, translation)

        assertThat(underTest.currentState).isEqualTo(State.DETACHED)
    }

    private fun setTargetsForRow(row: ExpandableNotificationRow) {
        underTest.setMagneticAndRoundableTargets(row, stackScrollLayout, sectionsManager)
    }
}
