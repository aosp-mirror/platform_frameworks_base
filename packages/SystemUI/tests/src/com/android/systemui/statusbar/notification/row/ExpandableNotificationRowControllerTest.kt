/*
 * Copyright (c) 2022 The Android Open Source Project
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
 *
 */

package com.android.systemui.statusbar.notification.row

import android.app.Notification
import android.net.Uri
import android.os.UserHandle
import android.os.UserHandle.USER_ALL
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.PluginManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.SbnBuilder
import com.android.systemui.statusbar.SmartReplyController
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder
import com.android.systemui.statusbar.notification.collection.provider.NotificationDismissibilityProvider
import com.android.systemui.statusbar.notification.collection.render.FakeNodeController
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRowController.BUBBLES_SETTING_URI
import com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer
import com.android.systemui.statusbar.notification.stack.NotificationChildrenContainerLogger
import com.android.systemui.statusbar.notification.stack.NotificationListContainer
import com.android.systemui.statusbar.notification.stack.ui.view.NotificationRowStatsLogger
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.SmartReplyConstants
import com.android.systemui.statusbar.policy.dagger.RemoteInputViewSubcomponent
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.withArgCaptor
import com.android.systemui.util.time.SystemClock
import com.android.systemui.wmshell.BubblesManager
import java.util.Optional
import junit.framework.Assert
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class ExpandableNotificationRowControllerTest : SysuiTestCase() {

    private val appName = "MyApp"
    private val notifKey = "MyNotifKey"

    private val view: ExpandableNotificationRow = mock()
    private val activableNotificationViewController: ActivatableNotificationViewController = mock()
    private val rivSubComponentFactory: RemoteInputViewSubcomponent.Factory = mock()
    private val metricsLogger: MetricsLogger = mock()
    private val logBufferLogger = NotificationRowLogger(logcatLogBuffer(), logcatLogBuffer())
    private val listContainer: NotificationListContainer = mock()
    private val childrenContainer: NotificationChildrenContainer = mock()
    private val smartReplyConstants: SmartReplyConstants = mock()
    private val smartReplyController: SmartReplyController = mock()
    private val pluginManager: PluginManager = mock()
    private val systemClock: SystemClock = mock()
    private val keyguardBypassController: KeyguardBypassController = mock()
    private val groupMembershipManager: GroupMembershipManager = mock()
    private val groupExpansionManager: GroupExpansionManager = mock()
    private val rowContentBindStage: RowContentBindStage = mock()
    private val notifLogger: NotificationRowStatsLogger = mock()
    private val headsUpManager: HeadsUpManager = mock()
    private val onExpandClickListener: ExpandableNotificationRow.OnExpandClickListener = mock()
    private val statusBarStateController: StatusBarStateController = mock()
    private val gutsManager: NotificationGutsManager = mock()
    private val onUserInteractionCallback: OnUserInteractionCallback = mock()
    private val falsingManager: FalsingManager = mock()
    private val featureFlags: FeatureFlags = mock()
    private val peopleNotificationIdentifier: PeopleNotificationIdentifier = mock()
    private val bubblesManager: BubblesManager = mock()
    private val settingsController: NotificationSettingsController = mock()
    private val dragController: ExpandableNotificationRowDragController = mock()
    private val dismissibilityProvider: NotificationDismissibilityProvider = mock()
    private val statusBarService: IStatusBarService = mock()
    private lateinit var controller: ExpandableNotificationRowController

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        controller =
            ExpandableNotificationRowController(
                view,
                activableNotificationViewController,
                rivSubComponentFactory,
                metricsLogger,
                logBufferLogger,
                NotificationChildrenContainerLogger(logcatLogBuffer()),
                listContainer,
                smartReplyConstants,
                smartReplyController,
                pluginManager,
                systemClock,
                appName,
                notifKey,
                keyguardBypassController,
                groupMembershipManager,
                groupExpansionManager,
                rowContentBindStage,
                notifLogger,
                headsUpManager,
                onExpandClickListener,
                statusBarStateController,
                gutsManager,
                /*allowLongPress=*/ false,
                onUserInteractionCallback,
                falsingManager,
                featureFlags,
                peopleNotificationIdentifier,
                Optional.of(bubblesManager),
                settingsController,
                dragController,
                dismissibilityProvider,
                statusBarService
            )
        whenever(view.childrenContainer).thenReturn(childrenContainer)

        val notification = Notification.Builder(mContext).build()
        val sbn = SbnBuilder().setNotification(notification).build()
        whenever(view.entry).thenReturn(NotificationEntryBuilder().setSbn(sbn).build())
    }

    @After
    fun tearDown() {
        disallowTestableLooperAsMainThread()
    }

    @Test
    fun offerKeepInParent_parentDismissed() {
        whenever(view.isParentDismissed).thenReturn(true)

        Assert.assertTrue(controller.offerToKeepInParentForAnimation())
        verify(view).setKeepInParentForDismissAnimation(true)
    }

    @Test
    fun offerKeepInParent_parentNotDismissed() {
        Assert.assertFalse(controller.offerToKeepInParentForAnimation())
        verify(view, never()).setKeepInParentForDismissAnimation(anyBoolean())
    }

    @Test
    fun removeFromParent_keptForAnimation() {
        val parentView: ExpandableNotificationRow = mock()
        whenever(view.notificationParent).thenReturn(parentView)
        whenever(view.keepInParentForDismissAnimation()).thenReturn(true)

        Assert.assertTrue(controller.removeFromParentIfKeptForAnimation())
        verify(parentView).removeChildNotification(view)
    }

    @Test
    fun removeFromParent_notKeptForAnimation() {
        val parentView: ExpandableNotificationRow = mock()
        whenever(view.notificationParent).thenReturn(parentView)

        Assert.assertFalse(controller.removeFromParentIfKeptForAnimation())
        Mockito.verifyNoMoreInteractions(parentView)
    }

    @Test
    fun removeChild_whenTransfer() {
        val childView: ExpandableNotificationRow = mock()
        val childNodeController = FakeNodeController(childView)

        // GIVEN a child is removed for transfer
        controller.removeChild(childNodeController, /* isTransfer= */ true)

        // VERIFY the listContainer is not notified
        verify(childView).isChangingPosition = eq(true)
        verify(view).removeChildNotification(eq(childView))
        verify(listContainer, never()).notifyGroupChildRemoved(any(), any())
    }

    @Test
    fun removeChild_whenNotTransfer() {
        val childView: ExpandableNotificationRow = mock()
        val childNodeController = FakeNodeController(childView)

        // GIVEN a child is removed for real
        controller.removeChild(childNodeController, /* isTransfer= */ false)

        // VERIFY the listContainer is passed the childrenContainer for transient animations
        verify(childView, never()).isChangingPosition = any()
        verify(view).removeChildNotification(eq(childView))
        verify(listContainer).notifyGroupChildRemoved(eq(childView), eq(childrenContainer))
    }

    @Test
    fun registerSettingsListener_forBubbles() {
        controller.init(mock(NotificationEntry::class.java))
        val viewStateObserver = withArgCaptor {
            verify(view).addOnAttachStateChangeListener(capture())
        }
        viewStateObserver.onViewAttachedToWindow(view)
        verify(settingsController).addCallback(any(), any())
    }

    @Test
    fun unregisterSettingsListener_forBubbles() {
        controller.init(mock(NotificationEntry::class.java))
        val viewStateObserver = withArgCaptor {
            verify(view).addOnAttachStateChangeListener(capture())
        }
        viewStateObserver.onViewDetachedFromWindow(view)
        verify(settingsController).removeCallback(any(), any())
    }

    @Test
    fun settingsListener_invalidUri() {
        controller.mSettingsListener.onSettingChanged(Uri.EMPTY, view.entry.sbn.userId, "1")

        verify(view, never()).getPrivateLayout()
    }

    @Test
    fun settingsListener_invalidUserId() {
        controller.mSettingsListener.onSettingChanged(BUBBLES_SETTING_URI, -1000, "1")
        controller.mSettingsListener.onSettingChanged(BUBBLES_SETTING_URI, -1000, null)

        verify(view, never()).getPrivateLayout()
    }

    @Test
    fun settingsListener_validUserId() {
        val childView: NotificationContentView = mock()
        whenever(view.privateLayout).thenReturn(childView)

        controller.mSettingsListener.onSettingChanged(
            BUBBLES_SETTING_URI,
            view.entry.sbn.userId,
            "1"
        )
        verify(childView).setBubblesEnabledForUser(true)

        controller.mSettingsListener.onSettingChanged(
            BUBBLES_SETTING_URI,
            view.entry.sbn.userId,
            "9"
        )
        verify(childView).setBubblesEnabledForUser(false)
    }

    @Test
    fun settingsListener_userAll() {
        val childView: NotificationContentView = mock()
        whenever(view.privateLayout).thenReturn(childView)

        val notification = Notification.Builder(mContext).build()
        val sbn =
            SbnBuilder().setNotification(notification).setUser(UserHandle.of(USER_ALL)).build()
        whenever(view.entry)
            .thenReturn(
                NotificationEntryBuilder().setSbn(sbn).setUser(UserHandle.of(USER_ALL)).build()
            )

        controller.mSettingsListener.onSettingChanged(BUBBLES_SETTING_URI, 9, "1")
        verify(childView).setBubblesEnabledForUser(true)

        controller.mSettingsListener.onSettingChanged(BUBBLES_SETTING_URI, 1, "0")
        verify(childView).setBubblesEnabledForUser(false)
    }
}
