/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.qs;

import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.annotation.Nullable;
import android.app.Fragment;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.lifecycle.Lifecycle;
import androidx.test.filters.SmallTest;

import com.android.keyguard.BouncerPanelExpansionCalculator;
import com.android.systemui.R;
import com.android.systemui.SysuiBaseFragmentTest;
import com.android.systemui.animation.ShadeInterpolation;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.FeatureFlags;
import com.android.systemui.flags.Flags;
import com.android.systemui.media.controls.ui.MediaHost;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.dagger.QSFragmentComponent;
import com.android.systemui.qs.external.TileServiceRequestController;
import com.android.systemui.qs.footer.ui.binder.FooterActionsViewBinder;
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.settings.FakeDisplayTracker;
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.util.animation.UniqueObjectHostView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
public class QSFragmentTest extends SysuiBaseFragmentTest {

    @Mock private QSFragmentComponent.Factory mQsComponentFactory;
    @Mock private QSFragmentComponent mQsFragmentComponent;
    @Mock private QSPanelController mQSPanelController;
    @Mock private MediaHost mQSMediaHost;
    @Mock private MediaHost mQQSMediaHost;
    @Mock private KeyguardBypassController mBypassController;
    @Mock private TileServiceRequestController.Builder mTileServiceRequestControllerBuilder;
    @Mock private TileServiceRequestController mTileServiceRequestController;
    @Mock private QSCustomizerController mQsCustomizerController;
    @Mock private QuickQSPanelController mQuickQSPanelController;
    @Mock private FooterActionsController mQSFooterActionController;
    @Mock private QSContainerImplController mQSContainerImplController;
    @Mock private QSContainerImpl mContainer;
    @Mock private QSFooter mFooter;
    @Mock private LayoutInflater mLayoutInflater;
    @Mock private NonInterceptingScrollView mQSPanelScrollView;
    @Mock private QuickStatusBarHeader mHeader;
    @Mock private QSPanel.QSTileLayout mQsTileLayout;
    @Mock private QSPanel.QSTileLayout mQQsTileLayout;
    @Mock private QSAnimator mQSAnimator;
    @Mock private SysuiStatusBarStateController mStatusBarStateController;
    @Mock private QSSquishinessController mSquishinessController;
    @Mock private FooterActionsViewModel mFooterActionsViewModel;
    @Mock private FooterActionsViewModel.Factory mFooterActionsViewModelFactory;
    @Mock private LargeScreenShadeInterpolator mLargeScreenShadeInterpolator;
    @Mock private FeatureFlags mFeatureFlags;
    private View mQsFragmentView;

    public QSFragmentTest() {
        super(QSFragment.class);
    }

    @Before
    public void setup() {
        injectLeakCheckedDependencies(ALL_SUPPORTED_CLASSES);
    }

    @Test
    public void testListening() {
        QSFragment qs = (QSFragment) mFragment;
        mFragments.dispatchResume();
        processAllMessages();

        qs.setListening(true);
        processAllMessages();

        qs.setListening(false);
        processAllMessages();
    }

    @Test
    public void testSaveState() {
        mFragments.dispatchResume();
        processAllMessages();

        QSFragment qs = (QSFragment) mFragment;
        qs.setListening(true);
        qs.setExpanded(true);
        qs.setQsVisible(true);
        processAllMessages();
        recreateFragment();
        processAllMessages();

        // Get the reference to the new fragment.
        qs = (QSFragment) mFragment;
        assertTrue(qs.isListening());
        assertTrue(qs.isExpanded());
        assertTrue(qs.isQsVisible());
    }

    @Test
    public void transitionToFullShade_smallScreen_alphaAlways1() {
        QSFragment fragment = resumeAndGetFragment();
        setIsSmallScreen();
        setStatusBarCurrentAndUpcomingState(StatusBarState.SHADE);
        boolean isTransitioningToFullShade = true;
        float transitionProgress = 0.5f;
        float squishinessFraction = 0.5f;

        fragment.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);

