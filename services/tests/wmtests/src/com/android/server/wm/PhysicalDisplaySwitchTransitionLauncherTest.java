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

import static com.android.internal.R.bool.config_unfoldTransitionEnabled;
import static com.android.server.wm.DeviceStateController.DeviceState.REAR;
import static com.android.server.wm.DeviceStateController.DeviceState.FOLDED;
import static com.android.server.wm.DeviceStateController.DeviceState.HALF_FOLDED;
import static com.android.server.wm.DeviceStateController.DeviceState.OPEN;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
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
@RunWith(WindowTestRunner.class)
public class PhysicalDisplaySwitchTransitionLauncherTest extends WindowTestsBase {

    @Mock
    Context mContext;
    @Mock
    Resources mResources;
    @Mock
    BLASTSyncEngine mSyncEngine;

    WindowTestsBase.TestTransitionPlayer mPlayer;
    TransitionController mTransitionController;
    DisplayContent mDisplayContent;

    private PhysicalDisplaySwitchTransitionLauncher mTarget;
    private float mOriginalAnimationScale;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTransitionController = new WindowTestsBase.TestTransitionController(mAtm);
        mTransitionController.setSyncEngine(mSyncEngine);
        mPlayer = new WindowTestsBase.TestTransitionPlayer(
                mTransitionController, mAtm.mWindowOrganizerController);
        when(mContext.getResources()).thenReturn(mResources);
        mDisplayContent = new TestDisplayContent.Builder(mAtm, 100, 150).build();
        mTarget = new PhysicalDisplaySwitchTransitionLauncher(mDisplayContent, mAtm, mContext,
                mTransitionController);
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
        final Rect origBounds = new Rect();
        mDisplayContent.getBounds(origBounds);
        origBounds.offsetTo(0, 0);
        mTarget.requestDisplaySwitchTransitionIfNeeded(
                mDisplayContent.getDisplayId(),
                origBounds.width(),
                origBounds.height(),
                /* newDisplayWidth= */ 200,
                /* newDisplayHeight= */ 250
        );

        assertNotNull(mPlayer.mLastRequest);
        assertEquals(mDisplayContent.getDisplayId(),
                mPlayer.mLastRequest.getDisplayChange().getDisplayId());
        assertEquals(origBounds, mPlayer.mLastRequest.getDisplayChange().getStartAbsBounds());
        assertEquals(new Rect(0, 0, 200, 250),
                mPlayer.mLastRequest.getDisplayChange().getEndAbsBounds());
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
        mPlayer.mLastRequest = null;

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
        givenAllAnimationsEnabled();
        mTarget.foldStateChanged(FOLDED);

        mTarget.foldStateChanged(OPEN);
        requestDisplaySwitch();

        // Collects to the current transition
        assertTrue(mTransitionController.getCollectingTransition().mParticipants.contains(
                mDisplayContent));
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
        assertNotNull(mPlayer.mLastRequest);
    }

    private void assertTransitionNotRequested() {
        assertNull(mPlayer.mLastRequest);
    }

    private void requestDisplaySwitch() {
        mTarget.requestDisplaySwitchTransitionIfNeeded(
                mDisplayContent.getDisplayId(),
                mDisplayContent.getBounds().width(),
                mDisplayContent.getBounds().height(),
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
        if (enabled) {
            mTransitionController.registerTransitionPlayer(mPlayer, null /* proc */);
        } else {
            mTransitionController.unregisterTransitionPlayer(mPlayer);
        }
    }

    private void givenDisplayContentHasContent(boolean hasContent) {
        when(mDisplayContent.getLastHasContent()).thenReturn(hasContent);
    }
}
