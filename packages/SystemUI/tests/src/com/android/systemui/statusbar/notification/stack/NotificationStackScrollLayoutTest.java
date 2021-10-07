/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at * *      http://www.apache.org/licenses/LICENSE-2.0 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.stack;

import static android.provider.Settings.Secure.NOTIFICATION_NEW_INTERRUPTION_MODEL;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.metrics.LogMaker;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.systemui.ActivityStarterDelegate;
import com.android.systemui.Dependency;
import com.android.systemui.ExpandHelper;
import com.android.systemui.InitController;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.classifier.FalsingManagerFake;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.EmptyShadeView;
import com.android.systemui.statusbar.NotificationLockscreenUserManager;
import com.android.systemui.statusbar.NotificationLockscreenUserManager.UserChangedListener;
import com.android.systemui.statusbar.NotificationPresenter;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.RemoteInputController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.DynamicPrivacyController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.FooterView;
import com.android.systemui.statusbar.notification.row.NotificationBlockingHelperManager;
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.NotificationGroupManager;
import com.android.systemui.statusbar.phone.NotificationIconAreaController;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.phone.StatusBarTest.TestableNotificationEntryManager;
import com.android.systemui.statusbar.policy.ConfigurationController;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;

/**
 * Tests for {@link NotificationStackScrollLayout}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationStackScrollLayoutTest extends SysuiTestCase {

    private NotificationStackScrollLayout mStackScroller;  // Normally test this
    private NotificationStackScrollLayout mStackScrollerInternal;  // See explanation below

    @Rule public MockitoRule mockito = MockitoJUnit.rule();
    @Mock private StatusBar mBar;
    @Mock private SysuiStatusBarStateController mBarState;
    @Mock private HeadsUpManagerPhone mHeadsUpManager;
    @Mock private NotificationBlockingHelperManager mBlockingHelperManager;
    @Mock private NotificationGroupManager mGroupManager;
    @Mock private ExpandHelper mExpandHelper;
    @Mock private EmptyShadeView mEmptyShadeView;
    @Mock private NotificationData mNotificationData;
    @Mock private NotificationRemoteInputManager mRemoteInputManager;
    @Mock private RemoteInputController mRemoteInputController;
    @Mock private NotificationIconAreaController mNotificationIconAreaController;
    @Mock private MetricsLogger mMetricsLogger;
    @Mock private NotificationRoundnessManager mNotificationRoundnessManager;
    @Mock private KeyguardBypassController mKeyguardBypassController;
    @Mock private NotificationLockscreenUserManager mLockscreenUserManager;
    private UserChangedListener mUserChangedListener;
    private TestableNotificationEntryManager mEntryManager;
    private int mOriginalInterruptionModelSetting;


    @Before
    @UiThreadTest
    public void setUp() throws Exception {
        com.android.systemui.util.Assert.sMainLooper = TestableLooper.get(this).getLooper();

        mOriginalInterruptionModelSetting = Settings.Secure.getInt(mContext.getContentResolver(),
                NOTIFICATION_NEW_INTERRUPTION_MODEL, 0);
        Settings.Secure.putInt(mContext.getContentResolver(),
                NOTIFICATION_NEW_INTERRUPTION_MODEL, 1);

        // Inject dependencies before initializing the layout
        mDependency.injectTestDependency(
                NotificationBlockingHelperManager.class,
                mBlockingHelperManager);
        mDependency.injectTestDependency(SysuiStatusBarStateController.class, mBarState);
        mDependency.injectTestDependency(NotificationLockscreenUserManager.class,
                mLockscreenUserManager);
        mDependency.injectTestDependency(MetricsLogger.class, mMetricsLogger);
        mDependency.injectTestDependency(NotificationRemoteInputManager.class,
                mRemoteInputManager);
        mDependency.injectMockDependency(ShadeController.class);
        when(mRemoteInputManager.getController()).thenReturn(mRemoteInputController);

        mEntryManager = new TestableNotificationEntryManager(mContext);
        mDependency.injectTestDependency(NotificationEntryManager.class, mEntryManager);
        Dependency.get(InitController.class).executePostInitTasks();
        mEntryManager.setUpForTest(mock(NotificationPresenter.class), null, mHeadsUpManager,
                mNotificationData);


        NotificationShelf notificationShelf = mock(NotificationShelf.class);
        ArgumentCaptor<UserChangedListener> userChangedCaptor = ArgumentCaptor
                .forClass(UserChangedListener.class);

        // The actual class under test.  You may need to work with this class directly when
        // testing anonymous class members of mStackScroller, like mMenuEventListener,
        // which refer to members of NotificationStackScrollLayout. The spy
        // holds a copy of the CUT's instances of these classes, so they still refer to the CUT's
        // member variables, not the spy's member variables.
        mStackScrollerInternal = new NotificationStackScrollLayout(getContext(), null,
                true /* allowLongPress */, mNotificationRoundnessManager,
                mock(DynamicPrivacyController.class),
                mock(ConfigurationController.class),
                mock(ActivityStarterDelegate.class),
                mock(SysuiStatusBarStateController.class),
                mHeadsUpManager,
                mKeyguardBypassController,
                new FalsingManagerFake());
        mStackScroller = spy(mStackScrollerInternal);
        mStackScroller.setShelf(notificationShelf);
        mStackScroller.setStatusBar(mBar);
        mStackScroller.setScrimController(mock(ScrimController.class));
        mStackScroller.setGroupManager(mGroupManager);
        mStackScroller.setEmptyShadeView(mEmptyShadeView);
        mStackScroller.setIconAreaController(mNotificationIconAreaController);
        verify(mLockscreenUserManager).addUserChangedListener(userChangedCaptor.capture());
        mUserChangedListener = userChangedCaptor.getValue();

        // Stub out functionality that isn't necessary to test.
        doNothing().when(mBar)
                .executeRunnableDismissingKeyguard(any(Runnable.class),
                        any(Runnable.class),
                        anyBoolean(),
                        anyBoolean(),
                        anyBoolean());
        doNothing().when(mGroupManager).collapseAllGroups();
        doNothing().when(mExpandHelper).cancelImmediately();
        doNothing().when(notificationShelf).setAnimationsEnabled(anyBoolean());
    }

    @After
    public void tearDown() {
        Settings.Secure.putInt(mContext.getContentResolver(),
                NOTIFICATION_NEW_INTERRUPTION_MODEL, mOriginalInterruptionModelSetting);
    }

    @Test
    public void testNotDimmedOnKeyguard() {
        when(mBarState.getState()).thenReturn(StatusBarState.SHADE);
        mStackScroller.setDimmed(true /* dimmed */, false /* animate */);
        mStackScroller.setDimmed(true /* dimmed */, true /* animate */);
        assertFalse(mStackScroller.isDimmed());
    }

    @Test
    public void updateEmptyView_dndSuppressing() {
        when(mEmptyShadeView.willBeGone()).thenReturn(true);
        when(mBar.areNotificationsHidden()).thenReturn(true);

        mStackScroller.updateEmptyShadeView(true);

        verify(mEmptyShadeView).setText(R.string.dnd_suppressing_shade_text);
    }

    @Test
    public void updateEmptyView_dndNotSuppressing() {
        mStackScroller.setEmptyShadeView(mEmptyShadeView);
        when(mEmptyShadeView.willBeGone()).thenReturn(true);
        when(mBar.areNotificationsHidden()).thenReturn(false);

        mStackScroller.updateEmptyShadeView(true);

        verify(mEmptyShadeView).setText(R.string.empty_shade_text);
    }

    @Test
    public void updateEmptyView_noNotificationsToDndSuppressing() {
        mStackScroller.setEmptyShadeView(mEmptyShadeView);
        when(mEmptyShadeView.willBeGone()).thenReturn(true);
        when(mBar.areNotificationsHidden()).thenReturn(false);
        mStackScroller.updateEmptyShadeView(true);
        verify(mEmptyShadeView).setText(R.string.empty_shade_text);

        when(mBar.areNotificationsHidden()).thenReturn(true);
        mStackScroller.updateEmptyShadeView(true);
        verify(mEmptyShadeView).setText(R.string.dnd_suppressing_shade_text);
    }

    @Test
    @UiThreadTest
    public void testSetExpandedHeight_blockingHelperManagerReceivedCallbacks() {
        mStackScroller.setExpandedHeight(0f);
        verify(mBlockingHelperManager).setNotificationShadeExpanded(0f);
        reset(mBlockingHelperManager);

        mStackScroller.setExpandedHeight(100f);
        verify(mBlockingHelperManager).setNotificationShadeExpanded(100f);
    }

    @Test
    public void testOnStatePostChange_verifyIfProfileIsPublic() {
        mUserChangedListener.onUserChanged(0);
        verify(mLockscreenUserManager).isAnyProfilePublicMode();
    }

    @Test
    public void manageNotifications_visible() {
        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        when(view.willBeGone()).thenReturn(true);

        mStackScroller.updateFooterView(true, false);

        verify(view).setVisible(eq(true), anyBoolean());
        verify(view).setSecondaryVisible(eq(false), anyBoolean());
    }

    @Test
    public void clearAll_visible() {
        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        when(view.willBeGone()).thenReturn(true);

        mStackScroller.updateFooterView(true, true);

        verify(view).setVisible(eq(true), anyBoolean());
        verify(view).setSecondaryVisible(eq(true), anyBoolean());
    }

    @Test
    public void testInflateFooterView() {
        mStackScroller.inflateFooterView();
        ArgumentCaptor<FooterView> captor = ArgumentCaptor.forClass(FooterView.class);
        verify(mStackScroller).setFooterView(captor.capture());

        assertNotNull(captor.getValue().findViewById(R.id.manage_text).hasOnClickListeners());
        assertNotNull(captor.getValue().findViewById(R.id.dismiss_text).hasOnClickListeners());
    }

    @Test
    public void testUpdateFooter_noNotifications() {
        setBarStateForTest(StatusBarState.SHADE);
        mStackScroller.setCurrentUserSetup(true);
        assertEquals(0, mNotificationData.getActiveNotifications().size());

        mStackScroller.updateFooter();
        verify(mStackScroller, atLeastOnce()).updateFooterView(false, false);
    }

    @Test
    public void testUpdateFooter_remoteInput() {
        setBarStateForTest(StatusBarState.SHADE);
        mStackScroller.setCurrentUserSetup(true);

        ArrayList<NotificationEntry> entries = new ArrayList<>();
        entries.add(mock(NotificationEntry.class));
        when(mNotificationData.getActiveNotifications()).thenReturn(entries);

        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        when(row.canViewBeDismissed()).thenReturn(true);
        when(mStackScroller.getChildCount()).thenReturn(1);
        when(mStackScroller.getChildAt(anyInt())).thenReturn(row);
        when(mRemoteInputController.isRemoteInputActive()).thenReturn(true);

        mStackScroller.updateFooter();
        verify(mStackScroller).updateFooterView(false, true);
    }

    @Test
    public void testUpdateFooter_oneClearableNotification() {
        setBarStateForTest(StatusBarState.SHADE);
        mStackScroller.setCurrentUserSetup(true);

        ArrayList<NotificationEntry> entries = new ArrayList<>();
        entries.add(mock(NotificationEntry.class));
        when(mNotificationData.getActiveNotifications()).thenReturn(entries);

        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        when(row.canViewBeDismissed()).thenReturn(true);
        when(mStackScroller.getChildCount()).thenReturn(1);
        when(mStackScroller.getChildAt(anyInt())).thenReturn(row);

        mStackScroller.updateFooter();
        verify(mStackScroller).updateFooterView(true, true);
    }

    @Test
    public void testUpdateFooter_oneClearableNotification_beforeUserSetup() {
        setBarStateForTest(StatusBarState.SHADE);
        mStackScroller.setCurrentUserSetup(false);

        ArrayList<NotificationEntry> entries = new ArrayList<>();
        entries.add(mock(NotificationEntry.class));
        when(mNotificationData.getActiveNotifications()).thenReturn(entries);

        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        when(row.canViewBeDismissed()).thenReturn(true);
        when(mStackScroller.getChildCount()).thenReturn(1);
        when(mStackScroller.getChildAt(anyInt())).thenReturn(row);

        mStackScroller.updateFooter();
        verify(mStackScroller).updateFooterView(false, true);
    }

    @Test
    public void testUpdateFooter_oneNonClearableNotification() {
        setBarStateForTest(StatusBarState.SHADE);
        mStackScroller.setCurrentUserSetup(true);

        ArrayList<NotificationEntry> entries = new ArrayList<>();
        entries.add(mock(NotificationEntry.class));
        when(mEntryManager.getNotificationData().getActiveNotifications()).thenReturn(entries);
        assertTrue(mEntryManager.getNotificationData().getActiveNotifications().size() != 0);

        mStackScroller.updateFooter();
        verify(mStackScroller).updateFooterView(true, false);
    }

    @Test
    public void testUpdateFooter_atEnd() {
        mStackScroller.setCurrentUserSetup(true);

        // add footer
        mStackScroller.inflateFooterView();

        // add notification
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        NotificationEntry entry = mock(NotificationEntry.class);
        when(row.getEntry()).thenReturn(entry);
        when(entry.isClearable()).thenReturn(true);
        mStackScroller.addContainerView(row);

        mStackScroller.onUpdateRowStates();

        // move footer to end
        verify(mStackScroller).changeViewPosition(any(FooterView.class), eq(-1 /* end */));
    }

    @Test
    public void testOnDensityOrFontScaleChanged_reInflatesFooterViews() {
        clearInvocations(mStackScroller);
        mStackScroller.onDensityOrFontScaleChanged();
        verify(mStackScroller).setFooterView(any());
        verify(mStackScroller).setEmptyShadeView(any());
    }

    @Test
    @UiThreadTest
    public void testSetIsBeingDraggedResetsExposedMenu() {
        NotificationSwipeHelper swipeActionHelper =
                (NotificationSwipeHelper) mStackScroller.getSwipeActionHelper();
        swipeActionHelper.setExposedMenuView(new View(mContext));
        mStackScroller.setIsBeingDragged(true);
        assertNull(swipeActionHelper.getExposedMenuView());
    }

    @Test
    @UiThreadTest
    public void testPanelTrackingStartResetsExposedMenu() {
        NotificationSwipeHelper swipeActionHelper =
                (NotificationSwipeHelper) mStackScroller.getSwipeActionHelper();
        swipeActionHelper.setExposedMenuView(new View(mContext));
        mStackScroller.onPanelTrackingStarted();
        assertNull(swipeActionHelper.getExposedMenuView());
    }

    @Test
    @UiThreadTest
    public void testDarkModeResetsExposedMenu() {
        NotificationSwipeHelper swipeActionHelper =
                (NotificationSwipeHelper) mStackScroller.getSwipeActionHelper();
        swipeActionHelper.setExposedMenuView(new View(mContext));
        mStackScroller.setHideAmount(0.1f, 0.1f);
        assertNull(swipeActionHelper.getExposedMenuView());
    }

    class LogMatcher implements ArgumentMatcher<LogMaker> {
        private int mCategory, mType;

        LogMatcher(int category, int type) {
            mCategory = category;
            mType = type;
        }
        public boolean matches(LogMaker l) {
            return (l.getCategory() == mCategory)
                    && (l.getType() == mType);
        }

        public String toString() {
            return String.format("LogMaker(%d, %d)", mCategory, mType);
        }
    }

    private LogMaker logMatcher(int category, int type) {
        return argThat(new LogMatcher(category, type));
    }

    @Test
    @UiThreadTest
    public void testOnMenuClickedLogging() {
        // Set up the object under test to have a valid mLongPressListener.  We're testing an
        // anonymous-class member, mMenuEventListener, so we need to modify the state of the
        // class itself, not the Mockito spy copied from it.  See notes in setup.
        mStackScrollerInternal.setLongPressListener(
                mock(ExpandableNotificationRow.LongPressListener.class));

        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class, RETURNS_DEEP_STUBS);
        when(row.getStatusBarNotification().getLogMaker()).thenReturn(new LogMaker(
                MetricsProto.MetricsEvent.VIEW_UNKNOWN));

        mStackScroller.mMenuEventListener.onMenuClicked(row, 0, 0, mock(
                NotificationMenuRowPlugin.MenuItem.class));
        verify(row.getStatusBarNotification()).getLogMaker();  // This writes most of the log data
        verify(mMetricsLogger).write(logMatcher(MetricsProto.MetricsEvent.ACTION_TOUCH_GEAR,
                MetricsProto.MetricsEvent.TYPE_ACTION));
    }

    @Test
    @UiThreadTest
    public void testOnMenuShownLogging() { ;

        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class, RETURNS_DEEP_STUBS);
        when(row.getStatusBarNotification().getLogMaker()).thenReturn(new LogMaker(
                MetricsProto.MetricsEvent.VIEW_UNKNOWN));

        mStackScroller.mMenuEventListener.onMenuShown(row);
        verify(row.getStatusBarNotification()).getLogMaker();  // This writes most of the log data
        verify(mMetricsLogger).write(logMatcher(MetricsProto.MetricsEvent.ACTION_REVEAL_GEAR,
                MetricsProto.MetricsEvent.TYPE_ACTION));
    }

    private void setBarStateForTest(int state) {
        // Can't inject this through the listener or we end up on the actual implementation
        // rather than the mock because the spy just coppied the anonymous inner /shruggie.
        mStackScroller.setStatusBarState(state);
    }
}
