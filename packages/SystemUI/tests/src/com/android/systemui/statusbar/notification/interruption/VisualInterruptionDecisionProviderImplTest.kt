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

import android.Manifest.permission
import android.app.Notification.CATEGORY_ALARM
import android.app.Notification.CATEGORY_CAR_EMERGENCY
import android.app.Notification.CATEGORY_CAR_WARNING
import android.app.Notification.CATEGORY_EVENT
import android.app.Notification.CATEGORY_REMINDER
import android.app.NotificationManager
import android.content.pm.PackageManager.PERMISSION_DENIED
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.BUBBLE
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PEEK
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PULSE
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.whenever
import java.util.Optional

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
class VisualInterruptionDecisionProviderImplTest : VisualInterruptionDecisionProviderTestBase() {
    override val provider by lazy {
        VisualInterruptionDecisionProviderImpl(
            ambientDisplayConfiguration,
            batteryController,
            deviceProvisionedController,
            eventLog,
            globalSettings,
            headsUpManager,
            keyguardNotificationVisibilityProvider,
            keyguardStateController,
            newLogger,
            mainHandler,
            powerManager,
            statusBarStateController,
            systemClock,
            uiEventLogger,
            userTracker,
            avalancheProvider,
            systemSettings,
            packageManager,
            Optional.of(bubbles),
            context,
            notificationManager,
            settingsInteractor
        )
    }

    @Test
    fun testNothingCondition_suppressesNothing() {
        withCondition(TestCondition(types = emptySet()) { true }) {
            assertPeekNotSuppressed()
            assertPulseNotSuppressed()
            assertBubbleNotSuppressed()
            assertFsiNotSuppressed()
        }
    }

    @Test
    fun testNothingFilter_suppressesNothing() {
        withFilter(TestFilter(types = emptySet()) { true }) {
            assertPeekNotSuppressed()
            assertPulseNotSuppressed()
            assertBubbleNotSuppressed()
            assertFsiNotSuppressed()
        }
    }

    // Avalanche tests are in VisualInterruptionDecisionProviderImplTest
    // instead of VisualInterruptionDecisionProviderTestBase
    // because avalanche code is based on the suppression refactor.

    private fun getAvalancheSuppressor() : AvalancheSuppressor {
        return AvalancheSuppressor(
            avalancheProvider, systemClock, settingsInteractor, packageManager,
            uiEventLogger, context, notificationManager, logger, systemSettings
        )
    }

    @Test
    fun testAvalancheFilter_suppress_hasNotSeenEdu_showEduHun() {
        setAllowedEmergencyPkg(false)
        whenever(avalancheProvider.timeoutMs).thenReturn(20)
        whenever(avalancheProvider.startTime).thenReturn(whenAgo(10))

        val avalancheSuppressor = getAvalancheSuppressor()
        avalancheSuppressor.hasSeenEdu = false

        withFilter(avalancheSuppressor) {
            ensurePeekState()
            assertShouldNotHeadsUp(
                buildEntry {
                    importance = NotificationManager.IMPORTANCE_HIGH
                    whenMs = whenAgo(5)
                }
            )
        }
        verify(notificationManager, times(1)).notify(anyInt(), any())
    }

    @Test
    fun testAvalancheFilter_suppress_hasSeenEduHun_doNotShowEduHun() {
        setAllowedEmergencyPkg(false)
        whenever(avalancheProvider.timeoutMs).thenReturn(20)
        whenever(avalancheProvider.startTime).thenReturn(whenAgo(10))

        val avalancheSuppressor = getAvalancheSuppressor()
        avalancheSuppressor.hasSeenEdu = true

        withFilter(avalancheSuppressor) {
            ensurePeekState()
            assertShouldNotHeadsUp(
                buildEntry {
                    importance = NotificationManager.IMPORTANCE_HIGH
                    whenMs = whenAgo(5)
                }
            )
        }
        verify(notificationManager, times(0)).notify(anyInt(), any())
    }

    @Test
    fun testAvalancheFilter_duringAvalanche_allowConversationFromAfterEvent() {
        avalancheProvider.startTime = whenAgo(10)

        withFilter(
            getAvalancheSuppressor()
        ) {
            ensurePeekState()
            assertShouldHeadsUp(
                buildEntry {
                    importance = NotificationManager.IMPORTANCE_HIGH
                    isConversation = true
                    isImportantConversation = false
                    whenMs = whenAgo(5)
                }
            )
        }
    }

    @Test
    fun testAvalancheFilter_duringAvalanche_suppressConversationFromBeforeEvent() {
        avalancheProvider.startTime = whenAgo(10)

        withFilter(
            getAvalancheSuppressor()
        ) {
            ensurePeekState()
            assertShouldNotHeadsUp(
                buildEntry {
                    importance = NotificationManager.IMPORTANCE_DEFAULT
                    isConversation = true
                    isImportantConversation = false
                    whenMs = whenAgo(15)
                }
            )
        }
    }

