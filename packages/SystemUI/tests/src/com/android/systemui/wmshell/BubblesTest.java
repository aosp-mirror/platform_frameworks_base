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
 * limitations under the License.
 */

package com.android.systemui.wmshell;

import static android.app.Notification.FLAG_BUBBLE;
import static android.app.PendingIntent.FLAG_MUTABLE;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.face.FaceManager;
import android.os.Handler;
import android.os.PowerManager;
import android.os.UserHandle;
import android.service.dreams.IDreamManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.ZenModeConfig;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.model.SysUiState;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.shared.system.QuickStepContract;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationRemoveInterceptor;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationFilter;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationShadeWindowControllerImpl;
import com.android.systemui.statusbar.phone.NotificationShadeWindowView;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.wm.shell.R;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.bubbles.Bubble;
import com.android.wm.shell.bubbles.BubbleData;
import com.android.wm.shell.bubbles.BubbleDataRepository;
import com.android.wm.shell.bubbles.BubbleEntry;
import com.android.wm.shell.bubbles.BubbleIconFactory;
import com.android.wm.shell.bubbles.BubbleLogger;
import com.android.wm.shell.bubbles.BubbleOverflow;
import com.android.wm.shell.bubbles.BubbleStackView;
import com.android.wm.shell.bubbles.BubbleViewInfoTask;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TaskStackListenerImpl;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

