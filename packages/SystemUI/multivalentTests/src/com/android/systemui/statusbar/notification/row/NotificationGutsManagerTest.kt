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
 * limitations under the License
 */
@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.row

import android.R
import android.app.AppOpsManager
import android.app.INotificationManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutManager
import android.graphics.Color
import android.os.Binder
import android.os.UserManager
import android.os.fakeExecutorHandler
import android.platform.test.flag.junit.FlagsParameterization
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.testing.TestableLooper.RunWithLooper
import android.util.ArraySet
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.statusbar.IStatusBarService
import com.android.systemui.SysuiTestCase
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.people.widget.PeopleSpaceWidgetManager
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin.MenuItem
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.domain.interactor.PowerInteractorFactory.create
import com.android.systemui.scene.data.repository.Idle
import com.android.systemui.scene.data.repository.WindowRootViewVisibilityRepository
import com.android.systemui.scene.data.repository.setSceneTransition
import com.android.systemui.scene.domain.interactor.WindowRootViewVisibilityInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.settings.UserContextProvider
import com.android.systemui.shade.ShadeController
import com.android.systemui.statusbar.NotificationEntryHelper
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.NotificationPresenter
import com.android.systemui.statusbar.notification.AssistantFeedbackController
import com.android.systemui.statusbar.notification.NotificationActivityStarter
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider
import com.android.systemui.statusbar.notification.domain.interactor.activeNotificationsInteractor
import com.android.systemui.statusbar.notification.headsup.HeadsUpManager
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier
import com.android.systemui.statusbar.notification.stack.NotificationListContainer
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.testKosmos
import com.android.systemui.util.kotlin.JavaAdapter
import com.android.systemui.wmshell.BubblesManager
import java.util.Optional
import kotlin.test.assertNotNull
import kotlin.test.fail
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.invocation.InvocationOnMock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.spy
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

/** Tests for [NotificationGutsManager]. */
@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@RunWithLooper
class NotificationGutsManagerTest(flags: FlagsParameterization) : SysuiTestCase() {
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

    @get:Rule val rule: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var metricsLogger: MetricsLogger
    @Mock private lateinit var onUserInteractionCallback: OnUserInteractionCallback
    @Mock private lateinit var presenter: NotificationPresenter
    @Mock private lateinit var notificationActivityStarter: NotificationActivityStarter
    @Mock private lateinit var notificationListContainer: NotificationListContainer
    @Mock
    private lateinit var onSettingsClickListener: NotificationGutsManager.OnSettingsClickListener
    @Mock private lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock private lateinit var accessibilityManager: AccessibilityManager
    @Mock private lateinit var highPriorityProvider: HighPriorityProvider
    @Mock private lateinit var iNotificationManager: INotificationManager
    @Mock private lateinit var barService: IStatusBarService
    @Mock private lateinit var launcherApps: LauncherApps
    @Mock private lateinit var shortcutManager: ShortcutManager
    @Mock private lateinit var channelEditorDialogController: ChannelEditorDialogController
    @Mock private lateinit var peopleNotificationIdentifier: PeopleNotificationIdentifier
    @Mock private lateinit var contextTracker: UserContextProvider
    @Mock private lateinit var bubblesManager: BubblesManager
    @Mock private lateinit var shadeController: ShadeController
    @Mock private lateinit var peopleSpaceWidgetManager: PeopleSpaceWidgetManager
    @Mock private lateinit var assistantFeedbackController: AssistantFeedbackController
    @Mock private lateinit var notificationLockscreenUserManager: NotificationLockscreenUserManager
    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var headsUpManager: HeadsUpManager
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var userManager: UserManager

    private lateinit var windowRootViewVisibilityInteractor: WindowRootViewVisibilityInteractor

