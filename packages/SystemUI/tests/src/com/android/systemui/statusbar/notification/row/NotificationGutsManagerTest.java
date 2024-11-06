/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.row;

import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.OP_SYSTEM_ALERT_WINDOW;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.app.NotificationManager.IMPORTANCE_HIGH;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE;

import static com.android.systemui.statusbar.NotificationEntryHelper.modifyRanking;

import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.content.Intent;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutManager;
import android.graphics.Color;
import android.os.Binder;
import android.os.Handler;
import android.os.UserManager;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import android.testing.TestableLooper;
import android.util.ArraySet;
import android.view.View;
import android.view.accessibility.AccessibilityManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository;
import com.android.systemui.kosmos.KosmosJavaAdapter;
import com.android.systemui.people.widget.PeopleSpaceWidgetManager;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.power.domain.interactor.PowerInteractorFactory;
import com.android.systemui.scene.data.repository.WindowRootViewVisibilityRepository;
import com.android.systemui.scene.domain.interactor.WindowRootViewVisibilityInteractor;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.notification.AssistantFeedbackController;
import com.android.systemui.statusbar.notification.NotificationActivityStarter;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.row.NotificationGutsManager.OnSettingsClickListener;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.kotlin.JavaAdapter;
import com.android.systemui.wmshell.BubblesManager;

import kotlinx.coroutines.test.TestScope;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Optional;