    @Test
    fun testAvalancheFilter_duringAvalanche_allowHighPriorityConversation() {
        avalancheProvider.startTime = whenAgo(10)

        withFilter(
            getAvalancheSuppressor()
        ) {
            ensurePeekState()
            assertShouldHeadsUp(
                buildEntry {
                    importance = NotificationManager.IMPORTANCE_HIGH
                    isImportantConversation = true
                }
            )
        }
    }

    @Test
    fun testAvalancheFilter_duringAvalanche_allowCall() {
        avalancheProvider.startTime = whenAgo(10)

        withFilter(
            getAvalancheSuppressor()
        ) {
            ensurePeekState()
            assertShouldHeadsUp(
                buildEntry {
                    importance = NotificationManager.IMPORTANCE_HIGH
                    isCall = true
                }
            )
        }
    }

    @Test
    fun testAvalancheFilter_duringAvalanche_allowCategoryReminder() {
        avalancheProvider.startTime = whenAgo(10)

        withFilter(
            getAvalancheSuppressor()
        ) {
            ensurePeekState()
            assertShouldHeadsUp(
                buildEntry {
                    importance = NotificationManager.IMPORTANCE_HIGH
                    category = CATEGORY_REMINDER
                }
            )
        }
    }

    @Test
    fun testAvalancheFilter_duringAvalanche_allowCategoryEvent() {
        avalancheProvider.startTime = whenAgo(10)

        withFilter(
            getAvalancheSuppressor()
        ) {
            ensurePeekState()
            assertShouldHeadsUp(
                buildEntry {
                    importance = NotificationManager.IMPORTANCE_HIGH
                    category = CATEGORY_EVENT
                }
            )
        }
    }

    @Test
    fun testAvalancheFilter_duringAvalanche_allowCategoryAlarm() {
        avalancheProvider.startTime = whenAgo(10)

        withFilter(
            getAvalancheSuppressor()
        ) {
            ensurePeekState()
            assertShouldHeadsUp(
                buildEntry {
                    importance = NotificationManager.IMPORTANCE_HIGH
                    category = CATEGORY_ALARM
                }
            )
        }
    }

    @Test
    fun testAvalancheFilter_duringAvalanche_allowCategoryCarEmergency() {
        avalancheProvider.startTime = whenAgo(10)

        withFilter(
            getAvalancheSuppressor()
        ) {
            ensurePeekState()
            assertShouldHeadsUp(
                buildEntry {
                    importance = NotificationManager.IMPORTANCE_HIGH
                    category = CATEGORY_CAR_EMERGENCY

                }
            )
        }
    }

    @Test
    fun testAvalancheFilter_duringAvalanche_allowCategoryCarWarning() {
        avalancheProvider.startTime = whenAgo(10)

        withFilter(
            getAvalancheSuppressor()
        ) {
            ensurePeekState()
            assertShouldHeadsUp(
                buildEntry {
                    importance = NotificationManager.IMPORTANCE_HIGH
                    category = CATEGORY_CAR_WARNING
                }
            )
        }
    }

    @Test
    fun testAvalancheFilter_duringAvalanche_allowFsi() {
        avalancheProvider.startTime = whenAgo(10)

        withFilter(
            getAvalancheSuppressor()
        ) {
            assertFsiNotSuppressed()
        }
    }

    @Test
    fun testAvalancheFilter_duringAvalanche_allowColorized() {
        avalancheProvider.startTime = whenAgo(10)

        withFilter(
            getAvalancheSuppressor()
        ) {
            ensurePeekState()
            assertShouldHeadsUp(
                buildEntry {
                    importance = NotificationManager.IMPORTANCE_HIGH
                    isColorized = true
                }
            )
        }
    }

    private fun setAllowedEmergencyPkg(allow: Boolean) {
        `when`(
            packageManager.checkPermission(
                org.mockito.Mockito.eq(permission.RECEIVE_EMERGENCY_BROADCAST),
                anyString()
            )
        ).thenReturn(if (allow) PERMISSION_GRANTED else PERMISSION_DENIED)
    }

    @Test
    fun testAvalancheFilter_duringAvalanche_allowEmergency() {
        avalancheProvider.startTime = whenAgo(10)

        setAllowedEmergencyPkg(true)

        withFilter(
            getAvalancheSuppressor()
        ) {
            ensurePeekState()
            assertShouldHeadsUp(
                buildEntry {
                    importance = NotificationManager.IMPORTANCE_HIGH
                }
            )
        }
    }


    @Test
    fun testPeekCondition_suppressesOnlyPeek() {
        withCondition(TestCondition(types = setOf(PEEK)) { true }) {
            assertPeekSuppressed()
            assertPulseNotSuppressed()
            assertBubbleNotSuppressed()
            assertFsiNotSuppressed()
        }
    }

