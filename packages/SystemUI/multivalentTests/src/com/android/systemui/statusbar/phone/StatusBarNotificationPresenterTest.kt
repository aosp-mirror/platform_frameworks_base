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
package com.android.systemui.statusbar.phone

import android.app.Notification
import android.app.Notification.Builder
import android.app.PendingIntent
import android.app.StatusBarManager
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper
import android.testing.TestableLooper.RunWithLooper
import android.view.Display.DEFAULT_DISPLAY
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.InitController
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.runTest
import com.android.systemui.plugins.activityStarter
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.settings.FakeDisplayTracker
import com.android.systemui.shade.domain.interactor.panelExpansionInteractor
import com.android.systemui.shade.notificationShadeWindowView
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.commandQueue
import com.android.systemui.statusbar.lockscreenShadeTransitionController
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.domain.interactor.notificationAlertsInteractor
import com.android.systemui.statusbar.notification.dynamicPrivacyController
import com.android.systemui.statusbar.notification.headsup.headsUpManager
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptSuppressor
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionCondition
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionFilter
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionRefactor
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.stack.notificationStackScrollLayoutController
import com.android.systemui.statusbar.notification.visualInterruptionDecisionProvider
import com.android.systemui.statusbar.notificationLockscreenUserManager
import com.android.systemui.statusbar.notificationRemoteInputManager
import com.android.systemui.statusbar.notificationShadeWindowController
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.keyguardStateController
import com.android.systemui.statusbar.sysuiStatusBarStateController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
class StatusBarNotificationPresenterTest : SysuiTestCase() {
    private val kosmos: Kosmos =
        testKosmos().apply {
            whenever(notificationShadeWindowView.resources).thenReturn(mContext.resources)
            whenever(notificationStackScrollLayoutController.view).thenReturn(mock())
            whenever(notificationAlertsInteractor.areNotificationAlertsEnabled()).thenReturn(true)
            commandQueue = CommandQueue(mContext, FakeDisplayTracker(mContext))

            // override this controller with a mock, otherwise it would start some animators which
            // are not cleaned up after these tests
            lockscreenShadeTransitionController = mock()
        }

    // initiated by argumentCaptors later in the setup step, based on the flag states
    private var interruptSuppressor: NotificationInterruptSuppressor? = null
    private var alertsDisabledCondition: VisualInterruptionCondition? = null
    private var vrModeCondition: VisualInterruptionCondition? = null
    private var needsRedactionFilter: VisualInterruptionFilter? = null
    private var panelsDisabledCondition: VisualInterruptionCondition? = null

    private val commandQueue: CommandQueue = kosmos.commandQueue
    private val keyguardStateController: KeyguardStateController = kosmos.keyguardStateController
    private val notificationAlertsInteractor = kosmos.notificationAlertsInteractor
    private val visualInterruptionDecisionProvider = kosmos.visualInterruptionDecisionProvider

    private lateinit var underTest: StatusBarNotificationPresenter

    @Before
    fun setup() {
        underTest = createPresenter()
        if (VisualInterruptionRefactor.isEnabled) {
            verifyAndCaptureSuppressors()
        } else {
            verifyAndCaptureLegacySuppressor()
        }
    }

    @Test
    @DisableFlags(VisualInterruptionRefactor.FLAG_NAME)
    fun testInit_refactorDisabled() {
        assertThat(VisualInterruptionRefactor.isEnabled).isFalse()
        assertThat(alertsDisabledCondition).isNull()
        assertThat(vrModeCondition).isNull()
        assertThat(needsRedactionFilter).isNull()
        assertThat(panelsDisabledCondition).isNull()
        assertThat(interruptSuppressor).isNotNull()
    }

    @Test
    @EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
    fun testInit_refactorEnabled() {
        assertThat(VisualInterruptionRefactor.isEnabled).isTrue()
        assertThat(alertsDisabledCondition).isNotNull()
        assertThat(vrModeCondition).isNotNull()
        assertThat(needsRedactionFilter).isNotNull()
        assertThat(panelsDisabledCondition).isNotNull()
        assertThat(interruptSuppressor).isNull()
    }

