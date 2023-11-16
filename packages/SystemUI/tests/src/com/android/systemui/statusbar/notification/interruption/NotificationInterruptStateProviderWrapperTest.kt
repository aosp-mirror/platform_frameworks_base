/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.interruption

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider.FullScreenIntentDecision
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider.FullScreenIntentDecision.FSI_DEVICE_NOT_INTERACTIVE
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider.FullScreenIntentDecision.NO_FSI_NOT_IMPORTANT_ENOUGH
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider.FullScreenIntentDecision.NO_FSI_SUPPRESSED_BY_DND
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider.FullScreenIntentDecision.NO_FSI_SUPPRESSED_ONLY_BY_DND
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderWrapper.DecisionImpl
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderWrapper.FullScreenIntentDecisionImpl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class NotificationInterruptStateProviderWrapperTest : VisualInterruptionDecisionProviderTestBase() {
    override val provider by lazy {
        NotificationInterruptStateProviderWrapper(
            NotificationInterruptStateProviderImpl(
                powerManager,
                ambientDisplayConfiguration,
                batteryController,
                statusBarStateController,
                keyguardStateController,
                headsUpManager,
                oldLogger,
                mainHandler,
                flags,
                keyguardNotificationVisibilityProvider,
                uiEventLogger,
                userTracker,
                deviceProvisionedController,
                systemClock,
                globalSettings,
                eventLog
            )
        )
    }

    // Tests of internals of the wrapper:

    @Test
    fun decisionOfTrue() {
        assertTrue(DecisionImpl.of(true).shouldInterrupt)
    }

    @Test
    fun decisionOfFalse() {
        assertFalse(DecisionImpl.of(false).shouldInterrupt)
    }

    @Test
    fun decisionOfTrueInterned() {
        assertEquals(DecisionImpl.of(true), DecisionImpl.of(true))
    }

    @Test
    fun decisionOfFalseInterned() {
        assertEquals(DecisionImpl.of(false), DecisionImpl.of(false))
    }

    @Test
    fun fullScreenIntentDecisionShouldInterrupt() {
        makeFsiDecision(FSI_DEVICE_NOT_INTERACTIVE).let {
            assertTrue(it.shouldInterrupt)
            assertFalse(it.wouldInterruptWithoutDnd)
        }
    }

    @Test
    fun fullScreenIntentDecisionShouldNotInterrupt() {
        makeFsiDecision(NO_FSI_NOT_IMPORTANT_ENOUGH).let {
            assertFalse(it.shouldInterrupt)
            assertFalse(it.wouldInterruptWithoutDnd)
        }
    }

    @Test
    fun fullScreenIntentDecisionWouldInterruptWithoutDnd() {
        makeFsiDecision(NO_FSI_SUPPRESSED_ONLY_BY_DND).let {
            assertFalse(it.shouldInterrupt)
            assertTrue(it.wouldInterruptWithoutDnd)
        }
    }

    @Test
    fun fullScreenIntentDecisionWouldNotInterruptEvenWithoutDnd() {
        makeFsiDecision(NO_FSI_SUPPRESSED_BY_DND).let {
            assertFalse(it.shouldInterrupt)
            assertFalse(it.wouldInterruptWithoutDnd)
        }
    }

    private fun makeFsiDecision(originalDecision: FullScreenIntentDecision) =
        FullScreenIntentDecisionImpl(NotificationEntryBuilder().build(), originalDecision)
}
