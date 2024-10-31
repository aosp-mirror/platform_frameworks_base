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
import static android.view.WindowInsets.Type.ime;

import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.ROWS_ALL;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.ROWS_GENTLE;
import static com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout.RUBBER_BAND_FACTOR_NORMAL;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertFalse;
import static org.mockito.AdditionalMatchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.DimenRes;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.SystemClock;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;
import android.testing.TestableResources;
import android.util.MathUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.keyguard.BouncerPanelExpansionCalculator;
import com.android.systemui.ExpandHelper;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.DisableSceneContainer;
import com.android.systemui.flags.EnableSceneContainer;
import com.android.systemui.flags.FakeFeatureFlags;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.qs.flags.NewQsUI;
import com.android.systemui.qs.flags.QSComposeFragment;
import com.android.systemui.res.R;
import com.android.systemui.shade.QSHeaderBoundsProvider;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator;
import com.android.systemui.statusbar.NotificationShelf;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.render.GroupExpansionManager;
import com.android.systemui.statusbar.notification.collection.render.GroupMembershipManager;
import com.android.systemui.statusbar.notification.emptyshade.shared.ModesEmptyShadeFix;
import com.android.systemui.statusbar.notification.emptyshade.ui.view.EmptyShadeView;
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor;
import com.android.systemui.statusbar.notification.footer.shared.NotifRedesignFooter;
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.ExpandableView;
import com.android.systemui.statusbar.notification.shared.NotificationThrottleHun;
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimBounds;
import com.android.systemui.statusbar.notification.stack.shared.model.ShadeScrimShape;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.ScreenOffAnimationController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.AvalancheController;
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController;
import com.android.systemui.wallpapers.domain.interactor.WallpaperInteractor;

import kotlin.Unit;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.function.Consumer;

