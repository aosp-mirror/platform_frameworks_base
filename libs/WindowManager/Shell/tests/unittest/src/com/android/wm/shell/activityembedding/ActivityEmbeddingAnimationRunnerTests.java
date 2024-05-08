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

import static com.android.wm.shell.activityembedding.ActivityEmbeddingAnimationRunner.calculateParentBounds;
import static com.android.wm.shell.transition.Transitions.TRANSIT_TASK_FRAGMENT_DRAG_RESIZE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.animation.Animator;
import android.annotation.NonNull;
import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.window.TransitionInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.window.flags.Flags;
import com.android.wm.shell.transition.TransitionInfoBuilder;

import org.junit.Before;
import org.junit.Rule;
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

    @Rule
    public SetFlagsRule mRule = new SetFlagsRule();

    @Before
    public void setup() {
        super.setUp();
        doNothing().when(mController).onAnimationFinished(any());
    }

    @EnableFlags(Flags.FLAG_MOVE_ANIMATION_OPTIONS_TO_CHANGE)
    @Test
    public void testStartAnimation() {
        final TransitionInfo info = new TransitionInfoBuilder(TRANSIT_OPEN, 0)
                .addChange(createChange(FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY))
                .build();
        doReturn(mAnimator).when(mAnimRunner).createAnimator(any(), any(), any(), any(),
                any());

        mAnimRunner.startAnimation(mTransition, info, mStartTransaction, mFinishTransaction);

        final ArgumentCaptor<Runnable> finishCallback = ArgumentCaptor.forClass(Runnable.class);
        verify(mAnimRunner).createAnimator(eq(info), eq(mStartTransaction),
                eq(mFinishTransaction),
                finishCallback.capture(), any());
        verify(mStartTransaction).apply();
        verify(mAnimator).start();
        verifyNoMoreInteractions(mFinishTransaction);
        verify(mController, never()).onAnimationFinished(any());

        // Call onAnimationFinished() when the animation is finished.
        finishCallback.getValue().run();

        verify(mController).onAnimationFinished(mTransition);
    }

    @EnableFlags(Flags.FLAG_MOVE_ANIMATION_OPTIONS_TO_CHANGE)
    @Test
    public void testChangesBehindStartingWindow() {
        final TransitionInfo info = new TransitionInfoBuilder(TRANSIT_OPEN, 0)
                .addChange(createChange(FLAG_IS_BEHIND_STARTING_WINDOW))
                .build();
        final Animator animator = mAnimRunner.createAnimator(
                info, mStartTransaction, mFinishTransaction,
                () -> mFinishCallback.onTransitionFinished(null /* wct */),
                new ArrayList());

        // The animation should be empty when it is behind starting window.
        assertEquals(0, animator.getDuration());
    }

    @EnableFlags(Flags.FLAG_MOVE_ANIMATION_OPTIONS_TO_CHANGE)
    @Test
    public void testTransitionTypeDragResize() {
        final TransitionInfo info = new TransitionInfoBuilder(TRANSIT_TASK_FRAGMENT_DRAG_RESIZE, 0)
                .addChange(createChange(FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY))
                .build();
        final Animator animator = mAnimRunner.createAnimator(
                info, mStartTransaction, mFinishTransaction,
                () -> mFinishCallback.onTransitionFinished(null /* wct */),
                new ArrayList());

        // The animation should be empty when it is a jump cut for drag resize.
        assertEquals(0, animator.getDuration());
    }

    @DisableFlags(Flags.FLAG_MOVE_ANIMATION_OPTIONS_TO_CHANGE)
    @Test
    public void testInvalidCustomAnimation_disableAnimationOptionsPerChange() {
        final TransitionInfo info = new TransitionInfoBuilder(TRANSIT_OPEN, 0)
                .addChange(createChange(FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY, TRANSIT_OPEN))
                .build();
        info.setAnimationOptions(TransitionInfo.AnimationOptions
                .makeCustomAnimOptions("packageName", 0 /* enterResId */, 0 /* exitResId */,
                        0 /* backgroundColor */, false /* overrideTaskTransition */));
        final Animator animator = mAnimRunner.createAnimator(
                info, mStartTransaction, mFinishTransaction,
                () -> mFinishCallback.onTransitionFinished(null /* wct */),
                new ArrayList<>());

        // An invalid custom animation is equivalent to jump-cut.
        assertEquals(0, animator.getDuration());
    }

    @EnableFlags(Flags.FLAG_MOVE_ANIMATION_OPTIONS_TO_CHANGE)
    @Test
    public void testInvalidCustomAnimation_enableAnimationOptionsPerChange() {
        final TransitionInfo info = new TransitionInfoBuilder(TRANSIT_OPEN, 0)
                .addChange(createChange(FLAG_IN_TASK_WITH_EMBEDDED_ACTIVITY, TRANSIT_OPEN))
                .build();
        info.getChanges().getFirst().setAnimationOptions(TransitionInfo.AnimationOptions
                .makeCustomAnimOptions("packageName", 0 /* enterResId */, 0 /* exitResId */,
                        0 /* backgroundColor */, false /* overrideTaskTransition */));
        final Animator animator = mAnimRunner.createAnimator(
                info, mStartTransaction, mFinishTransaction,
                () -> mFinishCallback.onTransitionFinished(null /* wct */),
                new ArrayList<>());

        // An invalid custom animation is equivalent to jump-cut.
        assertEquals(0, animator.getDuration());
    }

    @DisableFlags(Flags.FLAG_ACTIVITY_EMBEDDING_OVERLAY_PRESENTATION_FLAG)
    @Test
    public void testCalculateParentBounds_flagDisabled() {
        final Rect parentBounds = new Rect(0, 0, 2000, 2000);
        final Rect primaryBounds = new Rect();
        final Rect secondaryBounds = new Rect();
        parentBounds.splitVertically(primaryBounds, secondaryBounds);

        final TransitionInfo.Change change = createChange(0 /* flags */);
        change.setStartAbsBounds(secondaryBounds);

        final TransitionInfo.Change boundsAnimationChange = createChange(0 /* flags */);
        boundsAnimationChange.setStartAbsBounds(primaryBounds);
        boundsAnimationChange.setEndAbsBounds(primaryBounds);
        final Rect actualParentBounds = new Rect();

        calculateParentBounds(change, boundsAnimationChange, actualParentBounds);

        assertEquals(parentBounds, actualParentBounds);

        actualParentBounds.setEmpty();

        boundsAnimationChange.setStartAbsBounds(secondaryBounds);
        boundsAnimationChange.setEndAbsBounds(primaryBounds);

        calculateParentBounds(boundsAnimationChange, boundsAnimationChange, actualParentBounds);

        assertEquals(parentBounds, actualParentBounds);
    }

    // TODO(b/243518738): Rewrite with TestParameter
    @EnableFlags(Flags.FLAG_ACTIVITY_EMBEDDING_OVERLAY_PRESENTATION_FLAG)
    @Test
    public void testCalculateParentBounds_flagEnabled() {
        TransitionInfo.Change change;
        final TransitionInfo.Change stubChange = createChange(0 /* flags */);
        final Rect actualParentBounds = new Rect();
        Rect parentBounds = new Rect(0, 0, 2000, 2000);
        Rect endAbsBounds = new Rect(0, 0, 2000, 2000);
        change = prepareChangeForParentBoundsCalculationTest(
                new Point(0, 0) /* endRelOffset */,
                endAbsBounds,
                new Point() /* endParentSize */
        );

        calculateParentBounds(change, stubChange, actualParentBounds);

        assertTrue("Parent bounds must be empty because end parent size is not set.",
                actualParentBounds.isEmpty());

        String testString = "Parent start with (0, 0)";
        change = prepareChangeForParentBoundsCalculationTest(
                new Point(endAbsBounds.left - parentBounds.left,
                        endAbsBounds.top - parentBounds.top),
                endAbsBounds, new Point(parentBounds.width(), parentBounds.height()));

        calculateParentBounds(change, stubChange, actualParentBounds);

        assertEquals(testString + ": Parent bounds must be " + parentBounds, parentBounds,
                actualParentBounds);

        testString = "Container not start with (0, 0)";
        parentBounds = new Rect(0, 0, 2000, 2000);
        endAbsBounds = new Rect(1000, 500, 2000, 1500);
        change = prepareChangeForParentBoundsCalculationTest(
                new Point(endAbsBounds.left - parentBounds.left,
                        endAbsBounds.top - parentBounds.top),
                endAbsBounds, new Point(parentBounds.width(), parentBounds.height()));

        calculateParentBounds(change, stubChange, actualParentBounds);

        assertEquals(testString + ": Parent bounds must be " + parentBounds, parentBounds,
                actualParentBounds);

        testString = "Parent container on the right";
        parentBounds = new Rect(1000, 0, 2000, 2000);
        endAbsBounds = new Rect(1000, 500, 1500, 1500);
        change = prepareChangeForParentBoundsCalculationTest(
                new Point(endAbsBounds.left - parentBounds.left,
                        endAbsBounds.top - parentBounds.top),
                endAbsBounds, new Point(parentBounds.width(), parentBounds.height()));

        calculateParentBounds(change, stubChange, actualParentBounds);

        assertEquals(testString + ": Parent bounds must be " + parentBounds, parentBounds,
                actualParentBounds);

        testString = "Parent container on the bottom";
        parentBounds = new Rect(0, 1000, 2000, 2000);
        endAbsBounds = new Rect(500, 1500, 1500, 2000);
        change = prepareChangeForParentBoundsCalculationTest(
                new Point(endAbsBounds.left - parentBounds.left,
                        endAbsBounds.top - parentBounds.top),
                endAbsBounds, new Point(parentBounds.width(), parentBounds.height()));

        calculateParentBounds(change, stubChange, actualParentBounds);

        assertEquals(testString + ": Parent bounds must be " + parentBounds, parentBounds,
                actualParentBounds);

        testString = "Parent container in the middle";
        parentBounds = new Rect(500, 500, 1500, 1500);
        endAbsBounds = new Rect(1000, 500, 1500, 1000);
        change = prepareChangeForParentBoundsCalculationTest(
                new Point(endAbsBounds.left - parentBounds.left,
                        endAbsBounds.top - parentBounds.top),
                endAbsBounds, new Point(parentBounds.width(), parentBounds.height()));

        calculateParentBounds(change, stubChange, actualParentBounds);

        assertEquals(testString + ": Parent bounds must be " + parentBounds, parentBounds,
                actualParentBounds);
    }

    @NonNull
    private static TransitionInfo.Change prepareChangeForParentBoundsCalculationTest(
            @NonNull Point endRelOffset, @NonNull Rect endAbsBounds, @NonNull Point endParentSize) {
        final TransitionInfo.Change change = createChange(0 /* flags */);
        change.setEndRelOffset(endRelOffset.x, endRelOffset.y);
        change.setEndAbsBounds(endAbsBounds);
        change.setEndParentSize(endParentSize.x, endParentSize.y);
        return change;
    }
}