    companion object {
        private const val TEST_CHANNEL_ID = "NotificationManagerServiceTestChannelId"

        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf(
                    android.app.Flags.FLAG_NOTIFICATION_CLASSIFICATION_UI
                )
                .andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Before
    fun setUp() {
        allowTestableLooperAsMainThread()
        helper = NotificationTestHelper(mContext, mDependency)
        whenever(accessibilityManager.isTouchExplorationEnabled).thenReturn(false)

        windowRootViewVisibilityInteractor =
            WindowRootViewVisibilityInteractor(
                testScope.backgroundScope,
                WindowRootViewVisibilityRepository(barService, executor),
                FakeKeyguardRepository(),
                headsUpManager,
                create().powerInteractor,
                kosmos.activeNotificationsInteractor,
            ) {
                kosmos.sceneInteractor
            }

        gutsManager =
            NotificationGutsManager(
                mContext,
                handler,
                handler,
                javaAdapter,
                accessibilityManager,
                highPriorityProvider,
                iNotificationManager,
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
                barService,
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
            handler.post(((invocation.arguments[0] as Runnable)))
            null
        }

        // Test doesn't support animation since the guts view is not attached.
        doNothing().whenever(guts).openControls(anyInt(), anyInt(), anyBoolean(), any())

        val realRow = createTestNotificationRow()
        val menuItem = createTestMenuItem(realRow)

        val row = spy(realRow)
        whenever(row.windowToken).thenReturn(Binder())
        whenever(row.guts).thenReturn(guts)

        assertTrue(gutsManager.openGutsInternal(row, 0, 0, menuItem))
        assertEquals(View.INVISIBLE.toLong(), guts.visibility.toLong())
        executor.runAllReady()
        verify(guts).openControls(anyInt(), anyInt(), anyBoolean(), any<Runnable>())
        verify(headsUpManager).setGutsShown(realRow.entry, true)

        assertEquals(View.VISIBLE.toLong(), guts.visibility.toLong())
        gutsManager.closeAndSaveGuts(false, false, true, 0, 0, false)

        verify(guts).closeControls(anyBoolean(), anyBoolean(), anyInt(), anyInt(), anyBoolean())
        verify(row, times(1)).setGutsView(any<MenuItem>())
        executor.runAllReady()
        verify(headsUpManager).setGutsShown(realRow.entry, false)
    }

    @Test
    fun testLockscreenShadeVisible_visible_gutsNotClosed() =
        testScope.runTest {
            // First, start out lockscreen or shade as not visible
            windowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(false)
            runCurrent()

            val guts: NotificationGuts = mock()
            gutsManager.exposedGuts = guts

            // WHEN the lockscreen or shade becomes visible
            windowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true)
            runCurrent()

            // THEN the guts are not closed
            verify(guts, never()).removeCallbacks(any())
            verify(guts, never())
                .closeControls(anyBoolean(), anyBoolean(), anyInt(), anyInt(), anyBoolean())
        }

    @Test
    @DisableSceneContainer
    fun testLockscreenShadeVisible_notVisible_gutsClosed() =
        testScope.runTest {
            // First, start out lockscreen or shade as visible
            windowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true)
            runCurrent()

            val guts: NotificationGuts = mock()
            gutsManager.exposedGuts = guts

            // WHEN the lockscreen or shade is no longer visible
            windowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(false)
            runCurrent()