/**
 * Tests for {@link NotificationGutsManager}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class NotificationGutsManagerTest extends SysuiTestCase {
    private static final String TEST_CHANNEL_ID = "NotificationManagerServiceTestChannelId";

    private NotificationChannel mTestNotificationChannel = new NotificationChannel(
            TEST_CHANNEL_ID, TEST_CHANNEL_ID, IMPORTANCE_DEFAULT);

    private final KosmosJavaAdapter mKosmos = new KosmosJavaAdapter(this);
    private final TestScope mTestScope = mKosmos.getTestScope();
    private final JavaAdapter mJavaAdapter = new JavaAdapter(mTestScope.getBackgroundScope());
    private final FakeExecutor mExecutor = mKosmos.getFakeExecutor();
    private final Handler mHandler = mKosmos.getFakeExecutorHandler();
    private NotificationTestHelper mHelper;
    private NotificationGutsManager mGutsManager;

    @Rule public MockitoRule mockito = MockitoJUnit.rule();
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private OnUserInteractionCallback mOnUserInteractionCallback;
    @Mock private NotificationPresenter mPresenter;
    @Mock private NotificationActivityStarter mNotificationActivityStarter;
    @Mock private NotificationListContainer mNotificationListContainer;
    @Mock private OnSettingsClickListener mOnSettingsClickListener;
    @Mock private DeviceProvisionedController mDeviceProvisionedController;
    @Mock private AccessibilityManager mAccessibilityManager;
    @Mock private HighPriorityProvider mHighPriorityProvider;
    @Mock private INotificationManager mINotificationManager;
    @Mock private IStatusBarService mBarService;
    @Mock private LauncherApps mLauncherApps;
    @Mock private ShortcutManager mShortcutManager;
    @Mock private ChannelEditorDialogController mChannelEditorDialogController;
    @Mock private PeopleNotificationIdentifier mPeopleNotificationIdentifier;
    @Mock private UserContextProvider mContextTracker;
    @Mock private BubblesManager mBubblesManager;
    @Mock private ShadeController mShadeController;
    @Mock private PeopleSpaceWidgetManager mPeopleSpaceWidgetManager;
    @Mock private AssistantFeedbackController mAssistantFeedbackController;
    @Mock private NotificationLockscreenUserManager mNotificationLockscreenUserManager;
    @Mock private StatusBarStateController mStatusBarStateController;
    @Mock private HeadsUpManager mHeadsUpManager;
    @Mock private ActivityStarter mActivityStarter;

    @Mock private UserManager mUserManager;

    private WindowRootViewVisibilityInteractor mWindowRootViewVisibilityInteractor;

    @Before
    public void setUp() {
        allowTestableLooperAsMainThread();
        mHelper = new NotificationTestHelper(mContext, mDependency);
        when(mAccessibilityManager.isTouchExplorationEnabled()).thenReturn(false);

        mWindowRootViewVisibilityInteractor = new WindowRootViewVisibilityInteractor(
                mTestScope.getBackgroundScope(),
                new WindowRootViewVisibilityRepository(mBarService, mExecutor),
                new FakeKeyguardRepository(),
                mHeadsUpManager,
                PowerInteractorFactory.create().getPowerInteractor(),
                mKosmos.getActiveNotificationsInteractor(),
                () -> mKosmos.getSceneInteractor()
        );

        mGutsManager = new NotificationGutsManager(
                mContext,
                mHandler,
                mHandler,
                mJavaAdapter,
                mAccessibilityManager,
                mHighPriorityProvider,
                mINotificationManager,
                mUserManager,
                mPeopleSpaceWidgetManager,
                mLauncherApps,
                mShortcutManager,
                mChannelEditorDialogController,
                mContextTracker,
                mAssistantFeedbackController,
                Optional.of(mBubblesManager),
                new UiEventLoggerFake(),
                mOnUserInteractionCallback,
                mShadeController,
                mWindowRootViewVisibilityInteractor,
                mNotificationLockscreenUserManager,
                mStatusBarStateController,
                mBarService,
                mDeviceProvisionedController,
                mMetricsLogger,
                mHeadsUpManager,
                mActivityStarter);
        mGutsManager.setUpWithPresenter(mPresenter, mNotificationListContainer,
                mOnSettingsClickListener);
        mGutsManager.setNotificationActivityStarter(mNotificationActivityStarter);
        mGutsManager.start();
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Test methods:

    @Test
    public void testOpenAndCloseGuts() {
        NotificationGuts guts = spy(new NotificationGuts(mContext));
        when(guts.post(any())).thenAnswer(invocation -> {
            mHandler.post(((Runnable) invocation.getArguments()[0]));
            return null;
        });

        // Test doesn't support animation since the guts view is not attached.
        doNothing().when(guts).openControls(
                anyInt(),
                anyInt(),
                anyBoolean(),
                any(Runnable.class));

        ExpandableNotificationRow realRow = createTestNotificationRow();
        NotificationMenuRowPlugin.MenuItem menuItem = createTestMenuItem(realRow);

        ExpandableNotificationRow row = spy(realRow);
        when(row.getWindowToken()).thenReturn(new Binder());
        when(row.getGuts()).thenReturn(guts);

        assertTrue(mGutsManager.openGutsInternal(row, 0, 0, menuItem));
        assertEquals(View.INVISIBLE, guts.getVisibility());
        mExecutor.runAllReady();
        verify(guts).openControls(
                anyInt(),
                anyInt(),
                anyBoolean(),
                any(Runnable.class));
        verify(mHeadsUpManager).setGutsShown(realRow.getEntry(), true);

        assertEquals(View.VISIBLE, guts.getVisibility());
        mGutsManager.closeAndSaveGuts(false, false, true, 0, 0, false);

        verify(guts).closeControls(anyBoolean(), anyBoolean(), anyInt(), anyInt(), anyBoolean());
        verify(row, times(1)).setGutsView(any());
        mExecutor.runAllReady();
        verify(mHeadsUpManager).setGutsShown(realRow.getEntry(), false);
    }

    @Test
    public void testLockscreenShadeVisible_visible_gutsNotClosed() {
        // First, start out lockscreen or shade as not visible
        mWindowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(false);
        mTestScope.getTestScheduler().runCurrent();

        NotificationGuts guts = mock(NotificationGuts.class);
        mGutsManager.setExposedGuts(guts);

        // WHEN the lockscreen or shade becomes visible
        mWindowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true);
        mTestScope.getTestScheduler().runCurrent();

        // THEN the guts are not closed
        verify(guts, never()).removeCallbacks(any());
        verify(guts, never()).closeControls(
                anyBoolean(), anyBoolean(), anyInt(), anyInt(), anyBoolean());
    }

    @Test
    public void testLockscreenShadeVisible_notVisible_gutsClosed() {
        // First, start out lockscreen or shade as visible
        mWindowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true);
        mTestScope.getTestScheduler().runCurrent();

        NotificationGuts guts = mock(NotificationGuts.class);
        mGutsManager.setExposedGuts(guts);

        // WHEN the lockscreen or shade is no longer visible
        mWindowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(false);
        mTestScope.getTestScheduler().runCurrent();

        // THEN the guts are closed
        verify(guts).removeCallbacks(any());
        verify(guts).closeControls(
                /* leavebehinds= */ eq(true),
                /* controls= */ eq(true),
                /* x= */ anyInt(),
                /* y= */ anyInt(),
                /* force= */ eq(true));
    }

    @Test
    public void testLockscreenShadeVisible_notVisible_listContainerReset() {
        // First, start out lockscreen or shade as visible
        mWindowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(true);
        mTestScope.getTestScheduler().runCurrent();

        // WHEN the lockscreen or shade is no longer visible
        mWindowRootViewVisibilityInteractor.setIsLockscreenOrShadeVisible(false);
        mTestScope.getTestScheduler().runCurrent();

        // THEN the list container is reset
        verify(mNotificationListContainer).resetExposedMenuView(anyBoolean(), anyBoolean());
    }

    @Test
    public void testChangeDensityOrFontScale() {
        NotificationGuts guts = spy(new NotificationGuts(mContext));
        when(guts.post(any())).thenAnswer(invocation -> {
            mHandler.post(((Runnable) invocation.getArguments()[0]));
            return null;
        });

        // Test doesn't support animation since the guts view is not attached.
        doNothing().when(guts).openControls(
                anyInt(),
                anyInt(),
                anyBoolean(),
                any(Runnable.class));

        ExpandableNotificationRow realRow = createTestNotificationRow();
        NotificationMenuRowPlugin.MenuItem menuItem = createTestMenuItem(realRow);

        ExpandableNotificationRow row = spy(realRow);

        when(row.getWindowToken()).thenReturn(new Binder());
        when(row.getGuts()).thenReturn(guts);
        doNothing().when(row).ensureGutsInflated();

        NotificationEntry realEntry = realRow.getEntry();
        NotificationEntry entry = spy(realEntry);

        when(entry.getRow()).thenReturn(row);
        when(entry.getGuts()).thenReturn(guts);

        assertTrue(mGutsManager.openGutsInternal(row, 0, 0, menuItem));
        mExecutor.runAllReady();
        verify(guts).openControls(
                anyInt(),
                anyInt(),
                anyBoolean(),
                any(Runnable.class));

        // called once by mGutsManager.bindGuts() in mGutsManager.openGuts()
        verify(row).setGutsView(any());

        row.onDensityOrFontScaleChanged();
        mGutsManager.onDensityOrFontScaleChanged(entry);

        mExecutor.runAllReady();

        mGutsManager.closeAndSaveGuts(false, false, false, 0, 0, false);

        verify(guts).closeControls(anyBoolean(), anyBoolean(), anyInt(), anyInt(), anyBoolean());

        // called again by mGutsManager.bindGuts(), in mGutsManager.onDensityOrFontScaleChanged()
        verify(row, times(2)).setGutsView(any());
    }

    @Test
    public void testAppOpsSettingsIntent_camera() {
        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(OP_CAMERA);
        mGutsManager.startAppOpsSettingsActivity("", 0, ops, null);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mNotificationActivityStarter, times(1))
                .startNotificationGutsIntent(captor.capture(), anyInt(), any());
        assertEquals(Intent.ACTION_MANAGE_APP_PERMISSIONS, captor.getValue().getAction());
    }

    @Test
    public void testAppOpsSettingsIntent_mic() {
        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(OP_RECORD_AUDIO);
        mGutsManager.startAppOpsSettingsActivity("", 0, ops, null);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mNotificationActivityStarter, times(1))
                .startNotificationGutsIntent(captor.capture(), anyInt(), any());
        assertEquals(Intent.ACTION_MANAGE_APP_PERMISSIONS, captor.getValue().getAction());
    }

    @Test
    public void testAppOpsSettingsIntent_camera_mic() {
        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(OP_CAMERA);
        ops.add(OP_RECORD_AUDIO);
        mGutsManager.startAppOpsSettingsActivity("", 0, ops, null);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mNotificationActivityStarter, times(1))
                .startNotificationGutsIntent(captor.capture(), anyInt(), any());
        assertEquals(Intent.ACTION_MANAGE_APP_PERMISSIONS, captor.getValue().getAction());
    }

    @Test
    public void testAppOpsSettingsIntent_overlay() {
        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(OP_SYSTEM_ALERT_WINDOW);
        mGutsManager.startAppOpsSettingsActivity("", 0, ops, null);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mNotificationActivityStarter, times(1))
                .startNotificationGutsIntent(captor.capture(), anyInt(), any());
        assertEquals(Settings.ACTION_MANAGE_APP_OVERLAY_PERMISSION, captor.getValue().getAction());
    }

    @Test
    public void testAppOpsSettingsIntent_camera_mic_overlay() {
        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(OP_CAMERA);
        ops.add(OP_RECORD_AUDIO);
        ops.add(OP_SYSTEM_ALERT_WINDOW);
        mGutsManager.startAppOpsSettingsActivity("", 0, ops, null);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mNotificationActivityStarter, times(1))
                .startNotificationGutsIntent(captor.capture(), anyInt(), any());
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, captor.getValue().getAction());
    }

    @Test
    public void testAppOpsSettingsIntent_camera_overlay() {
        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(OP_CAMERA);
        ops.add(OP_SYSTEM_ALERT_WINDOW);
        mGutsManager.startAppOpsSettingsActivity("", 0, ops, null);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mNotificationActivityStarter, times(1))
                .startNotificationGutsIntent(captor.capture(), anyInt(), any());
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, captor.getValue().getAction());
    }

    @Test
    public void testAppOpsSettingsIntent_mic_overlay() {
        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(OP_RECORD_AUDIO);
        ops.add(OP_SYSTEM_ALERT_WINDOW);
        mGutsManager.startAppOpsSettingsActivity("", 0, ops, null);
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mNotificationActivityStarter, times(1))
                .startNotificationGutsIntent(captor.capture(), anyInt(), any());
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, captor.getValue().getAction());
    }

    @Test
    public void testInitializeNotificationInfoView_highPriority() throws Exception {
        NotificationInfo notificationInfoView = mock(NotificationInfo.class);
        ExpandableNotificationRow row = spy(mHelper.createRow());
        final NotificationEntry entry = row.getEntry();
        modifyRanking(entry)
                .setUserSentiment(USER_SENTIMENT_NEGATIVE)
                .setImportance(IMPORTANCE_HIGH)
                .build();

        when(row.getIsNonblockable()).thenReturn(false);
        when(mHighPriorityProvider.isHighPriority(entry)).thenReturn(true);
        StatusBarNotification statusBarNotification = entry.getSbn();
        mGutsManager.initializeNotificationInfo(row, notificationInfoView);

        verify(notificationInfoView).bindNotification(
                any(PackageManager.class),
                any(INotificationManager.class),
                eq(mOnUserInteractionCallback),
                eq(mChannelEditorDialogController),
                eq(statusBarNotification.getPackageName()),
                any(NotificationChannel.class),
                eq(entry),
                any(NotificationInfo.OnSettingsClickListener.class),
                any(NotificationInfo.OnAppSettingsClickListener.class),
                any(UiEventLogger.class),
                eq(false),
                eq(false),
                eq(true), /* wasShownHighPriority */
                eq(mAssistantFeedbackController),
                any(MetricsLogger.class));
    }

    @Test
    public void testInitializeNotificationInfoView_PassesAlongProvisionedState() throws Exception {
        NotificationInfo notificationInfoView = mock(NotificationInfo.class);
        ExpandableNotificationRow row = spy(mHelper.createRow());
        modifyRanking(row.getEntry())
                .setUserSentiment(USER_SENTIMENT_NEGATIVE)
                .build();
        when(row.getIsNonblockable()).thenReturn(false);
        StatusBarNotification statusBarNotification = row.getEntry().getSbn();
        NotificationEntry entry = row.getEntry();

        when(mDeviceProvisionedController.isDeviceProvisioned()).thenReturn(true);

        mGutsManager.initializeNotificationInfo(row, notificationInfoView);

        verify(notificationInfoView).bindNotification(
                any(PackageManager.class),
                any(INotificationManager.class),
                eq(mOnUserInteractionCallback),
                eq(mChannelEditorDialogController),
                eq(statusBarNotification.getPackageName()),
                any(NotificationChannel.class),
                eq(entry),
                any(NotificationInfo.OnSettingsClickListener.class),
                any(NotificationInfo.OnAppSettingsClickListener.class),
                any(UiEventLogger.class),
                eq(true),
                eq(false),
                eq(false), /* wasShownHighPriority */
                eq(mAssistantFeedbackController),
                any(MetricsLogger.class));
    }

    @Test
    public void testInitializeNotificationInfoView_withInitialAction() throws Exception {
        NotificationInfo notificationInfoView = mock(NotificationInfo.class);
        ExpandableNotificationRow row = spy(mHelper.createRow());
        modifyRanking(row.getEntry())
                .setUserSentiment(USER_SENTIMENT_NEGATIVE)
                .build();
        when(row.getIsNonblockable()).thenReturn(false);
        StatusBarNotification statusBarNotification = row.getEntry().getSbn();
        NotificationEntry entry = row.getEntry();

        mGutsManager.initializeNotificationInfo(row, notificationInfoView);

        verify(notificationInfoView).bindNotification(
                any(PackageManager.class),
                any(INotificationManager.class),
                eq(mOnUserInteractionCallback),
                eq(mChannelEditorDialogController),
                eq(statusBarNotification.getPackageName()),
                any(NotificationChannel.class),
                eq(entry),
                any(NotificationInfo.OnSettingsClickListener.class),
                any(NotificationInfo.OnAppSettingsClickListener.class),
                any(UiEventLogger.class),
                eq(false),
                eq(false),
                eq(false), /* wasShownHighPriority */
                eq(mAssistantFeedbackController),
                any(MetricsLogger.class));
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    // Utility methods:

    private ExpandableNotificationRow createTestNotificationRow() {
        Notification.Builder nb = new Notification.Builder(mContext,
                mTestNotificationChannel.getId())
                                        .setContentTitle("foo")
                                        .setColorized(true).setColor(Color.RED)
                                        .setFlag(Notification.FLAG_CAN_COLORIZE, true)
                                        .setSmallIcon(android.R.drawable.sym_def_app_icon);

        try {
            ExpandableNotificationRow row = mHelper.createRow(nb.build());
            modifyRanking(row.getEntry())
                    .setChannel(mTestNotificationChannel)
                    .build();
            return row;
        } catch (Exception e) {
            fail();
            return null;
        }
    }

    private NotificationMenuRowPlugin.MenuItem createTestMenuItem(ExpandableNotificationRow row) {
        NotificationMenuRowPlugin menuRow =
                new NotificationMenuRow(mContext, mPeopleNotificationIdentifier);
        menuRow.createMenu(row, row.getEntry().getSbn());

        NotificationMenuRowPlugin.MenuItem menuItem = menuRow.getLongpressMenuItem(mContext);
        assertNotNull(menuItem);
        return menuItem;
    }
}
