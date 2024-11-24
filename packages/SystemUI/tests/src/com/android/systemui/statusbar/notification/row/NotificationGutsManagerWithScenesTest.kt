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
package com.android.systemui.statusbar.notification.row

import android.R
import android.app.AppOpsManager
import android.app.INotificationManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.content.pm.launcherApps
import android.graphics.Color
import android.os.Binder
import android.os.fakeExecutorHandler
import android.os.userManager
import android.provider.Settings
import android.service.notification.NotificationListenerService.Ranking
import android.testing.TestableLooper.RunWithLooper
import android.util.ArraySet
import android.view.View
import android.view.accessibility.accessibilityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.metricsLogger
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.statusbar.statusBarService
import com.android.systemui.SysuiTestCase
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.people.widget.PeopleSpaceWidgetManager
import com.android.systemui.plugins.activityStarter
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin
import com.android.systemui.plugins.statusbar.statusBarStateController
import com.android.systemui.power.domain.interactor.PowerInteractorFactory.create
import com.android.systemui.scene.data.repository.WindowRootViewVisibilityRepository
import com.android.systemui.scene.domain.interactor.WindowRootViewVisibilityInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.UserContextProvider
import com.android.systemui.shade.shadeControllerSceneImpl
import com.android.systemui.statusbar.NotificationEntryHelper
import com.android.systemui.statusbar.NotificationPresenter
import com.android.systemui.statusbar.notification.AssistantFeedbackController
import com.android.systemui.statusbar.notification.NotificationActivityStarter
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider
import com.android.systemui.statusbar.notification.domain.interactor.activeNotificationsInteractor
import com.android.systemui.statusbar.notification.headsup.headsUpManager
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier
import com.android.systemui.statusbar.notification.stack.NotificationListContainer
import com.android.systemui.statusbar.notificationLockscreenUserManager
import com.android.systemui.statusbar.policy.deviceProvisionedController
import com.android.systemui.testKosmos
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.wmshell.BubblesManager
import java.util.Optional
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Tests for [NotificationGutsManager] with the scene container enabled. */
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
@EnableSceneContainer
class NotificationGutsManagerWithScenesTest : SysuiTestCase() {
    private val testNotificationChannel =
        NotificationChannel(
            TEST_CHANNEL_ID,
            TEST_CHANNEL_ID,
            NotificationManager.IMPORTANCE_DEFAULT,
        )

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val javaAdapter = JavaAdapter(testScope.backgroundScope)
    private val executor = kosmos.fakeExecutor
    private val handler = kosmos.fakeExecutorHandler
    private lateinit var helper: NotificationTestHelper
    private lateinit var gutsManager: NotificationGutsManager
    private lateinit var windowRootViewVisibilityInteractor: WindowRootViewVisibilityInteractor

    private val metricsLogger = kosmos.metricsLogger
    private val deviceProvisionedController = kosmos.deviceProvisionedController
    private val accessibilityManager = kosmos.accessibilityManager
    private val mBarService = kosmos.statusBarService
    private val launcherApps = kosmos.launcherApps
    private val shadeController = kosmos.shadeControllerSceneImpl
    private val notificationLockscreenUserManager = kosmos.notificationLockscreenUserManager
    private val statusBarStateController = kosmos.statusBarStateController
    private val headsUpManager = kosmos.headsUpManager
    private val activityStarter = kosmos.activityStarter
    private val userManager = kosmos.userManager
    private val activeNotificationsInteractor = kosmos.activeNotificationsInteractor
    private val sceneInteractor = kosmos.sceneInteractor