            // THEN the guts are closed
            verify(guts).removeCallbacks(null)
            verify(guts)
                .closeControls(
                    /* leavebehinds = */ eq(true),
                    /* controls = */ eq(true),
                    /* x = */ anyInt(),
                    /* y = */ anyInt(),
                    /* force = */ eq(true),
                )
        }

    @Test
    @EnableSceneContainer
    fun testShadeVisible_notVisible_gutsClosed() =
        testScope.runTest {
            // First, start with shade as visible
            kosmos.setSceneTransition(Idle(Scenes.Shade))
            runCurrent()

            val guts: NotificationGuts = mock()
            gutsManager.exposedGuts = guts

            // WHEN the shade is no longer visible
            kosmos.setSceneTransition(Idle(Scenes.Gone))
            runCurrent()

            // THEN the guts are closed
            verify(guts).removeCallbacks(null)
            verify(guts)
                .closeControls(
                    /* leavebehinds = */ eq(true),
                    /* controls = */ eq(true),
                    /* x = */ anyInt(),
                    /* y = */ anyInt(),
                    /* force = */ eq(true),
                )
        }

    @Test
    @DisableSceneContainer
    fun testLockscreenShadeVisible_notVisible_listContainerReset() =
        testScope.runTest {
            // First, start out lockscreen or shade as visible
            windowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true)
            runCurrent()
            clearInvocations(notificationListContainer)

            // WHEN the lockscreen or shade is no longer visible
            windowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(false)
            runCurrent()

            // THEN the list container is reset
            verify(notificationListContainer).resetExposedMenuView(anyBoolean(), anyBoolean())
        }

    @Test
    @EnableSceneContainer
    fun testShadeVisible_notVisible_listContainerReset() =
        testScope.runTest {
            // First, start with shade as visible
            kosmos.setSceneTransition(Idle(Scenes.Shade))
            runCurrent()
            clearInvocations(notificationListContainer)

            // WHEN the shade is no longer visible
            kosmos.setSceneTransition(Idle(Scenes.Gone))
            runCurrent()

            // THEN the list container is reset
            verify(notificationListContainer).resetExposedMenuView(anyBoolean(), anyBoolean())
        }

    @Test
    fun testChangeDensityOrFontScale() {
        val guts = spy(NotificationGuts(mContext))
        whenever(guts.post(any())).thenAnswer { invocation: InvocationOnMock ->
            handler.post(((invocation.arguments[0] as Runnable)))
            null
        }

        // Test doesn't support animation since the guts view is not attached.
        doNothing().whenever(guts).openControls(anyInt(), anyInt(), anyBoolean(), any<Runnable>())

        val realRow = createTestNotificationRow()
        val menuItem = createTestMenuItem(realRow)

        val row = spy(realRow)

        whenever(row.windowToken).thenReturn(Binder())
        whenever(row.guts).thenReturn(guts)
        doNothing().whenever(row).ensureGutsInflated()

        val realEntry = realRow.entry
        val entry = spy(realEntry)

        whenever(entry.row).thenReturn(row)
        whenever(entry.guts).thenReturn(guts)

        assertTrue(gutsManager.openGutsInternal(row, 0, 0, menuItem))
        executor.runAllReady()
        verify(guts).openControls(anyInt(), anyInt(), anyBoolean(), any<Runnable>())

        // called once by mGutsManager.bindGuts() in mGutsManager.openGuts()
        verify(row).setGutsView(any<MenuItem>())

        row.onDensityOrFontScaleChanged()
        gutsManager.onDensityOrFontScaleChanged(entry)

        executor.runAllReady()

        gutsManager.closeAndSaveGuts(false, false, false, 0, 0, false)

        verify(guts).closeControls(anyBoolean(), anyBoolean(), anyInt(), anyInt(), anyBoolean())

        // called again by mGutsManager.bindGuts(), in mGutsManager.onDensityOrFontScaleChanged()
        verify(row, times(2)).setGutsView(any<MenuItem>())
    }

    @Test
    fun testAppOpsSettingsIntent_camera() {
        val row = createTestNotificationRow()
        val ops = ArraySet<Int>()
        ops.add(AppOpsManager.OP_CAMERA)
        gutsManager.startAppOpsSettingsActivity("", 0, ops, row)
        val captor = argumentCaptor<Intent>()
        verify(notificationActivityStarter, times(1))
            .startNotificationGutsIntent(captor.capture(), anyInt(), eq(row))
        assertEquals(Intent.ACTION_MANAGE_APP_PERMISSIONS, captor.lastValue.action)
    }

    @Test
    fun testAppOpsSettingsIntent_mic() {
        val row = createTestNotificationRow()
        val ops = ArraySet<Int>()
        ops.add(AppOpsManager.OP_RECORD_AUDIO)
        gutsManager.startAppOpsSettingsActivity("", 0, ops, row)
        val captor = argumentCaptor<Intent>()
        verify(notificationActivityStarter, times(1))
            .startNotificationGutsIntent(captor.capture(), anyInt(), eq(row))
        assertEquals(Intent.ACTION_MANAGE_APP_PERMISSIONS, captor.lastValue.action)
    }

    @Test
    fun testAppOpsSettingsIntent_camera_mic() {
        val row = createTestNotificationRow()
        val ops = ArraySet<Int>()
        ops.add(AppOpsManager.OP_CAMERA)
        ops.add(AppOpsManager.OP_RECORD_AUDIO)
        gutsManager.startAppOpsSettingsActivity("", 0, ops, row)
        val captor = argumentCaptor<Intent>()
        verify(notificationActivityStarter, times(1))
            .startNotificationGutsIntent(captor.capture(), anyInt(), eq(row))
        assertEquals(Intent.ACTION_MANAGE_APP_PERMISSIONS, captor.lastValue.action)
    }

    @Test
    fun testAppOpsSettingsIntent_overlay() {
        val row = createTestNotificationRow()
        val ops = ArraySet<Int>()
        ops.add(AppOpsManager.OP_SYSTEM_ALERT_WINDOW)
        gutsManager.startAppOpsSettingsActivity("", 0, ops, row)
        val captor = argumentCaptor<Intent>()
        verify(notificationActivityStarter, times(1))
            .startNotificationGutsIntent(captor.capture(), anyInt(), eq(row))
        assertEquals(Settings.ACTION_MANAGE_APP_OVERLAY_PERMISSION, captor.lastValue.action)
    }

    @Test
    fun testAppOpsSettingsIntent_camera_mic_overlay() {
        val row = createTestNotificationRow()
        val ops = ArraySet<Int>()
        ops.add(AppOpsManager.OP_CAMERA)
        ops.add(AppOpsManager.OP_RECORD_AUDIO)
        ops.add(AppOpsManager.OP_SYSTEM_ALERT_WINDOW)
        gutsManager.startAppOpsSettingsActivity("", 0, ops, row)
        val captor = argumentCaptor<Intent>()
        verify(notificationActivityStarter, times(1))
            .startNotificationGutsIntent(captor.capture(), anyInt(), eq(row))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, captor.lastValue.action)
    }

    @Test
    fun testAppOpsSettingsIntent_camera_overlay() {
        val row = createTestNotificationRow()
        val ops = ArraySet<Int>()
        ops.add(AppOpsManager.OP_CAMERA)
        ops.add(AppOpsManager.OP_SYSTEM_ALERT_WINDOW)
        gutsManager.startAppOpsSettingsActivity("", 0, ops, row)
        val captor = argumentCaptor<Intent>()
        verify(notificationActivityStarter, times(1))
            .startNotificationGutsIntent(captor.capture(), anyInt(), eq(row))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, captor.lastValue.action)
    }

    @Test
    fun testAppOpsSettingsIntent_mic_overlay() {
        val row = createTestNotificationRow()
        val ops = ArraySet<Int>()
        ops.add(AppOpsManager.OP_RECORD_AUDIO)
        ops.add(AppOpsManager.OP_SYSTEM_ALERT_WINDOW)
        gutsManager.startAppOpsSettingsActivity("", 0, ops, row)
        val captor = argumentCaptor<Intent>()
        verify(notificationActivityStarter, times(1))
            .startNotificationGutsIntent(captor.capture(), anyInt(), eq(row))
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, captor.lastValue.action)
    }

    @Test
    @Throws(Exception::class)
    fun testInitializeNotificationInfoView_highPriority() {
        val notificationInfoView: NotificationInfo = mock()
        val row = spy(helper.createRow())
        val entry = row.entry
        NotificationEntryHelper.modifyRanking(entry)
            .setUserSentiment(NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE)
            .setImportance(NotificationManager.IMPORTANCE_HIGH)
            .build()

        whenever(row.isNonblockable).thenReturn(false)
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
                /* isDeviceProvisioned = */ eq(false),
                /* isNonblockable = */ eq(false),
                /* wasShownHighPriority = */ eq(true),
                eq(assistantFeedbackController),
                eq(metricsLogger),
            )
    }

    @Test
    @Throws(Exception::class)
    fun testInitializeNotificationInfoView_PassesAlongProvisionedState() {
        val notificationInfoView: NotificationInfo = mock()
        val row = spy(helper.createRow())
        NotificationEntryHelper.modifyRanking(row.entry)
            .setUserSentiment(NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE)
            .build()
        whenever(row.isNonblockable).thenReturn(false)
        val statusBarNotification = row.entry.sbn
        val entry = row.entry

        whenever(deviceProvisionedController.isDeviceProvisioned).thenReturn(true)

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
                /* isDeviceProvisioned = */ eq(true),
                /* isNonblockable = */ eq(false),
                /* wasShownHighPriority = */ eq(false),
                eq(assistantFeedbackController),
                eq(metricsLogger),
            )
    }

    @Test
    @Throws(Exception::class)
    fun testInitializeNotificationInfoView_withInitialAction() {
        val notificationInfoView: NotificationInfo = mock()
        val row = spy(helper.createRow())
        NotificationEntryHelper.modifyRanking(row.entry)
            .setUserSentiment(NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE)
            .build()
        whenever(row.isNonblockable).thenReturn(false)
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
                /* isDeviceProvisioned = */ eq(false),
                /* isNonblockable = */ eq(false),
                /* wasShownHighPriority = */ eq(false),
                eq(assistantFeedbackController),
                eq(metricsLogger),
            )
    }

    @Test
    @Throws(Exception::class)
    fun testInitializeBundleNotificationInfoView() {
        val infoView: BundleNotificationInfo = mock()
        val row = spy(helper.createRow())
        val entry = row.entry

        // Modify the notification entry to have a channel that is in SYSTEM_RESERVED_IDS
        val channel = NotificationChannel(NotificationChannel.NEWS_ID, "name", 2)
        NotificationEntryHelper.modifyRanking(entry).setChannel(channel).build()

        whenever(row.isNonblockable).thenReturn(false)
        val statusBarNotification = entry.sbn
        // Can we change this to a call to bindGuts instead? We have the row,
        // we need a MenuItem that we can put the infoView into.
        gutsManager.initializeBundleNotificationInfo(row, infoView)

        verify(infoView)
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
                /* isDeviceProvisioned = */ eq(false),
                /* isNonblockable = */ eq(false),
                /* wasShownHighPriority = */ eq(false),
                eq(assistantFeedbackController),
                eq(metricsLogger),
            )
    }

    private fun createTestNotificationRow(): ExpandableNotificationRow {
        val nb =
            Notification.Builder(mContext, testNotificationChannel.id)
                .setContentTitle("foo")
                .setColorized(true)
                .setColor(Color.RED)
                .setFlag(Notification.FLAG_CAN_COLORIZE, true)
                .setSmallIcon(R.drawable.sym_def_app_icon)

        try {
            val row = helper.createRow(nb.build())
            NotificationEntryHelper.modifyRanking(row.entry)
                .setChannel(testNotificationChannel)
                .build()
            return row
        } catch (e: Exception) {
            fail()
        }
    }

    private fun createTestMenuItem(
        row: ExpandableNotificationRow
    ): NotificationMenuRowPlugin.MenuItem {
        val menuRow: NotificationMenuRowPlugin =
            NotificationMenuRow(mContext, peopleNotificationIdentifier)
        menuRow.createMenu(row, row.entry.sbn)

        val menuItem = menuRow.getLongpressMenuItem(mContext)
        assertNotNull(menuItem)
        return menuItem
    }
}
