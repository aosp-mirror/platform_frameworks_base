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

package com.android.systemui.bubbles;

import static android.app.Notification.FLAG_BUBBLE;
import static android.service.notification.NotificationListenerService.REASON_APP_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_CANCEL_ALL;
import static android.service.notification.NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.pm.LauncherApps;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.face.FaceManager;
import android.os.Handler;
import android.os.PowerManager;
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
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.NotificationShadeWindowView;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.FloatingContentCoordinator;

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
 * {@link NewNotifPipelineBubbleControllerTest}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class BubbleControllerTest extends SysuiTestCase {
    @Mock
    private NotificationEntryManager mNotificationEntryManager;
    @Mock
    private NotificationGroupManager mNotificationGroupManager;
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

    private TestableBubbleController mBubbleController;
    private NotificationShadeWindowController mNotificationShadeWindowController;
    private NotificationEntryListener mEntryListener;
    private NotificationRemoveInterceptor mRemoveInterceptor;

    private NotificationTestHelper mNotificationTestHelper;
    private ExpandableNotificationRow mRow;
    private ExpandableNotificationRow mRow2;
    private ExpandableNotificationRow mRow3;
    private ExpandableNotificationRow mNonBubbleNotifRow;

    @Mock
    private BubbleController.BubbleExpandListener mBubbleExpandListener;
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

    private BubbleData mBubbleData;

    private TestableLooper mTestableLooper;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);

        mContext.addMockSystemService(FaceManager.class, mFaceManager);
        when(mColorExtractor.getNeutralColors()).thenReturn(mGradientColors);

        mNotificationShadeWindowController = new NotificationShadeWindowController(mContext,
                mWindowManager, mActivityManager, mDozeParameters, mStatusBarStateController,
                mConfigurationController, mKeyguardViewMediator, mKeyguardBypassController,
                mColorExtractor, mDumpManager);
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

        // Return non-null notification data from the NEM
        when(mNotificationEntryManager
                .getActiveNotificationUnfiltered(mRow.getEntry().getKey())).thenReturn(
                mRow.getEntry());

        mZenModeConfig.suppressedVisualEffects = 0;
        when(mZenModeController.getConfig()).thenReturn(mZenModeConfig);

        mSysUiState = new SysUiState();
        mSysUiState.addCallback(sysUiFlags ->
                mSysUiStateBubblesExpanded =
                        (sysUiFlags & QuickStepContract.SYSUI_STATE_BUBBLES_EXPANDED) != 0);

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
        mBubbleData = new BubbleData(mContext);
        when(mFeatureFlagsOldPipeline.isNewNotifPipelineRenderingEnabled()).thenReturn(false);
        mBubbleController = new TestableBubbleController(
                mContext,
                mNotificationShadeWindowController,
                mStatusBarStateController,
                mShadeController,
                mBubbleData,
                mConfigurationController,
                interruptionStateProvider,
                mZenModeController,
                mLockscreenUserManager,
                mNotificationGroupManager,
                mNotificationEntryManager,
                mNotifPipeline,
                mFeatureFlagsOldPipeline,
                mDumpManager,
                mFloatingContentCoordinator,
                mDataRepository,
                mSysUiState,
                mock(INotificationManager.class),
                mStatusBarService,
                mWindowManager,
                mLauncherApps);
        mBubbleController.setExpandListener(mBubbleExpandListener);

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
        mBubbleController.updateBubble(mRow.getEntry());
        assertTrue(mBubbleController.hasBubbles());

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testHasBubbles() {
        assertFalse(mBubbleController.hasBubbles());
        mBubbleController.updateBubble(mRow.getEntry());
        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testRemoveBubble() {
        mBubbleController.updateBubble(mRow.getEntry());
        assertNotNull(mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()));
        assertTrue(mBubbleController.hasBubbles());
        verify(mNotificationEntryManager).updateNotifications(any());

        mBubbleController.removeBubble(
                mRow.getEntry().getKey(), BubbleController.DISMISS_USER_GESTURE);
        assertNull(mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()));
        verify(mNotificationEntryManager, times(2)).updateNotifications(anyString());

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testPromoteBubble_autoExpand() throws Exception {
        mBubbleController.updateBubble(mRow2.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());
        when(mNotificationEntryManager.getPendingOrActiveNotif(mRow.getEntry().getKey()))
                .thenReturn(mRow.getEntry());
        when(mNotificationEntryManager.getPendingOrActiveNotif(mRow2.getEntry().getKey()))
                .thenReturn(mRow2.getEntry());
        mBubbleController.removeBubble(
                mRow.getEntry().getKey(), BubbleController.DISMISS_USER_GESTURE);

        Bubble b = mBubbleData.getOverflowBubbleWithKey(mRow.getEntry().getKey());
        assertThat(mBubbleData.getOverflowBubbles()).isEqualTo(ImmutableList.of(b));
        verify(mNotificationEntryManager, never()).performRemoveNotification(
                eq(mRow.getEntry().getSbn()), anyInt());
        assertThat(mRow.getEntry().isBubble()).isFalse();

        Bubble b2 = mBubbleData.getBubbleInStackWithKey(mRow2.getEntry().getKey());
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
        mBubbleController.updateBubble(mRow2.getEntry());
        mBubbleController.updateBubble(mRow.getEntry(), /* suppressFlyout */
                false, /* showInShade */ true);
        when(mNotificationEntryManager.getPendingOrActiveNotif(mRow.getEntry().getKey()))
                .thenReturn(mRow.getEntry());
        when(mNotificationEntryManager.getPendingOrActiveNotif(mRow2.getEntry().getKey()))
                .thenReturn(mRow2.getEntry());
        mBubbleController.removeBubble(
                mRow.getEntry().getKey(), BubbleController.DISMISS_USER_GESTURE);

        mBubbleController.removeBubble(
                mRow.getEntry().getKey(), BubbleController.DISMISS_NOTIF_CANCEL);
        verify(mNotificationEntryManager, times(1)).performRemoveNotification(
                eq(mRow.getEntry().getSbn()), anyInt());
        assertThat(mBubbleData.getOverflowBubbles()).isEmpty();
        assertFalse(mRow.getEntry().isBubble());
    }

    @Test
    public void testUserChange_doesNotRemoveNotif() {
        mBubbleController.updateBubble(mRow.getEntry());
        assertTrue(mBubbleController.hasBubbles());

        mBubbleController.removeBubble(
                mRow.getEntry().getKey(), BubbleController.DISMISS_USER_CHANGED);
        verify(mNotificationEntryManager, never()).performRemoveNotification(
                eq(mRow.getEntry().getSbn()), anyInt());
        assertFalse(mBubbleController.hasBubbles());
        assertFalse(mSysUiStateBubblesExpanded);
        assertTrue(mRow.getEntry().isBubble());
    }

    @Test
    public void testDismissStack() {
        mBubbleController.updateBubble(mRow.getEntry());
        verify(mNotificationEntryManager, times(1)).updateNotifications(any());
        assertNotNull(mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()));
        mBubbleController.updateBubble(mRow2.getEntry());
        verify(mNotificationEntryManager, times(2)).updateNotifications(any());
        assertNotNull(mBubbleData.getBubbleInStackWithKey(mRow2.getEntry().getKey()));
        assertTrue(mBubbleController.hasBubbles());

        mBubbleData.dismissAll(BubbleController.DISMISS_USER_GESTURE);
        verify(mNotificationEntryManager, times(3)).updateNotifications(any());
        assertNull(mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()));
        assertNull(mBubbleData.getBubbleInStackWithKey(mRow2.getEntry().getKey()));

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testExpandCollapseStack() {
        assertFalse(mBubbleController.isStackExpanded());

        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        // Expand the stack
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleData.setExpanded(true);
        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().getKey());

        assertTrue(mSysUiStateBubblesExpanded);

        // Make sure the notif is suppressed
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        // Collapse
        mBubbleController.collapseStack();
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow.getEntry().getKey());
        assertFalse(mBubbleController.isStackExpanded());

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    @Ignore("Currently broken.")
    public void testCollapseAfterChangingExpandedBubble() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mEntryListener.onPendingEntryAdded(mRow2.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());
        mBubbleController.updateBubble(mRow2.getEntry());

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow2.getEntry()));

        // Expand
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleData.setExpanded(true);
        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener, atLeastOnce()).onBubbleExpandChanged(
                true, mRow2.getEntry().getKey());

        assertTrue(mSysUiStateBubblesExpanded);

        // Last added is the one that is expanded
        assertEquals(mRow2.getEntry().getKey(), mBubbleData.getSelectedBubble().getKey());
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow2.getEntry()));

        // Switch which bubble is expanded
        mBubbleData.setSelectedBubble(mBubbleData.getBubbleInStackWithKey(
                mRow.getEntry().getKey()));
        mBubbleData.setExpanded(true);
        assertEquals(mRow.getEntry().getKey(), mBubbleData.getBubbleInStackWithKey(
                stackView.getExpandedBubble().getKey()).getKey());
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        // collapse for previous bubble
        verify(mBubbleExpandListener, atLeastOnce()).onBubbleExpandChanged(
                false, mRow2.getEntry().getKey());
        // expand for selected bubble
        verify(mBubbleExpandListener, atLeastOnce()).onBubbleExpandChanged(
                true, mRow.getEntry().getKey());

        // Collapse
        mBubbleController.collapseStack();
        assertFalse(mBubbleController.isStackExpanded());

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testExpansionRemovesShowInShadeAndDot() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        mTestableLooper.processAllMessages();
        assertTrue(mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()).showDot());

        // Expand
        mBubbleData.setExpanded(true);
        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().getKey());

        assertTrue(mSysUiStateBubblesExpanded);

        // Notif is suppressed after expansion
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()).showDot());
    }

    @Test
    public void testUpdateWhileExpanded_DoesntChangeShowInShadeAndDot() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        mTestableLooper.processAllMessages();
        assertTrue(mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()).showDot());

        // Expand
        mBubbleData.setExpanded(true);
        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().getKey());

        assertTrue(mSysUiStateBubblesExpanded);

        // Notif is suppressed after expansion
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()).showDot());

        // Send update
        mEntryListener.onPreEntryUpdated(mRow.getEntry());

        // Nothing should have changed
        // Notif is suppressed after expansion
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()).showDot());
    }

    @Test
    public void testRemoveLastExpandedCollapses() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mEntryListener.onPendingEntryAdded(mRow2.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());
        mBubbleController.updateBubble(mRow2.getEntry());

        // Expand
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleData.setExpanded(true);

        assertTrue(mSysUiStateBubblesExpanded);

        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow2.getEntry().getKey());

        // Last added is the one that is expanded
        assertEquals(mRow2.getEntry().getKey(), mBubbleData.getBubbleInStackWithKey(
                stackView.getExpandedBubble().getKey()).getKey());
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow2.getEntry()));

        // Dismiss currently expanded
        mBubbleController.removeBubble(
                mBubbleData.getBubbleInStackWithKey(
                        stackView.getExpandedBubble().getKey()).getKey(),
                BubbleController.DISMISS_USER_GESTURE);
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow2.getEntry().getKey());

        // Make sure first bubble is selected
        assertEquals(mRow.getEntry().getKey(), mBubbleData.getBubbleInStackWithKey(
                stackView.getExpandedBubble().getKey()).getKey());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().getKey());

        // Dismiss that one
        mBubbleController.removeBubble(
                mBubbleData.getBubbleInStackWithKey(
                        stackView.getExpandedBubble().getKey()).getKey(),
                BubbleController.DISMISS_USER_GESTURE);

        // Make sure state changes and collapse happens
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow.getEntry().getKey());
        assertFalse(mBubbleController.hasBubbles());

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testAutoExpand_fails_noFlag() {
        assertFalse(mBubbleController.isStackExpanded());
        setMetadataFlags(mRow.getEntry(),
                Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE, false /* enableFlag */);

        // Add the auto expand bubble
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // Expansion shouldn't change
        verify(mBubbleExpandListener, never()).onBubbleExpandChanged(false /* expanded */,
                mRow.getEntry().getKey());
        assertFalse(mBubbleController.isStackExpanded());

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testAutoExpand_succeeds_withFlag() {
        setMetadataFlags(mRow.getEntry(),
                Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE, true /* enableFlag */);

        // Add the auto expand bubble
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // Expansion should change
        verify(mBubbleExpandListener).onBubbleExpandChanged(true /* expanded */,
                mRow.getEntry().getKey());
        assertTrue(mBubbleController.isStackExpanded());

        assertTrue(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testSuppressNotif_onInitialNotif() {
        setMetadataFlags(mRow.getEntry(),
                Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION, true /* enableFlag */);

        // Add the suppress notif bubble
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // Notif should be suppressed because we were foreground
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));
        // Dot + flyout is hidden because notif is suppressed
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()).showDot());
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()).showFlyout());

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testSuppressNotif_onUpdateNotif() {
        mBubbleController.updateBubble(mRow.getEntry());

        // Should not be suppressed
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));
        // Should show dot
        assertTrue(mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()).showDot());

        // Update to suppress notif
        setMetadataFlags(mRow.getEntry(),
                Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION, true /* enableFlag */);
        mBubbleController.updateBubble(mRow.getEntry());

        // Notif should be suppressed
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));
        // Dot + flyout is hidden because notif is suppressed
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()).showDot());
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()).showFlyout());

        assertFalse(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testExpandStackAndSelectBubble_removedFirst() {
        final String key = mRow.getEntry().getKey();

        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // Simulate notification cancellation.
        mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getEntry().getKey(), mRow.getEntry(), REASON_APP_CANCEL);

        mBubbleController.expandStackAndSelectBubble(mRow.getEntry());

        assertTrue(mSysUiStateBubblesExpanded);
    }

    @Test
    public void testMarkNewNotificationAsShowInShade() {
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        mTestableLooper.processAllMessages();
        assertTrue(mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()).showDot());
    }

    @Test
    public void testAddNotif_notBubble() {
        mEntryListener.onPendingEntryAdded(mNonBubbleNotifRow.getEntry());
        mEntryListener.onPreEntryUpdated(mNonBubbleNotifRow.getEntry());

        assertThat(mBubbleController.hasBubbles()).isFalse();
    }

    @Test
    public void testDeleteIntent_removeBubble_aged() throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mRow.getEntry());
        mBubbleController.removeBubble(mRow.getEntry().getKey(), BubbleController.DISMISS_AGED);
        verify(mDeleteIntent, never()).send();
    }

    @Test
    public void testDeleteIntent_removeBubble_user() throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mRow.getEntry());
        mBubbleController.removeBubble(
                mRow.getEntry().getKey(), BubbleController.DISMISS_USER_GESTURE);
        verify(mDeleteIntent, times(1)).send();
    }

    @Test
    public void testDeleteIntent_dismissStack() throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mRow.getEntry());
        mBubbleController.updateBubble(mRow2.getEntry());
        mBubbleData.dismissAll(BubbleController.DISMISS_USER_GESTURE);
        verify(mDeleteIntent, times(2)).send();
    }

    @Test
    public void testRemoveBubble_noLongerBubbleAfterUpdate()
            throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mRow.getEntry());
        assertTrue(mBubbleController.hasBubbles());

        mRow.getEntry().getSbn().getNotification().flags &= ~FLAG_BUBBLE;
        NotificationListenerService.Ranking ranking = new RankingBuilder(
                mRow.getEntry().getRanking()).setCanBubble(false).build();
        mRow.getEntry().setRanking(ranking);
        mEntryListener.onPreEntryUpdated(mRow.getEntry());

        assertFalse(mBubbleController.hasBubbles());
        verify(mDeleteIntent, never()).send();
    }

    @Test
    public void testRemoveBubble_succeeds_appCancel() {
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());

        boolean intercepted = mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getEntry().getKey(), mRow.getEntry(), REASON_APP_CANCEL);

        // Cancels always remove so no need to intercept
        assertFalse(intercepted);
    }

    @Test
    public void testRemoveBubble_entryListenerRemove() {
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());

        // Removes the notification
        mEntryListener.onEntryRemoved(mRow.getEntry(), null, false, REASON_APP_CANCEL);
        assertFalse(mBubbleController.hasBubbles());
    }

    @Test
    public void removeBubble_clearAllIntercepted()  {
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        boolean intercepted = mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getEntry().getKey(), mRow.getEntry(), REASON_CANCEL_ALL);

        // Intercept!
        assertTrue(intercepted);
        // Should update show in shade state
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));
    }

    @Test
    public void removeBubble_userDismissNotifIntercepted() {
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        boolean intercepted = mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getEntry().getKey(), mRow.getEntry(), REASON_CANCEL);

        // Intercept!
        assertTrue(intercepted);
        // Should update show in shade state
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));
    }

    @Test
    public void removeNotif_inOverflow_intercepted() {
        // Get bubble with notif in shade.
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());
        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        // Dismiss the bubble into overflow.
        mBubbleController.removeBubble(
                mRow.getEntry().getKey(), BubbleController.DISMISS_USER_GESTURE);
        assertFalse(mBubbleController.hasBubbles());

        boolean intercepted = mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getEntry().getKey(), mRow.getEntry(), REASON_CANCEL);

        // Notif is no longer a bubble, but still in overflow, so we intercept removal.
        assertTrue(intercepted);
    }

    @Test
    public void removeNotif_notInOverflow_notIntercepted() {
        // Get bubble with notif in shade.
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        mBubbleController.removeBubble(
                mRow.getEntry().getKey(), BubbleController.DISMISS_NO_LONGER_BUBBLE);
        assertFalse(mBubbleController.hasBubbles());

        boolean intercepted = mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getEntry().getKey(), mRow.getEntry(), REASON_CANCEL);

        // Notif is no longer a bubble, so we should not intercept removal.
        assertFalse(intercepted);
    }

    @Test
    public void testOverflowBubble_maxReached_notInShade_bubbleRemoved() {
        mBubbleController.updateBubble(
                mRow.getEntry(), /* suppressFlyout */ false, /* showInShade */ false);
        mBubbleController.updateBubble(
                mRow2.getEntry(), /* suppressFlyout */ false, /* showInShade */ false);
        mBubbleController.updateBubble(
                mRow3.getEntry(), /* suppressFlyout */ false, /* showInShade */ false);
        when(mNotificationEntryManager.getPendingOrActiveNotif(mRow.getEntry().getKey()))
                .thenReturn(mRow.getEntry());
        when(mNotificationEntryManager.getPendingOrActiveNotif(mRow2.getEntry().getKey()))
                .thenReturn(mRow2.getEntry());
        when(mNotificationEntryManager.getPendingOrActiveNotif(mRow3.getEntry().getKey()))
                .thenReturn(mRow3.getEntry());
        assertEquals(mBubbleData.getBubbles().size(), 3);

        mBubbleData.setMaxOverflowBubbles(1);
        mBubbleController.removeBubble(
                mRow.getEntry().getKey(), BubbleController.DISMISS_USER_GESTURE);
        assertEquals(mBubbleData.getBubbles().size(), 2);
        assertEquals(mBubbleData.getOverflowBubbles().size(), 1);

        mBubbleController.removeBubble(
                mRow2.getEntry().getKey(), BubbleController.DISMISS_USER_GESTURE);
        // Overflow max of 1 is reached; mRow is oldest, so it gets removed
        verify(mNotificationEntryManager, times(1)).performRemoveNotification(
                mRow.getEntry().getSbn(), REASON_CANCEL);
        assertEquals(mBubbleData.getBubbles().size(), 1);
        assertEquals(mBubbleData.getOverflowBubbles().size(), 1);
    }

    @Test
    public void testNotifyShadeSuppressionChange_notificationDismiss() {
        BubbleController.NotificationSuppressionChangedListener listener =
                mock(BubbleController.NotificationSuppressionChangedListener.class);
        mBubbleData.setSuppressionChangedListener(listener);

        mEntryListener.onPendingEntryAdded(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getEntry().getKey(), mRow.getEntry(), REASON_CANCEL);

        // Should update show in shade state
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        // Should notify delegate that shade state changed
        verify(listener).onBubbleNotificationSuppressionChange(
                mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()));
    }

    @Test
    public void testNotifyShadeSuppressionChange_bubbleExpanded() {
        BubbleController.NotificationSuppressionChangedListener listener =
                mock(BubbleController.NotificationSuppressionChangedListener.class);
        mBubbleData.setSuppressionChangedListener(listener);

        mEntryListener.onPendingEntryAdded(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        mBubbleData.setExpanded(true);

        // Once a bubble is expanded the notif is suppressed
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        // Should notify delegate that shade state changed
        verify(listener).onBubbleNotificationSuppressionChange(
                mBubbleData.getBubbleInStackWithKey(mRow.getEntry().getKey()));
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
        mBubbleController.handleDismissalInterception(groupSummary.getEntry());

        // THEN the summary and bubbled child are suppressed from the shade
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                groupedBubble.getEntry()));
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
        mBubbleController.handleDismissalInterception(groupSummary.getEntry());

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
        mBubbleController.handleDismissalInterception(groupSummary.getEntry());

        // THEN only the NON-bubble children are dismissed
        List<ExpandableNotificationRow> childrenRows = groupSummary.getAttachedChildren();
        verify(mNotificationEntryManager, times(1)).performRemoveNotification(
                childrenRows.get(0).getEntry().getSbn(), REASON_GROUP_SUMMARY_CANCELED);
        verify(mNotificationEntryManager, times(1)).performRemoveNotification(
                childrenRows.get(1).getEntry().getSbn(), REASON_GROUP_SUMMARY_CANCELED);
        verify(mNotificationEntryManager, never()).performRemoveNotification(
                eq(groupedBubble.getEntry().getSbn()), anyInt());

        // THEN the bubble child is suppressed from the shade
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                groupedBubble.getEntry()));

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
        mBubbleController.updateBubble(mRow.getEntry());
        mBubbleController.updateBubble(mRow2.getEntry());
        assertTrue(mBubbleController.hasBubbles());

        // Overflow it
        mBubbleData.dismissBubbleWithKey(mRow.getEntry().getKey(),
                BubbleController.DISMISS_USER_GESTURE);
        assertThat(mBubbleData.hasBubbleInStackWithKey(mRow.getEntry().getKey())).isFalse();
        assertThat(mBubbleData.hasOverflowBubbleWithKey(mRow.getEntry().getKey())).isTrue();

        // Test
        mBubbleController.updateBubble(mRow.getEntry());
        assertThat(mBubbleData.hasBubbleInStackWithKey(mRow.getEntry().getKey())).isFalse();
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
}
