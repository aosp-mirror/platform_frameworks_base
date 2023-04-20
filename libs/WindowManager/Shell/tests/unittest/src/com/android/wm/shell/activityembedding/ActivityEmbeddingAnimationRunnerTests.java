/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.activityembedding;

import static android.view.WindowManager.TRANSIT_OPEN;
import static android.window.TransitionInfo.FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY;
import static android.window.TransitionInfo.FLAG_IS_BEHIND_STARTING_WINDOW;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.animation.Animator;
import android.window.TransitionInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;

/**
 * Tests for {@link ActivityEmbeddingAnimationRunner}.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:ActivityEmbeddingAnimationRunnerTests
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class ActivityEmbeddingAnimationRunnerTests extends ActivityEmbeddingAnimationTestBase {

    @Before
    public void setup() {
        super.setUp();
        doNothing().when(mController).onAnimationFinished(any());
    }

    @Test
    public void testStartAnimation() {
        final TransitionInfo info = new TransitionInfo(TRANSIT_OPEN, 0);
        final TransitionInfo.Change embeddingChange = createChange();
        embeddingChange.setFlags(FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY);
        info.addChange(embeddingChange);
        doReturn(mAnimator).when(mAnimRunner).createAnimator(any(), any(), any(), any(), any());

        mAnimRunner.startAnimation(mTransition, info, mStartTransaction, mFinishTransaction);

        final ArgumentCaptor<Runnable> finishCallback = ArgumentCaptor.forClass(Runnable.class);
        verify(mAnimRunner).createAnimator(eq(info), eq(mStartTransaction), eq(mFinishTransaction),
                finishCallback.capture(), any());
        verify(mStartTransaction).apply();
        verify(mAnimator).start();
        verifyNoMoreInteractions(mFinishTransaction);
        verify(mController, never()).onAnimationFinished(any());

        // Call onAnimationFinished() when the animation is finished.
        finishCallback.getValue().run();

        verify(mController).onAnimationFinished(mTransition);
    }

    @Test
    public void testChangesBehindStartingWindow() {
        final TransitionInfo info = new TransitionInfo(TRANSIT_OPEN, 0);
        final TransitionInfo.Change embeddingChange = createChange();
        embeddingChange.setFlags(FLAG_IS_BEHIND_STARTING_WINDOW);
        info.addChange(embeddingChange);
        final Animator animator = mAnimRunner.createAnimator(
                info, mStartTransaction, mFinishTransaction,
                () -> mFinishCallback.onTransitionFinished(null /* wct */, null /* wctCB */),
                new ArrayList());

        // The animation should be empty when it is behind starting window.
        assertEquals(0, animator.getDuration());
    }
}
