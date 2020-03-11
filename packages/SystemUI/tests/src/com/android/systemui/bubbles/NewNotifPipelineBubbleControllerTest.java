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

package com.android.systemui.bubbles;

import static android.app.Notification.FLAG_BUBBLE;
import static android.service.notification.NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import android.app.Notification;
import android.app.PendingIntent;
import android.content.res.Resources;
import android.hardware.display.AmbientDisplayConfiguration;
import android.hardware.face.FaceManager;
import android.os.Handler;
import android.os.PowerManager;
import android.service.dreams.IDreamManager;
import android.service.notification.ZenModeConfig;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.FeatureFlags;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.SuperStatusBarViewFactory;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationFilter;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.row.ActivatableNotificationView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;
import com.android.systemui.statusbar.notification.row.dagger.NotificationRowComponent;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.LockscreenLockIconController;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.FloatingContentCoordinator;
import com.android.systemui.util.InjectionInflationController;

import org.junit.Before;
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
 * {@link BubbleControllerTest}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class NewNotifPipelineBubbleControllerTest extends SysuiTestCase {
    @Mock
    private NotificationEntryManager mNotificationEntryManager;
    @Mock
    private NotificationGroupManager mNotificationGroupManager;
    @Mock
    private BubbleController.NotifCallback mNotifCallback;
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
    private KeyguardBypassController mKeyguardBypassController;
    @Mock
    private FloatingContentCoordinator mFloatingContentCoordinator;
    @Captor
    private ArgumentCaptor<NotifCollectionListener> mNotifListenerCaptor;
    private TestableBubbleController mBubbleController;
    private NotificationShadeWindowController mNotificationShadeWindowController;
    private NotifCollectionListener mEntryListener;
    private NotificationTestHelper mNotificationTestHelper;
    private ExpandableNotificationRow mRow;
    private ExpandableNotificationRow mRow2;
    private ExpandableNotificationRow mNonBubbleNotifRow;
    @Mock
    private BubbleController.BubbleStateChangeListener mBubbleStateChangeListener;
    @Mock
    private BubbleController.BubbleExpandListener mBubbleExpandListener;
    @Mock
    private PendingIntent mDeleteIntent;
    @Mock
    private SysuiColorExtractor mColorExtractor;
    @Mock
    ColorExtractor.GradientColors mGradientColors;
    @Mock
    private Resources mResources;
    @Mock
    private ShadeController mShadeController;
    @Mock
    private NotificationRowComponent mNotificationRowComponent;
    @Mock
    private NotifPipeline mNotifPipeline;
    @Mock
    private FeatureFlags mFeatureFlagsNewPipeline;
    @Mock
    private DumpManager mDumpManager;
    @Mock
    private LockscreenLockIconController mLockIconController;

    private SuperStatusBarViewFactory mSuperStatusBarViewFactory;
    private BubbleData mBubbleData;

    private TestableLooper mTestableLooper;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTestableLooper = TestableLooper.get(this);

        mContext.addMockSystemService(FaceManager.class, mFaceManager);
        when(mColorExtractor.getNeutralColors()).thenReturn(mGradientColors);

        mSuperStatusBarViewFactory = new SuperStatusBarViewFactory(mContext,
                new InjectionInflationController(SystemUIFactory.getInstance().getRootComponent()),
                new NotificationRowComponent.Builder() {
                    @Override
                    public NotificationRowComponent.Builder activatableNotificationView(
                            ActivatableNotificationView view) {
                        return this;
                    }

                    @Override
                    public NotificationRowComponent build() {
                        return mNotificationRowComponent;
                    }
                },
                mLockIconController);

        // Bubbles get added to status bar window view
        mNotificationShadeWindowController = new NotificationShadeWindowController(mContext,
                mWindowManager, mActivityManager, mDozeParameters, mStatusBarStateController,
                mConfigurationController, mKeyguardBypassController, mColorExtractor,
                mDumpManager);
        mNotificationShadeWindowController.setNotificationShadeView(
                mSuperStatusBarViewFactory.getNotificationShadeWindowView());
        mNotificationShadeWindowController.attach();

        // Need notifications for bubbles
        mNotificationTestHelper = new NotificationTestHelper(mContext, mDependency);
        mRow = mNotificationTestHelper.createBubble(mDeleteIntent);
        mRow2 = mNotificationTestHelper.createBubble(mDeleteIntent);
        mNonBubbleNotifRow = mNotificationTestHelper.createRow();

        mZenModeConfig.suppressedVisualEffects = 0;
        when(mZenModeController.getConfig()).thenReturn(mZenModeConfig);

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
        when(mFeatureFlagsNewPipeline.isNewNotifPipelineRenderingEnabled()).thenReturn(true);
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
                mFeatureFlagsNewPipeline,
                mDumpManager,
                mFloatingContentCoordinator);
        mBubbleController.addNotifCallback(mNotifCallback);
        mBubbleController.setBubbleStateChangeListener(mBubbleStateChangeListener);
        mBubbleController.setExpandListener(mBubbleExpandListener);

        // Get a reference to the BubbleController's entry listener
        verify(mNotifPipeline, atLeastOnce())
                .addCollectionListener(mNotifListenerCaptor.capture());
        mEntryListener = mNotifListenerCaptor.getValue();
    }

    @Test
    public void testAddBubble() {
        mBubbleController.updateBubble(mRow.getEntry());
        assertTrue(mBubbleController.hasBubbles());

        verify(mBubbleStateChangeListener).onHasBubblesChanged(true);
    }

    @Test
    public void testHasBubbles() {
        assertFalse(mBubbleController.hasBubbles());
        mBubbleController.updateBubble(mRow.getEntry());
        assertTrue(mBubbleController.hasBubbles());
    }

    @Test
    public void testRemoveBubble() {
        mBubbleController.updateBubble(mRow.getEntry());
        assertNotNull(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()));
        assertTrue(mBubbleController.hasBubbles());
        verify(mNotifCallback, times(1)).invalidateNotifications(anyString());
        verify(mBubbleStateChangeListener).onHasBubblesChanged(true);

        mBubbleController.removeBubble(mRow.getEntry(), BubbleController.DISMISS_USER_GESTURE);
        assertFalse(mNotificationShadeWindowController.getBubblesShowing());
        assertNull(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()));
        verify(mNotifCallback, times(2)).invalidateNotifications(anyString());
        verify(mBubbleStateChangeListener).onHasBubblesChanged(false);
    }

    @Test
    public void testRemoveBubble_withDismissedNotif() {
        mEntryListener.onEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(mRow.getEntry()));

        // Make it look like dismissed notif
        mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).setSuppressNotification(true);

        // Now remove the bubble
        mBubbleController.removeBubble(mRow.getEntry(), BubbleController.DISMISS_USER_GESTURE);

        // Since the notif is dismissed, once the bubble is removed, removeNotification gets
        // called to really remove the notif
        verify(mNotifCallback, times(1)).removeNotification(eq(mRow.getEntry()), anyInt());
        assertFalse(mBubbleController.hasBubbles());
    }

    @Test
    public void testDismissStack() {
        mBubbleController.updateBubble(mRow.getEntry());
        verify(mNotifCallback, times(1)).invalidateNotifications(anyString());
        assertNotNull(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()));
        mBubbleController.updateBubble(mRow2.getEntry());
        verify(mNotifCallback, times(2)).invalidateNotifications(anyString());
        assertNotNull(mBubbleData.getBubbleWithKey(mRow2.getEntry().getKey()));
        assertTrue(mBubbleController.hasBubbles());

        mBubbleController.dismissStack(BubbleController.DISMISS_USER_GESTURE);
        assertFalse(mNotificationShadeWindowController.getBubblesShowing());
        verify(mNotifCallback, times(3)).invalidateNotifications(anyString());
        assertNull(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()));
        assertNull(mBubbleData.getBubbleWithKey(mRow2.getEntry().getKey()));
    }

    @Test
    public void testExpandCollapseStack() {
        assertFalse(mBubbleController.isStackExpanded());

        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));
        assertFalse(mNotificationShadeWindowController.getBubbleExpanded());

        // Expand the stack
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleController.expandStack();
        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().getKey());
        assertTrue(mNotificationShadeWindowController.getBubbleExpanded());

        // Make sure the notif is suppressed
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(mRow.getEntry()));

        // Collapse
        mBubbleController.collapseStack();
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow.getEntry().getKey());
        assertFalse(mBubbleController.isStackExpanded());
        assertFalse(mNotificationShadeWindowController.getBubbleExpanded());
    }

    @Test
    public void testCollapseAfterChangingExpandedBubble() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow.getEntry());
        mEntryListener.onEntryAdded(mRow2.getEntry());
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
        mBubbleController.expandStack();
        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow2.getEntry().getKey());

        // Last added is the one that is expanded
        assertEquals(mRow2.getEntry(), mBubbleData.getSelectedBubble().getEntry());
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(mRow2.getEntry()));

        // Switch which bubble is expanded
        mBubbleController.selectBubble(mRow.getEntry().getKey());
        mBubbleData.setExpanded(true);
        assertEquals(mRow.getEntry(),
                mBubbleData.getBubbleWithKey(stackView.getExpandedBubble().getKey()).getEntry());
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        // collapse for previous bubble
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow2.getEntry().getKey());
        // expand for selected bubble
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().getKey());

        // Collapse
        mBubbleController.collapseStack();
        assertFalse(mBubbleController.isStackExpanded());
    }

    @Test
    public void testExpansionRemovesShowInShadeAndDot() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(mRow.getEntry()));

        mTestableLooper.processAllMessages();
        assertTrue(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());

        // Expand
        mBubbleController.expandStack();
        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().getKey());

        // Notif is suppressed after expansion
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());
    }

    @Test
    public void testUpdateWhileExpanded_DoesntChangeShowInShadeAndDot() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        mTestableLooper.processAllMessages();
        assertTrue(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());

        // Expand
        mBubbleController.expandStack();
        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().getKey());

        // Notif is suppressed after expansion
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());

        // Send update
        mEntryListener.onEntryUpdated(mRow.getEntry());

        // Nothing should have changed
        // Notif is suppressed after expansion
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());
    }

    @Test
    public void testRemoveLastExpandedCollapses() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onEntryAdded(mRow.getEntry());
        mEntryListener.onEntryAdded(mRow2.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());
        mBubbleController.updateBubble(mRow2.getEntry());
        verify(mBubbleStateChangeListener).onHasBubblesChanged(true);

        // Expand
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleController.expandStack();

        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow2.getEntry().getKey());

        // Last added is the one that is expanded
        assertEquals(mRow2.getEntry(),
                mBubbleData.getBubbleWithKey(stackView.getExpandedBubble().getKey()).getEntry());
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow2.getEntry()));

        // Dismiss currently expanded
        mBubbleController.removeBubble(
                mBubbleData.getBubbleWithKey(stackView.getExpandedBubble().getKey()).getEntry(),
                BubbleController.DISMISS_USER_GESTURE);
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow2.getEntry().getKey());

        // Make sure first bubble is selected
        assertEquals(mRow.getEntry(),
                mBubbleData.getBubbleWithKey(stackView.getExpandedBubble().getKey()).getEntry());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().getKey());

        // Dismiss that one
        mBubbleController.removeBubble(
                mBubbleData.getBubbleWithKey(stackView.getExpandedBubble().getKey()).getEntry(),
                BubbleController.DISMISS_USER_GESTURE);

        // Make sure state changes and collapse happens
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow.getEntry().getKey());
        verify(mBubbleStateChangeListener).onHasBubblesChanged(false);
        assertFalse(mBubbleController.hasBubbles());
    }

    @Test
    public void testAutoExpand_fails_noFlag() {
        assertFalse(mBubbleController.isStackExpanded());
        setMetadataFlags(mRow.getEntry(),
                Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE, false /* enableFlag */);

        // Add the auto expand bubble
        mEntryListener.onEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // Expansion shouldn't change
        verify(mBubbleExpandListener, never()).onBubbleExpandChanged(false /* expanded */,
                mRow.getEntry().getKey());
        assertFalse(mBubbleController.isStackExpanded());

        // # of bubbles should change
        verify(mBubbleStateChangeListener).onHasBubblesChanged(true /* hasBubbles */);
    }

    @Test
    public void testAutoExpand_succeeds_withFlag() {
        setMetadataFlags(mRow.getEntry(),
                Notification.BubbleMetadata.FLAG_AUTO_EXPAND_BUBBLE, true /* enableFlag */);

        // Add the auto expand bubble
        mEntryListener.onEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // Expansion should change
        verify(mBubbleExpandListener).onBubbleExpandChanged(true /* expanded */,
                mRow.getEntry().getKey());
        assertTrue(mBubbleController.isStackExpanded());

        // # of bubbles should change
        verify(mBubbleStateChangeListener).onHasBubblesChanged(true /* hasBubbles */);
    }

    @Test
    public void testSuppressNotif_onInitialNotif() {
        setMetadataFlags(mRow.getEntry(),
                Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION, true /* enableFlag */);

        // Add the suppress notif bubble
        mEntryListener.onEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // Notif should be suppressed because we were foreground
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));
        // Dot + flyout is hidden because notif is suppressed
        assertFalse(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());
        assertFalse(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showFlyout());

        // # of bubbles should change
        verify(mBubbleStateChangeListener).onHasBubblesChanged(true /* hasBubbles */);
    }

    @Test
    public void testSuppressNotif_onUpdateNotif() {
        mBubbleController.updateBubble(mRow.getEntry());

        // Should not be suppressed
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));
        // Should show dot
        assertTrue(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());

        // Update to suppress notif
        setMetadataFlags(mRow.getEntry(),
                Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION, true /* enableFlag */);
        mBubbleController.updateBubble(mRow.getEntry());

        // Notif should be suppressed
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));
        // Dot + flyout is hidden because notif is suppressed
        assertFalse(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());
        assertFalse(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showFlyout());

        // # of bubbles should change
        verify(mBubbleStateChangeListener).onHasBubblesChanged(true /* hasBubbles */);
    }

    @Test
    public void testMarkNewNotificationAsShowInShade() {
        mEntryListener.onEntryAdded(mRow.getEntry());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        mTestableLooper.processAllMessages();
        assertTrue(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());
    }

    @Test
    public void testAddNotif_notBubble() {
        mEntryListener.onEntryAdded(mNonBubbleNotifRow.getEntry());
        mEntryListener.onEntryUpdated(mNonBubbleNotifRow.getEntry());

        verify(mBubbleStateChangeListener, never()).onHasBubblesChanged(anyBoolean());
        assertThat(mBubbleController.hasBubbles()).isFalse();
    }

    @Test
    public void testDeleteIntent_removeBubble_aged() throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mRow.getEntry());
        mBubbleController.removeBubble(mRow.getEntry(), BubbleController.DISMISS_AGED);
        verify(mDeleteIntent, never()).send();
    }

    @Test
    public void testDeleteIntent_removeBubble_user() throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mRow.getEntry());
        mBubbleController.removeBubble(
                mRow.getEntry(), BubbleController.DISMISS_USER_GESTURE);
        verify(mDeleteIntent, times(1)).send();
    }

    @Test
    public void testDeleteIntent_dismissStack() throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mRow.getEntry());
        mBubbleController.updateBubble(mRow2.getEntry());
        mBubbleController.dismissStack(BubbleController.DISMISS_USER_GESTURE);
        verify(mDeleteIntent, times(2)).send();
    }

    @Test
    public void testRemoveBubble_noLongerBubbleAfterUpdate()
            throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mRow.getEntry());
        assertTrue(mBubbleController.hasBubbles());

        mRow.getEntry().getSbn().getNotification().flags &= ~FLAG_BUBBLE;
        mEntryListener.onEntryUpdated(mRow.getEntry());

        assertFalse(mBubbleController.hasBubbles());
        verify(mDeleteIntent, never()).send();
    }

    @Test
    public void testRemoveBubble_entryListenerRemove() {
        mEntryListener.onEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());

        // Removes the notification
        mEntryListener.onEntryRemoved(mRow.getEntry(), 0);
        assertFalse(mBubbleController.hasBubbles());
    }

    @Test
    public void removeBubble_intercepted() {
        mEntryListener.onEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        boolean intercepted = mBubbleController.handleDismissalInterception(mRow.getEntry());

        // Intercept!
        assertTrue(intercepted);
        // Should update show in shade state
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(mRow.getEntry()));
    }

    @Test
    public void removeBubble_succeeds_userDismissBubble_userDimissNotif() {
        mEntryListener.onEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        // Dismiss the bubble
        mBubbleController.removeBubble(
                mRow.getEntry(), BubbleController.DISMISS_USER_GESTURE);
        assertFalse(mBubbleController.hasBubbles());

        // Dismiss the notification
        boolean intercepted = mBubbleController.handleDismissalInterception(mRow.getEntry());

        // It's no longer a bubble so we shouldn't intercept
        assertFalse(intercepted);
    }

    @Test
    public void testNotifyShadeSuppressionChange_notificationDismiss() {
        BubbleController.NotificationSuppressionChangedListener listener =
                mock(BubbleController.NotificationSuppressionChangedListener.class);
        mBubbleData.setSuppressionChangedListener(listener);

        mEntryListener.onEntryAdded(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        mBubbleController.handleDismissalInterception(mRow.getEntry());

        // Should update show in shade state
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        // Should notify delegate that shade state changed
        verify(listener).onBubbleNotificationSuppressionChange(
                mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()));
    }

    @Test
    public void testNotifyShadeSuppressionChange_bubbleExpanded() {
        BubbleController.NotificationSuppressionChangedListener listener =
                mock(BubbleController.NotificationSuppressionChangedListener.class);
        mBubbleData.setSuppressionChangedListener(listener);

        mEntryListener.onEntryAdded(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        mBubbleData.setExpanded(true);

        // Once a bubble is expanded the notif is suppressed
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry()));

        // Should notify delegate that shade state changed
        verify(listener).onBubbleNotificationSuppressionChange(
                mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()));
    }

    @Test
    public void testBubbleSummaryDismissal_suppressesSummaryAndBubbleFromShade() throws Exception {
        // GIVEN a group summary with a bubble child
        ExpandableNotificationRow groupSummary = mNotificationTestHelper.createGroup(0);
        ExpandableNotificationRow groupedBubble = mNotificationTestHelper.createBubbleInGroup();
        mEntryListener.onEntryAdded(groupedBubble.getEntry());
        groupSummary.addChildNotification(groupedBubble);
        assertTrue(mBubbleData.hasBubbleWithKey(groupedBubble.getEntry().getKey()));

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
        mEntryListener.onEntryAdded(groupedBubble.getEntry());
        groupSummary.addChildNotification(groupedBubble);
        assertTrue(mBubbleData.hasBubbleWithKey(groupedBubble.getEntry().getKey()));

        // GIVEN the summary is dismissed
        mBubbleController.handleDismissalInterception(groupSummary.getEntry());

        // WHEN the summary is cancelled by the app
        mEntryListener.onEntryRemoved(groupSummary.getEntry(), 0);

        // THEN the summary and its children are removed from bubble data
        assertFalse(mBubbleData.hasBubbleWithKey(groupedBubble.getEntry().getKey()));
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
        groupSummary.addChildNotification(groupedBubble);

        // WHEN the summary is dismissed
        mBubbleController.handleDismissalInterception(groupSummary.getEntry());

        // THEN only the NON-bubble children are dismissed
        List<ExpandableNotificationRow> childrenRows = groupSummary.getNotificationChildren();
        verify(mNotifCallback, times(1)).removeNotification(
                childrenRows.get(0).getEntry(), REASON_GROUP_SUMMARY_CANCELED);
        verify(mNotifCallback, times(1)).removeNotification(
                childrenRows.get(1).getEntry(), REASON_GROUP_SUMMARY_CANCELED);
        verify(mNotifCallback, never()).removeNotification(eq(groupedBubble.getEntry()), anyInt());

        // THEN the bubble child still exists as a bubble and is suppressed from the shade
        assertTrue(mBubbleData.hasBubbleWithKey(groupedBubble.getEntry().getKey()));
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                groupedBubble.getEntry()));

        // THEN the summary is also suppressed from the shade
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                groupSummary.getEntry()));
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
}
