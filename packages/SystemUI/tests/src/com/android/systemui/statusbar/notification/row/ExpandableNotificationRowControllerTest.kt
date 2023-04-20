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

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.PluginManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationMediaManager
import com.android.systemui.statusbar.SmartReplyController
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager
import com.android.systemui.statusbar.notification.logging.NotificationLogger
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier
import com.android.systemui.statusbar.notification.stack.NotificationListContainer
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.SmartReplyConstants
import com.android.systemui.statusbar.policy.dagger.RemoteInputViewSubcomponent
import com.android.systemui.util.mockito.mock
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
import org.mockito.Mockito.never
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
    private val logBufferLogger: NotificationRowLogger = mock()
    private val listContainer: NotificationListContainer = mock()
    private val mediaManager: NotificationMediaManager = mock()
    private val smartReplyConstants: SmartReplyConstants = mock()
    private val smartReplyController: SmartReplyController = mock()
    private val pluginManager: PluginManager = mock()
    private val systemClock: SystemClock = mock()
    private val keyguardBypassController: KeyguardBypassController = mock()
    private val groupMembershipManager: GroupMembershipManager = mock()
    private val groupExpansionManager: GroupExpansionManager = mock()
    private val rowContentBindStage: RowContentBindStage = mock()
    private val notifLogger: NotificationLogger = mock()
    private val headsUpManager: HeadsUpManager = mock()
    private val onExpandClickListener: ExpandableNotificationRow.OnExpandClickListener = mock()
    private val statusBarStateController: StatusBarStateController = mock()
    private val gutsManager: NotificationGutsManager = mock()
    private val onUserInteractionCallback: OnUserInteractionCallback = mock()
    private val falsingManager: FalsingManager = mock()
    private val falsingCollector: FalsingCollector = mock()
    private val featureFlags: FeatureFlags = mock()
    private val peopleNotificationIdentifier: PeopleNotificationIdentifier = mock()
    private val bubblesManager: BubblesManager = mock()
    private val dragController: ExpandableNotificationRowDragController = mock()
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
                listContainer,
                mediaManager,
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
                falsingCollector,
                featureFlags,
                peopleNotificationIdentifier,
                Optional.of(bubblesManager),
                dragController
            )
    }

    @After
    fun tearDown() {
        disallowTestableLooperAsMainThread()
    }

    @Test
    fun offerKeepInParent_parentDismissed() {
        whenever(featureFlags.isEnabled(Flags.NOTIFICATION_GROUP_DISMISSAL_ANIMATION))
            .thenReturn(true)
        whenever(view.isParentDismissed).thenReturn(true)

        Assert.assertTrue(controller.offerToKeepInParentForAnimation())
        Mockito.verify(view).setKeepInParentForDismissAnimation(true)
    }

    @Test
    fun offerKeepInParent_parentNotDismissed() {
        whenever(featureFlags.isEnabled(Flags.NOTIFICATION_GROUP_DISMISSAL_ANIMATION))
            .thenReturn(true)

        Assert.assertFalse(controller.offerToKeepInParentForAnimation())
        Mockito.verify(view, never()).setKeepInParentForDismissAnimation(anyBoolean())
    }

    @Test
    fun removeFromParent_keptForAnimation() {
        val parentView: ExpandableNotificationRow = mock()
        whenever(view.notificationParent).thenReturn(parentView)
        whenever(view.keepInParentForDismissAnimation()).thenReturn(true)

        Assert.assertTrue(controller.removeFromParentIfKeptForAnimation())
        Mockito.verify(parentView).removeChildNotification(view)
    }

    @Test
    fun removeFromParent_notKeptForAnimation() {
        val parentView: ExpandableNotificationRow = mock()
        whenever(view.notificationParent).thenReturn(parentView)

        Assert.assertFalse(controller.removeFromParentIfKeptForAnimation())
        Mockito.verifyNoMoreInteractions(parentView)
    }
}
