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

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
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
import java.util.function.BiFunction;

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
                createAnimationAdapters(info, startTransaction);
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
            @NonNull TransitionInfo info, @NonNull SurfaceControl.Transaction startTransaction) {
        for (TransitionInfo.Change change : info.getChanges()) {
            if (change.getMode() == TRANSIT_CHANGE
                    && !change.getStartAbsBounds().equals(change.getEndAbsBounds())) {
                return createChangeAnimationAdapters(info, startTransaction);
            }
        }
        if (Transitions.isClosingType(info.getType())) {
            return createCloseAnimationAdapters(info);
        }
        return createOpenAnimationAdapters(info);
    }

    @NonNull
    private List<ActivityEmbeddingAnimationAdapter> createOpenAnimationAdapters(
            @NonNull TransitionInfo info) {
        return createOpenCloseAnimationAdapters(info, true /* isOpening */,
                mAnimationSpec::loadOpenAnimation);
    }

    @NonNull
    private List<ActivityEmbeddingAnimationAdapter> createCloseAnimationAdapters(
            @NonNull TransitionInfo info) {
        return createOpenCloseAnimationAdapters(info, false /* isOpening */,
                mAnimationSpec::loadCloseAnimation);
    }

    /**
     * Creates {@link ActivityEmbeddingAnimationAdapter} for OPEN and CLOSE types of transition.
     * @param isOpening {@code true} for OPEN type, {@code false} for CLOSE type.
     */
    @NonNull
    private List<ActivityEmbeddingAnimationAdapter> createOpenCloseAnimationAdapters(
            @NonNull TransitionInfo info, boolean isOpening,
            @NonNull BiFunction<TransitionInfo.Change, Rect, Animation> animationProvider) {
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
                    change, animationProvider, openingWholeScreenBounds);
            if (isOpening) {
                adapter.overrideLayer(offsetLayer++);
            }
            adapters.add(adapter);
        }
        for (TransitionInfo.Change change : closingChanges) {
            final ActivityEmbeddingAnimationAdapter adapter = createOpenCloseAnimationAdapter(
                    change, animationProvider, closingWholeScreenBounds);
            if (!isOpening) {
                adapter.overrideLayer(offsetLayer++);
            }
            adapters.add(adapter);
        }
        return adapters;
    }

    @NonNull
    private ActivityEmbeddingAnimationAdapter createOpenCloseAnimationAdapter(
            @NonNull TransitionInfo.Change change,
            @NonNull BiFunction<TransitionInfo.Change, Rect, Animation> animationProvider,
            @NonNull Rect wholeAnimationBounds) {
        final Animation animation = animationProvider.apply(change, wholeAnimationBounds);
        return new ActivityEmbeddingAnimationAdapter(animation, change, change.getLeash(),
                wholeAnimationBounds);
    }

    @NonNull
    private List<ActivityEmbeddingAnimationAdapter> createChangeAnimationAdapters(
            @NonNull TransitionInfo info, @NonNull SurfaceControl.Transaction startTransaction) {
        final List<ActivityEmbeddingAnimationAdapter> adapters = new ArrayList<>();
        for (TransitionInfo.Change change : info.getChanges()) {
            if (change.getMode() == TRANSIT_CHANGE
                    && !change.getStartAbsBounds().equals(change.getEndAbsBounds())) {
                // This is the window with bounds change.
                final WindowContainerToken parentToken = change.getParent();
                final Rect parentBounds;
                if (parentToken != null) {
                    TransitionInfo.Change parentChange = info.getChange(parentToken);
                    parentBounds = parentChange != null
                            ? parentChange.getEndAbsBounds()
                            : change.getEndAbsBounds();
                } else {
                    parentBounds = change.getEndAbsBounds();
                }
                final Animation[] animations =
                        mAnimationSpec.createChangeBoundsChangeAnimations(change, parentBounds);
                // Adapter for the starting screenshot leash.
                final SurfaceControl screenshotLeash = createScreenshot(change, startTransaction);
                if (screenshotLeash != null) {
                    // The screenshot leash will be removed in SnapshotAdapter#onAnimationEnd
                    adapters.add(new ActivityEmbeddingAnimationAdapter.SnapshotAdapter(
                            animations[0], change, screenshotLeash));
                } else {
                    Log.e(TAG, "Failed to take screenshot for change=" + change);
                }
                // Adapter for the ending bounds changed leash.
                adapters.add(new ActivityEmbeddingAnimationAdapter.BoundsChangeAdapter(
                        animations[1], change));
                continue;
            }

            // These are the other windows that don't have bounds change in the same transition.
            final Animation animation;
            if (!TransitionInfo.isIndependent(change, info)) {
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

    /** Takes a screenshot of the given {@link TransitionInfo.Change} surface. */
    @Nullable
    private SurfaceControl createScreenshot(@NonNull TransitionInfo.Change change,
            @NonNull SurfaceControl.Transaction startTransaction) {
        final Rect cropBounds = new Rect(change.getStartAbsBounds());
        cropBounds.offsetTo(0, 0);
        return ScreenshotUtils.takeScreenshot(startTransaction, change.getLeash(), cropBounds,
                Integer.MAX_VALUE);
    }
}