        assertThat(mQsFragmentView.getAlpha()).isEqualTo(1f);
    }

    @Test
    public void transitionToFullShade_largeScreen_flagEnabled_alphaLargeScreenShadeInterpolator() {
        when(mFeatureFlags.isEnabled(Flags.LARGE_SHADE_GRANULAR_ALPHA_INTERPOLATION))
                .thenReturn(true);
        QSFragment fragment = resumeAndGetFragment();
        setIsLargeScreen();
        setStatusBarCurrentAndUpcomingState(StatusBarState.SHADE);
        boolean isTransitioningToFullShade = true;
        float transitionProgress = 0.5f;
        float squishinessFraction = 0.5f;
        when(mLargeScreenShadeInterpolator.getQsAlpha(transitionProgress)).thenReturn(123f);

        fragment.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);

        assertThat(mQsFragmentView.getAlpha())
                .isEqualTo(123f);
    }

    @Test
    public void transitionToFullShade_largeScreen_flagDisabled_alphaStandardInterpolator() {
        when(mFeatureFlags.isEnabled(Flags.LARGE_SHADE_GRANULAR_ALPHA_INTERPOLATION))
                .thenReturn(false);
        QSFragment fragment = resumeAndGetFragment();
        setIsLargeScreen();
        setStatusBarCurrentAndUpcomingState(StatusBarState.SHADE);
        boolean isTransitioningToFullShade = true;
        float transitionProgress = 0.5f;
        float squishinessFraction = 0.5f;
        when(mLargeScreenShadeInterpolator.getQsAlpha(transitionProgress)).thenReturn(123f);

        fragment.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);

        assertThat(mQsFragmentView.getAlpha())
                .isEqualTo(ShadeInterpolation.getContentAlpha(transitionProgress));
    }

    @Test
    public void
            transitionToFullShade_onKeyguard_noBouncer_setsAlphaUsingLinearInterpolator() {
        QSFragment fragment = resumeAndGetFragment();
        setStatusBarCurrentAndUpcomingState(KEYGUARD);
        when(mQSPanelController.isBouncerInTransit()).thenReturn(false);
        boolean isTransitioningToFullShade = true;
        float transitionProgress = 0.5f;
        float squishinessFraction = 0.5f;

        fragment.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);

        assertThat(mQsFragmentView.getAlpha()).isEqualTo(transitionProgress);
    }

    @Test
    public void
            transitionToFullShade_onKeyguard_bouncerActive_setsAlphaUsingBouncerInterpolator() {
        QSFragment fragment = resumeAndGetFragment();
        setStatusBarCurrentAndUpcomingState(KEYGUARD);
        when(mQSPanelController.isBouncerInTransit()).thenReturn(true);
        boolean isTransitioningToFullShade = true;
        float transitionProgress = 0.5f;
        float squishinessFraction = 0.5f;

        fragment.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);

        assertThat(mQsFragmentView.getAlpha())
                .isEqualTo(
                        BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(
                                transitionProgress));
    }

    @Test
    public void transitionToFullShade_inFullWidth_alwaysSetsAlphaTo1() {
        QSFragment fragment = resumeAndGetFragment();
        fragment.setIsNotificationPanelFullWidth(true);

        boolean isTransitioningToFullShade = true;
        float transitionProgress = 0.1f;
        float squishinessFraction = 0.5f;
        fragment.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);
        assertThat(mQsFragmentView.getAlpha()).isEqualTo(1);

        transitionProgress = 0.5f;
        fragment.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);
        assertThat(mQsFragmentView.getAlpha()).isEqualTo(1);
        assertThat(mQsFragmentView.getAlpha()).isEqualTo(1);

        transitionProgress = 0.7f;
        fragment.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);
        assertThat(mQsFragmentView.getAlpha()).isEqualTo(1);
    }

    @Test
    public void transitionToFullShade_setsSquishinessOnController() {
        QSFragment fragment = resumeAndGetFragment();
        boolean isTransitioningToFullShade = true;
        float transitionProgress = 0.123f;
        float squishinessFraction = 0.456f;

        fragment.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);

        verify(mQsFragmentComponent.getQSSquishinessController())
                .setSquishiness(squishinessFraction);
    }

    @Test
    public void setQsExpansion_inSplitShade_setsFooterActionsExpansion_basedOnPanelExpFraction() {
        // Random test values without any meaning. They just have to be different from each other.
        float expansion = 0.123f;
        float panelExpansionFraction = 0.321f;
        float proposedTranslation = 456f;
        float squishinessFraction = 0.987f;

        QSFragment fragment = resumeAndGetFragment();
        enableSplitShade();

        fragment.setQsExpansion(expansion, panelExpansionFraction, proposedTranslation,
                squishinessFraction);

        verify(mFooterActionsViewModel).onQuickSettingsExpansionChanged(
                panelExpansionFraction, /* isInSplitShade= */ true);
    }

    @Test
    public void setQsExpansion_notInSplitShade_setsFooterActionsExpansion_basedOnExpansion() {
        // Random test values without any meaning. They just have to be different from each other.
        float expansion = 0.123f;
        float panelExpansionFraction = 0.321f;
        float proposedTranslation = 456f;
        float squishinessFraction = 0.987f;

        QSFragment fragment = resumeAndGetFragment();
        disableSplitShade();

        fragment.setQsExpansion(expansion, panelExpansionFraction, proposedTranslation,
                squishinessFraction);

        verify(mFooterActionsViewModel).onQuickSettingsExpansionChanged(
                expansion, /* isInSplitShade= */ false);
    }

    @Test
    public void setQsExpansion_inSplitShade_whenTransitioningToKeyguard_setsAlphaBasedOnShadeTransitionProgress() {
        QSFragment fragment = resumeAndGetFragment();
        enableSplitShade();
        when(mStatusBarStateController.getState()).thenReturn(SHADE);
        when(mStatusBarStateController.getCurrentOrUpcomingState()).thenReturn(KEYGUARD);
        boolean isTransitioningToFullShade = false;
        float transitionProgress = 0;
        float squishinessFraction = 0f;

        fragment.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);

        // trigger alpha refresh with non-zero expansion and fraction values
        fragment.setQsExpansion(/* expansion= */ 1, /* panelExpansionFraction= */1,
                /* proposedTranslation= */ 0, /* squishinessFraction= */ 1);

        // alpha should follow lockscreen to shade progress, not panel expansion fraction
        assertThat(mQsFragmentView.getAlpha()).isEqualTo(transitionProgress);
    }

    @Test
    public void getQsMinExpansionHeight_notInSplitShade_returnsHeaderHeight() {
        QSFragment fragment = resumeAndGetFragment();
        disableSplitShade();
        when(mHeader.getHeight()).thenReturn(1234);

        int height = fragment.getQsMinExpansionHeight();

        assertThat(height).isEqualTo(mHeader.getHeight());
    }

    @Test
    public void getQsMinExpansionHeight_inSplitShade_returnsAbsoluteBottomOfQSContainer() {
        int top = 1234;
        int height = 9876;
        QSFragment fragment = resumeAndGetFragment();
        enableSplitShade();
        setLocationOnScreen(mQsFragmentView, top);
        when(mQsFragmentView.getHeight()).thenReturn(height);

        int expectedHeight = top + height;
        assertThat(fragment.getQsMinExpansionHeight()).isEqualTo(expectedHeight);
    }

    @Test
    public void getQsMinExpansionHeight_inSplitShade_returnsAbsoluteBottomExcludingTranslation() {
        int top = 1234;
        int height = 9876;
        float translationY = -600f;
        QSFragment fragment = resumeAndGetFragment();
        enableSplitShade();
        setLocationOnScreen(mQsFragmentView, (int) (top + translationY));
        when(mQsFragmentView.getHeight()).thenReturn(height);
        when(mQsFragmentView.getTranslationY()).thenReturn(translationY);

        int expectedHeight = top + height;
        assertThat(fragment.getQsMinExpansionHeight()).isEqualTo(expectedHeight);
    }

    @Test
    public void hideImmediately_notInSplitShade_movesViewUpByHeaderHeight() {
        QSFragment fragment = resumeAndGetFragment();
        disableSplitShade();
        when(mHeader.getHeight()).thenReturn(555);

        fragment.hideImmediately();

        assertThat(mQsFragmentView.getY()).isEqualTo(-mHeader.getHeight());
    }

    @Test
    public void hideImmediately_inSplitShade_movesViewUpByQSAbsoluteBottom() {
        QSFragment fragment = resumeAndGetFragment();
        enableSplitShade();
        int top = 1234;
        int height = 9876;
        setLocationOnScreen(mQsFragmentView, top);
        when(mQsFragmentView.getHeight()).thenReturn(height);

        fragment.hideImmediately();

        int qsAbsoluteBottom = top + height;
        assertThat(mQsFragmentView.getY()).isEqualTo(-qsAbsoluteBottom);
    }

    @Test
    public void setCollapseExpandAction_passedToControllers() {
        Runnable action = () -> {};
        QSFragment fragment = resumeAndGetFragment();
        fragment.setCollapseExpandAction(action);

        verify(mQSPanelController).setCollapseExpandAction(action);
        verify(mQuickQSPanelController).setCollapseExpandAction(action);
    }

    @Test
    public void setOverScrollAmount_setsTranslationOnView() {
        QSFragment fragment = resumeAndGetFragment();

        fragment.setOverScrollAmount(123);

        assertThat(mQsFragmentView.getTranslationY()).isEqualTo(123);
    }

    @Test
    public void setOverScrollAmount_beforeViewCreated_translationIsNotSet() {
        QSFragment fragment = getFragment();

        fragment.setOverScrollAmount(123);

        assertThat(mQsFragmentView.getTranslationY()).isEqualTo(0);
    }

    private Lifecycle.State getListeningAndVisibilityLifecycleState() {
        return getFragment()
                .getListeningAndVisibilityLifecycleOwner()
                .getLifecycle()
                .getCurrentState();
    }

    @Test
    public void setListeningFalse_notVisible() {
        QSFragment fragment = resumeAndGetFragment();
        fragment.setQsVisible(false);
        clearInvocations(mQSContainerImplController, mQSPanelController, mQSFooterActionController);

        fragment.setListening(false);
        verify(mQSContainerImplController).setListening(false);
        assertThat(getListeningAndVisibilityLifecycleState()).isEqualTo(Lifecycle.State.CREATED);
        verify(mQSPanelController).setListening(eq(false), anyBoolean());
    }

    @Test
    public void setListeningTrue_notVisible() {
        QSFragment fragment = resumeAndGetFragment();
        fragment.setQsVisible(false);
        clearInvocations(mQSContainerImplController, mQSPanelController, mQSFooterActionController);

        fragment.setListening(true);
        verify(mQSContainerImplController).setListening(false);
        assertThat(getListeningAndVisibilityLifecycleState()).isEqualTo(Lifecycle.State.STARTED);
        verify(mQSPanelController).setListening(eq(false), anyBoolean());
    }

    @Test
    public void setListeningFalse_visible() {
        QSFragment fragment = resumeAndGetFragment();
        fragment.setQsVisible(true);
        clearInvocations(mQSContainerImplController, mQSPanelController, mQSFooterActionController);

        fragment.setListening(false);
        verify(mQSContainerImplController).setListening(false);
        assertThat(getListeningAndVisibilityLifecycleState()).isEqualTo(Lifecycle.State.CREATED);
        verify(mQSPanelController).setListening(eq(false), anyBoolean());
    }

    @Test
    public void setListeningTrue_visible() {
        QSFragment fragment = resumeAndGetFragment();
        fragment.setQsVisible(true);
        clearInvocations(mQSContainerImplController, mQSPanelController, mQSFooterActionController);

        fragment.setListening(true);
        verify(mQSContainerImplController).setListening(true);
        assertThat(getListeningAndVisibilityLifecycleState()).isEqualTo(Lifecycle.State.RESUMED);
        verify(mQSPanelController).setListening(eq(true), anyBoolean());
    }

    @Test
    public void passCorrectExpansionState_inSplitShade() {
        QSFragment fragment = resumeAndGetFragment();
        enableSplitShade();
        clearInvocations(mQSPanelController);

        fragment.setExpanded(true);
        verify(mQSPanelController).setExpanded(true);

        fragment.setExpanded(false);
        verify(mQSPanelController).setExpanded(false);
    }

    @Test
    public void startsListeningAfterStateChangeToExpanded_inSplitShade() {
        QSFragment fragment = resumeAndGetFragment();
        enableSplitShade();
        fragment.setQsVisible(true);
        clearInvocations(mQSPanelController);

        fragment.setExpanded(true);
        verify(mQSPanelController).setListening(true, true);
    }

    @Test
    public void testUpdateQSBounds_setMediaClipCorrectly() {
        QSFragment fragment = resumeAndGetFragment();
        disableSplitShade();

        Rect mediaHostClip = new Rect();
        when(mQSPanelController.getPaddingBottom()).thenReturn(50);
        setLocationOnScreen(mQSPanelScrollView, 25);
        when(mQSPanelScrollView.getMeasuredHeight()).thenReturn(200);
        when(mQSMediaHost.getCurrentClipping()).thenReturn(mediaHostClip);

        fragment.updateQsBounds();

        assertEquals(25, mediaHostClip.top);
        assertEquals(175, mediaHostClip.bottom);
    }

    @Test
    public void testQsUpdatesQsAnimatorWithUpcomingState() {
        QSFragment fragment = resumeAndGetFragment();
        setStatusBarCurrentAndUpcomingState(SHADE);
        fragment.onUpcomingStateChanged(KEYGUARD);

        verify(mQSAnimator).setOnKeyguard(true);
    }

    @Override
    protected Fragment instantiate(Context context, String className, Bundle arguments) {
        MockitoAnnotations.initMocks(this);
        CommandQueue commandQueue = new CommandQueue(context, new FakeDisplayTracker(context));

        setupQsComponent();
        setUpViews();
        setUpInflater();
        setUpMedia();
        setUpOther();

        return new QSFragment(
                new RemoteInputQuickSettingsDisabler(
                        context, commandQueue, mock(ConfigurationController.class)),
                mStatusBarStateController,
                commandQueue,
                mQSMediaHost,
                mQQSMediaHost,
                mBypassController,
                mQsComponentFactory,
                mock(QSFragmentDisableFlagsLogger.class),
                mock(DumpManager.class),
                mock(QSLogger.class),
                mock(FooterActionsController.class),
                mFooterActionsViewModelFactory,
                mLargeScreenShadeInterpolator,
                mFeatureFlags);
    }

    private void setUpOther() {
        when(mTileServiceRequestControllerBuilder.create(any()))
                .thenReturn(mTileServiceRequestController);
        when(mQSContainerImplController.getView()).thenReturn(mContainer);
        when(mQSPanelController.getTileLayout()).thenReturn(mQQsTileLayout);
        when(mQuickQSPanelController.getTileLayout()).thenReturn(mQsTileLayout);
        when(mFooterActionsViewModelFactory.create(any())).thenReturn(mFooterActionsViewModel);
    }

    private void setUpMedia() {
        when(mQSMediaHost.getCurrentClipping()).thenReturn(new Rect());
        when(mQSMediaHost.getHostView()).thenReturn(new UniqueObjectHostView(mContext));
        when(mQQSMediaHost.getHostView()).thenReturn(new UniqueObjectHostView(mContext));
    }

    private void setUpViews() {
        mQsFragmentView = spy(new View(mContext));
        when(mQsFragmentView.findViewById(R.id.expanded_qs_scroll_view))
                .thenReturn(mQSPanelScrollView);
        when(mQsFragmentView.findViewById(R.id.header)).thenReturn(mHeader);
        when(mQsFragmentView.findViewById(android.R.id.edit)).thenReturn(new View(mContext));
        when(mQsFragmentView.findViewById(R.id.qs_footer_actions)).thenAnswer(
                invocation -> FooterActionsViewBinder.create(mContext));
    }

    private void setUpInflater() {
        LayoutInflater realInflater = LayoutInflater.from(mContext);

        when(mLayoutInflater.cloneInContext(any(Context.class))).thenReturn(mLayoutInflater);
        when(mLayoutInflater.inflate(anyInt(), nullable(ViewGroup.class), anyBoolean()))
                .thenAnswer((invocation) -> inflate(realInflater, (int) invocation.getArgument(0),
                        (ViewGroup) invocation.getArgument(1),
                        (boolean) invocation.getArgument(2)));
        when(mLayoutInflater.inflate(anyInt(), nullable(ViewGroup.class)))
                .thenAnswer((invocation) -> inflate(realInflater, (int) invocation.getArgument(0),
                        (ViewGroup) invocation.getArgument(1)));
        mContext.addMockSystemService(Context.LAYOUT_INFLATER_SERVICE, mLayoutInflater);
    }

    private View inflate(LayoutInflater realInflater, int layoutRes, @Nullable ViewGroup root) {
        return inflate(realInflater, layoutRes, root, root != null);
    }

    private View inflate(LayoutInflater realInflater, int layoutRes, @Nullable ViewGroup root,
            boolean attachToRoot) {
        if (layoutRes == R.layout.footer_actions
                || layoutRes == R.layout.footer_actions_text_button
                || layoutRes == R.layout.footer_actions_number_button
                || layoutRes == R.layout.footer_actions_icon_button) {
            return realInflater.inflate(layoutRes, root, attachToRoot);
        }

        return mQsFragmentView;
    }

    private void setupQsComponent() {
        when(mQsComponentFactory.create(any(QSFragment.class))).thenReturn(mQsFragmentComponent);
        when(mQsFragmentComponent.getQSPanelController()).thenReturn(mQSPanelController);
        when(mQsFragmentComponent.getQuickQSPanelController()).thenReturn(mQuickQSPanelController);
        when(mQsFragmentComponent.getQSCustomizerController()).thenReturn(mQsCustomizerController);
        when(mQsFragmentComponent.getQSContainerImplController())
                .thenReturn(mQSContainerImplController);
        when(mQsFragmentComponent.getQSFooter()).thenReturn(mFooter);
        when(mQsFragmentComponent.getQSFooterActionController())
                .thenReturn(mQSFooterActionController);
        when(mQsFragmentComponent.getQSAnimator()).thenReturn(mQSAnimator);
        when(mQsFragmentComponent.getQSSquishinessController()).thenReturn(mSquishinessController);
    }

    private QSFragment getFragment() {
        return ((QSFragment) mFragment);
    }

    private QSFragment resumeAndGetFragment() {
        mFragments.dispatchResume();
        processAllMessages();
        return getFragment();
    }

    private void setStatusBarCurrentAndUpcomingState(int statusBarState) {
        when(mStatusBarStateController.getState()).thenReturn(statusBarState);
        when(mStatusBarStateController.getCurrentOrUpcomingState()).thenReturn(statusBarState);
        getFragment().onStateChanged(statusBarState);
    }

    private void enableSplitShade() {
        setSplitShadeEnabled(true);
    }

    private void disableSplitShade() {
        setSplitShadeEnabled(false);
    }

    private void setSplitShadeEnabled(boolean enabled) {
        getFragment().setInSplitShade(enabled);
    }

    private void setLocationOnScreen(View view, int top) {
        doAnswer(invocation -> {
            int[] locationOnScreen = invocation.getArgument(/* index= */ 0);
            locationOnScreen[0] = 0;
            locationOnScreen[1] = top;
            return null;
        }).when(view).getLocationOnScreen(any(int[].class));
    }

    private void setIsLargeScreen() {
        getFragment().setIsNotificationPanelFullWidth(false);
    }

    private void setIsSmallScreen() {
        getFragment().setIsNotificationPanelFullWidth(true);
    }
}