/**
 * Tests for {@link NotificationStackScrollLayout}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class NotificationStackScrollLayoutTest extends SysuiTestCase {

    private final FakeFeatureFlags mFeatureFlags = new FakeFeatureFlags();
    private NotificationStackScrollLayout mStackScroller;  // Normally test this
    private NotificationStackScrollLayout mStackScrollerInternal;  // See explanation below
    private AmbientState mAmbientState;
    private TestableResources mTestableResources;
    @Rule public MockitoRule mockito = MockitoJUnit.rule();
    @Mock private SysuiStatusBarStateController mBarState;
    @Mock private GroupMembershipManager mGroupMembershipManger;
    @Mock private GroupExpansionManager mGroupExpansionManager;
    @Mock private DumpManager mDumpManager;
    @Mock private ExpandHelper mExpandHelper;
    @Mock private EmptyShadeView mEmptyShadeView;
    @Mock private NotificationRoundnessManager mNotificationRoundnessManager;
    @Mock private KeyguardBypassController mBypassController;
    @Mock private NotificationSectionsManager mNotificationSectionsManager;
    @Mock private NotificationSection mNotificationSection;
    @Mock private NotificationSwipeHelper mNotificationSwipeHelper;
    @Mock private NotificationStackScrollLayoutController mStackScrollLayoutController;
    @Mock private ScreenOffAnimationController mScreenOffAnimationController;
    @Mock private NotificationShelf mNotificationShelf;
    @Mock private WallpaperInteractor mWallpaperInteractor;
    @Mock private NotificationStackSizeCalculator mStackSizeCalculator;
    @Mock private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock private LargeScreenShadeInterpolator mLargeScreenShadeInterpolator;
    @Mock private AvalancheController mAvalancheController;

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
        mTestableResources = mContext.getOrCreateTestableResources();

        // Interact with real instance of AmbientState.
        mAmbientState = spy(new AmbientState(
                mContext,
                mDumpManager,
                mNotificationSectionsManager,
                mBypassController,
                mStatusBarKeyguardViewManager,
                mLargeScreenShadeInterpolator,
                mAvalancheController
        ));

        // Register the debug flags we use
        assertFalse(Flags.NSSL_DEBUG_LINES.getDefault());
        assertFalse(Flags.NSSL_DEBUG_REMOVE_ANIMATION.getDefault());
        mFeatureFlags.set(Flags.NSSL_DEBUG_LINES, false);
        mFeatureFlags.set(Flags.NSSL_DEBUG_REMOVE_ANIMATION, false);
        mFeatureFlags.set(Flags.LOCKSCREEN_ENABLE_LANDSCAPE, false);

        // Inject dependencies before initializing the layout
        mDependency.injectTestDependency(FeatureFlags.class, mFeatureFlags);
        mDependency.injectTestDependency(SysuiStatusBarStateController.class, mBarState);
        mDependency.injectMockDependency(ShadeController.class);
        mDependency.injectTestDependency(
                NotificationSectionsManager.class, mNotificationSectionsManager);
        mDependency.injectTestDependency(GroupMembershipManager.class, mGroupMembershipManger);
        mDependency.injectTestDependency(GroupExpansionManager.class, mGroupExpansionManager);
        mDependency.injectTestDependency(AmbientState.class, mAmbientState);
        mDependency.injectTestDependency(NotificationShelf.class, mNotificationShelf);
        mDependency.injectTestDependency(
                ScreenOffAnimationController.class, mScreenOffAnimationController);

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
                mStackSizeCalculator);
        mStackScroller = spy(mStackScrollerInternal);
        mStackScroller.setResetUserExpandedStatesRunnable(() -> {});
        mStackScroller.setEmptyShadeView(mEmptyShadeView);
        when(mStackScrollLayoutController.isHistoryEnabled()).thenReturn(true);
        when(mStackScrollLayoutController.getNotificationRoundnessManager())
                .thenReturn(mNotificationRoundnessManager);
        mStackScroller.setController(mStackScrollLayoutController);
        mStackScroller.setShelf(mNotificationShelf);
        mStackScroller.setWallpaperInteractor(mWallpaperInteractor);
        when(mStackScroller.getExpandHelper()).thenReturn(mExpandHelper);

        doNothing().when(mGroupExpansionManager).collapseGroups();
        doNothing().when(mExpandHelper).cancelImmediately();
        doNothing().when(mNotificationShelf).setAnimationsEnabled(anyBoolean());
    }

    @Test
    @DisableSceneContainer // TODO(b/332574413) cover stack bounds integration with tests
    public void testUpdateStackHeight_qsExpansionGreaterThanZero() {
        final float expansionFraction = 0.2f;
        final float overExpansion = 50f;

        mStackScroller.setQsExpansionFraction(1f);
        mAmbientState.setExpansionFraction(expansionFraction);
        mAmbientState.setOverExpansion(overExpansion);
        when(mAmbientState.isBouncerInTransit()).thenReturn(true);


        mStackScroller.setExpandedHeight(100f);

        float expected = MathUtils.lerp(0, overExpansion,
                BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(expansionFraction));
        assertThat(mAmbientState.getStackY()).isEqualTo(expected);
    }

    @Test
    @EnableSceneContainer
    public void testIntrinsicStackHeight() {
        int stackHeight = 300;
        when(mStackSizeCalculator.computeHeight(eq(mStackScroller), anyInt(), anyFloat()))
                .thenReturn((float) stackHeight);

        mStackScroller.updateIntrinsicStackHeight();

        assertThat(mStackScroller.getIntrinsicStackHeight()).isEqualTo(stackHeight);
    }

    @Test
    @DisableSceneContainer // TODO(b/312473478): address disabled test
    public void testUpdateStackHeight_qsExpansionZero() {
        final float expansionFraction = 0.2f;
        final float overExpansion = 50f;

        mStackScroller.setQsExpansionFraction(0f);
        mAmbientState.setExpansionFraction(expansionFraction);
        mAmbientState.setOverExpansion(overExpansion);
        when(mAmbientState.isBouncerInTransit()).thenReturn(true);

        mStackScroller.setExpandedHeight(100f);

        float expected = MathUtils.lerp(0, overExpansion, expansionFraction);
        assertThat(mAmbientState.getStackY()).isEqualTo(expected);
    }

    @Test
    public void testUpdateStackHeight_withExpansionAmount_whenDozeNotChanging() {
        final float endHeight = 8f;
        final float expansionFraction = 0.5f;
        final float expected = MathUtils.lerp(
                endHeight * StackScrollAlgorithm.START_FRACTION,
                endHeight, expansionFraction);

        mStackScroller.updateInterpolatedStackHeight(endHeight, expansionFraction);
        assertThat(mAmbientState.getInterpolatedStackHeight()).isEqualTo(expected);
    }

    @Test
    @EnableSceneContainer
    public void updateStackEndHeightAndStackHeight_shadeFullyExpanded_withSceneContainer() {
        final float stackTop = 200f;
        final float stackCutoff = 1000f;
        final float stackEndHeight = stackCutoff - stackTop;
        mAmbientState.setStackTop(stackTop);
        mAmbientState.setStackCutoff(stackCutoff);
        mAmbientState.setStatusBarState(StatusBarState.KEYGUARD);
        clearInvocations(mAmbientState);

        // WHEN shade is fully expanded
        mStackScroller.updateStackEndHeightAndStackHeight(/* fraction = */ 1.0f);

        // THEN stackHeight and stackEndHeight are the same
        verify(mAmbientState).setStackEndHeight(stackEndHeight);
        verify(mAmbientState).setInterpolatedStackHeight(stackEndHeight);
    }

    @Test
    @EnableSceneContainer
    public void updateStackEndHeightAndStackHeight_shadeExpanding_withSceneContainer() {
        final float stackTop = 200f;
        final float stackCutoff = 1000f;
        final float stackEndHeight = stackCutoff - stackTop;
        mAmbientState.setStackTop(stackTop);
        mAmbientState.setStackCutoff(stackCutoff);
        mAmbientState.setStatusBarState(StatusBarState.KEYGUARD);
        clearInvocations(mAmbientState);

        // WHEN shade is expanding
        final float expansionFraction = 0.5f;
        mStackScroller.updateStackEndHeightAndStackHeight(expansionFraction);

        // THEN stackHeight is changed by the expansion frac
        verify(mAmbientState).setStackEndHeight(stackEndHeight);
        verify(mAmbientState).setInterpolatedStackHeight(stackEndHeight * 0.75f);
    }

    @Test
    @EnableSceneContainer
    public void updateStackEndHeightAndStackHeight_shadeOverscrolledToTop_withSceneContainer() {
        // GIVEN stack scrolled over the top, stack top is negative
        final float stackTop = -2000f;
        final float stackCutoff = 1000f;
        final float stackEndHeight = stackCutoff - stackTop;
        mAmbientState.setStackTop(stackTop);
        mAmbientState.setStackCutoff(stackCutoff);
        mAmbientState.setStatusBarState(StatusBarState.KEYGUARD);
        clearInvocations(mAmbientState);

        // WHEN stack is updated
        mStackScroller.updateStackEndHeightAndStackHeight(/* fraction = */ 1.0f);

        // THEN stackHeight is measured from the stack top
        verify(mAmbientState).setStackEndHeight(stackEndHeight);
        verify(mAmbientState).setInterpolatedStackHeight(stackEndHeight);
    }

    @Test
    @EnableSceneContainer
    public void updateStackEndHeightAndStackHeight_maxNotificationsSet_withSceneContainer() {
        float stackHeight = 300f;
        when(mStackSizeCalculator.computeHeight(eq(mStackScroller), anyInt(), anyFloat()))
                .thenReturn(stackHeight);
        mStackScroller.setMaxDisplayedNotifications(3); // any non-zero amount

        clearInvocations(mAmbientState);
        mStackScroller.updateStackEndHeightAndStackHeight(1f);

        verify(mAmbientState).setInterpolatedStackHeight(eq(300f));
    }

    @Test
    public void updateStackEndHeightAndStackHeight_onlyUpdatesStackHeightDuringSwipeUp() {
        final float expansionFraction = 0.5f;
        mAmbientState.setStatusBarState(StatusBarState.KEYGUARD);
        mAmbientState.setSwipingUp(true);

        // Validate that when the gesture is in progress, we update only the stackHeight
        clearInvocations(mAmbientState);
        mStackScroller.updateStackEndHeightAndStackHeight(expansionFraction);
        verify(mAmbientState, never()).setStackEndHeight(anyFloat());
        verify(mAmbientState).setInterpolatedStackHeight(anyFloat());
    }

    @Test
    public void setPanelFlinging_updatesStackEndHeightOnlyOnFinish() {
        final float expansionFraction = 0.5f;
        mAmbientState.setStatusBarState(StatusBarState.KEYGUARD);
        mAmbientState.setSwipingUp(true);
        mStackScroller.setPanelFlinging(true);
        mAmbientState.setSwipingUp(false);

        // Validate that when the animation is running, we update only the stackHeight
        clearInvocations(mAmbientState);
        mStackScroller.updateStackEndHeightAndStackHeight(expansionFraction);
        verify(mAmbientState, never()).setStackEndHeight(anyFloat());
        verify(mAmbientState).setInterpolatedStackHeight(anyFloat());

        // Validate that when the animation ends the stackEndHeight is recalculated immediately
        clearInvocations(mAmbientState);
        mStackScroller.setPanelFlinging(false);
        verify(mAmbientState).setFlinging(eq(false));
        verify(mAmbientState).setStackEndHeight(anyFloat());
        verify(mAmbientState).setInterpolatedStackHeight(anyFloat());
    }

    @Test
    @DisableFlags(ModesEmptyShadeFix.FLAG_NAME)
    public void updateEmptyView_dndSuppressing() {
        when(mEmptyShadeView.willBeGone()).thenReturn(true);

        mStackScroller.updateEmptyShadeView(/* visible = */ true,
                /* areNotificationsHiddenInShade = */ true,
                /* hasFilteredOutSeenNotifications = */ false);

        verify(mEmptyShadeView).setText(R.string.dnd_suppressing_shade_text);
    }

    @Test
    @DisableFlags(ModesEmptyShadeFix.FLAG_NAME)
    public void updateEmptyView_dndNotSuppressing() {
        mStackScroller.setEmptyShadeView(mEmptyShadeView);
        when(mEmptyShadeView.willBeGone()).thenReturn(true);

        mStackScroller.updateEmptyShadeView(/* visible = */ true,
                /* areNotificationsHiddenInShade = */ false,
                /* hasFilteredOutSeenNotifications = */ false);

        verify(mEmptyShadeView).setText(R.string.empty_shade_text);
    }

    @Test
    @DisableFlags(ModesEmptyShadeFix.FLAG_NAME)
    public void updateEmptyView_noNotificationsToDndSuppressing() {
        mStackScroller.setEmptyShadeView(mEmptyShadeView);
        when(mEmptyShadeView.willBeGone()).thenReturn(true);
        mStackScroller.updateEmptyShadeView(/* visible = */ true,
                /* areNotificationsHiddenInShade = */ false,
                /* hasFilteredOutSeenNotifications = */ false);
        verify(mEmptyShadeView).setText(R.string.empty_shade_text);

        mStackScroller.updateEmptyShadeView(/* visible = */ true,
                /* areNotificationsHiddenInShade = */ true,
                /* hasFilteredOutSeenNotifications = */ false);
        verify(mEmptyShadeView).setText(R.string.dnd_suppressing_shade_text);
    }

    @Test
    @EnableSceneContainer
    public void setExpandFraction_fullyCollapsed() {
        // Given: NSSL has a height
        when(mStackScroller.getHeight()).thenReturn(1200);
        // And: stack bounds are set
        float expandFraction = 0.0f;
        float stackTop = 100;
        float stackCutoff = 1100;
        float stackHeight = stackCutoff - stackTop;
        mStackScroller.setStackTop(stackTop);
        mStackScroller.setStackCutoff(stackCutoff);

        // When: panel is fully collapsed
        mStackScroller.setExpandFraction(expandFraction);

        // Then
        assertThat(mAmbientState.getExpansionFraction()).isEqualTo(expandFraction);
        assertThat(mAmbientState.isExpansionChanging()).isFalse();
        assertThat(mAmbientState.getStackEndHeight()).isEqualTo(stackHeight);
        assertThat(mAmbientState.getInterpolatedStackHeight()).isEqualTo(
                stackHeight * StackScrollAlgorithm.START_FRACTION);
        assertThat(mAmbientState.isShadeExpanded()).isFalse();
        assertThat(mStackScroller.getExpandedHeight()).isZero();
    }

    @Test
    @EnableSceneContainer
    public void setExpandFraction_expanding() {
        // Given: NSSL has a height
        when(mStackScroller.getHeight()).thenReturn(1200);
        // And: stack bounds are set
        float expandFraction = 0.6f;
        float stackTop = 100;
        float stackCutoff = 1100;
        float stackHeight = stackCutoff - stackTop;
        mStackScroller.setStackTop(stackTop);
        mStackScroller.setStackCutoff(stackCutoff);

        // When: panel is expanding
        mStackScroller.setExpandFraction(expandFraction);

        // Then
        assertThat(mAmbientState.getExpansionFraction()).isEqualTo(expandFraction);
        assertThat(mAmbientState.isExpansionChanging()).isTrue();
        assertThat(mAmbientState.getStackEndHeight()).isEqualTo(stackHeight);
        assertThat(mAmbientState.getInterpolatedStackHeight()).isGreaterThan(
                stackHeight * StackScrollAlgorithm.START_FRACTION);
        assertThat(mAmbientState.getInterpolatedStackHeight()).isLessThan(stackHeight);
        assertThat(mStackScroller.getExpandedHeight()).isGreaterThan(0f);
        assertThat(mAmbientState.isShadeExpanded()).isTrue();
    }

    @Test
    @EnableSceneContainer
    public void setExpandFraction_fullyExpanded() {
        // Given: NSSL has a height
        int viewHeight = 1200;
        when(mStackScroller.getHeight()).thenReturn(viewHeight);
        // And: stack bounds are set
        float expandFraction = 1.0f;
        float stackTop = 100;
        float stackCutoff = 1100;
        float stackHeight = stackCutoff - stackTop;
        mStackScroller.setStackTop(stackTop);
        mStackScroller.setStackCutoff(stackCutoff);

        // When: panel is fully expanded
        mStackScroller.setExpandFraction(expandFraction);

        // Then
        assertThat(mAmbientState.getExpansionFraction()).isEqualTo(expandFraction);
        assertThat(mAmbientState.isExpansionChanging()).isFalse();
        assertThat(mAmbientState.getStackEndHeight()).isEqualTo(stackHeight);
        assertThat(mAmbientState.getInterpolatedStackHeight()).isEqualTo(stackHeight);
        assertThat(mStackScroller.getExpandedHeight()).isEqualTo(viewHeight);
        assertThat(mAmbientState.isShadeExpanded()).isTrue();
    }

    @Test
    @DisableSceneContainer
    public void testSetExpandedHeight_listenerReceivedCallbacks() {
        final float expectedHeight = 0f;

        mStackScroller.addOnExpandedHeightChangedListener((height, appear) -> {
            Assert.assertEquals(expectedHeight, height, 0);
        });
        mStackScroller.setExpandedHeight(expectedHeight);
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
    @DisableSceneContainer
    public void testSetExpandedHeight_withSplitShade_doesntInterpolateStackHeight() {
        mTestableResources
                .addOverride(R.bool.config_use_split_notification_shade, /* value= */ true);
        final int[] expectedStackHeight = {0};

        mStackScroller.addOnExpandedHeightChangedListener((expandedHeight, appear) -> {
            assertWithMessage("Given shade enabled: %s",
                    true)
                    .that(mStackScroller.getHeight())
                    .isEqualTo(expectedStackHeight[0]);
        });

        mTestableResources
                .addOverride(R.bool.config_use_split_notification_shade, /* value= */ false);
        expectedStackHeight[0] = 0;
        mStackScroller.setExpandedHeight(100f);

        mTestableResources
                .addOverride(R.bool.config_use_split_notification_shade, /* value= */ true);
        expectedStackHeight[0] = 100;
        mStackScroller.setExpandedHeight(100f);
    }

    @Test
    @DisableFlags({FooterViewRefactor.FLAG_NAME, NotifRedesignFooter.FLAG_NAME})
    public void manageNotifications_visible() {
        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        when(view.willBeGone()).thenReturn(true);

        mStackScroller.updateFooterView(true, false, true);

        verify(view).setVisible(eq(true), anyBoolean());
        verify(view).setClearAllButtonVisible(eq(false), anyBoolean());
    }

    @Test
    @DisableFlags({FooterViewRefactor.FLAG_NAME, NotifRedesignFooter.FLAG_NAME})
    public void clearAll_visible() {
        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        when(view.willBeGone()).thenReturn(true);

        mStackScroller.updateFooterView(true, true, true);

        verify(view).setVisible(eq(true), anyBoolean());
        verify(view).setClearAllButtonVisible(eq(true), anyBoolean());
    }

    @Test
    @DisableFlags({FooterViewRefactor.FLAG_NAME, NotifRedesignFooter.FLAG_NAME})
    public void testInflateFooterView() {
        mStackScroller.inflateFooterView();
        ArgumentCaptor<FooterView> captor = ArgumentCaptor.forClass(FooterView.class);
        verify(mStackScroller).setFooterView(captor.capture());

        assertNotNull(captor.getValue().findViewById(R.id.manage_text));
        assertNotNull(captor.getValue().findViewById(R.id.dismiss_text));
    }

    @Test
    @DisableFlags({FooterViewRefactor.FLAG_NAME, NotifRedesignFooter.FLAG_NAME})
    public void testUpdateFooter_noNotifications() {
        setBarStateForTest(StatusBarState.SHADE);
        mStackScroller.setCurrentUserSetup(true);

        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        mStackScroller.updateFooter();
        verify(mStackScroller, atLeastOnce()).updateFooterView(false, false, true);
    }

    @Test
    @DisableFlags({FooterViewRefactor.FLAG_NAME, NotifRedesignFooter.FLAG_NAME})
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
        verify(mStackScroller, atLeastOnce()).updateFooterView(false, true, true);
    }

    @Test
    @DisableFlags({FooterViewRefactor.FLAG_NAME, NotifRedesignFooter.FLAG_NAME})
    public void testUpdateFooter_withoutNotifications() {
        setBarStateForTest(StatusBarState.SHADE);
        mStackScroller.setCurrentUserSetup(true);

        when(mStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(0);
        when(mStackScrollLayoutController.hasActiveClearableNotifications(eq(ROWS_ALL)))
                .thenReturn(false);

        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        mStackScroller.updateFooter();
        verify(mStackScroller, atLeastOnce()).updateFooterView(false, false, true);
    }

    @Test
    @DisableFlags({FooterViewRefactor.FLAG_NAME, NotifRedesignFooter.FLAG_NAME})
    public void testUpdateFooter_oneClearableNotification() {
        setBarStateForTest(StatusBarState.SHADE);
        mStackScroller.setCurrentUserSetup(true);

        when(mStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        when(mStackScrollLayoutController.hasActiveClearableNotifications(eq(ROWS_ALL)))
                .thenReturn(true);

        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        mStackScroller.updateFooter();
        verify(mStackScroller, atLeastOnce()).updateFooterView(true, true, true);
    }

    @Test
    @DisableFlags({FooterViewRefactor.FLAG_NAME, NotifRedesignFooter.FLAG_NAME})
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
        verify(mStackScroller, atLeastOnce()).updateFooterView(true, true, false);
    }

    @Test
    @DisableFlags({FooterViewRefactor.FLAG_NAME, NotifRedesignFooter.FLAG_NAME})
    public void testUpdateFooter_oneClearableNotification_beforeUserSetup() {
        setBarStateForTest(StatusBarState.SHADE);
        mStackScroller.setCurrentUserSetup(false);

        when(mStackScrollLayoutController.getVisibleNotificationCount()).thenReturn(1);
        when(mStackScrollLayoutController.hasActiveClearableNotifications(eq(ROWS_ALL)))
                .thenReturn(true);

        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);
        mStackScroller.updateFooter();
        verify(mStackScroller, atLeastOnce()).updateFooterView(false, true, true);
    }

    @Test
    @DisableFlags({FooterViewRefactor.FLAG_NAME, NotifRedesignFooter.FLAG_NAME})
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
        verify(mStackScroller, atLeastOnce()).updateFooterView(true, false, true);
    }

    @Test
    public void testFooterPosition_atEnd() {
        // add footer
        FooterView view = mock(FooterView.class);
        mStackScroller.setFooterView(view);

        // add notification
        ExpandableNotificationRow row = createClearableRow();
        mStackScroller.addContainerView(row);

        mStackScroller.onUpdateRowStates();

        // Expecting the footer to be the last child
        int expected = mStackScroller.getChildCount() - 1;
        verify(mStackScroller).changeViewPosition(any(FooterView.class), eq(expected));
    }

    @Test
    @DisableFlags({FooterViewRefactor.FLAG_NAME, ModesEmptyShadeFix.FLAG_NAME})
    public void testReInflatesFooterViews() {
        when(mEmptyShadeView.getTextResource()).thenReturn(R.string.empty_shade_text);
        clearInvocations(mStackScroller);
        mStackScroller.reinflateViews();
        verify(mStackScroller).setFooterView(any());
        verify(mStackScroller).setEmptyShadeView(any());
    }

    @Test
    @EnableFlags(FooterViewRefactor.FLAG_NAME)
    @DisableFlags(ModesEmptyShadeFix.FLAG_NAME)
    public void testReInflatesEmptyShadeView() {
        when(mEmptyShadeView.getTextResource()).thenReturn(R.string.empty_shade_text);
        clearInvocations(mStackScroller);
        mStackScroller.reinflateViews();
        verify(mStackScroller, never()).setFooterView(any());
        verify(mStackScroller).setEmptyShadeView(any());
    }

    @Test
    public void testSetIsBeingDraggedResetsExposedMenu() {
        mStackScroller.setIsBeingDragged(true);
        verify(mNotificationSwipeHelper).resetExposedMenuView(true, true);
    }

    @Test
    public void testPanelTrackingStartResetsExposedMenu() {
        mStackScroller.onPanelTrackingStarted();
        verify(mNotificationSwipeHelper).resetExposedMenuView(true, true);
    }

    @Test
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

        mStackScroller.clearNotifications(ROWS_ALL,
                /* closeShade = */ true,
                /* hideSilentSection = */ true);
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

        mStackScroller.clearNotifications(NotificationStackScrollLayout.ROWS_GENTLE,
                /* closeShade = */ false,
                /* hideSilentSection = */ true);
        assertEquals(1, numCalls[0]);
        assertEquals(ROWS_GENTLE, selected[0]);
    }

    @Test
    public void testClearNotifications_clearAllInProgress() {
        ExpandableNotificationRow row = createClearableRow();
        when(row.getEntry().hasFinishedInitialization()).thenReturn(true);
        doReturn(true).when(mStackScroller).isVisible(row);
        mStackScroller.addContainerView(row);

        mStackScroller.clearNotifications(ROWS_ALL,
                /* closeShade = */ false,
                /* hideSilentSection = */ false);

        assertClearAllInProgress(true);
        verify(mNotificationRoundnessManager).setClearAllInProgress(true);
    }

    @Test
    public void testOnChildAnimationFinished_resetsClearAllInProgress() {
        mStackScroller.setClearAllInProgress(true);

        mStackScroller.onChildAnimationFinished();

        assertClearAllInProgress(false);
        verify(mNotificationRoundnessManager).setClearAllInProgress(false);
    }

    @Test
    public void testShadeCollapsed_resetsClearAllInProgress() {
        mStackScroller.setClearAllInProgress(true);

        mStackScroller.setIsExpanded(false);

        assertClearAllInProgress(false);
        verify(mNotificationRoundnessManager).setClearAllInProgress(false);
    }

    @Test
    public void testShadeExpanded_doesntChangeClearAllInProgress() {
        mStackScroller.setClearAllInProgress(true);
        clearInvocations(mNotificationRoundnessManager);

        mStackScroller.setIsExpanded(true);

        assertClearAllInProgress(true);
        verify(mNotificationRoundnessManager, never()).setClearAllInProgress(anyBoolean());
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

    @Test
    @DisableFlags({QSComposeFragment.FLAG_NAME, NewQsUI.FLAG_NAME})
    @DisableSceneContainer
    public void testInsideQSHeader_noOffset() {
        ViewGroup qsHeader = mock(ViewGroup.class);
        Rect boundsOnScreen = new Rect(0, 0, 1000, 1000);
        mockBoundsOnScreen(qsHeader, boundsOnScreen);

        mStackScroller.setQsHeader(qsHeader);
        mStackScroller.setLeftTopRightBottom(0, 0, 2000, 2000);

        MotionEvent event1 = transformEventForView(createMotionEvent(100f, 100f), mStackScroller);
        assertTrue(mStackScroller.isInsideQsHeader(event1));

        MotionEvent event2 = transformEventForView(createMotionEvent(1100f, 100f), mStackScroller);
        assertFalse(mStackScroller.isInsideQsHeader(event2));
    }

    @Test
    @DisableFlags({QSComposeFragment.FLAG_NAME, NewQsUI.FLAG_NAME})
    @DisableSceneContainer
    public void testInsideQSHeader_Offset() {
        ViewGroup qsHeader = mock(ViewGroup.class);
        Rect boundsOnScreen = new Rect(100, 100, 1000, 1000);
        mockBoundsOnScreen(qsHeader, boundsOnScreen);

        mStackScroller.setQsHeader(qsHeader);
        mStackScroller.setLeftTopRightBottom(200, 200, 2000, 2000);

        MotionEvent event1 = transformEventForView(createMotionEvent(50f, 50f), mStackScroller);
        assertFalse(mStackScroller.isInsideQsHeader(event1));

        MotionEvent event2 = transformEventForView(createMotionEvent(150f, 150f), mStackScroller);
        assertFalse(mStackScroller.isInsideQsHeader(event2));

        MotionEvent event3 = transformEventForView(createMotionEvent(250f, 250f), mStackScroller);
        assertTrue(mStackScroller.isInsideQsHeader(event3));
    }

    @Test
    @EnableFlags({QSComposeFragment.FLAG_NAME, NewQsUI.FLAG_NAME})
    @DisableSceneContainer
    public void testInsideQSHeader_noOffset_qsCompose() {
        ViewGroup qsHeader = mock(ViewGroup.class);
        Rect boundsOnScreen = new Rect(0, 0, 1000, 1000);
        mockBoundsOnScreen(qsHeader, boundsOnScreen);

        QSHeaderBoundsProvider provider = new QSHeaderBoundsProvider(
                () -> 0,
                boundsOnScreen::height,
                rect -> {
                    qsHeader.getBoundsOnScreen(rect);
                    return Unit.INSTANCE;
                }
        );

        mStackScroller.setQsHeaderBoundsProvider(provider);
        mStackScroller.setLeftTopRightBottom(0, 0, 2000, 2000);

        MotionEvent event1 = transformEventForView(createMotionEvent(100f, 100f), mStackScroller);
        assertTrue(mStackScroller.isInsideQsHeader(event1));

        MotionEvent event2 = transformEventForView(createMotionEvent(1100f, 100f), mStackScroller);
        assertFalse(mStackScroller.isInsideQsHeader(event2));
    }

    @Test
    @EnableFlags({QSComposeFragment.FLAG_NAME, NewQsUI.FLAG_NAME})
    @DisableSceneContainer
    public void testInsideQSHeader_Offset_qsCompose() {
        ViewGroup qsHeader = mock(ViewGroup.class);
        Rect boundsOnScreen = new Rect(100, 100, 1000, 1000);
        mockBoundsOnScreen(qsHeader, boundsOnScreen);

        QSHeaderBoundsProvider provider = new QSHeaderBoundsProvider(
                () -> 0,
                boundsOnScreen::height,
                rect -> {
                    qsHeader.getBoundsOnScreen(rect);
                    return Unit.INSTANCE;
                }
        );

        mStackScroller.setQsHeaderBoundsProvider(provider);
        mStackScroller.setLeftTopRightBottom(200, 200, 2000, 2000);

        MotionEvent event1 = transformEventForView(createMotionEvent(50f, 50f), mStackScroller);
        assertFalse(mStackScroller.isInsideQsHeader(event1));

        MotionEvent event2 = transformEventForView(createMotionEvent(150f, 150f), mStackScroller);
        assertFalse(mStackScroller.isInsideQsHeader(event2));

        MotionEvent event3 = transformEventForView(createMotionEvent(250f, 250f), mStackScroller);
        assertTrue(mStackScroller.isInsideQsHeader(event3));
    }

    @Test
    @EnableSceneContainer
    public void testIsInsideScrollableRegion_noScrim() {
        mStackScroller.setLeftTopRightBottom(0, 0, 2000, 2000);

        MotionEvent event = transformEventForView(createMotionEvent(250f, 250f), mStackScroller);
        assertThat(mStackScroller.isInScrollableRegion(event)).isTrue();
    }

    @Test
    @EnableSceneContainer
    public void testIsInsideScrollableRegion_noOffset() {
        mStackScroller.setLeftTopRightBottom(0, 0, 1000, 2000);
        mStackScroller.setScrimClippingShape(createScrimShape(100, 500, 900, 2000));

        MotionEvent event1 = transformEventForView(createMotionEvent(500f, 400f), mStackScroller);
        assertThat(mStackScroller.isInScrollableRegion(event1)).isFalse();

        MotionEvent event2 = transformEventForView(createMotionEvent(50, 1000f), mStackScroller);
        assertThat(mStackScroller.isInScrollableRegion(event2)).isFalse();

        MotionEvent event3 = transformEventForView(createMotionEvent(950f, 1000f), mStackScroller);
        assertThat(mStackScroller.isInScrollableRegion(event3)).isFalse();

        MotionEvent event4 = transformEventForView(createMotionEvent(500f, 1000f), mStackScroller);
        assertThat(mStackScroller.isInScrollableRegion(event4)).isTrue();
    }

    @Test
    @EnableSceneContainer
    public void testIsInsideScrollableRegion_offset() {
        mStackScroller.setLeftTopRightBottom(1000, 0, 2000, 2000);
        mStackScroller.setScrimClippingShape(createScrimShape(100, 500, 900, 2000));

        MotionEvent event1 = transformEventForView(createMotionEvent(1500f, 400f), mStackScroller);
        assertThat(mStackScroller.isInScrollableRegion(event1)).isFalse();

        MotionEvent event2 = transformEventForView(createMotionEvent(1050, 1000f), mStackScroller);
        assertThat(mStackScroller.isInScrollableRegion(event2)).isFalse();

        MotionEvent event3 = transformEventForView(createMotionEvent(1950f, 1000f), mStackScroller);
        assertThat(mStackScroller.isInScrollableRegion(event3)).isFalse();

        MotionEvent event4 = transformEventForView(createMotionEvent(1500f, 1000f), mStackScroller);
        assertThat(mStackScroller.isInScrollableRegion(event4)).isTrue();
    }

    @Test
    @DisableSceneContainer // TODO(b/312473478): address disabled test
    public void setFractionToShade_recomputesStackHeight() {
        mStackScroller.setFractionToShade(1f);
        verify(mStackSizeCalculator).computeHeight(any(), anyInt(), anyFloat());
    }

    @Test
    @DisableSceneContainer // TODO(b/312473478): address disabled test
    public void testSetOwnScrollY_shadeNotClosing_scrollYChanges() {
        // Given: shade is not closing, scrollY is 0
        mAmbientState.setScrollY(0);
        assertEquals(0, mAmbientState.getScrollY());
        mAmbientState.setIsClosing(false);

        // When: call NotificationStackScrollLayout.setOwnScrollY to set scrollY to 1
        mStackScroller.setOwnScrollY(1);

        // Then: scrollY should be set to 1
        assertEquals(1, mAmbientState.getScrollY());

        // Reset scrollY back to 0 to avoid interfering with other tests
        mStackScroller.setOwnScrollY(0);
        assertEquals(0, mAmbientState.getScrollY());
    }

    @Test
    public void testSetOwnScrollY_shadeClosing_scrollYDoesNotChange() {
        // Given: shade is closing, scrollY is 0
        mAmbientState.setScrollY(0);
        assertEquals(0, mAmbientState.getScrollY());
        mAmbientState.setIsClosing(true);

        // When: call NotificationStackScrollLayout.setOwnScrollY to set scrollY to 1
        mStackScroller.setOwnScrollY(1);

        // Then: scrollY should not change, it should still be 0
        assertEquals(0, mAmbientState.getScrollY());

        // Reset scrollY and mAmbientState.mIsClosing to avoid interfering with other tests
        mAmbientState.setIsClosing(false);
        mStackScroller.setOwnScrollY(0);
        assertEquals(0, mAmbientState.getScrollY());
    }

    @Test
    public void testSetOwnScrollY_clearAllInProgress_scrollYDoesNotChange() {
        // Given: clear all is in progress, scrollY is 0
        mAmbientState.setScrollY(0);
        assertEquals(0, mAmbientState.getScrollY());
        mAmbientState.setClearAllInProgress(true);

        // When: call NotificationStackScrollLayout.setOwnScrollY to set scrollY to 1
        mStackScroller.setOwnScrollY(1);

        // Then: scrollY should not change, it should still be 0
        assertEquals(0, mAmbientState.getScrollY());

        // Reset scrollY and mAmbientState.mIsClosing to avoid interfering with other tests
        mAmbientState.setClearAllInProgress(false);
        mStackScroller.setOwnScrollY(0);
        assertEquals(0, mAmbientState.getScrollY());
    }

    @Test
    public void onShadeFlingClosingEnd_scrollYShouldBeSetToZero() {
        // Given: mAmbientState.mIsClosing is set to be true
        // mIsExpanded is set to be false
        mAmbientState.setIsClosing(true);
        mStackScroller.setIsExpanded(false);

        // When: onExpansionStopped is called
        mStackScroller.onExpansionStopped();

        // Then: mAmbientState.scrollY should be set to be 0
        assertEquals(mAmbientState.getScrollY(), 0);
    }

    @Test
    public void onShadeClosesWithAnimationWillResetTouchState() {
        // GIVEN shade is expanded
        mStackScroller.setIsExpanded(true);
        clearInvocations(mNotificationSwipeHelper);

        // WHEN closing the shade with the animations
        mStackScroller.onExpansionStarted();
        mStackScroller.setIsExpanded(false);
        mStackScroller.onExpansionStopped();

        // VERIFY touch is reset
        verify(mNotificationSwipeHelper).resetTouchState();
    }

    @Test
    public void onShadeClosesWithoutAnimationWillResetTouchState() {
        // GIVEN shade is expanded
        mStackScroller.setIsExpanded(true);
        clearInvocations(mNotificationSwipeHelper);

        // WHEN closing the shade without the animation
        mStackScroller.setIsExpanded(false);

        // VERIFY touch is reset
        verify(mNotificationSwipeHelper).resetTouchState();
    }

    @Test
    @DisableSceneContainer // TODO(b/312473478): address disabled test
    public void testSplitShade_hasTopOverscroll() {
        mTestableResources
                .addOverride(R.bool.config_use_split_notification_shade, /* value= */ true);
        mStackScroller.passSplitShadeStateController(new ResourcesSplitShadeStateController());
        mStackScroller.updateSplitNotificationShade();
        mAmbientState.setExpansionFraction(1f);

        int topOverscrollPixels = 100;
        mStackScroller.setOverScrolledPixels(topOverscrollPixels, true, false);

        float expectedTopOverscrollAmount = topOverscrollPixels * RUBBER_BAND_FACTOR_NORMAL;
        assertEquals(expectedTopOverscrollAmount, mStackScroller.getCurrentOverScrollAmount(true));
        assertEquals(expectedTopOverscrollAmount, mAmbientState.getStackY());
    }

    @Test
    @DisableSceneContainer // NSSL has no more scroll logic when SceneContainer is on
    public void testNormalShade_hasNoTopOverscroll() {
        mTestableResources
                .addOverride(R.bool.config_use_split_notification_shade, /* value= */ false);
        mStackScroller.passSplitShadeStateController(new ResourcesSplitShadeStateController());
        mStackScroller.updateSplitNotificationShade();
        mAmbientState.setExpansionFraction(1f);

        int topOverscrollPixels = 100;
        mStackScroller.setOverScrolledPixels(topOverscrollPixels, true, false);

        float expectedTopOverscrollAmount = topOverscrollPixels * RUBBER_BAND_FACTOR_NORMAL;
        assertEquals(expectedTopOverscrollAmount, mStackScroller.getCurrentOverScrollAmount(true));
        // When not in split shade mode, then the overscroll effect is handled in
        // NotificationPanelViewController and not in NotificationStackScrollLayout. Therefore
        // mAmbientState must have stackY set to 0
        assertEquals(0f, mAmbientState.getStackY());
    }

    @Test
    @DisableFlags({FooterViewRefactor.FLAG_NAME, NotifRedesignFooter.FLAG_NAME})
    public void hasFilteredOutSeenNotifs_updateFooter() {
        mStackScroller.setCurrentUserSetup(true);

        // add footer
        mStackScroller.inflateFooterView();
        TextView footerLabel =
                mStackScroller.mFooterView.requireViewById(R.id.unlock_prompt_footer);

        mStackScroller.setHasFilteredOutSeenNotifications(true);
        mStackScroller.updateFooter();

        assertThat(footerLabel.getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    @DisableFlags({FooterViewRefactor.FLAG_NAME, ModesEmptyShadeFix.FLAG_NAME})
    public void hasFilteredOutSeenNotifs_updateEmptyShadeView() {
        mStackScroller.setHasFilteredOutSeenNotifications(true);
        mStackScroller.updateEmptyShadeView(true, false);

        verify(mEmptyShadeView).setFooterText(not(eq(0)));
    }

    @Test
    public void testWindowInsetAnimationProgress_updatesBottomInset() {
        int imeInset = 100;
        WindowInsets windowInsets = new WindowInsets.Builder()
                .setInsets(ime(), Insets.of(0, 0, 0, imeInset)).build();
        ArrayList<WindowInsetsAnimation> windowInsetsAnimations = new ArrayList<>();
        mStackScrollerInternal
                .dispatchWindowInsetsAnimationProgress(windowInsets, windowInsetsAnimations);

        assertEquals(imeInset, mStackScrollerInternal.mImeInset);
    }

    @Test
    @EnableSceneContainer
    public void testSetMaxDisplayedNotifications_updatesStackHeight() {
        int fullStackHeight = 300;
        int limitedStackHeight = 100;
        int maxNotifs = 2; // any non-zero limit
        float stackTop = 100;
        float stackCutoff = 1100;
        float stackViewPortHeight = stackCutoff - stackTop;
        mStackScroller.setStackTop(stackTop);
        mStackScroller.setStackCutoff(stackCutoff);
        when(mStackSizeCalculator.computeHeight(eq(mStackScroller), eq(-1), anyFloat()))
                .thenReturn((float) fullStackHeight);
        when(mStackSizeCalculator.computeHeight(eq(mStackScroller), eq(maxNotifs), anyFloat()))
                .thenReturn((float) limitedStackHeight);

        // When we set a limit on max displayed notifications
        mStackScroller.setMaxDisplayedNotifications(maxNotifs);

        // Then
        assertThat(mStackScroller.getIntrinsicStackHeight()).isEqualTo(limitedStackHeight);
        assertThat(mAmbientState.getStackEndHeight()).isEqualTo(limitedStackHeight);

        // When there is no limit on max displayed notifications
        mStackScroller.setMaxDisplayedNotifications(-1);

        // Then
        assertThat(mStackScroller.getIntrinsicStackHeight()).isEqualTo(fullStackHeight);
        assertThat(mAmbientState.getStackEndHeight()).isEqualTo(stackViewPortHeight);
    }

    @Test
    @DisableSceneContainer
    public void testSetMaxDisplayedNotifications_notifiesListeners() {
        ExpandableView.OnHeightChangedListener listener =
                mock(ExpandableView.OnHeightChangedListener.class);
        Runnable runnable = mock(Runnable.class);
        mStackScroller.setOnHeightChangedListener(listener);
        mStackScroller.setOnHeightChangedRunnable(runnable);

        mStackScroller.setMaxDisplayedNotifications(50);

        verify(listener).onHeightChanged(mNotificationShelf, false);
        verify(runnable).run();
    }

    @Test
    @DisableSceneContainer
    public void testDispatchTouchEvent_sceneContainerDisabled() {
        MotionEvent event = MotionEvent.obtain(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                0,
                0,
                0
        );

        mStackScroller.dispatchTouchEvent(event);

        verify(mStackScrollLayoutController, never()).sendTouchToSceneFramework(any());
    }

    @Test
    @EnableSceneContainer
    public void testDispatchTouchEvent_sceneContainerEnabled() {
        mStackScroller.setIsBeingDragged(true);

        long downTime = SystemClock.uptimeMillis() - 100;
        MotionEvent moveEvent1 = MotionEvent.obtain(
                /* downTime= */ downTime,
                /* eventTime= */ SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                101,
                201,
                0
        );
        MotionEvent syntheticDownEvent = moveEvent1.copy();
        syntheticDownEvent.setAction(MotionEvent.ACTION_DOWN);
        mStackScroller.dispatchTouchEvent(moveEvent1);

        assertThatMotionEvent(captureTouchSentToSceneFramework()).matches(syntheticDownEvent);
        assertTrue(mStackScroller.getIsBeingDragged());
        clearInvocations(mStackScrollLayoutController);

        MotionEvent moveEvent2 = MotionEvent.obtain(
                /* downTime= */ downTime,
                /* eventTime= */ SystemClock.uptimeMillis(),
                MotionEvent.ACTION_MOVE,
                102,
                202,
                0
        );

        mStackScroller.dispatchTouchEvent(moveEvent2);

        assertThatMotionEvent(captureTouchSentToSceneFramework()).matches(moveEvent2);
        assertTrue(mStackScroller.getIsBeingDragged());
        clearInvocations(mStackScrollLayoutController);

        MotionEvent upEvent = MotionEvent.obtain(
                /* downTime= */ downTime,
                /* eventTime= */ SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                103,
                203,
                0
        );

        mStackScroller.dispatchTouchEvent(upEvent);

        assertThatMotionEvent(captureTouchSentToSceneFramework()).matches(upEvent);
        assertFalse(mStackScroller.getIsBeingDragged());
    }

    @Test
    @EnableSceneContainer
    public void testDispatchTouchEvent_sceneContainerEnabled_ignoresInitialActionUp() {
        mStackScroller.setIsBeingDragged(true);

        MotionEvent upEvent = MotionEvent.obtain(
                SystemClock.uptimeMillis(),
                SystemClock.uptimeMillis(),
                MotionEvent.ACTION_UP,
                0,
                0,
                0
        );

        mStackScroller.dispatchTouchEvent(upEvent);
        verify(mStackScrollLayoutController, never()).sendTouchToSceneFramework(any());
        assertFalse(mStackScroller.getIsBeingDragged());
    }


    @Test
    @EnableSceneContainer
    public void testGenerateHeadsUpDisappearEvent_setsHeadsUpAnimatingAway() {
        // GIVEN NSSL is ready for HUN animations
        Consumer<Boolean> headsUpAnimatingAwayListener = mock(BooleanConsumer.class);
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        prepareStackScrollerForHunAnimations(headsUpAnimatingAwayListener);

        // WHEN we generate a disappear event
        mStackScroller.generateHeadsUpAnimation(row, /* isHeadsUp = */ false);

        // THEN headsUpAnimatingAway is true
        verify(headsUpAnimatingAwayListener).accept(true);
        assertTrue(mStackScroller.mHeadsUpAnimatingAway);
    }

    @Test
    @EnableSceneContainer
    public void testGenerateHeadsUpDisappearEvent_stackExpanded_headsUpAnimatingAwayNotSet() {
        // GIVEN NSSL would be ready for HUN animations, BUT it is expanded
        Consumer<Boolean> headsUpAnimatingAwayListener = mock(BooleanConsumer.class);
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        assertTrue("Should be expanded by default.", mStackScroller.isExpanded());
        mStackScroller.setHeadsUpAnimatingAwayListener(headsUpAnimatingAwayListener);
        mStackScroller.setAnimationsEnabled(true);
        mStackScroller.setHeadsUpGoingAwayAnimationsAllowed(true);

        // WHEN we generate a disappear event
        mStackScroller.generateHeadsUpAnimation(row, /* isHeadsUp = */ false);

        // THEN nothing happens
        verify(headsUpAnimatingAwayListener, never()).accept(anyBoolean());
        assertFalse(mStackScroller.mHeadsUpAnimatingAway);
    }

    @Test
    @EnableSceneContainer
    public void testGenerateHeadsUpDisappearEvent_pendingAppearEvent_headsUpAnimatingAwayNotSet() {
        // GIVEN NSSL is ready for HUN animations
        Consumer<Boolean> headsUpAnimatingAwayListener = mock(BooleanConsumer.class);
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        prepareStackScrollerForHunAnimations(headsUpAnimatingAwayListener);
        // BUT there is a pending appear event
        mStackScroller.generateHeadsUpAnimation(row, /* isHeadsUp = */ true);

        // WHEN we generate a disappear event
        mStackScroller.generateHeadsUpAnimation(row, /* isHeadsUp = */ false);

        // THEN nothing happens
        verify(headsUpAnimatingAwayListener, never()).accept(anyBoolean());
        assertFalse(mStackScroller.mHeadsUpAnimatingAway);
    }

    @Test
    @EnableSceneContainer
    public void testGenerateHeadsUpAppearEvent_headsUpAnimatingAwayNotSet() {
        // GIVEN NSSL is ready for HUN animations
        Consumer<Boolean> headsUpAnimatingAwayListener = mock(BooleanConsumer.class);
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        prepareStackScrollerForHunAnimations(headsUpAnimatingAwayListener);

        // WHEN we generate a disappear event
        mStackScroller.generateHeadsUpAnimation(row, /* isHeadsUp = */ true);

        // THEN headsUpAnimatingWay is not set
        verify(headsUpAnimatingAwayListener, never()).accept(anyBoolean());
        assertFalse(mStackScroller.mHeadsUpAnimatingAway);
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    public void testGenerateHeadsUpAnimation_isSeenInShade_noAnimation() {
        // GIVEN NSSL is ready for HUN animations
        Consumer<Boolean> headsUpAnimatingAwayListener = mock(BooleanConsumer.class);
        prepareStackScrollerForHunAnimations(headsUpAnimatingAwayListener);

        // Entry was seen in shade
        NotificationEntry entry = mock(NotificationEntry.class);
        when(entry.isSeenInShade()).thenReturn(true);
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        when(row.getEntry()).thenReturn(entry);

        // WHEN we generate an add event
        mStackScroller.generateHeadsUpAnimation(row, /* isHeadsUp = */ true);

        // THEN nothing happens
        assertThat(mStackScroller.isAddOrRemoveAnimationPending()).isFalse();
    }

    @Test
    @EnableSceneContainer
    public void testOnChildAnimationsFinished_resetsheadsUpAnimatingAway() {
        // GIVEN NSSL is ready for HUN animations
        Consumer<Boolean> headsUpAnimatingAwayListener = mock(BooleanConsumer.class);
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        prepareStackScrollerForHunAnimations(headsUpAnimatingAwayListener);

        // AND there is a HUN animating away
        mStackScroller.generateHeadsUpAnimation(row, /* isHeadsUp = */ false);
        assertTrue("a HUN should be animating away", mStackScroller.mHeadsUpAnimatingAway);

        // WHEN the child animations are finished
        mStackScroller.onChildAnimationFinished();

        // THEN headsUpAnimatingAway is false
        verify(headsUpAnimatingAwayListener).accept(false);
        assertFalse(mStackScroller.mHeadsUpAnimatingAway);
    }

    @Test
    @EnableSceneContainer
    public void finishExpanding_sceneContainerEnabled() {
        mStackScroller.startOverscrollAfterExpanding();
        verify(mStackScroller.getExpandHelper()).finishExpanding();
        assertTrue(mStackScroller.getIsBeingDragged());
    }

    private MotionEvent captureTouchSentToSceneFramework() {
        ArgumentCaptor<MotionEvent> captor = ArgumentCaptor.forClass(MotionEvent.class);
        verify(mStackScrollLayoutController).sendTouchToSceneFramework(captor.capture());
        return captor.getValue();
    }

    private void setBarStateForTest(int state) {
        // Can't inject this through the listener or we end up on the actual implementation
        // rather than the mock because the spy just coppied the anonymous inner /shruggie.
        mStackScroller.setStatusBarState(state);
    }

    private void prepareStackScrollerForHunAnimations(
            Consumer<Boolean> headsUpAnimatingAwayListener) {
        mStackScroller.setHeadsUpAnimatingAwayListener(headsUpAnimatingAwayListener);
        mStackScroller.setIsExpanded(false);
        mStackScroller.setAnimationsEnabled(true);
        mStackScroller.setHeadsUpGoingAwayAnimationsAllowed(true);
    }

    private ExpandableNotificationRow createClearableRow() {
        ExpandableNotificationRow row = mock(ExpandableNotificationRow.class);
        NotificationEntry entry = mock(NotificationEntry.class);
        when(row.canViewBeCleared()).thenReturn(true);
        when(row.getEntry()).thenReturn(entry);
        when(entry.isClearable()).thenReturn(true);

        return row;
    }

    private void assertClearAllInProgress(boolean expected) {
        assertEquals(expected, mStackScroller.getClearAllInProgress());
        assertEquals(expected, mAmbientState.isClearAllInProgress());
    }

    private int px(@DimenRes int id) {
        return mTestableResources.getResources().getDimensionPixelSize(id);
    }

    private static void mockBoundsOnScreen(View view, Rect bounds) {
        doAnswer(invocation -> {
            Rect out = invocation.getArgument(0);
            out.set(bounds);
            return null;
        }).when(view).getBoundsOnScreen(any());
    }

    private static MotionEvent transformEventForView(MotionEvent event, View view) {
        // From `ViewGroup#dispatchTransformedTouchEvent`
        MotionEvent transformed = event.copy();
        transformed.offsetLocation(/* deltaX = */-view.getLeft(), /* deltaY = */ -view.getTop());
        return transformed;
    }

    private static MotionEvent createMotionEvent(float x, float y) {
        return MotionEvent.obtain(
                /* downTime= */0,
                /* eventTime= */0,
                MotionEvent.ACTION_DOWN,
                x,
                y,
                /* metaState= */0
        );
    }

    private MotionEventSubject assertThatMotionEvent(MotionEvent actual) {
        return new MotionEventSubject(actual);
    }

    private static class MotionEventSubject {
        private final MotionEvent mActual;

        MotionEventSubject(MotionEvent actual) {
            mActual = actual;
        }

        public void matches(MotionEvent expected) {
            assertThat(mActual.getActionMasked()).isEqualTo(expected.getActionMasked());
            assertThat(mActual.getDownTime()).isEqualTo(expected.getDownTime());
            assertThat(mActual.getEventTime()).isEqualTo(expected.getEventTime());
            assertThat(mActual.getX()).isEqualTo(expected.getX());
            assertThat(mActual.getY()).isEqualTo(expected.getY());
        }
    }

    private abstract static class BooleanConsumer implements Consumer<Boolean> { }

    private ShadeScrimShape createScrimShape(int left, int top, int right, int bottom) {
        ShadeScrimBounds bounds = new ShadeScrimBounds(left, top, right, bottom);
        return new ShadeScrimShape(bounds, 0, 0);
    }
}
