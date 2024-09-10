/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.qs;

import static android.app.StatusBarManager.DISABLE2_QUICK_SETTINGS;

import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.StatusBarState.SHADE;
import static com.android.systemui.statusbar.StatusBarState.SHADE_LOCKED;

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
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.testing.TestableLooper.RunWithLooper;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.compose.ui.platform.ComposeView;
import androidx.lifecycle.Lifecycle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.keyguard.BouncerPanelExpansionCalculator;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.flags.EnableSceneContainer;
import com.android.systemui.media.controls.ui.view.MediaHost;
import com.android.systemui.qs.customize.QSCustomizerController;
import com.android.systemui.qs.dagger.QSComponent;
import com.android.systemui.qs.external.TileServiceRequestController;
import com.android.systemui.qs.footer.ui.viewmodel.FooterActionsViewModel;
import com.android.systemui.qs.logging.QSLogger;
import com.android.systemui.res.R;
import com.android.systemui.settings.FakeDisplayTracker;
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.RemoteInputQuickSettingsDisabler;
import com.android.systemui.statusbar.policy.ResourcesSplitShadeStateController;
import com.android.systemui.util.animation.UniqueObjectHostView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
public class QSImplTest extends SysuiTestCase {

    @Mock private QSComponent mQsComponent;
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
    private ViewGroup mQsView;

    private final CommandQueue mCommandQueue =
            new CommandQueue(mContext, new FakeDisplayTracker(mContext));

    private QSImpl mUnderTest;


    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mUnderTest = instantiate();

