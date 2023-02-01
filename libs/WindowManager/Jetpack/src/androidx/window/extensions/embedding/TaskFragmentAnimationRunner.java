/*
 * Copyright (C) 2021 The Android Open Source Project
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

package androidx.window.extensions.embedding;

import static android.os.Process.THREAD_PRIORITY_DISPLAY;
import static android.view.RemoteAnimationTarget.MODE_CLOSING;
import static android.view.WindowManager.TRANSIT_OLD_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_TASK_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_CHANGE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_TASK_OPEN;
import static android.view.WindowManagerPolicyConstants.TYPE_LAYER_OFFSET;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.util.Log;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.animation.Animation;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/** To run the TaskFragment animations. */
class TaskFragmentAnimationRunner extends IRemoteAnimationRunner.Stub {

    private static final String TAG = "TaskFragAnimationRunner";
    private final Handler mHandler;
    private final TaskFragmentAnimationSpec mAnimationSpec;

    TaskFragmentAnimationRunner() {
        HandlerThread animationThread = new HandlerThread(
                "androidx.window.extensions.embedding", THREAD_PRIORITY_DISPLAY);
        animationThread.start();
        mHandler = animationThread.getThreadHandler();
        mAnimationSpec = new TaskFragmentAnimationSpec(mHandler);
    }

    @Nullable
    private Animator mAnimator;

    @Override
    public void onAnimationStart(@WindowManager.TransitionOldType int transit,
            @NonNull RemoteAnimationTarget[] apps,
            @NonNull RemoteAnimationTarget[] wallpapers,
            @NonNull RemoteAnimationTarget[] nonApps,
            @NonNull IRemoteAnimationFinishedCallback finishedCallback) {
        if (wallpapers.length != 0 || nonApps.length != 0) {
            throw new IllegalArgumentException("TaskFragment shouldn't handle animation with"
                    + "wallpaper or non-app windows.");
        }
        if (TaskFragmentAnimationController.DEBUG) {
            Log.v(TAG, "onAnimationStart transit=" + transit);
        }
        mHandler.post(() -> startAnimation(transit, apps, finishedCallback));
    }

    @Override
    public void onAnimationCancelled(boolean isKeyguardOccluded) {
        if (TaskFragmentAnimationController.DEBUG) {
            Log.v(TAG, "onAnimationCancelled: isKeyguardOccluded=" + isKeyguardOccluded);
        }
        mHandler.post(this::cancelAnimation);
    }

    /** Creates and starts animation. */
    private void startAnimation(@WindowManager.TransitionOldType int transit,
            @NonNull RemoteAnimationTarget[] targets,
            @NonNull IRemoteAnimationFinishedCallback finishedCallback) {
        if (mAnimator != null) {
            Log.w(TAG, "start new animation when the previous one is not finished yet.");
            mAnimator.cancel();
        }
        mAnimator = createAnimator(transit, targets, finishedCallback);
        mAnimator.start();
    }

    /** Cancels animation. */
    private void cancelAnimation() {
        if (mAnimator == null) {
            return;
        }
        mAnimator.cancel();
        mAnimator = null;
    }

    /** Creates the animator given the transition type and windows. */
    @NonNull
    private Animator createAnimator(@WindowManager.TransitionOldType int transit,
            @NonNull RemoteAnimationTarget[] targets,
            @NonNull IRemoteAnimationFinishedCallback finishedCallback) {
        final List<TaskFragmentAnimationAdapter> adapters =
                createAnimationAdapters(transit, targets);
        long duration = 0;
        for (TaskFragmentAnimationAdapter adapter : adapters) {
            duration = Math.max(duration, adapter.getDurationHint());
        }
        final ValueAnimator animator = ValueAnimator.ofFloat(0, 1);
        animator.setDuration(duration);
        animator.addUpdateListener((anim) -> {
            // Update all adapters in the same transaction.
            final SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            for (TaskFragmentAnimationAdapter adapter : adapters) {
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
                for (TaskFragmentAnimationAdapter adapter : adapters) {
                    adapter.onAnimationEnd(t);
                }
                t.apply();

                try {
                    finishedCallback.onAnimationFinished();
                } catch (RemoteException e) {
                    e.rethrowFromSystemServer();
                }
                mAnimator = null;
            }

            @Override
            public void onAnimationCancel(Animator animation) {}

            @Override
            public void onAnimationRepeat(Animator animation) {}
        });
        return animator;
    }

    /** List of {@link TaskFragmentAnimationAdapter} to handle animations on all window targets. */
    @NonNull
    private List<TaskFragmentAnimationAdapter> createAnimationAdapters(
            @WindowManager.TransitionOldType int transit,
            @NonNull RemoteAnimationTarget[] targets) {
        switch (transit) {
            case TRANSIT_OLD_ACTIVITY_OPEN:
            case TRANSIT_OLD_TASK_FRAGMENT_OPEN:
            case TRANSIT_OLD_TASK_OPEN:
                return createOpenAnimationAdapters(targets);
            case TRANSIT_OLD_ACTIVITY_CLOSE:
            case TRANSIT_OLD_TASK_FRAGMENT_CLOSE:
            case TRANSIT_OLD_TASK_CLOSE:
                return createCloseAnimationAdapters(targets);
            case TRANSIT_OLD_TASK_FRAGMENT_CHANGE:
                return createChangeAnimationAdapters(targets);
            default:
                throw new IllegalArgumentException("Unhandled transit type=" + transit);
        }
    }

