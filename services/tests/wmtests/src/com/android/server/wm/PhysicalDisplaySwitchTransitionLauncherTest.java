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

package com.android.server.wm;

import static android.view.WindowManager.TRANSIT_CHANGE;

import static com.android.server.wm.DeviceStateController.DeviceState.FOLDED;
import static com.android.server.wm.DeviceStateController.DeviceState.HALF_FOLDED;
import static com.android.server.wm.DeviceStateController.DeviceState.OPEN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.window.TransitionRequestInfo.DisplayChange;

import static com.android.internal.R.bool.config_unfoldTransitionEnabled;
import static com.android.server.wm.DeviceStateController.DeviceState.REAR;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for the {@link WindowToken} class.
 *
 * Build/Install/Run:
 * atest WmTests:PhysicalDisplaySwitchTransitionLauncherTest
 */
@SmallTest
@Presubmit
public class PhysicalDisplaySwitchTransitionLauncherTest {

    @Mock
    DisplayContent mDisplayContent;
    @Mock
    Context mContext;
    @Mock
    Resources mResources;
    @Mock
    ActivityTaskManagerService mActivityTaskManagerService;
    @Mock
    BLASTSyncEngine mSyncEngine;
    @Mock
    TransitionController mTransitionController;

    private PhysicalDisplaySwitchTransitionLauncher mTarget;
    private float mOriginalAnimationScale;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mContext.getResources()).thenReturn(mResources);
        mTarget = new PhysicalDisplaySwitchTransitionLauncher(mDisplayContent,
                mActivityTaskManagerService, mContext, mTransitionController);
        mOriginalAnimationScale = ValueAnimator.getDurationScale();
    }

    @After
    public void after() {
        ValueAnimator.setDurationScale(mOriginalAnimationScale);
    }

    @Test
    public void testDisplaySwitchAfterUnfoldToOpen_animationsEnabled_requestsTransition() {
        givenAllAnimationsEnabled();
        mTarget.foldStateChanged(FOLDED);

        mTarget.foldStateChanged(OPEN);
        mTarget.requestDisplaySwitchTransitionIfNeeded(
                /* displayId= */ 123,
                /* oldDisplayWidth= */ 100,
                /* oldDisplayHeight= */ 150,
                /* newDisplayWidth= */ 200,
                /* newDisplayHeight= */ 250
        );

        ArgumentCaptor<DisplayChange> displayChangeArgumentCaptor =
                ArgumentCaptor.forClass(DisplayChange.class);
        verify(mTransitionController).requestTransitionIfNeeded(eq(TRANSIT_CHANGE), /* flags= */
                eq(0), eq(mDisplayContent), eq(mDisplayContent), /* remoteTransition= */ isNull(),
                displayChangeArgumentCaptor.capture());
        assertThat(displayChangeArgumentCaptor.getValue().getDisplayId()).isEqualTo(123);
        assertThat(displayChangeArgumentCaptor.getValue().getStartAbsBounds()).isEqualTo(
                new Rect(0, 0, 100, 150));
        assertThat(displayChangeArgumentCaptor.getValue().getEndAbsBounds()).isEqualTo(
                new Rect(0, 0, 200, 250));
    }

    @Test
    public void testDisplaySwitchAfterFolding_animationEnabled_doesNotRequestTransition() {
        givenAllAnimationsEnabled();
        mTarget.foldStateChanged(OPEN);

        mTarget.foldStateChanged(FOLDED);
        requestDisplaySwitch();

        assertTransitionNotRequested();
    }

    @Test
    public void testDisplaySwitchAfterUnfoldingToHalf_animationEnabled_requestsTransition() {
        givenAllAnimationsEnabled();
        mTarget.foldStateChanged(FOLDED);

        mTarget.foldStateChanged(HALF_FOLDED);
        requestDisplaySwitch();

        assertTransitionRequested();
    }

    @Test
    public void testDisplaySwitchSecondTimeAfterUnfolding_animationEnabled_noTransition() {
        givenAllAnimationsEnabled();
        mTarget.foldStateChanged(FOLDED);
        mTarget.foldStateChanged(OPEN);
        requestDisplaySwitch();
        clearInvocations(mTransitionController);

        requestDisplaySwitch();

        assertTransitionNotRequested();
    }


    @Test
    public void testDisplaySwitchAfterGoingToRearAndBack_animationEnabled_noTransition() {
        givenAllAnimationsEnabled();
        mTarget.foldStateChanged(OPEN);

        mTarget.foldStateChanged(REAR);
        mTarget.foldStateChanged(OPEN);
        requestDisplaySwitch();

        assertTransitionNotRequested();
    }

    @Test
    public void testDisplaySwitchAfterUnfoldingAndFolding_animationEnabled_noTransition() {
        givenAllAnimationsEnabled();
        mTarget.foldStateChanged(FOLDED);
        mTarget.foldStateChanged(OPEN);
        // No request display switch event (simulate very fast fold after unfold, even before
        // the displays switched)
        mTarget.foldStateChanged(FOLDED);

        requestDisplaySwitch();

        assertTransitionNotRequested();
    }

    @Test
    public void testDisplaySwitch_whenShellTransitionsNotEnabled_noTransition() {
        givenAllAnimationsEnabled();
        givenShellTransitionsEnabled(false);
        mTarget.foldStateChanged(FOLDED);

        mTarget.foldStateChanged(OPEN);
        requestDisplaySwitch();

        assertTransitionNotRequested();
    }

    @Test
    public void testDisplaySwitch_whenAnimationsDisabled_noTransition() {
        givenAllAnimationsEnabled();
        givenAnimationsEnabled(false);
        mTarget.foldStateChanged(FOLDED);

        mTarget.foldStateChanged(OPEN);
        requestDisplaySwitch();

        assertTransitionNotRequested();
    }

    @Test
    public void testDisplaySwitch_whenUnfoldAnimationDisabled_noTransition() {
        givenAllAnimationsEnabled();
        givenUnfoldTransitionEnabled(false);
        mTarget.foldStateChanged(FOLDED);

        mTarget.foldStateChanged(OPEN);
        requestDisplaySwitch();

        assertTransitionNotRequested();
    }

    @Test
    public void testDisplaySwitchAfterUnfolding_otherCollectingTransition_collectsDisplaySwitch() {
        givenCollectingTransition(createTransition(TRANSIT_CHANGE));
        givenAllAnimationsEnabled();
        mTarget.foldStateChanged(FOLDED);

        mTarget.foldStateChanged(OPEN);
        requestDisplaySwitch();

        // Collects to the current transition
        verify(mTransitionController).collect(mDisplayContent);
    }


    @Test
    public void testDisplaySwitch_whenNoContentInDisplayContent_noTransition() {
        givenAllAnimationsEnabled();
        givenDisplayContentHasContent(false);
        mTarget.foldStateChanged(FOLDED);

        mTarget.foldStateChanged(OPEN);
        requestDisplaySwitch();

        assertTransitionNotRequested();
    }

    private void assertTransitionRequested() {
        verify(mTransitionController).requestTransitionIfNeeded(anyInt(), anyInt(), any(), any(),
                any(), any());
    }

    private void assertTransitionNotRequested() {
        verify(mTransitionController, never()).requestTransitionIfNeeded(anyInt(), anyInt(), any(),
                any(), any(), any());
    }

    private void requestDisplaySwitch() {
        mTarget.requestDisplaySwitchTransitionIfNeeded(
                /* displayId= */ 123,
                /* oldDisplayWidth= */ 100,
                /* oldDisplayHeight= */ 150,
                /* newDisplayWidth= */ 200,
                /* newDisplayHeight= */ 250
        );
    }

    private void givenAllAnimationsEnabled() {
        givenAnimationsEnabled(true);
        givenUnfoldTransitionEnabled(true);
        givenShellTransitionsEnabled(true);
        givenDisplayContentHasContent(true);
    }

    private void givenUnfoldTransitionEnabled(boolean enabled) {
        when(mResources.getBoolean(config_unfoldTransitionEnabled)).thenReturn(enabled);
    }

    private void givenAnimationsEnabled(boolean enabled) {
        ValueAnimator.setDurationScale(enabled ? 1.0f : 0.0f);
    }

    private void givenShellTransitionsEnabled(boolean enabled) {
        when(mTransitionController.isShellTransitionsEnabled()).thenReturn(enabled);
    }

    private void givenCollectingTransition(@Nullable Transition transition) {
        when(mTransitionController.isCollecting()).thenReturn(transition != null);
        when(mTransitionController.getCollectingTransition()).thenReturn(transition);
    }

    private Transition createTransition(int type) {
        return new Transition(type, /* flags= */ 0, mTransitionController, mSyncEngine);
    }

    private void givenDisplayContentHasContent(boolean hasContent) {
        when(mDisplayContent.getLastHasContent()).thenReturn(hasContent);
    }
}
