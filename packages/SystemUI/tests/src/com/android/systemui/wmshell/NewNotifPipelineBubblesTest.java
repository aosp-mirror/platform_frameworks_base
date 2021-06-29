/*
 * Copyright (C) 2020 The Android Open Source Project
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.INotificationManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.pm.LauncherApps;
import android.hardware.display.AmbientDisplayConfiguration;
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
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.RankingBuilder;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationFilter;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
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
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.bubbles.BubbleData;
import com.android.wm.shell.bubbles.BubbleDataRepository;
import com.android.wm.shell.bubbles.BubbleEntry;
import com.android.wm.shell.bubbles.BubbleLogger;
import com.android.wm.shell.bubbles.BubbleOverflow;
import com.android.wm.shell.bubbles.BubbleStackView;
import com.android.wm.shell.bubbles.Bubbles;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.FloatingContentCoordinator;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TaskStackListenerImpl;

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
 * Tests the NotifPipeline setup with BubbleController.
 * The NotificationEntryManager setup with BubbleController is tested in
 * {@link BubblesTest}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class NewNotifPipelineBubblesTest extends SysuiTestCase {
    @Mock
    private NotificationEntryManager mNotificationEntryManager;
    @Mock
    private NotificationGroupManagerLegacy mNotificationGroupManager;
    @Mock
    private BubblesManager.NotifCallback mNotifCallback;
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
    @Mock
    private NotificationShadeWindowView mNotificationShadeWindowView;

    private SysUiState mSysUiState = new SysUiState();

    @Captor
    private ArgumentCaptor<NotifCollectionListener> mNotifListenerCaptor;
    private BubblesManager mBubblesManager;
    private TestableBubbleController mBubbleController;
    private NotificationShadeWindowControllerImpl mNotificationShadeWindowController;
    private NotifCollectionListener mEntryListener;
    private NotificationTestHelper mNotificationTestHelper;
    private NotificationEntry mRow;
    private NotificationEntry mRow2;
    private ExpandableNotificationRow mNonBubbleNotifRow;
    private BubbleEntry mBubbleEntry;
    private BubbleEntry mBubbleEntry2;
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
    private FeatureFlags mFeatureFlagsNewPipeline;
    @Mock
    private DumpManager mDumpManager;
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
        mNonBubbleNotifRow = mNotificationTestHelper.createRow();
        mBubbleEntry = BubblesManager.notifToBubbleEntry(mRow);
        mBubbleEntry2 = BubblesManager.notifToBubbleEntry(mRow2);

        mZenModeConfig.suppressedVisualEffects = 0;
        when(mZenModeController.getConfig()).thenReturn(mZenModeConfig);

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
        when(mFeatureFlagsNewPipeline.isNewNotifPipelineRenderingEnabled()).thenReturn(true);
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
                mock(Handler.class));
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
                mFeatureFlagsNewPipeline,
                mDumpManager,
                syncExecutor);
        mBubblesManager.addNotifCallback(mNotifCallback);

        // Get a reference to the BubbleController's entry listener
        verify(mNotifPipeline, atLeastOnce())
                .addCollectionListener(mNotifListenerCaptor.capture());
        mEntryListener = mNotifListenerCaptor.getValue();
    }

    @Test
    public void testAddBubble() {
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());
    }

    @Test
    public void testHasBubbles() {
        assertFalse(mBubbleController.hasBubbles());
        mBubbleController.updateBubble(mBubbleEntry);
        assertTrue(mBubbleController.hasBubbles());
    }

    @Test
    public void testRemoveBubble() {
        mBubbleController.updateBubble(mBubbleEntry);
        assertNotNull(mBubbleData.getBubbleInStackWithKey(mRow.getKey()));
        assertTrue(mBubbleController.hasBubbles());
        verify(mNotifCallback, times(1)).invalidateNotifications(anyString());

        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);
        assertNull(mBubbleData.getBubbleInStackWithKey(mRow.getKey()));
        verify(mNotifCallback, times(2)).invalidateNotifications(anyString());
    }

    @Test
    public void testRemoveBubble_withDismissedNotif_inOverflow() {
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        // Make it look like dismissed notif
        mBubbleData.getBubbleInStackWithKey(mRow.getKey()).setSuppressNotification(true);

        // Now remove the bubble
        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);
        assertTrue(mBubbleData.hasOverflowBubbleWithKey(mRow.getKey()));

        // We don't remove the notification since the bubble is still in overflow.
        verify(mNotifCallback, never()).removeNotification(eq(mRow), any(), anyInt());
        assertFalse(mBubbleController.hasBubbles());
    }

    @Test
    public void testRemoveBubble_withDismissedNotif_notInOverflow() {
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);
        when(mNotificationEntryManager.getPendingOrActiveNotif(mRow.getKey()))
                .thenReturn(mRow);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        // Make it look like dismissed notif
        mBubbleData.getBubbleInStackWithKey(mRow.getKey()).setSuppressNotification(true);

        // Now remove the bubble
        mBubbleController.removeBubble(
                mRow.getKey(), Bubbles.DISMISS_NOTIF_CANCEL);
        assertFalse(mBubbleData.hasOverflowBubbleWithKey(mRow.getKey()));

        // Since the notif is dismissed and not in overflow, once the bubble is removed,
        // removeNotification gets called to really remove the notif
        verify(mNotifCallback, times(1)).removeNotification(eq(mRow),
                any(), anyInt());
        assertFalse(mBubbleController.hasBubbles());
    }

    @Test
    public void testDismissStack() {
        mBubbleController.updateBubble(mBubbleEntry);
        verify(mNotifCallback, times(1)).invalidateNotifications(anyString());
        assertNotNull(mBubbleData.getBubbleInStackWithKey(mRow.getKey()));
        mBubbleController.updateBubble(mBubbleEntry2);
        verify(mNotifCallback, times(2)).invalidateNotifications(anyString());
        assertNotNull(mBubbleData.getBubbleInStackWithKey(mRow2.getKey()));
        assertTrue(mBubbleController.hasBubbles());

        mBubbleData.dismissAll(Bubbles.DISMISS_USER_GESTURE);
        verify(mNotifCallback, times(3)).invalidateNotifications(anyString());
        assertNull(mBubbleData.getBubbleInStackWithKey(mRow.getKey()));
        assertNull(mBubbleData.getBubbleInStackWithKey(mRow2.getKey()));
    }

    @Test
    public void testExpandCollapseStack() {
        assertStackCollapsed();

        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        // Expand the stack
        mBubbleData.setExpanded(true);
        assertStackExpanded();
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getKey());

        // Make sure the notif is suppressed
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);

        // Collapse
        mBubbleController.collapseStack();
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow.getKey());
        assertStackCollapsed();
    }

    @Test
    @Ignore("Currently broken.")
    public void testCollapseAfterChangingExpandedBubble() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow);
        mEntryListener.onEntryAdded(mRow2);
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
    }

    @Test
    public void testExpansionRemovesShowInShadeAndDot() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow);
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

        // Notif is suppressed after expansion
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());
    }

    @Test
    public void testUpdateWhileExpanded_DoesntChangeShowInShadeAndDot() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow);
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

        // Notif is suppressed after expansion
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());

        // Send update
        mEntryListener.onEntryUpdated(mRow);

        // Nothing should have changed
        // Notif is suppressed after expansion
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());
    }

    @Test
    public void testRemoveLastExpanded_selectsOverflow() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow);
        mEntryListener.onEntryAdded(mRow2);
        mBubbleController.updateBubble(mBubbleEntry);
        mBubbleController.updateBubble(mBubbleEntry2);

        // Expand
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleData.setExpanded(true);

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
    }

    @Test
    public void testRemoveLastExpandedEmptyOverflow_collapses() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // Expand
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleData.setExpanded(true);

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
    }


    @Test
    public void testAutoExpand_fails_noFlag() {
        assertStackCollapsed();
        setMetadataFlags(mRow,
                Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE, false /* enableFlag */);

        // Add the auto expand bubble
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // Expansion shouldn't change
        verify(mBubbleExpandListener, never()).onBubbleExpandChanged(false /* expanded */,
                mRow.getKey());
        assertStackCollapsed();
    }

    @Test
    public void testAutoExpand_succeeds_withFlag() {
        setMetadataFlags(mRow,
                Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE, true /* enableFlag */);

        // Add the auto expand bubble
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // Expansion should change
        verify(mBubbleExpandListener).onBubbleExpandChanged(true /* expanded */,
                mRow.getKey());
        assertStackExpanded();
    }

    @Test
    public void testSuppressNotif_onInitialNotif() {
        setMetadataFlags(mRow,
                Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION, true /* enableFlag */);

        // Add the suppress notif bubble
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        // Notif should be suppressed because we were foreground
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
        // Dot + flyout is hidden because notif is suppressed
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());
        assertFalse(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showFlyout());
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
    }

    @Test
    public void testMarkNewNotificationAsShowInShade() {
        mEntryListener.onEntryAdded(mRow);
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        mTestableLooper.processAllMessages();
        assertTrue(mBubbleData.getBubbleInStackWithKey(mRow.getKey()).showDot());
    }

    @Test
    public void testAddNotif_notBubble() {
        mEntryListener.onEntryAdded(mNonBubbleNotifRow.getEntry());
        mEntryListener.onEntryUpdated(mNonBubbleNotifRow.getEntry());

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
        mEntryListener.onEntryUpdated(mRow);

        assertFalse(mBubbleController.hasBubbles());
        verify(mDeleteIntent, never()).send();
    }

    @Test
    public void testRemoveBubble_entryListenerRemove() {
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        assertTrue(mBubbleController.hasBubbles());

        // Removes the notification
        mEntryListener.onEntryRemoved(mRow, 0);
        assertFalse(mBubbleController.hasBubbles());
    }

    @Test
    public void removeBubble_intercepted() {
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        boolean intercepted = mBubblesManager.handleDismissalInterception(mRow);

        // Intercept!
        assertTrue(intercepted);
        // Should update show in shade state
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);
    }

    @Test
    public void removeBubble_dismissIntoOverflow_intercepted() {
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        // Dismiss the bubble
        mBubbleController.removeBubble(mRow.getKey(), Bubbles.DISMISS_USER_GESTURE);
        assertFalse(mBubbleController.hasBubbles());

        // Dismiss the notification
        boolean intercepted = mBubblesManager.handleDismissalInterception(mRow);

        // Intercept dismissal since bubble is going into overflow
        assertTrue(intercepted);
    }

    @Test
    public void removeBubble_notIntercepted() {
        mEntryListener.onEntryAdded(mRow);
        mBubbleController.updateBubble(mBubbleEntry);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        // Dismiss the bubble
        mBubbleController.removeBubble(mRow.getKey(), Bubbles.DISMISS_NOTIF_CANCEL);
        assertFalse(mBubbleController.hasBubbles());

        // Dismiss the notification
        boolean intercepted = mBubblesManager.handleDismissalInterception(mRow);

        // Not a bubble anymore so we don't intercept dismissal.
        assertFalse(intercepted);
    }

    @Test
    public void testNotifyShadeSuppressionChange_notificationDismiss() {
        mEntryListener.onEntryAdded(mRow);

        assertTrue(mBubbleController.hasBubbles());
        assertBubbleNotificationNotSuppressedFromShade(mBubbleEntry);

        mBubblesManager.handleDismissalInterception(mRow);

        // Should update show in shade state
        assertBubbleNotificationSuppressedFromShade(mBubbleEntry);

        // Should notify delegate that shade state changed
        verify(mBubbleController).onBubbleNotificationSuppressionChanged(
                mBubbleData.getBubbleInStackWithKey(mRow.getKey()));
    }

    @Test
    public void testNotifyShadeSuppressionChange_bubbleExpanded() {
        mEntryListener.onEntryAdded(mRow);

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
        mEntryListener.onEntryAdded(groupedBubble.getEntry());
        when(mNotificationEntryManager.getPendingOrActiveNotif(groupedBubble.getEntry().getKey()))
                .thenReturn(groupedBubble.getEntry());
        groupSummary.addChildNotification(groupedBubble);
        assertTrue(mBubbleData.hasBubbleInStackWithKey(groupedBubble.getEntry().getKey()));

        // WHEN the summary is dismissed
        mBubblesManager.handleDismissalInterception(groupSummary.getEntry());

        // THEN the summary and bubbled child are suppressed from the shade
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                groupedBubble.getEntry().getKey(),
                groupedBubble.getEntry().getSbn().getGroupKey()));
        assertTrue(mBubbleController.getImplCachedState().isBubbleNotificationSuppressedFromShade(
                groupedBubble.getEntry().getKey(),
                groupedBubble.getEntry().getSbn().getGroupKey()));
        assertTrue(mBubbleData.isSummarySuppressed(groupSummary.getEntry().getSbn().getGroupKey()));
    }

    @Test
    public void testAppRemovesSummary_removesAllBubbleChildren() throws Exception {
        // GIVEN a group summary with a bubble child
        ExpandableNotificationRow groupSummary = mNotificationTestHelper.createGroup(0);
        ExpandableNotificationRow groupedBubble = mNotificationTestHelper.createBubbleInGroup();
        mEntryListener.onEntryAdded(groupedBubble.getEntry());
        when(mNotificationEntryManager.getPendingOrActiveNotif(groupedBubble.getEntry().getKey()))
                .thenReturn(groupedBubble.getEntry());
        groupSummary.addChildNotification(groupedBubble);
        assertTrue(mBubbleData.hasBubbleInStackWithKey(groupedBubble.getEntry().getKey()));

        // GIVEN the summary is dismissed
        mBubblesManager.handleDismissalInterception(groupSummary.getEntry());

        // WHEN the summary is cancelled by the app
        mEntryListener.onEntryRemoved(groupSummary.getEntry(), 0);

        // THEN the summary and its children are removed from bubble data
        assertFalse(mBubbleData.hasBubbleInStackWithKey(groupedBubble.getEntry().getKey()));
        assertFalse(mBubbleData.isSummarySuppressed(
                groupSummary.getEntry().getSbn().getGroupKey()));
    }

    @Test
    public void testSummaryDismissalMarksBubblesHiddenFromShadeAndDismissesNonBubbledChildren()
            throws Exception {
        // GIVEN a group summary with two (non-bubble) children and one bubble child
        ExpandableNotificationRow groupSummary = mNotificationTestHelper.createGroup(2);
        ExpandableNotificationRow groupedBubble = mNotificationTestHelper.createBubbleInGroup();
        mEntryListener.onEntryAdded(groupedBubble.getEntry());
        when(mNotificationEntryManager.getPendingOrActiveNotif(groupedBubble.getEntry().getKey()))
                .thenReturn(groupedBubble.getEntry());
        groupSummary.addChildNotification(groupedBubble);

        // WHEN the summary is dismissed
        mBubblesManager.handleDismissalInterception(groupSummary.getEntry());

        // THEN only the NON-bubble children are dismissed
        List<ExpandableNotificationRow> childrenRows = groupSummary.getAttachedChildren();
        verify(mNotifCallback, times(1)).removeNotification(
                eq(childrenRows.get(0).getEntry()), any(), eq(REASON_GROUP_SUMMARY_CANCELED));
        verify(mNotifCallback, times(1)).removeNotification(
                eq(childrenRows.get(1).getEntry()), any(), eq(REASON_GROUP_SUMMARY_CANCELED));
        verify(mNotifCallback, never()).removeNotification(eq(groupedBubble.getEntry()),
                any(), anyInt());

        // THEN the bubble child still exists as a bubble and is suppressed from the shade
        assertTrue(mBubbleData.hasBubbleInStackWithKey(groupedBubble.getEntry().getKey()));
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                groupedBubble.getEntry().getKey(),
                groupedBubble.getEntry().getSbn().getGroupKey()));
        assertTrue(mBubbleController.getImplCachedState().isBubbleNotificationSuppressedFromShade(
                groupedBubble.getEntry().getKey(),
                groupedBubble.getEntry().getSbn().getGroupKey()));

        // THEN the summary is also suppressed from the shade
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                groupSummary.getEntry().getKey(),
                groupSummary.getEntry().getSbn().getGroupKey()));
        assertTrue(mBubbleController.getImplCachedState().isBubbleNotificationSuppressedFromShade(
                groupSummary.getEntry().getKey(),
                groupSummary.getEntry().getSbn().getGroupKey()));
    }

    /**
     * Sets the bubble metadata flags for this entry. These flags are normally set by
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
