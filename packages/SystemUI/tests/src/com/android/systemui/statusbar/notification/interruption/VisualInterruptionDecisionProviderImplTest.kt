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
import android.app.Notification.CATEGORY_EVENT
import android.app.Notification.CATEGORY_REMINDER
import android.app.NotificationManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.BUBBLE
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PEEK
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PULSE
import java.util.Optional
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.anyString
import org.mockito.Mockito.`when`

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
            Optional.of(bubbles)
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

    @Test
    fun testAvalancheFilter_duringAvalanche_allowConversationFromAfterEvent() {
        avalancheProvider.startTime = whenAgo(10)

        withFilter(
            AvalancheSuppressor(avalancheProvider, systemClock, systemSettings, packageManager)
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
            AvalancheSuppressor(avalancheProvider, systemClock, systemSettings, packageManager)
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
            AvalancheSuppressor(avalancheProvider, systemClock, systemSettings, packageManager)
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
            AvalancheSuppressor(avalancheProvider, systemClock, systemSettings, packageManager)
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
            AvalancheSuppressor(avalancheProvider, systemClock, systemSettings, packageManager)
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
            AvalancheSuppressor(avalancheProvider, systemClock, systemSettings, packageManager)
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
    fun testAvalancheFilter_duringAvalanche_allowFsi() {
        avalancheProvider.startTime = whenAgo(10)

        withFilter(
            AvalancheSuppressor(avalancheProvider, systemClock, systemSettings, packageManager)
        ) {
            assertFsiNotSuppressed()
        }
    }

    @Test
    fun testAvalancheFilter_duringAvalanche_allowColorized() {
        avalancheProvider.startTime = whenAgo(10)

        withFilter(
            AvalancheSuppressor(avalancheProvider, systemClock, systemSettings, packageManager)
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

    @Test
    fun testAvalancheFilter_duringAvalanche_allowEmergency() {
        avalancheProvider.startTime = whenAgo(10)

        `when`(
            packageManager.checkPermission(
                org.mockito.Mockito.eq(permission.RECEIVE_EMERGENCY_BROADCAST),
                anyString()
            )
        ).thenReturn(PERMISSION_GRANTED)

        withFilter(
            AvalancheSuppressor(avalancheProvider, systemClock, systemSettings, packageManager)
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