    @Test
    @DisableFlags(VisualInterruptionRefactor.FLAG_NAME)
    fun testNoSuppressHeadsUp_default_refactorDisabled() {
        assertThat(interruptSuppressor!!.suppressAwakeHeadsUp(createNotificationEntry())).isFalse()
    }

    @Test
    @EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
    fun testNoSuppressHeadsUp_default_refactorEnabled() {
        assertThat(alertsDisabledCondition!!.shouldSuppress()).isFalse()
        assertThat(vrModeCondition!!.shouldSuppress()).isFalse()
        assertThat(needsRedactionFilter!!.shouldSuppress(createNotificationEntry())).isFalse()
        assertThat(alertsDisabledCondition!!.shouldSuppress()).isFalse()
    }

    @Test
    @DisableFlags(VisualInterruptionRefactor.FLAG_NAME)
    fun testSuppressHeadsUp_disabledStatusBar_refactorDisabled() {
        commandQueue.disable(
            /* displayId = */ DEFAULT_DISPLAY,
            /* flags = */ StatusBarManager.DISABLE_EXPAND,
            /* reason = */ 0,
            /* animate = */ false,
        )
        TestableLooper.get(this).processAllMessages()
        assertWithMessage("The panel should suppress heads up while disabled")
            .that(interruptSuppressor!!.suppressAwakeHeadsUp(createNotificationEntry()))
            .isTrue()
    }

    @Test
    @EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
    fun testSuppressHeadsUp_disabledStatusBar_refactorEnabled() {
        commandQueue.disable(
            /* displayId = */ DEFAULT_DISPLAY,
            /* flags = */ StatusBarManager.DISABLE_EXPAND,
            /* reason = */ 0,
            /* animate = */ false,
        )
        TestableLooper.get(this).processAllMessages()
        assertWithMessage("The panel should suppress heads up while disabled")
            .that(panelsDisabledCondition!!.shouldSuppress())
            .isTrue()
    }

    @Test
    @DisableFlags(VisualInterruptionRefactor.FLAG_NAME)
    fun testSuppressHeadsUp_disabledNotificationShade_refactorDisabled() {
        commandQueue.disable(
            /* displayId = */ DEFAULT_DISPLAY,
            /* flags = */ 0,
            /* reason = */ StatusBarManager.DISABLE2_NOTIFICATION_SHADE,
            /* animate = */ false,
        )
        TestableLooper.get(this).processAllMessages()
        assertWithMessage(
                "The panel should suppress interruptions while notification shade disabled"
            )
            .that(interruptSuppressor!!.suppressAwakeHeadsUp(createNotificationEntry()))
            .isTrue()
    }

    @Test
    @EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
    fun testSuppressHeadsUp_disabledNotificationShade_refactorEnabled() {
        commandQueue.disable(
            /* displayId = */ DEFAULT_DISPLAY,
            /* flags = */ 0,
            /* reason = */ StatusBarManager.DISABLE2_NOTIFICATION_SHADE,
            /* animate = */ false,
        )
        TestableLooper.get(this).processAllMessages()
        assertWithMessage(
                "The panel should suppress interruptions while notification shade disabled"
            )
            .that(panelsDisabledCondition!!.shouldSuppress())
            .isTrue()
    }

    @Test
    @EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
    fun testPanelsDisabledConditionSuppressesPeek() {
        val types: Set<VisualInterruptionType> = panelsDisabledCondition!!.types
        assertThat(types).contains(VisualInterruptionType.PEEK)
        assertThat(types)
            .containsNoneOf(VisualInterruptionType.BUBBLE, VisualInterruptionType.PULSE)
    }

    @Test
    @DisableFlags(VisualInterruptionRefactor.FLAG_NAME)
    fun testNoSuppressHeadsUp_FSI_nonOccludedKeyguard_refactorDisabled() {
        whenever(keyguardStateController.isShowing()).thenReturn(true)
        whenever(keyguardStateController.isOccluded()).thenReturn(false)
        assertThat(interruptSuppressor!!.suppressAwakeHeadsUp(createFsiNotificationEntry()))
            .isFalse()
    }

