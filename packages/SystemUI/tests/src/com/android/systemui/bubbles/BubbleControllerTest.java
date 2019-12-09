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

import static com.android.systemui.statusbar.notification.NotificationEntryManager.UNDEFINED_DISMISS_REASON;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.face.FaceManager;
import android.service.notification.ZenModeConfig;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.colorextraction.ColorExtractor;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoveInterceptor;
import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.SuperStatusBarViewFactory;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.NotificationFilter;
import com.android.systemui.statusbar.notification.NotificationInterruptionStateProvider;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBarWindowController;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.RemoteInputUriController;
import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.util.InjectionInflationController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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
    private KeyguardBypassController mKeyguardBypassController;

    @Captor
    private ArgumentCaptor<NotificationEntryListener> mEntryListenerCaptor;
    @Captor
    private ArgumentCaptor<NotificationRemoveInterceptor> mRemoveInterceptorCaptor;

    private TestableBubbleController mBubbleController;
    private StatusBarWindowController mStatusBarWindowController;
    private NotificationEntryListener mEntryListener;
    private NotificationRemoveInterceptor mRemoveInterceptor;

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
    private RemoteInputUriController mRemoteInputUriController;

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
                new InjectionInflationController(SystemUIFactory.getInstance().getRootComponent()));

        // Bubbles get added to status bar window view
        mStatusBarWindowController = new StatusBarWindowController(mContext, mWindowManager,
                mActivityManager, mDozeParameters, mStatusBarStateController,
                mConfigurationController, mKeyguardBypassController, mColorExtractor,
                mSuperStatusBarViewFactory, mResources);
        mStatusBarWindowController.attach();

        // Need notifications for bubbles
        mNotificationTestHelper = new NotificationTestHelper(mContext, mDependency);
        mRow = mNotificationTestHelper.createBubble(mDeleteIntent);
        mRow2 = mNotificationTestHelper.createBubble(mDeleteIntent);
        mNonBubbleNotifRow = mNotificationTestHelper.createRow();

        // Return non-null notification data from the NEM
        when(mNotificationEntryManager
                .getActiveNotificationUnfiltered(mRow.getEntry().getKey())).thenReturn(
                mRow.getEntry());

        mZenModeConfig.suppressedVisualEffects = 0;
        when(mZenModeController.getConfig()).thenReturn(mZenModeConfig);

        TestableNotificationInterruptionStateProvider interruptionStateProvider =
                new TestableNotificationInterruptionStateProvider(mContext,
                        mock(NotificationFilter.class),
                        mock(StatusBarStateController.class),
                        mock(BatteryController.class));
        interruptionStateProvider.setUpWithPresenter(
                mock(NotificationPresenter.class),
                mock(HeadsUpManager.class),
                mock(NotificationInterruptionStateProvider.HeadsUpSuppressor.class));
        mBubbleData = new BubbleData(mContext);
        mBubbleController = new TestableBubbleController(mContext,
                mStatusBarWindowController,
                mStatusBarStateController,
                mShadeController,
                mBubbleData,
                mConfigurationController,
                interruptionStateProvider,
                mZenModeController,
                mLockscreenUserManager,
                mNotificationGroupManager,
                mNotificationEntryManager,
                mRemoteInputUriController);
        mBubbleController.setBubbleStateChangeListener(mBubbleStateChangeListener);
        mBubbleController.setExpandListener(mBubbleExpandListener);

        // Get a reference to the BubbleController's entry listener
        verify(mNotificationEntryManager, atLeastOnce())
                .addNotificationEntryListener(mEntryListenerCaptor.capture());
        mEntryListener = mEntryListenerCaptor.getValue();
        // And the remove interceptor
        verify(mNotificationEntryManager, atLeastOnce())
                .setNotificationRemoveInterceptor(mRemoveInterceptorCaptor.capture());
        mRemoveInterceptor = mRemoveInterceptorCaptor.getValue();
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
        verify(mNotificationEntryManager).updateNotifications(any());
        verify(mBubbleStateChangeListener).onHasBubblesChanged(true);

        mBubbleController.removeBubble(
                mRow.getEntry().getKey(), BubbleController.DISMISS_USER_GESTURE);
        assertFalse(mStatusBarWindowController.getBubblesShowing());
        assertNull(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()));
        verify(mNotificationEntryManager, times(2)).updateNotifications(anyString());
        verify(mBubbleStateChangeListener).onHasBubblesChanged(false);
    }

    @Test
    public void testRemoveBubble_withDismissedNotif() {
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));

        // Make it look like dismissed notif
        mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).setShowInShade(false);

        // Now remove the bubble
        mBubbleController.removeBubble(
                mRow.getEntry().getKey(), BubbleController.DISMISS_USER_GESTURE);

        // Since the notif is dismissed, once the bubble is removed, performRemoveNotification gets
        // called to really remove the notif
        verify(mNotificationEntryManager, times(1)).performRemoveNotification(
                mRow.getEntry().getSbn(), UNDEFINED_DISMISS_REASON);
        assertFalse(mBubbleController.hasBubbles());
    }

    @Test
    public void testDismissStack() {
        mBubbleController.updateBubble(mRow.getEntry());
        verify(mNotificationEntryManager, times(1)).updateNotifications(any());
        assertNotNull(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()));
        mBubbleController.updateBubble(mRow2.getEntry());
        verify(mNotificationEntryManager, times(2)).updateNotifications(any());
        assertNotNull(mBubbleData.getBubbleWithKey(mRow2.getEntry().getKey()));
        assertTrue(mBubbleController.hasBubbles());

        mBubbleController.dismissStack(BubbleController.DISMISS_USER_GESTURE);
        assertFalse(mStatusBarWindowController.getBubblesShowing());
        verify(mNotificationEntryManager, times(3)).updateNotifications(any());
        assertNull(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()));
        assertNull(mBubbleData.getBubbleWithKey(mRow2.getEntry().getKey()));
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
                mRow.getEntry().getKey()));
        assertFalse(mStatusBarWindowController.getBubbleExpanded());

        // Expand the stack
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleController.expandStack();
        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().getKey());
        assertTrue(mStatusBarWindowController.getBubbleExpanded());

        // Make sure the notif is suppressed
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));

        // Collapse
        mBubbleController.collapseStack();
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow.getEntry().getKey());
        assertFalse(mBubbleController.isStackExpanded());
        assertFalse(mStatusBarWindowController.getBubbleExpanded());
    }

    @Test
    public void testCollapseAfterChangingExpandedBubble() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mEntryListener.onPendingEntryAdded(mRow2.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());
        mBubbleController.updateBubble(mRow2.getEntry());

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow2.getEntry().getKey()));

        // Expand
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleController.expandStack();
        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow2.getEntry().getKey());

        // Last added is the one that is expanded
        assertEquals(mRow2.getEntry(), mBubbleData.getSelectedBubble().getEntry());
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow2.getEntry().getKey()));

        // Switch which bubble is expanded
        mBubbleController.selectBubble(mRow.getEntry().getKey());
        stackView.setExpandedBubble(mRow.getEntry().getKey());
        assertEquals(mRow.getEntry(), stackView.getExpandedBubble().getEntry());
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));

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
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));

        mTestableLooper.processAllMessages();
        assertTrue(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());

        // Expand
        mBubbleController.expandStack();
        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().getKey());

        // Notif is suppressed after expansion
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());
    }

    @Test
    public void testUpdateWhileExpanded_DoesntChangeShowInShadeAndDot() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // We should have bubbles & their notifs should not be suppressed
        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));

        mTestableLooper.processAllMessages();
        assertTrue(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());

        // Expand
        mBubbleController.expandStack();
        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().getKey());

        // Notif is suppressed after expansion
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());

        // Send update
        mEntryListener.onPreEntryUpdated(mRow.getEntry());

        // Nothing should have changed
        // Notif is suppressed after expansion
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));
        // Notif shouldn't show dot after expansion
        assertFalse(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());
    }

    @Test
    public void testRemoveLastExpandedCollapses() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mEntryListener.onPendingEntryAdded(mRow2.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());
        mBubbleController.updateBubble(mRow2.getEntry());
        verify(mBubbleStateChangeListener).onHasBubblesChanged(true);

        // Expand
        BubbleStackView stackView = mBubbleController.getStackView();
        mBubbleController.expandStack();

        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow2.getEntry().getKey());

        // Last added is the one that is expanded
        assertEquals(mRow2.getEntry(), stackView.getExpandedBubble().getEntry());
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow2.getEntry().getKey()));

        // Dismiss currently expanded
        mBubbleController.removeBubble(stackView.getExpandedBubbleView().getKey(),
                BubbleController.DISMISS_USER_GESTURE);
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow2.getEntry().getKey());

        // Make sure first bubble is selected
        assertEquals(mRow.getEntry(), stackView.getExpandedBubble().getEntry());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().getKey());

        // Dismiss that one
        mBubbleController.removeBubble(stackView.getExpandedBubbleView().getKey(),
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
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
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
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
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
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // Notif should be suppressed because we were foreground
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));
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
                mRow.getEntry().getKey()));
        // Should show dot
        assertTrue(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());

        // Update to suppress notif
        setMetadataFlags(mRow.getEntry(),
                Notification.BubbleMetadata.FLAG_SUPPRESS_NOTIFICATION, true /* enableFlag */);
        mBubbleController.updateBubble(mRow.getEntry());

        // Notif should be suppressed
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));
        // Dot + flyout is hidden because notif is suppressed
        assertFalse(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());
        assertFalse(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showFlyout());

        // # of bubbles should change
        verify(mBubbleStateChangeListener).onHasBubblesChanged(true /* hasBubbles */);
    }


    @Test
    public void testExpandStackAndSelectBubble_removedFirst() {
        final String key = mRow.getEntry().getKey();

        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        // Simulate notification cancellation.
        mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getEntry().getKey(), REASON_APP_CANCEL);

        mBubbleController.expandStackAndSelectBubble(key);
    }

    @Test
    public void testMarkNewNotificationAsShowInShade() {
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));

        mTestableLooper.processAllMessages();
        assertTrue(mBubbleData.getBubbleWithKey(mRow.getEntry().getKey()).showDot());
    }

    @Test
    public void testAddNotif_notBubble() {
        mEntryListener.onPendingEntryAdded(mNonBubbleNotifRow.getEntry());
        mEntryListener.onPreEntryUpdated(mNonBubbleNotifRow.getEntry());

        verify(mBubbleStateChangeListener, never()).onHasBubblesChanged(anyBoolean());
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
        mBubbleController.dismissStack(BubbleController.DISMISS_USER_GESTURE);
        verify(mDeleteIntent, times(2)).send();
    }

    @Test
    public void testRemoveBubble_noLongerBubbleAfterUpdate()
            throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mRow.getEntry());
        assertTrue(mBubbleController.hasBubbles());

        mRow.getEntry().getSbn().getNotification().flags &= ~FLAG_BUBBLE;
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
                mRow.getEntry().getKey(), REASON_APP_CANCEL);

        // Cancels always remove so no need to intercept
        assertFalse(intercepted);
        assertFalse(mBubbleController.hasBubbles());
    }

    @Test
    public void removeBubble_fails_clearAll()  {
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));

        boolean intercepted = mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getEntry().getKey(), REASON_CANCEL_ALL);

        // Intercept!
        assertTrue(intercepted);
        // Should update show in shade state
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));

        verify(mNotificationEntryManager, never()).performRemoveNotification(
                any(), anyInt());
        assertTrue(mBubbleController.hasBubbles());
    }

    @Test
    public void removeBubble_fails_userDismissNotif() {
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));

        boolean intercepted = mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getEntry().getKey(), REASON_CANCEL);

        // Intercept!
        assertTrue(intercepted);
        // Should update show in shade state
        assertTrue(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));

        verify(mNotificationEntryManager, never()).performRemoveNotification(
                any(), anyInt());
        assertTrue(mBubbleController.hasBubbles());
    }

    @Test
    public void removeBubble_succeeds_userDismissBubble_userDimissNotif() {
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry());

        assertTrue(mBubbleController.hasBubbles());
        assertFalse(mBubbleController.isBubbleNotificationSuppressedFromShade(
                mRow.getEntry().getKey()));

        // Dismiss the bubble
        mBubbleController.removeBubble(
                mRow.getEntry().getKey(), BubbleController.DISMISS_USER_GESTURE);
        assertFalse(mBubbleController.hasBubbles());

        // Dismiss the notification
        boolean intercepted = mRemoveInterceptor.onNotificationRemoveRequested(
                mRow.getEntry().getKey(), REASON_CANCEL);

        // It's no longer a bubble so we shouldn't intercept
        assertFalse(intercepted);
    }

    static class TestableBubbleController extends BubbleController {
        // Let's assume surfaces can be synchronized immediately.
        TestableBubbleController(Context context,
                StatusBarWindowController statusBarWindowController,
                StatusBarStateController statusBarStateController,
                ShadeController shadeController,
                BubbleData data,
                ConfigurationController configurationController,
                NotificationInterruptionStateProvider interruptionStateProvider,
                ZenModeController zenModeController,
                NotificationLockscreenUserManager lockscreenUserManager,
                NotificationGroupManager groupManager,
                NotificationEntryManager entryManager,
                RemoteInputUriController remoteInputUriController) {
            super(context,
                    statusBarWindowController, statusBarStateController, shadeController,
                    data, Runnable::run, configurationController, interruptionStateProvider,
                    zenModeController, lockscreenUserManager, groupManager, entryManager,
                    remoteInputUriController);
            setInflateSynchronously(true);
        }
    }

    static class TestableNotificationInterruptionStateProvider extends
            NotificationInterruptionStateProvider {

        TestableNotificationInterruptionStateProvider(Context context,
                NotificationFilter filter, StatusBarStateController controller,
                BatteryController batteryController) {
            super(context, filter, controller, batteryController);
            mUseHeadsUp = true;
        }
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