    @Test
    fun testPeekFilter_suppressesOnlyPeek() {
        withFilter(TestFilter(types = setOf(PEEK)) { true }) {
            assertPeekSuppressed()
            assertPulseNotSuppressed()
            assertBubbleNotSuppressed()
            assertFsiNotSuppressed()
        }
    }

    @Test
    fun testPulseCondition_suppressesOnlyPulse() {
        withCondition(TestCondition(types = setOf(PULSE)) { true }) {
            assertPeekNotSuppressed()
            assertPulseSuppressed()
            assertBubbleNotSuppressed()
            assertFsiNotSuppressed()
        }
    }

    @Test
    fun testPulseFilter_suppressesOnlyPulse() {
        withFilter(TestFilter(types = setOf(PULSE)) { true }) {
            assertPeekNotSuppressed()
            assertPulseSuppressed()
            assertBubbleNotSuppressed()
            assertFsiNotSuppressed()
        }
    }

    @Test
    fun testBubbleCondition_suppressesOnlyBubble() {
        withCondition(TestCondition(types = setOf(BUBBLE)) { true }) {
            assertPeekNotSuppressed()
            assertPulseNotSuppressed()
            assertBubbleSuppressed()
            assertFsiNotSuppressed()
        }
    }

    @Test
    fun testBubbleFilter_suppressesOnlyBubble() {
        withFilter(TestFilter(types = setOf(BUBBLE)) { true }) {
            assertPeekNotSuppressed()
            assertPulseNotSuppressed()
            assertBubbleSuppressed()
            assertFsiNotSuppressed()
        }
    }

    @Test
    fun testCondition_differentState() {
        ensurePeekState()
        val entry = buildPeekEntry()

        var stateShouldSuppress = false
        withCondition(TestCondition(types = setOf(PEEK)) { stateShouldSuppress }) {
            assertShouldHeadsUp(entry)

            stateShouldSuppress = true
            assertShouldNotHeadsUp(entry)

            stateShouldSuppress = false
            assertShouldHeadsUp(entry)
        }
    }

    @Test
    fun testFilter_differentState() {
        ensurePeekState()
        val entry = buildPeekEntry()

        var stateShouldSuppress = false
        withFilter(TestFilter(types = setOf(PEEK)) { stateShouldSuppress }) {
            assertShouldHeadsUp(entry)

            stateShouldSuppress = true
            assertShouldNotHeadsUp(entry)

            stateShouldSuppress = false
            assertShouldHeadsUp(entry)
        }
    }

    @Test
    fun testFilter_differentNotif() {
        ensurePeekState()

        val suppressedEntry = buildPeekEntry()
        val unsuppressedEntry = buildPeekEntry()

        withFilter(TestFilter(types = setOf(PEEK)) { it == suppressedEntry }) {
            assertShouldNotHeadsUp(suppressedEntry)
            assertShouldHeadsUp(unsuppressedEntry)
        }
    }

    private fun assertPeekSuppressed() {
        ensurePeekState()
        assertShouldNotHeadsUp(buildPeekEntry())
    }

    private fun assertPeekNotSuppressed() {
        ensurePeekState()
        assertShouldHeadsUp(buildPeekEntry())
    }

    private fun assertPulseSuppressed() {
        ensurePulseState()
        assertShouldNotHeadsUp(buildPulseEntry())
    }

    private fun assertPulseNotSuppressed() {
        ensurePulseState()
        assertShouldHeadsUp(buildPulseEntry())
    }

    private fun assertBubbleSuppressed() {
        ensureBubbleState()
        assertShouldNotBubble(buildBubbleEntry())
    }

    private fun assertBubbleNotSuppressed() {
        ensureBubbleState()
        assertShouldBubble(buildBubbleEntry())
    }

    private fun assertFsiNotSuppressed() {
        forEachFsiState { assertShouldFsi(buildFsiEntry()) }
    }

    private fun withCondition(condition: VisualInterruptionCondition, block: () -> Unit) {
        provider.addCondition(condition)
        block()
        provider.removeCondition(condition)
    }

    private fun withFilter(filter: VisualInterruptionFilter, block: () -> Unit) {
        provider.addFilter(filter)
        block()
        provider.removeFilter(filter)
    }

    private class TestCondition(
        types: Set<VisualInterruptionType>,
        val onShouldSuppress: () -> Boolean
    ) : VisualInterruptionCondition(types = types, reason = "test condition") {
        override fun shouldSuppress(): Boolean = onShouldSuppress()
    }

    private class TestFilter(
        types: Set<VisualInterruptionType>,
        val onShouldSuppress: (NotificationEntry) -> Boolean = { true }
    ) : VisualInterruptionFilter(types = types, reason = "test filter") {
        override fun shouldSuppress(entry: NotificationEntry) = onShouldSuppress(entry)
    }
}