    @Test
    @EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
    fun testNoSuppressHeadsUp_FSI_nonOccludedKeyguard_refactorEnabled() {
        whenever(keyguardStateController.isShowing()).thenReturn(true)
        whenever(keyguardStateController.isOccluded()).thenReturn(false)
        assertThat(needsRedactionFilter!!.shouldSuppress(createFsiNotificationEntry())).isFalse()
        val types: Set<VisualInterruptionType> = needsRedactionFilter!!.types
        assertThat(types).contains(VisualInterruptionType.PEEK)
        assertThat(types)
            .containsNoneOf(VisualInterruptionType.BUBBLE, VisualInterruptionType.PULSE)
    }

    @Test
    @DisableFlags(VisualInterruptionRefactor.FLAG_NAME)
    fun testSuppressInterruptions_vrMode_refactorDisabled() {
        underTest.mVrMode = true
        assertWithMessage("Vr mode should suppress interruptions")
            .that(interruptSuppressor!!.suppressAwakeInterruptions(createNotificationEntry()))
            .isTrue()
    }

    @Test
    @EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
    fun testSuppressInterruptions_vrMode_refactorEnabled() {
        underTest.mVrMode = true
        assertWithMessage("Vr mode should suppress interruptions")
            .that(vrModeCondition!!.shouldSuppress())
            .isTrue()
        val types: Set<VisualInterruptionType> = vrModeCondition!!.types
        assertThat(types).contains(VisualInterruptionType.PEEK)
        assertThat(types).doesNotContain(VisualInterruptionType.PULSE)
        assertThat(types).contains(VisualInterruptionType.BUBBLE)
    }

    @Test
    @DisableFlags(VisualInterruptionRefactor.FLAG_NAME)
    fun testSuppressInterruptions_statusBarAlertsDisabled_refactorDisabled() {
        whenever(notificationAlertsInteractor.areNotificationAlertsEnabled()).thenReturn(false)
        assertWithMessage("When alerts aren't enabled, interruptions are suppressed")
            .that(interruptSuppressor!!.suppressInterruptions(createNotificationEntry()))
            .isTrue()
    }

    @Test
    @EnableFlags(VisualInterruptionRefactor.FLAG_NAME)
    fun testSuppressInterruptions_statusBarAlertsDisabled_refactorEnabled() {
        whenever(notificationAlertsInteractor.areNotificationAlertsEnabled()).thenReturn(false)
        assertWithMessage("When alerts aren't enabled, interruptions are suppressed")
            .that(alertsDisabledCondition!!.shouldSuppress())
            .isTrue()
        val types: Set<VisualInterruptionType> = alertsDisabledCondition!!.types
        assertThat(types).contains(VisualInterruptionType.PEEK)
        assertThat(types).contains(VisualInterruptionType.PULSE)
        assertThat(types).contains(VisualInterruptionType.BUBBLE)
    }

    @Test
    @EnableSceneContainer
    fun testExpandSensitiveNotification_onLockScreen_opensShade() =
        kosmos.runTest {
            // Given we are on the keyguard
            kosmos.sysuiStatusBarStateController.state = StatusBarState.KEYGUARD
            // And the device is locked
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )

            // When the user expands a sensitive Notification
            val row = createRow()
            val entry =
                row.entry.apply { setSensitive(/* sensitive= */ true, /* deviceSensitive= */ true) }

            underTest.onExpandClicked(entry, mock(), /* nowExpanded= */ true)