        mUnderTest.onComponentCreated(mQsComponent, null);
    }

    /*
     * Regression test for b/303180152.
     */
    @Test
    public void testDisableCallbackOnDisabledQuickSettingsUponCreationDoesntCrash() {
        QSImpl other = instantiate();
        mCommandQueue.disable(Display.DEFAULT_DISPLAY, 0, DISABLE2_QUICK_SETTINGS);

        other.onComponentCreated(mQsComponent, null);
    }

    @Test
    public void testSaveState() {
        mUnderTest.setListening(true);
        mUnderTest.setExpanded(true);
        mUnderTest.setQsVisible(true);

        Bundle bundle = new Bundle();
        mUnderTest.onSaveInstanceState(bundle);

        // Get a new instance
        QSImpl other = instantiate();
        other.onComponentCreated(mQsComponent, bundle);

        assertTrue(other.isListening());
        assertTrue(other.isExpanded());
        assertTrue(other.isQsVisible());
    }

    @Test
    public void transitionToFullShade_smallScreen_alphaAlways1() {
        setIsSmallScreen();
        setStatusBarCurrentAndUpcomingState(StatusBarState.SHADE);
        boolean isTransitioningToFullShade = true;
        float transitionProgress = 0.5f;
        float squishinessFraction = 0.5f;

        mUnderTest.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);

        assertThat(mQsView.getAlpha()).isEqualTo(1f);
    }

    @Test
    public void transitionToFullShade_largeScreen_alphaLargeScreenShadeInterpolator() {
        setIsLargeScreen();
        setStatusBarCurrentAndUpcomingState(StatusBarState.SHADE);
        boolean isTransitioningToFullShade = true;
        float transitionProgress = 0.5f;
        float squishinessFraction = 0.5f;
        when(mLargeScreenShadeInterpolator.getQsAlpha(transitionProgress)).thenReturn(123f);

        mUnderTest.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);

        assertThat(mQsView.getAlpha()).isEqualTo(123f);
    }

    @Test
    public void
            transitionToFullShade_onKeyguard_noBouncer_setsAlphaUsingLinearInterpolator() {
        setStatusBarCurrentAndUpcomingState(KEYGUARD);
        when(mQSPanelController.isBouncerInTransit()).thenReturn(false);
        boolean isTransitioningToFullShade = true;
        float transitionProgress = 0.5f;
        float squishinessFraction = 0.5f;

        mUnderTest.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);

        assertThat(mQsView.getAlpha()).isEqualTo(transitionProgress);
    }

    @Test
    public void
            transitionToFullShade_onKeyguard_bouncerActive_setsAlphaUsingBouncerInterpolator() {
        setStatusBarCurrentAndUpcomingState(KEYGUARD);
        when(mQSPanelController.isBouncerInTransit()).thenReturn(true);
        boolean isTransitioningToFullShade = true;
        float transitionProgress = 0.5f;
        float squishinessFraction = 0.5f;

        mUnderTest.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);

        assertThat(mQsView.getAlpha())
                .isEqualTo(
                        BouncerPanelExpansionCalculator.aboutToShowBouncerProgress(
                                transitionProgress));
    }

    @Test
    public void transitionToFullShade_inFullWidth_alwaysSetsAlphaTo1() {
        mUnderTest.setIsNotificationPanelFullWidth(true);

        boolean isTransitioningToFullShade = true;
        float transitionProgress = 0.1f;
        float squishinessFraction = 0.5f;
        mUnderTest.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);
        assertThat(mQsView.getAlpha()).isEqualTo(1);

        transitionProgress = 0.5f;
        mUnderTest.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);
        assertThat(mQsView.getAlpha()).isEqualTo(1);
        assertThat(mQsView.getAlpha()).isEqualTo(1);

        transitionProgress = 0.7f;
        mUnderTest.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);
        assertThat(mQsView.getAlpha()).isEqualTo(1);
    }

    @Test
    public void transitionToFullShade_setsSquishinessOnController() {
        boolean isTransitioningToFullShade = true;
        float transitionProgress = 0.123f;
        float squishinessFraction = 0.456f;

        mUnderTest.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);

        verify(mQsComponent.getQSSquishinessController()).setSquishiness(squishinessFraction);
    }

    @Test
    public void setQsExpansion_whenShouldUpdateSquishinessTrue_setsSquishinessBasedOnFraction() {
        enableSplitShade();
        when(mStatusBarStateController.getState()).thenReturn(KEYGUARD);
        float expansion = 0.456f;
        float panelExpansionFraction = 0.678f;
        float proposedTranslation = 567f;
        float squishinessFraction = 0.789f;

        mUnderTest.setShouldUpdateSquishinessOnMedia(true);
        mUnderTest.setQsExpansion(expansion, panelExpansionFraction, proposedTranslation,
                squishinessFraction);

        verify(mQSMediaHost).setSquishFraction(squishinessFraction);
    }

    @Test
    public void setQsExpansion_whenOnKeyguardAndShouldUpdateSquishinessFalse_setsSquishiness() {
        // Random test values without any meaning. They just have to be different from each other.
        float expansion = 0.123f;
        float panelExpansionFraction = 0.321f;
        float proposedTranslation = 456f;
        float squishinessFraction = 0.567f;

        enableSplitShade();
        setStatusBarCurrentAndUpcomingState(KEYGUARD);
        mUnderTest.setShouldUpdateSquishinessOnMedia(false);
        mUnderTest.setQsExpansion(expansion, panelExpansionFraction, proposedTranslation,
                squishinessFraction);

        verify(mQSMediaHost).setSquishFraction(1.0f);
    }

    @Test
    public void setQsExpansion_inSplitShade_setsFooterActionsExpansion_basedOnPanelExpFraction() {
        // Random test values without any meaning. They just have to be different from each other.
        float expansion = 0.123f;
        float panelExpansionFraction = 0.321f;
        float proposedTranslation = 456f;
        float squishinessFraction = 0.987f;

        enableSplitShade();

        mUnderTest.setQsExpansion(expansion, panelExpansionFraction, proposedTranslation,
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

        disableSplitShade();

        mUnderTest.setQsExpansion(expansion, panelExpansionFraction, proposedTranslation,
                squishinessFraction);

        verify(mFooterActionsViewModel).onQuickSettingsExpansionChanged(
                expansion, /* isInSplitShade= */ false);
    }

    @Test
    public void setQsExpansion_inSplitShade_whenTransitioningToKeyguard_setsAlphaBasedOnShadeTransitionProgress() {
        enableSplitShade();
        when(mStatusBarStateController.getState()).thenReturn(SHADE);
        when(mStatusBarStateController.getCurrentOrUpcomingState()).thenReturn(KEYGUARD);
        boolean isTransitioningToFullShade = false;
        float transitionProgress = 0;
        float squishinessFraction = 0f;

        mUnderTest.setTransitionToFullShadeProgress(isTransitioningToFullShade, transitionProgress,
                squishinessFraction);

        // trigger alpha refresh with non-zero expansion and fraction values
        mUnderTest.setQsExpansion(/* expansion= */ 1, /* panelExpansionFraction= */1,
                /* proposedTranslation= */ 0, /* squishinessFraction= */ 1);

        // alpha should follow lockscreen to shade progress, not panel expansion fraction
        assertThat(mQsView.getAlpha()).isEqualTo(transitionProgress);
    }

    @Test
    public void getQsMinExpansionHeight_notInSplitShade_returnsHeaderHeight() {
        disableSplitShade();
        when(mHeader.getHeight()).thenReturn(1234);

        int height = mUnderTest.getQsMinExpansionHeight();

        assertThat(height).isEqualTo(mHeader.getHeight());
    }

    @Test
    public void getQsMinExpansionHeight_inSplitShade_returnsAbsoluteBottomOfQSContainer() {
        int top = 1234;
        int height = 9876;
        enableSplitShade();
        setLocationOnScreen(mQsView, top);
        when(mQsView.getHeight()).thenReturn(height);

        int expectedHeight = top + height;
        assertThat(mUnderTest.getQsMinExpansionHeight()).isEqualTo(expectedHeight);
    }

    @Test
    public void getQsMinExpansionHeight_inSplitShade_returnsAbsoluteBottomExcludingTranslation() {
        int top = 1234;
        int height = 9876;
        float translationY = -600f;
        enableSplitShade();
        setLocationOnScreen(mQsView, (int) (top + translationY));
        when(mQsView.getHeight()).thenReturn(height);
        when(mQsView.getTranslationY()).thenReturn(translationY);

        int expectedHeight = top + height;
        assertThat(mUnderTest.getQsMinExpansionHeight()).isEqualTo(expectedHeight);
    }

    @Test
    public void hideImmediately_notInSplitShade_movesViewUpByHeaderHeight() {
        disableSplitShade();
        when(mHeader.getHeight()).thenReturn(555);

        mUnderTest.hideImmediately();

        assertThat(mQsView.getY()).isEqualTo(-mHeader.getHeight());
    }

    @Test
    public void hideImmediately_inSplitShade_movesViewUpByQSAbsoluteBottom() {
        enableSplitShade();
        int top = 1234;
        int height = 9876;
        setLocationOnScreen(mQsView, top);
        when(mQsView.getHeight()).thenReturn(height);

        mUnderTest.hideImmediately();

        int qsAbsoluteBottom = top + height;
        assertThat(mQsView.getY()).isEqualTo(-qsAbsoluteBottom);
    }

    @Test
    public void setCollapseExpandAction_passedToControllers() {
        Runnable action = () -> {};
        mUnderTest.setCollapseExpandAction(action);

        verify(mQSPanelController).setCollapseExpandAction(action);
        verify(mQuickQSPanelController).setCollapseExpandAction(action);
    }

    @Test
    public void setOverScrollAmount_setsTranslationOnView() {
        mUnderTest.setOverScrollAmount(123);

        assertThat(mQsView.getTranslationY()).isEqualTo(123);
    }

    @Test
    public void setOverScrollAmount_beforeViewCreated_translationIsNotSet() {
        QSImpl other = instantiate();
        other.setOverScrollAmount(123);

        assertThat(mQsView.getTranslationY()).isEqualTo(0);
    }

    private Lifecycle.State getListeningAndVisibilityLifecycleState() {
        return mUnderTest
                .getListeningAndVisibilityLifecycleOwner()
                .getLifecycle()
                .getCurrentState();
    }

    @Test
    public void setListeningFalse_notVisible() {
        mUnderTest.setQsVisible(false);
        clearInvocations(mQSContainerImplController, mQSPanelController, mQSFooterActionController);

        mUnderTest.setListening(false);
        verify(mQSContainerImplController).setListening(false);
        assertThat(getListeningAndVisibilityLifecycleState()).isEqualTo(Lifecycle.State.CREATED);
        verify(mQSPanelController).setListening(eq(false), anyBoolean());
    }

    @Test
    public void setListeningTrue_notVisible() {
        mUnderTest.setQsVisible(false);
        clearInvocations(mQSContainerImplController, mQSPanelController, mQSFooterActionController);

        mUnderTest.setListening(true);
        verify(mQSContainerImplController).setListening(false);
        assertThat(getListeningAndVisibilityLifecycleState()).isEqualTo(Lifecycle.State.STARTED);
        verify(mQSPanelController).setListening(eq(false), anyBoolean());
    }

    @Test
    public void setListeningFalse_visible() {
        mUnderTest.setQsVisible(true);
        clearInvocations(mQSContainerImplController, mQSPanelController, mQSFooterActionController);

        mUnderTest.setListening(false);
        verify(mQSContainerImplController).setListening(false);
        assertThat(getListeningAndVisibilityLifecycleState()).isEqualTo(Lifecycle.State.CREATED);
        verify(mQSPanelController).setListening(eq(false), anyBoolean());
    }

    @Test
    public void setListeningTrue_visible() {
        mUnderTest.setQsVisible(true);
        clearInvocations(mQSContainerImplController, mQSPanelController, mQSFooterActionController);

        mUnderTest.setListening(true);
        verify(mQSContainerImplController).setListening(true);
        assertThat(getListeningAndVisibilityLifecycleState()).isEqualTo(Lifecycle.State.RESUMED);
        verify(mQSPanelController).setListening(eq(true), anyBoolean());
    }

    @Test
    public void passCorrectExpansionState_inSplitShade() {
        enableSplitShade();
        clearInvocations(mQSPanelController);

        mUnderTest.setExpanded(true);
        verify(mQSPanelController).setExpanded(true);

        mUnderTest.setExpanded(false);
        verify(mQSPanelController).setExpanded(false);
    }

    @Test
    public void startsListeningAfterStateChangeToExpanded_inSplitShade() {
        enableSplitShade();
        mUnderTest.setQsVisible(true);
        clearInvocations(mQSPanelController);

        mUnderTest.setExpanded(true);
        verify(mQSPanelController).setListening(true, true);
    }

    @Test
    public void testUpdateQSBounds_setMediaClipCorrectly() {
        disableSplitShade();

        Rect mediaHostClip = new Rect();
        when(mQSPanelController.getPaddingBottom()).thenReturn(50);
        setLocationOnScreen(mQSPanelScrollView, 25);
        when(mQSPanelScrollView.getMeasuredHeight()).thenReturn(200);
        when(mQSMediaHost.getCurrentClipping()).thenReturn(mediaHostClip);

        mUnderTest.updateQsBounds();

        assertEquals(25, mediaHostClip.top);
        assertEquals(175, mediaHostClip.bottom);
    }

    @Test
    public void testQsUpdatesQsAnimatorWithUpcomingState() {
        setStatusBarCurrentAndUpcomingState(SHADE);
        mUnderTest.onUpcomingStateChanged(KEYGUARD);

        verify(mQSAnimator).setOnKeyguard(true);
    }

    @Test
    @EnableSceneContainer
    public void testSceneContainerFlagsEnabled_FooterActionsRemoved_controllerNotStarted() {
        clearInvocations(mFooterActionsViewModel, mFooterActionsViewModelFactory);
        QSImpl other = instantiate();

        other.onComponentCreated(mQsComponent, null);

        assertThat((View) other.getView().findViewById(R.id.qs_footer_actions)).isNull();
        verifyZeroInteractions(mFooterActionsViewModel, mFooterActionsViewModelFactory);
    }

    @Test
    @EnableSceneContainer
    public void testSceneContainerFlagsEnabled_statusBarStateIsShade() {
        mUnderTest.onStateChanged(KEYGUARD);
        assertThat(mUnderTest.getStatusBarState()).isEqualTo(SHADE);

        mUnderTest.onStateChanged(SHADE_LOCKED);
        assertThat(mUnderTest.getStatusBarState()).isEqualTo(SHADE);
    }

    @Test
    @EnableSceneContainer
    public void testSceneContainerFlagsEnabled_isKeyguardState_alwaysFalse() {
        mUnderTest.onStateChanged(KEYGUARD);
        assertThat(mUnderTest.isKeyguardState()).isFalse();

        when(mStatusBarStateController.getCurrentOrUpcomingState()).thenReturn(KEYGUARD);
        assertThat(mUnderTest.isKeyguardState()).isFalse();
    }

    private QSImpl instantiate() {
        setupQsComponent();
        setUpViews();
        setUpInflater();
        setUpMedia();
        setUpOther();

        return new QSImpl(
                new RemoteInputQuickSettingsDisabler(
                        mContext,
                        mCommandQueue,
                        new ResourcesSplitShadeStateController(),
                        mock(ConfigurationController.class)),
                mStatusBarStateController,
                mCommandQueue,
                mQSMediaHost,
                mQQSMediaHost,
                mBypassController,
                mock(QSDisableFlagsLogger.class),
                mock(DumpManager.class),
                mock(QSLogger.class),
                mock(FooterActionsController.class),
                mFooterActionsViewModelFactory,
                mLargeScreenShadeInterpolator
        );
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
        mQsView = spy(new FrameLayout(mContext));
        when(mQsComponent.getRootView()).thenReturn(mQsView);

        when(mQSPanelScrollView.findViewById(R.id.expanded_qs_scroll_view))
                .thenReturn(mQSPanelScrollView);
        mQsView.addView(mQSPanelScrollView);

        when(mHeader.findViewById(R.id.header)).thenReturn(mHeader);
        mQsView.addView(mHeader);

        View customizer = new View(mContext);
        customizer.setId(android.R.id.edit);
        mQsView.addView(customizer);

        ComposeView footerActionsView = new ComposeView(mContext);
        footerActionsView.setId(R.id.qs_footer_actions);
        mQsView.addView(footerActionsView);
    }

    private void setUpInflater() {
        when(mLayoutInflater.cloneInContext(any(Context.class))).thenReturn(mLayoutInflater);
        when(mLayoutInflater.inflate(anyInt(), nullable(ViewGroup.class), anyBoolean()))
                .thenAnswer((invocation) -> mQsView);
        when(mLayoutInflater.inflate(anyInt(), nullable(ViewGroup.class)))
                .thenAnswer((invocation) -> mQsView);
        mContext.addMockSystemService(Context.LAYOUT_INFLATER_SERVICE, mLayoutInflater);
    }

    private void setupQsComponent() {
        when(mQsComponent.getQSPanelController()).thenReturn(mQSPanelController);
        when(mQsComponent.getQuickQSPanelController()).thenReturn(mQuickQSPanelController);
        when(mQsComponent.getQSCustomizerController()).thenReturn(mQsCustomizerController);
        when(mQsComponent.getQSContainerImplController())
                .thenReturn(mQSContainerImplController);
        when(mQsComponent.getQSFooter()).thenReturn(mFooter);
        when(mQsComponent.getQSFooterActionController())
                .thenReturn(mQSFooterActionController);
        when(mQsComponent.getQSAnimator()).thenReturn(mQSAnimator);
        when(mQsComponent.getQSSquishinessController()).thenReturn(mSquishinessController);
    }

    private void setStatusBarCurrentAndUpcomingState(int statusBarState) {
        when(mStatusBarStateController.getState()).thenReturn(statusBarState);
        when(mStatusBarStateController.getCurrentOrUpcomingState()).thenReturn(statusBarState);
        mUnderTest.onStateChanged(statusBarState);
    }

    private void enableSplitShade() {
        setSplitShadeEnabled(true);
    }

    private void disableSplitShade() {
        setSplitShadeEnabled(false);
    }

    private void setSplitShadeEnabled(boolean enabled) {
        mUnderTest.setInSplitShade(enabled);
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
        mUnderTest.setIsNotificationPanelFullWidth(false);
    }

    private void setIsSmallScreen() {
        mUnderTest.setIsNotificationPanelFullWidth(true);
    }
}
