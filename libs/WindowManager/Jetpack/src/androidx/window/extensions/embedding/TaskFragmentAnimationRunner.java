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

import static android.view.RemoteAnimationTarget.MODE_CLOSING;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_CHANGE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_OPEN;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
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

/** To run the TaskFragment animations. */
class TaskFragmentAnimationRunner extends IRemoteAnimationRunner.Stub {

    private static final String TAG = "TaskFragAnimationRunner";
    private final Handler mHandler = new Handler(Looper.myLooper());
    private final TaskFragmentAnimationSpec mAnimationSpec;

    TaskFragmentAnimationRunner() {
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
    public void onAnimationCancelled() {
        if (TaskFragmentAnimationController.DEBUG) {
            Log.v(TAG, "onAnimationCancelled");
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
    private List<TaskFragmentAnimationAdapter> createAnimationAdapters(
            @WindowManager.TransitionOldType int transit,
            @NonNull RemoteAnimationTarget[] targets) {
        switch (transit) {
            case TRANSIT_OLD_TASK_FRAGMENT_OPEN:
                return createOpenAnimationAdapters(targets);
            case TRANSIT_OLD_TASK_FRAGMENT_CLOSE:
                return createCloseAnimationAdapters(targets);
            case TRANSIT_OLD_TASK_FRAGMENT_CHANGE:
                return createChangeAnimationAdapters(targets);
            default:
                throw new IllegalArgumentException("Unhandled transit type=" + transit);
        }
    }

    private List<TaskFragmentAnimationAdapter> createOpenAnimationAdapters(
            @NonNull RemoteAnimationTarget[] targets) {
        final List<TaskFragmentAnimationAdapter> adapters = new ArrayList<>();
        for (RemoteAnimationTarget target : targets) {
            final Animation animation =
                    mAnimationSpec.loadOpenAnimation(target.mode != MODE_CLOSING /* isEnter */);
            adapters.add(new TaskFragmentAnimationAdapter(animation, target));
        }
        return adapters;
    }

    private List<TaskFragmentAnimationAdapter> createCloseAnimationAdapters(
            @NonNull RemoteAnimationTarget[] targets) {
        final List<TaskFragmentAnimationAdapter> adapters = new ArrayList<>();
        for (RemoteAnimationTarget target : targets) {
            final Animation animation =
                    mAnimationSpec.loadCloseAnimation(target.mode != MODE_CLOSING /* isEnter */);
            adapters.add(new TaskFragmentAnimationAdapter(animation, target));
        }
        return adapters;
    }

    private List<TaskFragmentAnimationAdapter> createChangeAnimationAdapters(
            @NonNull RemoteAnimationTarget[] targets) {
        final List<TaskFragmentAnimationAdapter> adapters = new ArrayList<>();
        for (RemoteAnimationTarget target : targets) {
            if (target.startBounds != null) {
                final Animation[] animations =
                        mAnimationSpec.createChangeBoundsChangeAnimations(target);
                adapters.add(new TaskFragmentAnimationAdapter(animations[0], target,
                        target.startLeash, false /* sizeChanged */));
                adapters.add(new TaskFragmentAnimationAdapter(animations[1], target,
                        target.leash, true /* sizeChanged */));
                continue;
            }

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
