/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack;

import static android.view.View.GONE;

import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.ROWS_ALL;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.ROWS_GENTLE;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.MathUtils;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SmallTest;

import com.android.systemui.ExpandHelper;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.EmptyShadeView;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.NotificationShelfController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.FooterView;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.phone.UnlockedScreenOffAnimationController;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link NotificationStackScrollLayout}.
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationStackScrollLayoutTest extends SysuiTestCase {

    private NotificationStackScrollLayout mStackScroller;  // Normally test this
    private NotificationStackScrollLayout mStackScrollerInternal;  // See explanation below
    private AmbientState mAmbientState;

    @Rule public MockitoRule mockito = MockitoJUnit.rule();
    @Mock private CentralSurfaces mCentralSurfaces;
    @Mock private SysuiStatusBarStateController mBarState;
    @Mock private NotificationGroupManagerLegacy mGroupMembershipManger;
    @Mock private NotificationGroupManagerLegacy mGroupExpansionManager;
    @Mock private ExpandHelper mExpandHelper;
    @Mock private EmptyShadeView mEmptyShadeView;
    @Mock private NotificationRoundnessManager mNotificationRoundnessManager;
    @Mock private KeyguardBypassController mBypassController;
    @Mock private NotificationSectionsManager mNotificationSectionsManager;
    @Mock private NotificationSection mNotificationSection;
    @Mock private NotificationSwipeHelper mNotificationSwipeHelper;
    @Mock private NotificationStackScrollLayoutController mStackScrollLayoutController;
    @Mock private UnlockedScreenOffAnimationController mUnlockedScreenOffAnimationController;
    @Mock private NotificationShelf mNotificationShelf;
    @Mock private NotificationStackSizeCalculator mNotificationStackSizeCalculator;
    @Mock private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;

    @Before
    @UiThreadTest
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();

        // Interact with real instance of AmbientState.
        mAmbientState = new AmbientState(mContext, mNotificationSectionsManager, mBypassController,
                mStatusBarKeyguardViewManager);

        // Inject dependencies before initializing the layout
        mDependency.injectTestDependency(SysuiStatusBarStateController.class, mBarState);
        mDependency.injectMockDependency(ShadeController.class);
        mDependency.injectTestDependency(
                NotificationSectionsManager.class, mNotificationSectionsManager);
        mDependency.injectTestDependency(GroupMembershipManager.class, mGroupMembershipManger);
        mDependency.injectTestDependency(GroupExpansionManager.class, mGroupExpansionManager);
        mDependency.injectTestDependency(AmbientState.class, mAmbientState);
        mDependency.injectTestDependency(NotificationShelf.class, mNotificationShelf);
        mDependency.injectTestDependency(
                UnlockedScreenOffAnimationController.class, mUnlockedScreenOffAnimationController);

        NotificationShelfController notificationShelfController =
                mock(NotificationShelfController.class);
        when(notificationShelfController.getView()).thenReturn(mNotificationShelf);
        when(mNotificationSectionsManager.createSectionsForBuckets()).thenReturn(
                new NotificationSection[]{
                        mNotificationSection
                });

        // The actual class under test.  You may need to work with this class directly when
        // testing anonymous class members of mStackScroller, like mMenuEventListener,
        // which refer to members of NotificationStackScrollLayout. The spy
        // holds a copy of the CUT's instances of these KeyguardBypassController, so they still
        // refer to the CUT's member variables, not the spy's member variables.
        mStackScrollerInternal = new NotificationStackScrollLayout(getContext(), null);
        mStackScrollerInternal.initView(getContext(), mNotificationSwipeHelper,
                mNotificationStackSizeCalculator);
        mStackScroller = spy(mStackScrollerInternal);
        mStackScroller.setShelfController(notificationShelfController);
        mStackScroller.setCentralSurfaces(mCentralSurfaces);
        mStackScroller.setEmptyShadeView(mEmptyShadeView);
        when(mStackScrollLayoutController.isHistoryEnabled()).thenReturn(true);
        when(mStackScrollLayoutController.getNoticationRoundessManager())
                .thenReturn(mNotificationRoundnessManager);
        mStackScroller.setController(mStackScrollLayoutController);

        // Stub out functionality that isn't necessary to test.
        doNothing().when(mCentralSurfaces)
                .executeRunnableDismissingKeyguard(any(Runnable.class),
                        any(Runnable.class),
                        anyBoolean(),
                        anyBoolean(),
                        anyBoolean());
        doNothing().when(mGroupExpansionManager).collapseGroups();
        doNothing().when(mExpandHelper).cancelImmediately();
        doNothing().when(mNotificationShelf).setAnimationsEnabled(anyBoolean());
    }

    @Test
    public void testUpdateStackHeight_withDozeAmount_whenDozeChanging() {
        final float dozeAmount = 0.5f;
        mAmbientState.setDozeAmount(dozeAmount);

        final float endHeight = 8f;
        final float expansionFraction = 1f;
        float expected = MathUtils.lerp(
                endHeight * StackScrollAlgorithm.START_FRACTION,
                endHeight, dozeAmount);

        mStackScroller.updateStackHeight(endHeight, expansionFraction);
        assertTrue(mAmbientState.getStackHeight() == expected);
    }

    @Test
    public void testUpdateStackHeight_withExpansionAmount_whenDozeNotChanging() {
        final float dozeAmount = 1f;
        mAmbientState.setDozeAmount(dozeAmount);

        final float endHeight = 8f;
        final float expansionFraction = 0.5f;
        final float expected = MathUtils.lerp(
                endHeight * StackScrollAlgorithm.START_FRACTION,
                endHeight, expansionFraction);

        mStackScroller.updateStackHeight(endHeight, expansionFraction);
        assertTrue(mAmbientState.getStackHeight() == expected);
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

        mStackScroller.updateEmptyShadeView(true, true);

        verify(mEmptyShadeView).setText(R.string.dnd_suppressing_shade_text);
    }

    @Test
    public void updateEmptyView_dndNotSuppressing() {
        mStackScroller.setEmptyShadeView(mEmptyShadeView);
        when(mEmptyShadeView.willBeGone()).thenReturn(true);

        mStackScroller.updateEmptyShadeView(true, false);

        verify(mEmptyShadeView).setText(R.string.empty_shade_text);
    }

    @Test
    public void updateEmptyView_noNotificationsToDndSuppressing() {
        mStackScroller.setEmptyShadeView(mEmptyShadeView);
        when(mEmptyShadeView.willBeGone()).thenReturn(true);
        mStackScroller.updateEmptyShadeView(true, false);
        verify(mEmptyShadeView).setText(R.string.empty_shade_text);

        mStackScroller.updateEmptyShadeView(true, true);
        verify(mEmptyShadeView).setText(R.string.dnd_suppressing_shade_text);
    }

    @Test
    @UiThreadTest
    public void testSetExpandedHeight_listenerReceivedCallbacks() {
        final float expectedHeight = 0f;

        mStackScroller.addOnExpandedHeightChangedListener((height, appear) -> {
            Assert.assertEquals(expectedHeight, height, 0);
        });
        mStackScroller.setExpandedHeight(expectedHeight);
    }

    @Test
    public void testAppearFractionCalculation() {
        // appear start position
        when(mNotificationShelf.getIntrinsicHeight()).thenReturn(100);
        // because it's the same as shelf height, appear start position equals shelf height
        mStackScroller.mStatusBarHeight = 100;
        // appear end position
        when(mEmptyShadeView.getHeight()).thenReturn(200);

        assertEquals(0f, mStackScroller.calculateAppearFraction(100));
        assertEquals(1f, mStackScroller.calculateAppearFraction(200));
        assertEquals(0.5f, mStackScroller.calculateAppearFraction(150));
    }

    @Test
    public void testAppearFractionCalculationIsNotNegativeWhenShelfBecomesSmaller() {
        // this situation might occur if status bar height is defined in pixels while shelf height
        // in dp and screen density changes - appear start position
        // (calculated in NSSL#getMinExpansionHeight) that is adjusting for status bar might
        // increase and become bigger that end position, which should be prevented

        // appear start position
        when(mNotificationShelf.getIntrinsicHeight()).thenReturn(80);
        mStackScroller.mStatusBarHeight = 100;
        // appear end position
        when(mEmptyShadeView.getHeight()).thenReturn(90);

        assertThat(mStackScroller.calculateAppearFraction(100)).isAtLeast(0);
    }

    @Test
    @UiThreadTest
    public void testSetExpandedHeight_withSplitShade_doesntInterpolateStackHeight() {
        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_use_split_notification_shade, /* value= */ true);
        final int[] expectedStackHeight = {0};

        mStackScroller.addOnExpandedHeightChangedListener((expandedHeight, appear) -> {
            assertWithMessage("Given shade enabled: %s",
                    true)
                    .that(mStackScroller.getHeight())
                    .isEqualTo(expectedStackHeight[0]);
        });

        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_use_split_notification_shade, /* value= */ false);
        expectedStackHeight[0] = 0;
        mStackScroller.setExpandedHeight(100f);

        mContext.getOrCreateTestableResources()
                .addOverride(R.bool.config_use_split_notification_shade, /* value= */ true);
        expectedStackHeight[0] = 100;
        mStackScroller.setExpandedHeight(100f);
    }


    @Test
    public void manageNotifications_visible() {
        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        when(view.willBeGone()).thenReturn(true);

        mStackScroller.updateFooterView(true, false, true);

        verify(view).setVisible(eq(true), anyBoolean());
        verify(view).setSecondaryVisible(eq(false), anyBoolean());
    }

    @Test
    public void clearAll_visible() {
        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        when(view.willBeGone()).thenReturn(true);

        mStackScroller.updateFooterView(true, true, true);

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

        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        mStackScroller.updateFooter();
        verify(mStackScroller, atLeastOnce()).updateFooterView(false, false, true);
    }

    @Test
    public void testUpdateFooter_remoteInput() {
        setBarStateForTest(StatusBarState.SHADE);
        mStackScroller.setCurrentUserSetup(true);

        mStackScroller.setIsRemoteInputActive(true);
        when(mStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        when(mStackScrollLayoutController.hasActiveClearableNotifications(eq(ROWS_ALL)))
                .thenReturn(true);

        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        mStackScroller.updateFooter();
        verify(mStackScroller).updateFooterView(false, true, true);
    }

    @Test
    public void testUpdateFooter_withoutNotifications() {
        setBarStateForTest(StatusBarState.SHADE);
        mStackScroller.setCurrentUserSetup(true);

        when(mStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        when(mStackScrollLayoutController.hasActiveClearableNotifications(eq(ROWS_ALL)))
                .thenReturn(false);

        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        mStackScroller.updateFooter();
        verify(mStackScroller).updateFooterView(false, false, true);
    }

    @Test
    public void testUpdateFooter_oneClearableNotification() {
        setBarStateForTest(StatusBarState.SHADE);
        mStackScroller.setCurrentUserSetup(true);

        when(mStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        when(mStackScrollLayoutController.hasActiveClearableNotifications(eq(ROWS_ALL)))
                .thenReturn(true);

        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        mStackScroller.updateFooter();
        verify(mStackScroller).updateFooterView(true, true, true);
    }

    @Test
    public void testUpdateFooter_withoutHistory() {
        setBarStateForTest(StatusBarState.SHADE);
        mStackScroller.setCurrentUserSetup(true);

        when(mStackScrollLayoutController.isHistoryEnabled()).thenReturn(false);
        when(mStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        when(mStackScrollLayoutController.hasActiveClearableNotifications(eq(ROWS_ALL)))
                .thenReturn(true);

        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        mStackScroller.updateFooter();
        verify(mStackScroller).updateFooterView(true, true, false);
    }

    @Test
    public void testUpdateFooter_oneClearableNotification_beforeUserSetup() {
        setBarStateForTest(StatusBarState.SHADE);
        mStackScroller.setCurrentUserSetup(false);

        when(mStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        when(mStackScrollLayoutController.hasActiveClearableNotifications(eq(ROWS_ALL)))
                .thenReturn(true);

        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        mStackScroller.updateFooter();
        verify(mStackScroller).updateFooterView(false, true, true);
    }

    @Test
    public void testUpdateFooter_oneNonClearableNotification() {
        setBarStateForTest(StatusBarState.SHADE);
        mStackScroller.setCurrentUserSetup(true);

        when(mStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        when(mStackScrollLayoutController.hasActiveClearableNotifications(eq(ROWS_ALL)))
                .thenReturn(false);
        when(mEmptyShadeView.getVisibility()).thenReturn(GONE);

        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        mStackScroller.updateFooter();
        verify(mStackScroller).updateFooterView(true, false, true);
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

        // Expecting the footer to be the last child
        int expected = mStackScroller.getChildCount() - 1;

        // move footer to end
        verify(mStackScroller).changeViewPosition(any(FooterView.class), eq(expected));
    }

    @Test
    public void testReInflatesFooterViews() {
        clearInvocations(mStackScroller);
        mStackScroller.reinflateViews();
        verify(mStackScroller).setFooterView(any());
        verify(mStackScroller).setEmptyShadeView(any());
    }

    @Test
    @UiThreadTest
    public void testSetIsBeingDraggedResetsExposedMenu() {
        mStackScroller.setIsBeingDragged(true);
        verify(mNotificationSwipeHelper).resetExposedMenuView(true, true);
    }

    @Test
    @UiThreadTest
    public void testPanelTrackingStartResetsExposedMenu() {
        mStackScroller.onPanelTrackingStarted();
        verify(mNotificationSwipeHelper).resetExposedMenuView(true, true);
    }

    @Test
    @UiThreadTest
    public void testDarkModeResetsExposedMenu() {
        mStackScroller.setHideAmount(0.1f, 0.1f);
        verify(mNotificationSwipeHelper).resetExposedMenuView(true, true);
    }

    @Test
    public void testClearNotifications_All() {
        final int[] numCalls = {0};
        final int[] selected = {-1};
        mStackScroller.setClearAllListener(selectedRows -> {
            numCalls[0]++;
            selected[0] = selectedRows;
        });

        mStackScroller.clearNotifications(ROWS_ALL, true);
        assertEquals(1, numCalls[0]);
        assertEquals(ROWS_ALL, selected[0]);
    }

    @Test
    public void testClearNotifications_Gentle() {
        final int[] numCalls = {0};
        final int[] selected = {-1};
        mStackScroller.setClearAllListener(selectedRows -> {
            numCalls[0]++;
            selected[0] = selectedRows;
        });

        mStackScroller.clearNotifications(NotificationStackScrollLayout.ROWS_GENTLE, false);
        assertEquals(1, numCalls[0]);
        assertEquals(ROWS_GENTLE, selected[0]);
    }

    @Test
    public void testAddNotificationUpdatesSpeedBumpIndex() {
        // initial state calculated == 0
        assertEquals(0, mStackScroller.getSpeedBumpIndex());

        // add notification that's before the speed bump
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        NotificationEntry entry = mock(NotificationEntry.class);
        when(row.getEntry()).thenReturn(entry);
        when(entry.isAmbient()).thenReturn(false);
        mStackScroller.addContainerView(row);

        // speed bump = 1
        assertEquals(1, mStackScroller.getSpeedBumpIndex());
    }

    @Test
    public void testAddAmbientNotificationNoSpeedBumpUpdate() {
        // initial state calculated  == 0
        assertEquals(0, mStackScroller.getSpeedBumpIndex());

        // add notification that's after the speed bump
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        NotificationEntry entry = mock(NotificationEntry.class);
        when(row.getEntry()).thenReturn(entry);
        when(entry.isAmbient()).thenReturn(true);
        mStackScroller.addContainerView(row);

        // speed bump is set to 0
        assertEquals(0, mStackScroller.getSpeedBumpIndex());
    }

    @Test
    public void testRemoveNotificationUpdatesSpeedBump() {
        // initial state calculated == 0
        assertEquals(0, mStackScroller.getSpeedBumpIndex());

        // add 3 notification that are after the speed bump
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        NotificationEntry entry = mock(NotificationEntry.class);
        when(row.getEntry()).thenReturn(entry);
        when(entry.isAmbient()).thenReturn(false);
        mStackScroller.addContainerView(row);

        // speed bump is 1
        assertEquals(1, mStackScroller.getSpeedBumpIndex());

        // remove the notification that was before the speed bump
        mStackScroller.removeContainerView(row);

        // speed bump is now 0
        assertEquals(0, mStackScroller.getSpeedBumpIndex());
    }

    private void setBarStateForTest(int state) {
        // Can't inject this through the listener or we end up on the actual implementation
        // rather than the mock because the spy just coppied the anonymous inner /shruggie.
        mStackScroller.setStatusBarState(state);
    }
}