    @NonNull
    private List<TaskFragmentAnimationAdapter> createOpenAnimationAdapters(
            @NonNull RemoteAnimationTarget[] targets) {
        return createOpenCloseAnimationAdapters(targets, true /* isOpening */,
                mAnimationSpec::loadOpenAnimation);
    }

    @NonNull
    private List<TaskFragmentAnimationAdapter> createCloseAnimationAdapters(
            @NonNull RemoteAnimationTarget[] targets) {
        return createOpenCloseAnimationAdapters(targets, false /* isOpening */,
                mAnimationSpec::loadCloseAnimation);
    }

    /**
     * Creates {@link TaskFragmentAnimationAdapter} for OPEN and CLOSE types of transition.
     * @param isOpening {@code true} for OPEN type, {@code false} for CLOSE type.
     */
    @NonNull
    private List<TaskFragmentAnimationAdapter> createOpenCloseAnimationAdapters(
            @NonNull RemoteAnimationTarget[] targets, boolean isOpening,
            @NonNull BiFunction<RemoteAnimationTarget, Rect, Animation> animationProvider) {
        // We need to know if the target window is only a partial of the whole animation screen.
        // If so, we will need to adjust it to make the whole animation screen looks like one.
        final List<RemoteAnimationTarget> openingTargets = new ArrayList<>();
        final List<RemoteAnimationTarget> closingTargets = new ArrayList<>();
        final Rect openingWholeScreenBounds = new Rect();
        final Rect closingWholeScreenBounds = new Rect();
        for (RemoteAnimationTarget target : targets) {
            if (target.mode != MODE_CLOSING) {
                openingTargets.add(target);
                openingWholeScreenBounds.union(target.screenSpaceBounds);
            } else {
                closingTargets.add(target);
                closingWholeScreenBounds.union(target.screenSpaceBounds);
            }
        }

        // For OPEN transition, open windows should be above close windows.
        // For CLOSE transition, open windows should be below close windows.
        int offsetLayer = TYPE_LAYER_OFFSET;
        final List<TaskFragmentAnimationAdapter> adapters = new ArrayList<>();
        for (RemoteAnimationTarget target : openingTargets) {
            final TaskFragmentAnimationAdapter adapter = createOpenCloseAnimationAdapter(target,
                    animationProvider, openingWholeScreenBounds);
            if (isOpening) {
                adapter.overrideLayer(offsetLayer++);
            }
            adapters.add(adapter);
        }
        for (RemoteAnimationTarget target : closingTargets) {
            final TaskFragmentAnimationAdapter adapter = createOpenCloseAnimationAdapter(target,
                    animationProvider, closingWholeScreenBounds);
            if (!isOpening) {
                adapter.overrideLayer(offsetLayer++);
            }
            adapters.add(adapter);
        }
        return adapters;
    }

    @NonNull
    private TaskFragmentAnimationAdapter createOpenCloseAnimationAdapter(
            @NonNull RemoteAnimationTarget target,
            @NonNull BiFunction<RemoteAnimationTarget, Rect, Animation> animationProvider,
            @NonNull Rect wholeAnimationBounds) {
        final Animation animation = animationProvider.apply(target, wholeAnimationBounds);
        return new TaskFragmentAnimationAdapter(animation, target, target.leash,
                wholeAnimationBounds);
    }

    @NonNull
    private List<TaskFragmentAnimationAdapter> createChangeAnimationAdapters(
            @NonNull RemoteAnimationTarget[] targets) {
        final List<TaskFragmentAnimationAdapter> adapters = new ArrayList<>();
        for (RemoteAnimationTarget target : targets) {
            if (target.startBounds != null) {
                // This is the target with bounds change.
                final Animation[] animations =
                        mAnimationSpec.createChangeBoundsChangeAnimations(target);
                // Adapter for the starting snapshot leash.
                adapters.add(new TaskFragmentAnimationAdapter.SnapshotAdapter(
                        animations[0], target));
                // Adapter for the ending bounds changed leash.
                adapters.add(new TaskFragmentAnimationAdapter.BoundsChangeAdapter(
                        animations[1], target));
                continue;
            }

            // These are the other targets that don't have bounds change in the same transition.
            final Animation animation;
            if (target.hasAnimatingParent) {
                // No-op if it will be covered by the changing parent window.
                animation = TaskFragmentAnimationSpec.createNoopAnimation(target);
            } else if (target.mode == MODE_CLOSING) {
                animation = mAnimationSpec.createChangeBoundsCloseAnimation(target);
            } else {
                animation = mAnimationSpec.createChangeBoundsOpenAnimation(target);
            }
            adapters.add(new TaskFragmentAnimationAdapter(animation, target));
        }
        return adapters;
    }
}