    @Mock private lateinit var onUserInteractionCallback: OnUserInteractionCallback
    @Mock private lateinit var presenter: NotificationPresenter
    @Mock private lateinit var notificationActivityStarter: NotificationActivityStarter
    @Mock private lateinit var notificationListContainer: NotificationListContainer
    @Mock
    private lateinit var onSettingsClickListener: NotificationGutsManager.OnSettingsClickListener
    @Mock private lateinit var highPriorityProvider: HighPriorityProvider
    @Mock private lateinit var notificationManager: INotificationManager
    @Mock private lateinit var shortcutManager: ShortcutManager
    @Mock private lateinit var channelEditorDialogController: ChannelEditorDialogController
    @Mock private lateinit var peopleNotificationIdentifier: PeopleNotificationIdentifier
    @Mock private lateinit var contextTracker: UserContextProvider
    @Mock private lateinit var bubblesManager: BubblesManager
    @Mock private lateinit var peopleSpaceWidgetManager: PeopleSpaceWidgetManager
    @Mock private lateinit var assistantFeedbackController: AssistantFeedbackController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        allowTestableLooperAsMainThread()
        helper = NotificationTestHelper(mContext, mDependency)
        whenever(accessibilityManager.isTouchExplorationEnabled).thenReturn(false)
        windowRootViewVisibilityInteractor =
            WindowRootViewVisibilityInteractor(
                testScope.backgroundScope,
                WindowRootViewVisibilityRepository(mBarService, executor),
                FakeKeyguardRepository(),
                headsUpManager,
                create().powerInteractor,
                activeNotificationsInteractor,
            ) {
                sceneInteractor
            }
        gutsManager =
            NotificationGutsManager(
                mContext,
                handler,
                handler,
                javaAdapter,
                accessibilityManager,
                highPriorityProvider,
                notificationManager,
                userManager,
                peopleSpaceWidgetManager,
                launcherApps,
                shortcutManager,
                channelEditorDialogController,
                contextTracker,
                assistantFeedbackController,
                Optional.of(bubblesManager),
                UiEventLoggerFake(),
                onUserInteractionCallback,
                shadeController,
                windowRootViewVisibilityInteractor,
                notificationLockscreenUserManager,
                statusBarStateController,
                mBarService,
                deviceProvisionedController,
                metricsLogger,
                headsUpManager,
                activityStarter,
            )
        gutsManager.setUpWithPresenter(
            presenter,
            notificationListContainer,
            onSettingsClickListener,
        )
        gutsManager.setNotificationActivityStarter(notificationActivityStarter)
        gutsManager.start()
    }

    @Test
    fun testOpenAndCloseGuts() {
        val guts = spy(NotificationGuts(mContext))
        whenever(guts.post(any())).thenAnswer { invocation: InvocationOnMock ->
            handler.post((invocation.arguments[0] as Runnable))
            null
        }

        // Test doesn't support animation since the guts view is not attached.
        doNothing()
            .whenever(guts)
            .openControls(any<Int>(), any<Int>(), any<Boolean>(), any<Runnable>())
        val realRow = createTestNotificationRow()
        val menuItem = createTestMenuItem(realRow)
        val row = spy(realRow)
        whenever(row!!.windowToken).thenReturn(Binder())
        whenever(row.guts).thenReturn(guts)
        Assert.assertTrue(gutsManager.openGutsInternal(row, 0, 0, menuItem))
        assertEquals(View.INVISIBLE.toLong(), guts.visibility.toLong())
        executor.runAllReady()
        verify(guts).openControls(any<Int>(), any<Int>(), any<Boolean>(), any<Runnable>())
        verify(headsUpManager).setGutsShown(realRow!!.entry, true)
        assertEquals(View.VISIBLE.toLong(), guts.visibility.toLong())
        gutsManager.closeAndSaveGuts(false, false, true, 0, 0, false)
        verify(guts)
            .closeControls(any<Boolean>(), any<Boolean>(), any<Int>(), any<Int>(), any<Boolean>())
        verify(row, times(1)).setGutsView(any())
        executor.runAllReady()
        verify(headsUpManager).setGutsShown(realRow.entry, false)
    }

    @Test
    fun testLockscreenShadeVisible_visible_gutsNotClosed() {
        // First, start out lockscreen or shade as not visible
        setIsLockscreenOrShadeVisible(false)
        testScope.testScheduler.runCurrent()
        val guts = mock<NotificationGuts>()
        gutsManager.exposedGuts = guts

        // WHEN the lockscreen or shade becomes visible
        setIsLockscreenOrShadeVisible(true)
        testScope.testScheduler.runCurrent()

        // THEN the guts are not closed
        verify(guts, never()).removeCallbacks(any())
        verify(guts, never())
            .closeControls(any<Boolean>(), any<Boolean>(), any<Int>(), any<Int>(), any<Boolean>())
    }

    @Test
    fun testLockscreenShadeVisible_notVisible_gutsClosed() {
        // First, start out lockscreen or shade as visible
        setIsLockscreenOrShadeVisible(true)
        testScope.testScheduler.runCurrent()
        val guts = mock<NotificationGuts>()
        gutsManager.exposedGuts = guts

        // WHEN the lockscreen or shade is no longer visible
        setIsLockscreenOrShadeVisible(false)
        testScope.testScheduler.runCurrent()

        // THEN the guts are closed
        verify(guts).removeCallbacks(anyOrNull())
        verify(guts)
            .closeControls(
                /* leavebehinds= */ eq(true),
                /* controls= */ eq(true),
                /* x= */ any<Int>(),
                /* y= */ any<Int>(),
                /* force= */ eq(true),
            )
    }

    @Test
    fun testLockscreenShadeVisible_notVisible_listContainerReset() {
        // First, start out lockscreen or shade as visible
        setIsLockscreenOrShadeVisible(true)
        testScope.testScheduler.runCurrent()

        // WHEN the lockscreen or shade is no longer visible
        setIsLockscreenOrShadeVisible(false)
        testScope.testScheduler.runCurrent()

        // THEN the list container is reset
        verify(notificationListContainer).resetExposedMenuView(any<Boolean>(), any<Boolean>())
    }

    @Test
    fun testChangeDensityOrFontScale() {
        val guts = spy(NotificationGuts(mContext))
        whenever(guts.post(any())).thenAnswer { invocation: InvocationOnMock ->
            handler.post((invocation.arguments[0] as Runnable))
            null
        }

        // Test doesn't support animation since the guts view is not attached.
        doNothing()
            .whenever(guts)
            .openControls(any<Int>(), any<Int>(), any<Boolean>(), any<Runnable>())
        val realRow = createTestNotificationRow()
        val menuItem = createTestMenuItem(realRow)
        val row = spy(realRow)
        whenever(row!!.windowToken).thenReturn(Binder())
        whenever(row.guts).thenReturn(guts)
        doNothing().whenever(row).ensureGutsInflated()
        val realEntry = realRow!!.entry
        val entry = spy(realEntry)
        whenever(entry.row).thenReturn(row)
        whenever(entry.getGuts()).thenReturn(guts)
        Assert.assertTrue(gutsManager.openGutsInternal(row, 0, 0, menuItem))
        executor.runAllReady()
        verify(guts).openControls(any<Int>(), any<Int>(), any<Boolean>(), any<Runnable>())

        // called once by mGutsManager.bindGuts() in mGutsManager.openGuts()
        verify(row).setGutsView(any())
        row.onDensityOrFontScaleChanged()
        gutsManager.onDensityOrFontScaleChanged(entry)
        executor.runAllReady()
        gutsManager.closeAndSaveGuts(false, false, false, 0, 0, false)
        verify(guts)
            .closeControls(any<Boolean>(), any<Boolean>(), any<Int>(), any<Int>(), any<Boolean>())

        // called again by mGutsManager.bindGuts(), in mGutsManager.onDensityOrFontScaleChanged()
        verify(row, times(2)).setGutsView(any())
    }

    @Test
    fun testAppOpsSettingsIntent_camera() {
        val ops = ArraySet<Int>()
        ops.add(AppOpsManager.OP_CAMERA)
        gutsManager.startAppOpsSettingsActivity("", 0, ops, mock<ExpandableNotificationRow>())
        val captor = argumentCaptor<Intent>()
        verify(notificationActivityStarter, times(1))
            .startNotificationGutsIntent(captor.capture(), any<Int>(), any())
        assertEquals(Intent.ACTION_MANAGE_APP_PERMISSIONS, captor.lastValue.action)
    }

    @Test
    fun testAppOpsSettingsIntent_mic() {
        val ops = ArraySet<Int>()
        ops.add(AppOpsManager.OP_RECORD_AUDIO)
        gutsManager.startAppOpsSettingsActivity("", 0, ops, mock<ExpandableNotificationRow>())
        val captor = argumentCaptor<Intent>()
        verify(notificationActivityStarter, times(1))
            .startNotificationGutsIntent(captor.capture(), any<Int>(), any())
        assertEquals(Intent.ACTION_MANAGE_APP_PERMISSIONS, captor.lastValue.action)
    }

    @Test
    fun testAppOpsSettingsIntent_camera_mic() {
        val ops = ArraySet<Int>()
        ops.add(AppOpsManager.OP_CAMERA)
        ops.add(AppOpsManager.OP_RECORD_AUDIO)
        gutsManager.startAppOpsSettingsActivity("", 0, ops, mock<ExpandableNotificationRow>())
        val captor = argumentCaptor<Intent>()
        verify(notificationActivityStarter, times(1))
            .startNotificationGutsIntent(captor.capture(), any<Int>(), any())
        assertEquals(Intent.ACTION_MANAGE_APP_PERMISSIONS, captor.lastValue.action)
    }

    @Test
    fun testAppOpsSettingsIntent_overlay() {
        val ops = ArraySet<Int>()
        ops.add(AppOpsManager.OP_SYSTEM_ALERT_WINDOW)
        gutsManager.startAppOpsSettingsActivity("", 0, ops, mock<ExpandableNotificationRow>())
        val captor = argumentCaptor<Intent>()
        verify(notificationActivityStarter, times(1))
            .startNotificationGutsIntent(captor.capture(), any<Int>(), any())
        assertEquals(Settings.ACTION_MANAGE_APP_OVERLAY_PERMISSION, captor.lastValue.action)
    }

    @Test
    fun testAppOpsSettingsIntent_camera_mic_overlay() {
        val ops = ArraySet<Int>()
        ops.add(AppOpsManager.OP_CAMERA)
        ops.add(AppOpsManager.OP_RECORD_AUDIO)
        ops.add(AppOpsManager.OP_SYSTEM_ALERT_WINDOW)
        gutsManager.startAppOpsSettingsActivity("", 0, ops, mock<ExpandableNotificationRow>())
        val captor = argumentCaptor<Intent>()
        verify(notificationActivityStarter, times(1))
            .startNotificationGutsIntent(captor.capture(), any<Int>(), any())
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, captor.lastValue.action)
    }

    @Test
    fun testAppOpsSettingsIntent_camera_overlay() {
        val ops = ArraySet<Int>()
        ops.add(AppOpsManager.OP_CAMERA)
        ops.add(AppOpsManager.OP_SYSTEM_ALERT_WINDOW)
        gutsManager.startAppOpsSettingsActivity("", 0, ops, mock<ExpandableNotificationRow>())
        val captor = argumentCaptor<Intent>()
        verify(notificationActivityStarter, times(1))
            .startNotificationGutsIntent(captor.capture(), any<Int>(), any())
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, captor.lastValue.action)
    }

    @Test
    fun testAppOpsSettingsIntent_mic_overlay() {
        val ops = ArraySet<Int>()
        ops.add(AppOpsManager.OP_RECORD_AUDIO)
        ops.add(AppOpsManager.OP_SYSTEM_ALERT_WINDOW)
        gutsManager.startAppOpsSettingsActivity("", 0, ops, mock<ExpandableNotificationRow>())
        val captor = argumentCaptor<Intent>()
        verify(notificationActivityStarter, times(1))
            .startNotificationGutsIntent(captor.capture(), any<Int>(), any())
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, captor.lastValue.action)
    }

    @Test
    @Throws(Exception::class)
    fun testInitializeNotificationInfoView_highPriority() {
        val notificationInfoView = mock<NotificationInfo>()
        val row = spy(helper.createRow())
        val entry = row.entry
        NotificationEntryHelper.modifyRanking(entry)
            .setUserSentiment(Ranking.USER_SENTIMENT_NEGATIVE)
            .setImportance(NotificationManager.IMPORTANCE_HIGH)
            .build()
        whenever(row.getIsNonblockable()).thenReturn(false)
        whenever(highPriorityProvider.isHighPriority(entry)).thenReturn(true)
        val statusBarNotification = entry.sbn
        gutsManager.initializeNotificationInfo(row, notificationInfoView)
        verify(notificationInfoView)
            .bindNotification(
                any<PackageManager>(),
                any<INotificationManager>(),
                eq(onUserInteractionCallback),
                eq(channelEditorDialogController),
                eq(statusBarNotification.packageName),
                any<NotificationChannel>(),
                eq(entry),
                any<NotificationInfo.OnSettingsClickListener>(),
                any<NotificationInfo.OnAppSettingsClickListener>(),
                any<UiEventLogger>(),
                eq(true),
                eq(false),
                eq(true), /* wasShownHighPriority */
                eq(assistantFeedbackController),
                any<MetricsLogger>(),
            )
    }

    @Test
    @Throws(Exception::class)
    fun testInitializeNotificationInfoView_PassesAlongProvisionedState() {
        val notificationInfoView = mock<NotificationInfo>()
        val row = spy(helper.createRow())
        NotificationEntryHelper.modifyRanking(row.entry)
            .setUserSentiment(Ranking.USER_SENTIMENT_NEGATIVE)
            .build()
        whenever(row.getIsNonblockable()).thenReturn(false)
        val statusBarNotification = row.entry.sbn
        val entry = row.entry
        gutsManager.initializeNotificationInfo(row, notificationInfoView)
        verify(notificationInfoView)
            .bindNotification(
                any<PackageManager>(),
                any<INotificationManager>(),
                eq(onUserInteractionCallback),
                eq(channelEditorDialogController),
                eq(statusBarNotification.packageName),
                any<NotificationChannel>(),
                eq(entry),
                any<NotificationInfo.OnSettingsClickListener>(),
                any<NotificationInfo.OnAppSettingsClickListener>(),
                any<UiEventLogger>(),
                eq(true),
                eq(false),
                eq(false), /* wasShownHighPriority */
                eq(assistantFeedbackController),
                any<MetricsLogger>(),
            )
    }

    @Test
    @Throws(Exception::class)
    fun testInitializeNotificationInfoView_withInitialAction() {
        val notificationInfoView = mock<NotificationInfo>()
        val row = spy(helper.createRow())
        NotificationEntryHelper.modifyRanking(row.entry)
            .setUserSentiment(Ranking.USER_SENTIMENT_NEGATIVE)
            .build()
        whenever(row.getIsNonblockable()).thenReturn(false)
        val statusBarNotification = row.entry.sbn
        val entry = row.entry
        gutsManager.initializeNotificationInfo(row, notificationInfoView)
        verify(notificationInfoView)
            .bindNotification(
                any<PackageManager>(),
                any<INotificationManager>(),
                eq(onUserInteractionCallback),
                eq(channelEditorDialogController),
                eq(statusBarNotification.packageName),
                any<NotificationChannel>(),
                eq(entry),
                any<NotificationInfo.OnSettingsClickListener>(),
                any<NotificationInfo.OnAppSettingsClickListener>(),
                any<UiEventLogger>(),
                eq(true),
                eq(false),
                eq(false), /* wasShownHighPriority */
                eq(assistantFeedbackController),
                any<MetricsLogger>(),
            )
    }

    private fun createTestNotificationRow(): ExpandableNotificationRow? {
        val nb =
            Notification.Builder(mContext, testNotificationChannel.id)
                .setContentTitle("foo")
                .setColorized(true)
                .setColor(Color.RED)
                .setFlag(Notification.FLAG_CAN_COLORIZE, true)
                .setSmallIcon(R.drawable.sym_def_app_icon)
        return try {
            val row = helper.createRow(nb.build())
            NotificationEntryHelper.modifyRanking(row.entry)
                .setChannel(testNotificationChannel)
                .build()
            row
        } catch (e: Exception) {
            org.junit.Assert.fail()
            null
        }
    }

    private fun setIsLockscreenOrShadeVisible(isVisible: Boolean) {
        val key =
            if (isVisible) {
                Scenes.Lockscreen
            } else {
                Scenes.Bouncer
            }
        sceneInteractor.changeScene(key, "test")
        sceneInteractor.setTransitionState(
            MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(key))
        )
        testScope.runCurrent()
    }

    private fun createTestMenuItem(
        row: ExpandableNotificationRow?
    ): NotificationMenuRowPlugin.MenuItem {
        val menuRow: NotificationMenuRowPlugin =
            NotificationMenuRow(mContext, peopleNotificationIdentifier)
        menuRow.createMenu(row, row!!.entry.sbn)
        val menuItem = menuRow.getLongpressMenuItem(mContext)
        Assert.assertNotNull(menuItem)
        return menuItem
    }

    companion object {
        private const val TEST_CHANNEL_ID = "NotificationManagerServiceTestChannelId"
    }
}
