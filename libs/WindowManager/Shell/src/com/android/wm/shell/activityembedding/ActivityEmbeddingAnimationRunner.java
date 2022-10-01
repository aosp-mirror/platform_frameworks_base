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

import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManagerPolicyConstants.TYPE_LAYER_OFFSET;
import static android.window.TransitionInfo.FLAG_IS_BEHIND_STARTING_WINDOW;

import static com.android.wm.shell.transition.TransitionAnimationHelper.addBackgroundToTransition;
import static com.android.wm.shell.transition.TransitionAnimationHelper.getTransitionBackgroundColorIfSet;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.ArraySet;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.animation.Animation;
import android.window.TransitionInfo;
import android.window.WindowContainerToken;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.common.ScreenshotUtils;
import com.android.wm.shell.transition.Transitions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** To run the ActivityEmbedding animations. */
class ActivityEmbeddingAnimationRunner {

    private static final String TAG = "ActivityEmbeddingAnimR";

    private final ActivityEmbeddingController mController;
    @VisibleForTesting
    final ActivityEmbeddingAnimationSpec mAnimationSpec;

    ActivityEmbeddingAnimationRunner(@NonNull Context context,
            @NonNull ActivityEmbeddingController controller) {
        mController = controller;
        mAnimationSpec = new ActivityEmbeddingAnimationSpec(context);
    }