            // Then we open the locked shade
            verify(kosmos.lockscreenShadeTransitionController)
                // Explicit parameters to avoid issues with Kotlin default arguments in Mockito
                .goToLockedShade(row, true)
        }

    @Test
    @EnableSceneContainer
    fun testExpandSensitiveNotification_onLockedShade_showsBouncer() =
        kosmos.runTest {
            // Given we are on the locked shade
            kosmos.sysuiStatusBarStateController.state = StatusBarState.SHADE_LOCKED
            // And the device is locked
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )

            // When the user expands a sensitive Notification
            val entry =
                createRow().entry.apply {
                    setSensitive(/* sensitive= */ true, /* deviceSensitive= */ true)
                }
            underTest.onExpandClicked(entry, mock(), /* nowExpanded= */ true)

            // Then we show the bouncer
            verify(kosmos.activityStarter).dismissKeyguardThenExecute(any(), eq(null), eq(false))
        }

    private fun createPresenter(): StatusBarNotificationPresenter {
        val initController: InitController = InitController()
        return StatusBarNotificationPresenter(
                /* context = */ mContext,
                /* panel = */ mock(),
                kosmos.panelExpansionInteractor,
                /* quickSettingsController = */ mock(),
                kosmos.headsUpManager,
                kosmos.notificationShadeWindowView,
                kosmos.activityStarter,
                kosmos.notificationStackScrollLayoutController,
                kosmos.dozeScrimController,
                kosmos.notificationShadeWindowController,
                kosmos.dynamicPrivacyController,
                kosmos.keyguardStateController,
                kosmos.notificationAlertsInteractor,
                kosmos.lockscreenShadeTransitionController,
                kosmos.powerInteractor,
                kosmos.commandQueue,
                kosmos.notificationLockscreenUserManager,
                kosmos.sysuiStatusBarStateController,
                /* notifShadeEventSource = */ mock(),
                /* notificationMediaManager = */ mock(),
                /* notificationGutsManager = */ mock(),
                /* initController = */ initController,
                kosmos.visualInterruptionDecisionProvider,
                kosmos.notificationRemoteInputManager,
                /* remoteInputManagerCallback = */ mock(),
                /* notificationListContainer = */ mock(),
                kosmos.deviceUnlockedInteractor,
            )
            .also { initController.executePostInitTasks() }
    }

    private fun verifyAndCaptureSuppressors() {
        interruptSuppressor = null

        val conditionCaptor = argumentCaptor<VisualInterruptionCondition>()
        verify(visualInterruptionDecisionProvider, times(3)).addCondition(conditionCaptor.capture())

        val conditions: List<VisualInterruptionCondition> = conditionCaptor.allValues
        alertsDisabledCondition = conditions[0]
        vrModeCondition = conditions[1]
        panelsDisabledCondition = conditions[2]

        val needsRedactionFilterCaptor = argumentCaptor<VisualInterruptionFilter>()
        verify(visualInterruptionDecisionProvider).addFilter(needsRedactionFilterCaptor.capture())
        needsRedactionFilter = needsRedactionFilterCaptor.lastValue
    }

    private fun verifyAndCaptureLegacySuppressor() {
        alertsDisabledCondition = null
        vrModeCondition = null
        needsRedactionFilter = null
        panelsDisabledCondition = null

        val suppressorCaptor = argumentCaptor<NotificationInterruptSuppressor>()
        verify(visualInterruptionDecisionProvider).addLegacySuppressor(suppressorCaptor.capture())
        interruptSuppressor = suppressorCaptor.lastValue
    }

    private fun createRow(): ExpandableNotificationRow {
        val row: ExpandableNotificationRow = mock()
        val entry: NotificationEntry = createNotificationEntry()
        whenever(row.entry).thenReturn(entry)
        entry.row = row
        return row
    }

    private fun createNotificationEntry(): NotificationEntry =
        NotificationEntryBuilder()
            .setPkg("a")
            .setOpPkg("a")
            .setTag("a")
            .setNotification(Builder(mContext, "a").build())
            .build()

    private fun createFsiNotificationEntry(): NotificationEntry {
        val notification: Notification =
            Builder(mContext, "a")
                .setFullScreenIntent(mock<PendingIntent>(), /* highPriority= */ true)
                .build()
        return NotificationEntryBuilder()
            .setPkg("a")
            .setOpPkg("a")
            .setTag("a")
            .setNotification(notification)
            .build()
    }
}