/**
 * Tests the NotificationEntryManager setup with BubbleController.
 * The {@link NotifPipeline} setup with BubbleController is tested in
 * {@link NewNotifPipelineBubblesTest}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class BubblesTest extends SysuiTestCase {
    @Mock
    private NotificationEntryManager mNotificationEntryManager;
    @Mock
    private NotificationGroupManagerLegacy mNotificationGroupManager;
    @Mock
    private WindowManager mWindowManager;
    @Mock
    private IActivityManager mActivityManager;
    @Mock
    private DozeParameters mDozeParameters;
    @Mock
    private ConfigurationController mConfigurationController;
    @Mock
    private ZenModeController mZenModeController;
    @Mock
    private ZenModeConfig mZenModeConfig;
    @Mock
    private FaceManager mFaceManager;
    @Mock
    private NotificationLockscreenUserManager mLockscreenUserManager;
    @Mock
    private SysuiStatusBarStateController mStatusBarStateController;
    @Mock
    private KeyguardViewMediator mKeyguardViewMediator;
    @Mock
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private FloatingContentCoordinator mFloatingContentCoordinator;
    @Mock
    private BubbleDataRepository mDataRepository;

    private SysUiState mSysUiState;
    private boolean mSysUiStateBubblesExpanded;

    @Captor
    private ArgumentCaptor<NotificationEntryListener> mEntryListenerCaptor;
    @Captor
    private ArgumentCaptor<NotificationRemoveInterceptor> mRemoveInterceptorCaptor;

    private BubblesManager mBubblesManager;
    // TODO(178618782): Move tests on the controller directly to the shell
    private TestableBubbleController mBubbleController;
    private NotificationShadeWindowControllerImpl mNotificationShadeWindowController;
    private NotificationEntryListener mEntryListener;
    private NotificationRemoveInterceptor mRemoveInterceptor;

    private NotificationTestHelper mNotificationTestHelper;
    private NotificationEntry mRow;
    private NotificationEntry mRow2;
    private NotificationEntry mRow3;
    private ExpandableNotificationRow mNonBubbleNotifRow;
    private BubbleEntry mBubbleEntry;
    private BubbleEntry mBubbleEntry2;
    private BubbleEntry mBubbleEntry3;

    private BubbleEntry mBubbleEntryUser11;
    private BubbleEntry mBubbleEntry2User11;

    @Mock
    private Bubbles.BubbleExpandListener mBubbleExpandListener;
    @Mock
    private PendingIntent mDeleteIntent;
    @Mock
    private SysuiColorExtractor mColorExtractor;
    @Mock
    ColorExtractor.GradientColors mGradientColors;
    @Mock
    private ShadeController mShadeController;
    @Mock
    private NotifPipeline mNotifPipeline;
    @Mock
    private FeatureFlags mFeatureFlagsOldPipeline;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private NotificationShadeWindowView mNotificationShadeWindowView;
    @Mock
    private IStatusBarService mStatusBarService;
    @Mock
    private LauncherApps mLauncherApps;
    @Mock
    private WindowManagerShellWrapper mWindowManagerShellWrapper;
    @Mock
    private BubbleLogger mBubbleLogger;
    @Mock
    private TaskStackListenerImpl mTaskStackListener;
    @Mock
    private ShellTaskOrganizer mShellTaskOrganizer;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;

    private TestableBubblePositioner mPositioner;

    private BubbleData mBubbleData;

    private TestableLooper mTestableLooper;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);

        // For the purposes of this test, just run everything synchronously
        ShellExecutor syncExecutor = new SyncExecutor();

        mContext.addMockSystemService(FaceManager.class, mFaceManager);
        when(mColorExtractor.getNeutralColors()).thenReturn(mGradientColors);

        mNotificationShadeWindowController = new NotificationShadeWindowControllerImpl(mContext,
                mWindowManager, mActivityManager, mDozeParameters, mStatusBarStateController,
                mConfigurationController, mKeyguardViewMediator, mKeyguardBypassController,
                mColorExtractor, mDumpManager, mKeyguardStateController,
                mUnlockedScreenOffAnimationController);
        mNotificationShadeWindowController.setNotificationShadeView(mNotificationShadeWindowView);
        mNotificationShadeWindowController.attach();

        // Need notifications for bubbles
        mNotificationTestHelper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        mRow = mNotificationTestHelper.createBubble(mDeleteIntent);
        mRow2 = mNotificationTestHelper.createBubble(mDeleteIntent);
        mRow3 = mNotificationTestHelper.createBubble(mDeleteIntent);
        mNonBubbleNotifRow = mNotificationTestHelper.createRow();
        mBubbleEntry = BubblesManager.notifToBubbleEntry(mRow);
        mBubbleEntry2 = BubblesManager.notifToBubbleEntry(mRow2);
        mBubbleEntry3 = BubblesManager.notifToBubbleEntry(mRow3);

        UserHandle handle = mock(UserHandle.class);
        when(handle.getIdentifier()).thenReturn(11);
        mBubbleEntryUser11 = BubblesManager.notifToBubbleEntry(
                mNotificationTestHelper.createBubble(handle));
        mBubbleEntry2User11 = BubblesManager.notifToBubbleEntry(
                mNotificationTestHelper.createBubble(handle));

        // Return non-null notification data from the NEM
        when(mNotificationEntryManager
                .getActiveNotificationUnfiltered(mRow.getKey())).thenReturn(mRow);

        mZenModeConfig.suppressedVisualEffects = 0;
        when(mZenModeController.getConfig()).thenReturn(mZenModeConfig);

        mSysUiState = new SysUiState();
        mSysUiState.addCallback(sysUiFlags ->
                mSysUiStateBubblesExpanded =
                        (sysUiFlags & QuickStepContract.SYSUI_STATE_BUBBLES_EXPANDED) != 0);

        // TODO: Fix
        mPositioner = new TestableBubblePositioner(mContext, mWindowManager);
        mPositioner.setMaxBubbles(5);
        mBubbleData = new BubbleData(mContext, mBubbleLogger, mPositioner, syncExecutor);

        TestableNotificationInterruptStateProviderImpl interruptionStateProvider =
                new TestableNotificationInterruptStateProviderImpl(mContext.getContentResolver(),
                        mock(PowerManager.class),
                        mock(IDreamManager.class),
                        mock(AmbientDisplayConfiguration.class),
                        mock(NotificationFilter.class),
                        mock(StatusBarStateController.class),
                        mock(BatteryController.class),
                        mock(HeadsUpManager.class),
                        mock(Handler.class)
                );

        when(mFeatureFlagsOldPipeline.isNewNotifPipelineRenderingEnabled()).thenReturn(false);
        when(mShellTaskOrganizer.getExecutor()).thenReturn(syncExecutor);
        mBubbleController = new TestableBubbleController(
                mContext,
                mBubbleData,
                mFloatingContentCoordinator,
                mDataRepository,
                mStatusBarService,
                mWindowManager,
                mWindowManagerShellWrapper,
                mLauncherApps,
                mBubbleLogger,
                mTaskStackListener,
                mShellTaskOrganizer,
                mPositioner,
                mock(DisplayController.class),
                syncExecutor,
                mock(Handler.class),
                mock(SyncTransactionQueue.class));
        mBubbleController.setExpandListener(mBubbleExpandListener);
        spyOn(mBubbleController);

        mBubblesManager = new BubblesManager(
                mContext,
                mBubbleController.asBubbles(),
                mNotificationShadeWindowController,
                mStatusBarStateController,
                mShadeController,
                mConfigurationController,
                mStatusBarService,
                mock(INotificationManager.class),
                interruptionStateProvider,
                mZenModeController,
                mLockscreenUserManager,
                mNotificationGroupManager,
                mNotificationEntryManager,
                mNotifPipeline,
                mSysUiState,
                mFeatureFlagsOldPipeline,
                mDumpManager,
                syncExecutor);

        // Get a reference to the BubbleController's entry listener
        verify(mNotificationEntryManager, atLeastOnce())
                .addNotificationEntryListener(mEntryListenerCaptor.capture());
        mEntryListener = mEntryListenerCaptor.getValue();
        // And the remove interceptor
        verify(mNotificationEntryManager, atLeastOnce())
                .addNotificationRemoveInterceptor(mRemoveInterceptorCaptor.capture());
        mRemoveInterceptor = mRemoveInterceptorCaptor.getValue();
    }

    @Test
    public void testAddBubble() {
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testHasBubbles() {
        assertFalse(mBubbleController.hasBubbles());
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testRemoveBubble() {
        mBubbleController.updateBubble(mBubbleEntry);
        assertNotNull(mBubbleData.getBubbleInStackWithKey(mBubbleEntry.getKey()));
        assertTrue(mBubbleController.hasBubbles());
        verify(mNotificationEntryManager).updateNotifications(any());

        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);
        assertNull(mBubbleData.getBubbleInStackWithKey(mRow.getKey()));
        verify(mNotificationEntryManager, times(2)).updateNotifications(anyString());

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testPromoteBubble_autoExpand() throws Exception {
        mBubbleController.updateBubble(mBubbleEntry2);
        mBubbleController.updateBubble(mBubbleEntry);
        when(mNotificationEntryManager.getPendingOrActiveNotif(mRow.getKey()))
                .thenReturn(mRow);
        when(mNotificationEntryManager.getPendingOrActiveNotif(mRow2.getKey()))
                .thenReturn(mRow2);
        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);

        Bubble b = mBubbleData.getOverflowBubbleWithKey(mRow.getKey());
        assertThat(mBubbleData.getOverflowBubbles()).isEqualTo(ImmutableList.of(b));
        verify(mNotificationEntryManager, never()).performRemoveNotification(
                eq(mRow.getSbn()), any(),  anyInt());
        assertThat(mRow.isBubble()).isFalse();

        Bubble b2 = mBubbleData.getBubbleInStackWithKey(mRow2.getKey());
        assertThat(mBubbleData.getSelectedBubble()).isEqualTo(b2);

        mBubbleController.promoteBubbleFromOverflow(b);

        assertThat(b.isBubble()).isTrue();
        assertThat(b.shouldAutoExpand()).isTrue();
        int flags = Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE
                | Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION;
        verify(mStatusBarService, times(1)).onNotificationBubbleChanged(
                eq(b.getKey()), eq(true), eq(flags));
    }

    @Test
    public void testCancelOverflowBubble() {
        mBubbleController.updateBubble(mBubbleEntry2);
        mBubbleController.updateBubble(mBubbleEntry, /* suppressFlyout */
                false, /* showInShade */ true);
        when(mNotificationEntryManager.getPendingOrActiveNotif(mRow.getKey()))
                .thenReturn(mRow);
        when(mNotificationEntryManager.getPendingOrActiveNotif(mRow2.getKey()))
                .thenReturn(mRow2);
        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);

        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_NOTIF_CANCEL);
        verify(mNotificationEntryManager, times(1)).performRemoveNotification(
                eq(mRow.getSbn()), any(), anyInt());
        assertThat(mBubbleData.getOverflowBubbles()).isEmpty();
        assertFalse(mRow.isBubble());
    }

    @Test
    public void testUserChange_doesNotRemoveNotif() {
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());

        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_USER_CHANGED);
        verify(mNotificationEntryManager, never()).performRemoveNotification(
                eq(mRow.getSbn()), any(), anyInt());
        assertFalse(mBubbleController.hasBubbles());
        assertFalse(mSysUiStateBubblesExpanded);
        assertTrue(mRow.isBubble());
    }

    @Test
    public void testDismissStack() {
        mBubbleController.updateBubble(mBubbleEntry);
        verify(mNotificationEntryManager, times(1)).updateNotifications(any());
        assertNotNull(mBubbleData.getBubbleInStackWithKey(mRow.getKey()));
        mBubbleController.updateBubble(mBubbleEntry2);
        verify(mNotificationEntryManager, times(2)).updateNotifications(any());
        assertNotNull(mBubbleData.getBubbleInStackWithKey(mRow2.getKey()));
        assertTrue(mBubbleController.hasBubbles());

        mBubbleData.dismissAll(Bubbles.DISMISS_USER_GESTURE);
        verify(mNotificationEntryManager, times(3)).updateNotifications(any());
        assertNull(mBubbleData.getBubbleInStackWithKey(mRow.getKey()));
        assertNull(mBubbleData.getBubbleInStackWithKey(mRow2.getKey()));

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testExpandCollapseStack() {
        assertStackCollapsed();

        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        // Expand the stack
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleData.setExpanded(true);
        assertStackExpanded();
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getKey());

        assertTrue(mSysUiStateBubblesExpanded);

        // Make sure the notif is suppressed
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);

        // Collapse
        mBubbleController.collapseStack();
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow.getKey());
        assertStackCollapsed();

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    @Ignore("Currently broken.")
    public void testCollapseAfterChangingExpandedBubble() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow);
        mEntryListener.onPendingEntryAdded(mRow2);
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.updateBubble(mBubbleEntry2);

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry2);

        // Expand
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleData.setExpanded(true);
        assertStackExpanded();
        verify(mBubbleExpandListener, atLeastOnce()).onBubbleExpandChanged(
                true, mRow2.getKey());

        assertTrue(mSysUiStateBubblesExpanded);

        // Last added is the one that is expanded
        assertEquals(mRow2.getKey(), mBubbleData.getSelectedBubble().getKey());
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry2);

        // Switch which bubble is expanded
        mBubbleData.setSelectedBubble(mBubbleData.getBubbleInStackWithKey(
                mRow.getKey()));
        mBubbleData.setExpanded(true);
        assertEquals(mRow.getKey(), mBubbleData.getBubbleInStackWithKey(
                stackView.getExpandedBubble().getKey()).getKey());
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);

        // collapse for previous bubble
        verify(mBubbleExpandListener, atLeastOnce()).onBubbleExpandChanged(
                false, mRow2.getKey());
        // expand for selected bubble
        verify(mBubbleExpandListener, atLeastOnce()).onBubbleExpandChanged(
                true, mRow.getKey());

        // Collapse
        mBubbleController.collapseStack();
        assertStackCollapsed();

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testExpansionRemovesShowInShadeAndDot() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        mTestableLooper.processAllMessages();
        assertTrue(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());

        // Expand
        mBubbleData.setExpanded(true);
        assertStackExpanded();
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getKey());

        assertTrue(mSysUiStateBubblesExpanded);

        // Notif is suppressed after expansion
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());
    }

    @Test
    public void testUpdateWhileExpanded_DoesntChangeShowInShadeAndDot() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        mTestableLooper.processAllMessages();
        assertTrue(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());

        // Expand
        mBubbleData.setExpanded(true);
        assertStackExpanded();
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getKey());

        assertTrue(mSysUiStateBubblesExpanded);

        // Notif is suppressed after expansion
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());

        // Send update
        mEntryListener.onPreEntryUpdated(mRow);

        // Nothing should have changed
        // Notif is suppressed after expansion
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());
    }

    @Test
    public void testRemoveLastExpanded_selectsOverflow() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow);
        mEntryListener.onPendingEntryAdded(mRow2);
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.updateBubble(mBubbleEntry2);

        // Expand
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleData.setExpanded(true);

        assertTrue(mSysUiStateBubblesExpanded);

        assertStackExpanded();
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow2.getKey());

        // Last added is the one that is expanded
        assertEquals(mRow2.getKey(), mBubbleData.getBubbleInStackWithKey(
                stackView.getExpandedBubble().getKey()).getKey());
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry2);

        // Dismiss currently expanded
        mBubbleController.removeBubble(
                mBubbleData.getBubbleInStackWithKey(
                        stackView.getExpandedBubble().getKey()).getKey(),
                Bubbles.DISMISS_USER_GESTURE);
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow2.getKey());

        // Make sure first bubble is selected
        assertEquals(mRow.getKey(), mBubbleData.getBubbleInStackWithKey(
                stackView.getExpandedBubble().getKey()).getKey());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getKey());

        // Dismiss that one
        mBubbleController.removeBubble(
                mBubbleData.getBubbleInStackWithKey(
                        stackView.getExpandedBubble().getKey()).getKey(),
                Bubbles.DISMISS_USER_GESTURE);

        // Overflow should be selected
        assertEquals(mBubbleData.getSelectedBubble().getKey(), BubbleOverflow.KEY);
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, BubbleOverflow.KEY);
        assertTrue(mBubbleController.hasBubbles());
        assertTrue(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testRemoveLastExpandedEmptyOverflow_collapses() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // Expand
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleData.setExpanded(true);

        assertTrue(mSysUiStateBubblesExpanded);
        assertStackExpanded();
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getKey());

        // Block the bubble so it won't be in the overflow
        mBubbleController.removeBubble(
                mBubbleData.getBubbleInStackWithKey(
                        stackView.getExpandedBubble().getKey()).getKey(),
                Bubbles.DISMISS_BLOCKED);

        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow.getKey());

        // We should be collapsed
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow.getKey());
        assertFalse(mBubbleController.hasBubbles());
        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testAutoExpand_fails_noFlag() {
        assertStackCollapsed();
        setMetadataFlags(mRow,
                Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE, false /* enableFlag */);

        // Add the auto expand bubble
        mEntryListener.onPendingEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // Expansion shouldn't change
        verify(mBubbleExpandListener, never()).onBubbleExpandChanged(false /* expanded */,
                mRow.getKey());
        assertStackCollapsed();

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testAutoExpand_succeeds_withFlag() {
        setMetadataFlags(mRow,
                Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE, true /* enableFlag */);

        // Add the auto expand bubble
        mEntryListener.onPendingEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // Expansion should change
        verify(mBubbleExpandListener).onBubbleExpandChanged(true /* expanded */,
                mRow.getKey());
        assertStackExpanded();

        assertTrue(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testSuppressNotif_onInitialNotif() {
        setMetadataFlags(mRow,
                Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION, true /* enableFlag */);

        // Add the suppress notif bubble
        mEntryListener.onPendingEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // Notif should be suppressed because we were foreground
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
        // Dot + flyout is hidden because notif is suppressed
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showFlyout());

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testSuppressNotif_onUpdateNotif() {
        mBubbleController.updateBubble(mBubbleEntry);

        // Should not be suppressed
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);
        // Should show dot
        assertTrue(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());

        // Update to suppress notif
        setMetadataFlags(mRow,
                Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION, true /* enableFlag */);
        mBubbleController.updateBubble(mBubbleEntry);

        // Notif should be suppressed
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
        // Dot + flyout is hidden because notif is suppressed
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showFlyout());

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testExpandStackAndSelectBubble_removedFirst() {
        mEntryListener.onPendingEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // Simulate notification cancellation.
        mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getKey(), mRow, REASON_APP_CANCEL);

        mBubbleController.expandStackAndSelectBubble(mBubbleEntry);

        assertTrue(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testMarkNewNotificationAsShowInShade() {
        mEntryListener.onPendingEntryAdded(mRow);
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        mTestableLooper.processAllMessages();
        assertTrue(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());
    }

    @Test
    public void testAddNotif_notBubble() {
        mEntryListener.onPendingEntryAdded(mNonBubbleNotifRow.getEntry());
        mEntryListener.onPreEntryUpdated(mNonBubbleNotifRow.getEntry());

        assertThat(mBubbleController.hasBubbles()).isFalse();
    }

    @Test
    public void testDeleteIntent_removeBubble_aged() throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.removeBubble(mRow.getKey(), Bubbles.DISMISS_AGED);
        verify(mDeleteIntent, never()).send();
    }

    @Test
    public void testDeleteIntent_removeBubble_user() throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);
        verify(mDeleteIntent, times(1)).send();
    }

    @Test
    public void testDeleteIntent_dismissStack() throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.updateBubble(mBubbleEntry2);
        mBubbleData.dismissAll(Bubbles.DISMISS_USER_GESTURE);
        verify(mDeleteIntent, times(2)).send();
    }

    @Test
    public void testRemoveBubble_noLongerBubbleAfterUpdate()
            throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());

        mRow.getSbn().getNotification().flags &= ~FLAG_BUBBLE;
        NotificationListenerService.Ranking ranking = new RankingBuilder(
                mRow.getRanking()).setCanBubble(false).build();
        mRow.setRanking(ranking);
        mEntryListener.onPreEntryUpdated(mRow);

        assertFalse(mBubbleController.hasBubbles());
        verify(mDeleteIntent, never()).send();
    }

    @Test
    public void testRemoveBubble_succeeds_appCancel() {
        mEntryListener.onPendingEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        assertTrue(mBubbleController.hasBubbles());

        boolean intercepted = mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getKey(), mRow, REASON_APP_CANCEL);

        // Cancels always remove so no need to intercept
        assertFalse(intercepted);
    }

    @Test
    public void testRemoveBubble_entryListenerRemove() {
        mEntryListener.onPendingEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        assertTrue(mBubbleController.hasBubbles());

        // Removes the notification
        mEntryListener.onEntryRemoved(mRow, null, false, REASON_APP_CANCEL);
        assertFalse(mBubbleController.hasBubbles());
    }

    @Test
    public void removeBubble_clearAllIntercepted()  {
        mEntryListener.onPendingEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        boolean intercepted = mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getKey(), mRow, REASON_CANCEL_ALL);

        // Intercept!
        assertTrue(intercepted);
        // Should update show in shade state
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
    }

    @Test
    public void removeBubble_userDismissNotifIntercepted() {
        mEntryListener.onPendingEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        boolean intercepted = mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getKey(), mRow, REASON_CANCEL);

        // Intercept!
        assertTrue(intercepted);
        // Should update show in shade state
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
    }

    @Test
    public void removeNotif_inOverflow_intercepted() {
        // Get bubble with notif in shade.
        mEntryListener.onPendingEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        // Dismiss the bubble into overflow.
        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);
        assertFalse(mBubbleController.hasBubbles());

        boolean intercepted = mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getKey(), mRow, REASON_CANCEL);

        // Notif is no longer a bubble, but still in overflow, so we intercept removal.
        assertTrue(intercepted);
    }

    @Test
    public void removeNotif_notInOverflow_notIntercepted() {
        // Get bubble with notif in shade.
        mEntryListener.onPendingEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_NO_LONGER_BUBBLE);
        assertFalse(mBubbleController.hasBubbles());

        boolean intercepted = mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getKey(), mRow, REASON_CANCEL);

        // Notif is no longer a bubble, so we should not intercept removal.
        assertFalse(intercepted);
    }

    @Test
    public void testOverflowBubble_maxReached_notInShade_bubbleRemoved() {
        mBubbleController.updateBubble(
                mBubbleEntry, /* suppressFlyout */ false, /* showInShade */ false);
        mBubbleController.updateBubble(
                mBubbleEntry2, /* suppressFlyout */ false, /* showInShade */ false);
        mBubbleController.updateBubble(
                mBubbleEntry3, /* suppressFlyout */ false, /* showInShade */ false);
        when(mNotificationEntryManager.getPendingOrActiveNotif(mRow.getKey()))
                .thenReturn(mRow);
        when(mNotificationEntryManager.getPendingOrActiveNotif(mRow2.getKey()))
                .thenReturn(mRow2);
        when(mNotificationEntryManager.getPendingOrActiveNotif(mRow3.getKey()))
                .thenReturn(mRow3);
        assertEquals(mBubbleData.getBubbles().size(), 3);

        mBubbleData.setMaxOverflowBubbles(1);
        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);
        assertEquals(mBubbleData.getBubbles().size(), 2);
        assertEquals(mBubbleData.getOverflowBubbles().size(), 1);

        mBubbleController.removeBubble(
                mRow2.getKey(), Bubbles.DISMISS_USER_GESTURE);
        // Overflow max of 1 is reached; mRow is oldest, so it gets removed
        verify(mNotificationEntryManager, times(1)).performRemoveNotification(
                eq(mRow.getSbn()), any(), eq(REASON_CANCEL));
        assertEquals(mBubbleData.getBubbles().size(), 1);
        assertEquals(mBubbleData.getOverflowBubbles().size(), 1);
    }

    @Test
    public void testNotifyShadeSuppressionChange_notificationDismiss() {
        mEntryListener.onPendingEntryAdded(mRow);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getKey(), mRow, REASON_CANCEL);

        // Should update show in shade state
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);

        // Should notify delegate that shade state changed
        verify(mBubbleController).onBubbleNotificationSuppressionChanged(
                mBubbleData.getBubbleInStackWithKey(mRow.getKey()));
    }

    @Test
    public void testNotifyShadeSuppressionChange_bubbleExpanded() {
        mEntryListener.onPendingEntryAdded(mRow);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        mBubbleData.setExpanded(true);

        // Once a bubble is expanded the notif is suppressed
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);

        // Should notify delegate that shade state changed
        verify(mBubbleController).onBubbleNotificationSuppressionChanged(
                mBubbleData.getBubbleInStackWithKey(mRow.getKey()));
    }

    @Test
    public void testBubbleSummaryDismissal_suppressesSummaryAndBubbleFromShade() throws Exception {
        // GIVEN a group summary with a bubble child
        ExpandableNotificationRow groupSummary = mNotificationTestHelper.createGroup(0);
        ExpandableNotificationRow groupedBubble = mNotificationTestHelper.createBubbleInGroup();
        when(mNotificationEntryManager.getPendingOrActiveNotif(groupedBubble.getEntry().getKey()))
                .thenReturn(groupedBubble.getEntry());
        mEntryListener.onPendingEntryAdded(groupedBubble.getEntry());
        groupSummary.addChildNotification(groupedBubble);
        assertTrue(mBubbleData.hasBubbleInStackWithKey(groupedBubble.getEntry().getKey()));

        // WHEN the summary is dismissed
        mBubblesManager.handleDismissalInterception(groupSummary.getEntry());

        // THEN the summary and bubbled child are suppressed from the shade
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                groupedBubble.getEntry().getKey(),
                groupSummary.getEntry().getSbn().getGroupKey()));
        assertTrue(mBubbleController.getImplCachedState().isBubbleNotificationSuppressedFromShade(
                groupedBubble.getEntry().getKey(),
                groupSummary.getEntry().getSbn().getGroupKey()));
        assertTrue(mBubbleData.isSummarySuppressed(groupSummary.getEntry().getSbn().getGroupKey()));
    }

    @Test
    public void testAppRemovesSummary_removesAllBubbleChildren() throws Exception {
        // GIVEN a group summary with a bubble child
        ExpandableNotificationRow groupSummary = mNotificationTestHelper.createGroup(0);
        ExpandableNotificationRow groupedBubble = mNotificationTestHelper.createBubbleInGroup();
        mEntryListener.onPendingEntryAdded(groupedBubble.getEntry());
        when(mNotificationEntryManager.getPendingOrActiveNotif(groupedBubble.getEntry().getKey()))
                .thenReturn(groupedBubble.getEntry());
        groupSummary.addChildNotification(groupedBubble);
        assertTrue(mBubbleData.hasBubbleInStackWithKey(groupedBubble.getEntry().getKey()));

        // GIVEN the summary is dismissed
        mBubblesManager.handleDismissalInterception(groupSummary.getEntry());

        // WHEN the summary is cancelled by the app
        mEntryListener.onEntryRemoved(groupSummary.getEntry(), null, false, REASON_APP_CANCEL);

        // THEN the summary and its children are removed from bubble data
        assertFalse(mBubbleData.hasBubbleInStackWithKey(groupedBubble.getEntry().getKey()));
        assertFalse(mBubbleData.isSummarySuppressed(
                groupSummary.getEntry().getSbn().getGroupKey()));
    }

    @Test
    public void testSummaryDismissal_marksBubblesHiddenFromShadeAndDismissesNonBubbledChildren()
            throws Exception {
        // GIVEN a group summary with two (non-bubble) children and one bubble child
        ExpandableNotificationRow groupSummary = mNotificationTestHelper.createGroup(2);
        ExpandableNotificationRow groupedBubble = mNotificationTestHelper.createBubbleInGroup();
        when(mNotificationEntryManager.getPendingOrActiveNotif(groupedBubble.getEntry().getKey()))
                .thenReturn(groupedBubble.getEntry());
        mEntryListener.onPendingEntryAdded(groupedBubble.getEntry());
        groupSummary.addChildNotification(groupedBubble);

        // WHEN the summary is dismissed
        mBubblesManager.handleDismissalInterception(groupSummary.getEntry());

        // THEN only the NON-bubble children are dismissed
        List<ExpandableNotificationRow> childrenRows = groupSummary.getAttachedChildren();
        verify(mNotificationEntryManager, times(1)).performRemoveNotification(
                eq(childrenRows.get(0).getEntry().getSbn()), any(),
                eq(REASON_GROUP_SUMMARY_CANCELED));
        verify(mNotificationEntryManager, times(1)).performRemoveNotification(
                eq(childrenRows.get(1).getEntry().getSbn()), any(),
                eq(REASON_GROUP_SUMMARY_CANCELED));
        verify(mNotificationEntryManager, never()).performRemoveNotification(
                eq(groupedBubble.getEntry().getSbn()), any(), anyInt());

        // THEN the bubble child is suppressed from the shade
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                groupedBubble.getEntry().getKey(),
                groupedBubble.getEntry().getSbn().getGroupKey()));
        assertTrue(mBubbleController.getImplCachedState().isBubbleNotificationSuppressedFromShade(
                groupedBubble.getEntry().getKey(),
                groupedBubble.getEntry().getSbn().getGroupKey()));

        // THEN the summary is removed from GroupManager
        verify(mNotificationGroupManager, times(1)).onEntryRemoved(groupSummary.getEntry());
    }


    /**
     * Verifies that when a non visually interruptive update occurs for a bubble in the overflow,
     * the that bubble does not get promoted from the overflow.
     */
    @Test
    public void test_notVisuallyInterruptive_updateOverflowBubble_notAdded() {
        // Setup
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.updateBubble(mBubbleEntry2);
        assertTrue(mBubbleController.hasBubbles());

        // Overflow it
        mBubbleData.dismissBubbleWithKey(mRow.getKey(),
                Bubbles.DISMISS_USER_GESTURE);
        assertThat(mBubbleData.hasBubbleInStackWithKey(mRow.getKey())).isFalse();
        assertThat(mBubbleData.hasOverflowBubbleWithKey(mRow.getKey())).isTrue();

        // Test
        mBubbleController.updateBubble(mBubbleEntry);
        assertThat(mBubbleData.hasBubbleInStackWithKey(mRow.getKey())).isFalse();
    }

    /**
     * Verifies that when the user changes, the bubbles in the overflow list is cleared. Doesn't
     * test the loading from the repository which would be a nice thing to add.
     */
    @Test
    public void testOnUserChanged_overflowState() {
        int firstUserId = mBubbleEntry.getStatusBarNotification().getUser().getIdentifier();
        int secondUserId = mBubbleEntryUser11.getStatusBarNotification().getUser().getIdentifier();

        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.updateBubble(mBubbleEntry2);
        assertTrue(mBubbleController.hasBubbles());
        mBubbleData.dismissAll(Bubbles.DISMISS_USER_GESTURE);

        // Verify these are in the overflow
        assertThat(mBubbleData.getOverflowBubbleWithKey(mBubbleEntry.getKey())).isNotNull();
        assertThat(mBubbleData.getOverflowBubbleWithKey(mBubbleEntry2.getKey())).isNotNull();

        // Switch users
        mBubbleController.onUserChanged(secondUserId);
        assertThat(mBubbleData.getOverflowBubbles()).isEmpty();

        // Give this user some bubbles
        mBubbleController.updateBubble(mBubbleEntryUser11);
        mBubbleController.updateBubble(mBubbleEntry2User11);
        assertTrue(mBubbleController.hasBubbles());
        mBubbleData.dismissAll(Bubbles.DISMISS_USER_GESTURE);

        // Verify these are in the overflow
        assertThat(mBubbleData.getOverflowBubbleWithKey(mBubbleEntryUser11.getKey())).isNotNull();
        assertThat(mBubbleData.getOverflowBubbleWithKey(mBubbleEntry2User11.getKey())).isNotNull();
    }


    /**
     * Verifies that the package manager for the user is used when loading info for the bubble.
     */
    @Test
    public void test_bubbleViewInfoGetPackageForUser() throws Exception {
        final int workProfileUserId = 10;
        final UserHandle workUser = new UserHandle(workProfileUserId);
        final String workPkg = "work.pkg";

        final Bubble bubble = createBubble(workProfileUserId, workPkg);
        assertEquals(workProfileUserId, bubble.getUser().getIdentifier());

        final Context context = setUpContextWithPackageManager(workPkg, null /* AppInfo */);
        when(context.getResources()).thenReturn(mContext.getResources());
        final Context userContext = setUpContextWithPackageManager(workPkg,
                mock(ApplicationInfo.class));

        // If things are working correctly, StatusBar.getPackageManagerForUser will call this
        when(context.createPackageContextAsUser(eq(workPkg), anyInt(), eq(workUser)))
                .thenReturn(userContext);

        BubbleViewInfoTask.BubbleViewInfo info = BubbleViewInfoTask.BubbleViewInfo.populate(context,
                mBubbleController,
                mBubbleController.getStackView(),
                new BubbleIconFactory(mContext),
                bubble,
                true /* skipInflation */);
        verify(userContext, times(1)).getPackageManager();
        verify(context, times(1)).createPackageContextAsUser(eq(workPkg),
                eq(Context.CONTEXT_RESTRICTED),
                eq(workUser));
        assertNotNull(info);
    }

    /** Creates a bubble using the userId and package. */
    private Bubble createBubble(int userId, String pkg) {
        final UserHandle userHandle = new UserHandle(userId);
        NotificationEntry workEntry = new NotificationEntryBuilder()
                .setPkg(pkg)
                .setUser(userHandle)
                .build();
        workEntry.setBubbleMetadata(getMetadata());
        workEntry.setFlagBubble(true);

        return new Bubble(BubblesManager.notifToBubbleEntry(workEntry),
                null,
                mock(Bubbles.PendingIntentCanceledListener.class), new SyncExecutor());
    }

    /** Creates a context that will return a PackageManager with specific AppInfo. */
    private Context setUpContextWithPackageManager(String pkg, ApplicationInfo info)
            throws Exception {
        final PackageManager pm = mock(PackageManager.class);
        when(pm.getApplicationInfo(eq(pkg), anyInt())).thenReturn(info);

        if (info != null) {
            Drawable d = mock(Drawable.class);
            when(d.getBounds()).thenReturn(new Rect());
            when(pm.getApplicationIcon(anyString())).thenReturn(d);
            when(pm.getUserBadgedIcon(any(), any())).thenReturn(d);
        }

        final Context context = mock(Context.class);
        when(context.getPackageName()).thenReturn(pkg);
        when(context.getPackageManager()).thenReturn(pm);
        return context;
    }

    /**
     * Sets the bubble metadata flags for this entry. These ]flags are normally set by
     * NotificationManagerService when the notification is sent, however, these tests do not
     * go through that path so we set them explicitly when testing.
     */
    private void setMetadataFlags(NotificationEntry entry, int flag, boolean enableFlag) {
        Notification.BubbleMetadata bubbleMetadata =
                entry.getSbn().getNotification().getBubbleMetadata();
        int flags = bubbleMetadata.getFlags();
        if (enableFlag) {
            flags |= flag;
        } else {
            flags &= ~flag;
        }
        bubbleMetadata.setFlags(flags);
    }

    private Notification.BubbleMetadata getMetadata() {
        Intent target = new Intent(mContext, BubblesTestActivity.class);
        PendingIntent bubbleIntent = PendingIntent.getActivity(mContext, 0, target, FLAG_MUTABLE);

        return new Notification.BubbleMetadata.Builder(bubbleIntent,
                Icon.createWithResource(mContext, R.drawable.bubble_ic_create_bubble))
                .build();
    }

    /**
     * Asserts that the bubble stack is expanded and also validates the cached state is updated.
     */
    private void assertStackExpanded() {
        assertTrue(mBubbleController.isStackExpanded());
        assertTrue(mBubbleController.getImplCachedState().isStackExpanded());
    }

    /**
     * Asserts that the bubble stack is collapsed and also validates the cached state is updated.
     */
    private void assertStackCollapsed() {
        assertFalse(mBubbleController.isStackExpanded());
        assertFalse(mBubbleController.getImplCachedState().isStackExpanded());
    }

    /**
     * Asserts that a bubble notification is suppressed from the shade and also validates the cached
     * state is updated.
     */
    private void assertBubbleNotificationSuppressedFromShade(BubbleEntry entry) {
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                entry.getKey(), entry.getGroupKey()));
        assertTrue(mBubbleController.getImplCachedState().isBubbleNotificationSuppressedFromShade(
                entry.getKey(), entry.getGroupKey()));
    }

    /**
     * Asserts that a bubble notification is not suppressed from the shade and also validates the
     * cached state is updated.
     */
    private void assertBubbleNotificationNotSuppressedFromShade(BubbleEntry entry) {
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                entry.getKey(), entry.getGroupKey()));
        assertFalse(mBubbleController.getImplCachedState().isBubbleNotificationSuppressedFromShade(
                entry.getKey(), entry.getGroupKey()));
    }
}