    /** Creates and starts animation for ActivityEmbedding transition. */
    void startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction) {
        final Animator animator = createAnimator(info, startTransaction, finishTransaction,
                () -> mController.onAnimationFinished(transition));
        startTransaction.apply();
        animator.start();
    }

    /**
     * Sets transition animation scale settings value.
     * @param scale The setting value of transition animation scale.
     */
    void setAnimScaleSetting(float scale) {
        mAnimationSpec.setAnimScaleSetting(scale);
    }

    /** Creates the animator for the given {@link TransitionInfo}. */
    @VisibleForTesting
    @NonNull
    Animator createAnimator(@NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Runnable animationFinishCallback) {
        final List<ActivityEmbeddingAnimationAdapter> adapters =
                createAnimationAdapters(info, startTransaction, finishTransaction);
        long duration = 0;
        for (ActivityEmbeddingAnimationAdapter adapter : adapters) {
            duration = Math.max(duration, adapter.getDurationHint());
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(duration);
        animator.addUpdateListener((anim) -> {
            // Update all adapters in the same transaction.
            final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            for (ActivityEmbeddingAnimationAdapter adapter : adapters) {
                adapter.onAnimationUpdate(t, animator.getCurrentPlayTime());
            }
            t.apply();
        });
        animator.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {}

            @Override
            public void onAnimationEnd(Animator animation) {
                final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
                for (ActivityEmbeddingAnimationAdapter adapter : adapters) {
                    adapter.onAnimationEnd(t);
                }
                t.apply();
                animationFinishCallback.run();
            }

            @Override
            public void onAnimationCancel(Animator animation) {}

            @Override
            public void onAnimationRepeat(Animator animation) {}
        });
        return animator;
    }

    /**
     * Creates list of {@link ActivityEmbeddingAnimationAdapter} to handle animations on all window
     * changes.
     */
    @NonNull
    private List<ActivityEmbeddingAnimationAdapter> createAnimationAdapters(
            @NonNull TransitionInfo info, @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction) {
        boolean isChangeTransition = false;
        for (TransitionInfo.Change change : info.getChanges()) {
            if (change.hasFlags(FLAG_IS_BEHIND_STARTING_WINDOW)) {
                // Skip the animation if the windows are behind an app starting window.
                return new ArrayList<>();
            }
            if (!isChangeTransition && change.getMode() == TRANSIT_CHANGE
                    && !change.getStartAbsBounds().equals(change.getEndAbsBounds())) {
                isChangeTransition = true;
            }
        }
        if (isChangeTransition) {
            return createChangeAnimationAdapters(info, startTransaction);
        }
        if (Transitions.isClosingType(info.getType())) {
            return createCloseAnimationAdapters(info, startTransaction, finishTransaction);
        }
        return createOpenAnimationAdapters(info, startTransaction, finishTransaction);
    }

    @NonNull
    private List<ActivityEmbeddingAnimationAdapter> createOpenAnimationAdapters(
            @NonNull TransitionInfo info, @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction) {
        return createOpenCloseAnimationAdapters(info, startTransaction, finishTransaction,
                true /* isOpening */, mAnimationSpec::loadOpenAnimation);
    }

    @NonNull
    private List<ActivityEmbeddingAnimationAdapter> createCloseAnimationAdapters(
            @NonNull TransitionInfo info, @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction) {
        return createOpenCloseAnimationAdapters(info, startTransaction, finishTransaction,
                false /* isOpening */, mAnimationSpec::loadCloseAnimation);
    }

    /**
     * Creates {@link ActivityEmbeddingAnimationAdapter} for OPEN and CLOSE types of transition.
     * @param isOpening {@code true} for OPEN type, {@code false} for CLOSE type.
     */
    @NonNull
    private List<ActivityEmbeddingAnimationAdapter> createOpenCloseAnimationAdapters(
            @NonNull TransitionInfo info, @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction, boolean isOpening,
            @NonNull AnimationProvider animationProvider) {
        // We need to know if the change window is only a partial of the whole animation screen.
        // If so, we will need to adjust it to make the whole animation screen looks like one.
        final List<TransitionInfo.Change> openingChanges = new ArrayList<>();
        final List<TransitionInfo.Change> closingChanges = new ArrayList<>();
        final Rect openingWholeScreenBounds = new Rect();
        final Rect closingWholeScreenBounds = new Rect();
        for (TransitionInfo.Change change : info.getChanges()) {
            if (Transitions.isOpeningType(change.getMode())) {
                openingChanges.add(change);
                openingWholeScreenBounds.union(change.getEndAbsBounds());
            } else {
                closingChanges.add(change);
                closingWholeScreenBounds.union(change.getEndAbsBounds());
            }
        }

        // For OPEN transition, open windows should be above close windows.
        // For CLOSE transition, open windows should be below close windows.
        int offsetLayer = TYPE_LAYER_OFFSET;
        final List<ActivityEmbeddingAnimationAdapter> adapters = new ArrayList<>();
        for (TransitionInfo.Change change : openingChanges) {
            final ActivityEmbeddingAnimationAdapter adapter = createOpenCloseAnimationAdapter(
                    info, change, startTransaction, finishTransaction, animationProvider,
                    openingWholeScreenBounds);
            if (isOpening) {
                adapter.overrideLayer(offsetLayer++);
            }
            adapters.add(adapter);
        }
        for (TransitionInfo.Change change : closingChanges) {
            final ActivityEmbeddingAnimationAdapter adapter = createOpenCloseAnimationAdapter(
                    info, change, startTransaction, finishTransaction, animationProvider,
                    closingWholeScreenBounds);
            if (!isOpening) {
                adapter.overrideLayer(offsetLayer++);
            }
            adapters.add(adapter);
        }
        return adapters;
    }

    @NonNull
    private ActivityEmbeddingAnimationAdapter createOpenCloseAnimationAdapter(
            @NonNull TransitionInfo info, @NonNull TransitionInfo.Change change,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull AnimationProvider animationProvider, @NonNull Rect wholeAnimationBounds) {
        final Animation animation = animationProvider.get(info, change, wholeAnimationBounds);
        // We may want to show a background color for open/close transition.
        final int backgroundColor = getTransitionBackgroundColorIfSet(info, change, animation,
                0 /* defaultColor */);
        if (backgroundColor != 0) {
            addBackgroundToTransition(info.getRootLeash(), backgroundColor, startTransaction,
                    finishTransaction);
        }
        return new ActivityEmbeddingAnimationAdapter(animation, change, change.getLeash(),
                wholeAnimationBounds);
    }

    @NonNull
    private List<ActivityEmbeddingAnimationAdapter> createChangeAnimationAdapters(
            @NonNull TransitionInfo info, @NonNull SurfaceControl.Transaction startTransaction) {
        final List<ActivityEmbeddingAnimationAdapter> adapters = new ArrayList<>();
        final Set<TransitionInfo.Change> handledChanges = new ArraySet<>();

        // For the first iteration, we prepare the animation for the change type windows. This is
        // needed because there may be window that is reparented while resizing. In such case, we
        // will do the following:
        // 1. Capture a screenshot from the Activity surface.
        // 2. Attach the screenshot surface to the top of TaskFragment (Activity's parent) surface.
        // 3. Animate the TaskFragment using Activity Change info (start/end bounds).
        // This is because the TaskFragment surface/change won't contain the Activity's before its
        // reparent.
        for (TransitionInfo.Change change : info.getChanges()) {
            if (change.getMode() != TRANSIT_CHANGE
                    || change.getStartAbsBounds().equals(change.getEndAbsBounds())) {
                continue;
            }

            // This is the window with bounds change.
            handledChanges.add(change);
            final WindowContainerToken parentToken = change.getParent();
            TransitionInfo.Change boundsAnimationChange = change;
            if (parentToken != null) {
                // When the parent window is also included in the transition as an opening window,
                // we would like to animate the parent window instead.
                final TransitionInfo.Change parentChange = info.getChange(parentToken);
                if (parentChange != null && Transitions.isOpeningType(parentChange.getMode())) {
                    // We won't create a separate animation for the parent, but to animate the
                    // parent for the child resizing.
                    handledChanges.add(parentChange);
                    boundsAnimationChange = parentChange;
                }
            }

            final Animation[] animations = mAnimationSpec.createChangeBoundsChangeAnimations(change,
                    boundsAnimationChange.getEndAbsBounds());

            // Create a screenshot based on change, but attach it to the top of the
            // boundsAnimationChange.
            final SurfaceControl screenshotLeash = getOrCreateScreenshot(change,
                    boundsAnimationChange, startTransaction);
            if (screenshotLeash != null) {
                // Adapter for the starting screenshot leash.
                // The screenshot leash will be removed in SnapshotAdapter#onAnimationEnd
                adapters.add(new ActivityEmbeddingAnimationAdapter.SnapshotAdapter(
                        animations[0], change, screenshotLeash));
            } else {
                Log.e(TAG, "Failed to take screenshot for change=" + change);
            }
            // Adapter for the ending bounds changed leash.
            adapters.add(new ActivityEmbeddingAnimationAdapter.BoundsChangeAdapter(
                    animations[1], boundsAnimationChange));
        }

        // Handle the other windows that don't have bounds change in the same transition.
        for (TransitionInfo.Change change : info.getChanges()) {
            if (handledChanges.contains(change)) {
                // Skip windows that we have already handled in the previous iteration.
                continue;
            }

            final Animation animation;
            if (change.getParent() != null
                    && handledChanges.contains(info.getChange(change.getParent()))) {
                // No-op if it will be covered by the changing parent window.
                animation = ActivityEmbeddingAnimationSpec.createNoopAnimation(change);
            } else if (Transitions.isClosingType(change.getMode())) {
                animation = mAnimationSpec.createChangeBoundsCloseAnimation(change);
            } else {
                animation = mAnimationSpec.createChangeBoundsOpenAnimation(change);
            }
            adapters.add(new ActivityEmbeddingAnimationAdapter(animation, change));
        }
        return adapters;
    }

    /**
     * Takes a screenshot of the given {@code screenshotChange} surface if WM Core hasn't taken one.
     * The screenshot leash should be attached to the {@code animationChange} surface which we will
     * animate later.
     */
    @Nullable
    private SurfaceControl getOrCreateScreenshot(@NonNull TransitionInfo.Change screenshotChange,
            @NonNull TransitionInfo.Change animationChange,
            @NonNull SurfaceControl.Transaction t) {
        final SurfaceControl screenshotLeash = screenshotChange.getSnapshot();
        if (screenshotLeash != null) {
            // If WM Core has already taken a screenshot, make sure it is reparented to the
            // animation leash.
            t.reparent(screenshotLeash, animationChange.getLeash());
            return screenshotLeash;
        }

        // If WM Core hasn't taken a screenshot, take a screenshot now.
        final Rect cropBounds = new Rect(screenshotChange.getStartAbsBounds());
        cropBounds.offsetTo(0, 0);
        return ScreenshotUtils.takeScreenshot(t, screenshotChange.getLeash(),
                animationChange.getLeash(), cropBounds, Integer.MAX_VALUE);
    }

    /** To provide an {@link Animation} based on the transition infos. */
    private interface AnimationProvider {
        Animation get(@NonNull TransitionInfo info, @NonNull TransitionInfo.Change change,
                @NonNull Rect animationBounds);
    }
}
