/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell.legacysplitscreen;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FIRST_CUSTOM;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.transition.Transitions;

import java.util.ArrayList;

/** Plays transition animations for split-screen */
public class LegacySplitScreenTransitions implements Transitions.TransitionHandler {
    private static final String TAG = "SplitScreenTransitions";

    public static final int TRANSIT_SPLIT_DISMISS_SNAP = TRANSIT_FIRST_CUSTOM + 10;

    private final TransactionPool mTransactionPool;
    private final Transitions mTransitions;
    private final LegacySplitScreenController mSplitScreen;
    private final LegacySplitScreenTaskListener mListener;

    private IBinder mPendingDismiss = null;
    private boolean mDismissFromSnap = false;
    private IBinder mPendingEnter = null;
    private IBinder mAnimatingTransition = null;

    /** Keeps track of currently running animations */
    private final ArrayList<Animator> mAnimations = new ArrayList<>();

    private Transitions.TransitionFinishCallback mFinishCallback = null;
    private SurfaceControl.Transaction mFinishTransaction;

    LegacySplitScreenTransitions(@NonNull TransactionPool pool, @NonNull Transitions transitions,
            @NonNull LegacySplitScreenController splitScreen,
            @NonNull LegacySplitScreenTaskListener listener) {
        mTransactionPool = pool;
        mTransitions = transitions;
        mSplitScreen = splitScreen;
        mListener = listener;
    }

    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @Nullable TransitionRequestInfo request) {
        WindowContainerTransaction out = null;
        final ActivityManager.RunningTaskInfo triggerTask = request.getTriggerTask();
        final @WindowManager.TransitionType int type = request.getType();
        if (mSplitScreen.isDividerVisible()) {
            // try to handle everything while in split-screen
            out = new WindowContainerTransaction();
            if (triggerTask != null) {
                final boolean shouldDismiss =
                        // if we close the primary-docked task, then leave split-screen since there
                        // is nothing behind it.
                        ((type == TRANSIT_CLOSE || type == TRANSIT_TO_BACK)
                                && triggerTask.parentTaskId == mListener.mPrimary.taskId)
                        // if an activity that is not supported in multi window mode is launched,
                        // we also need to leave split-screen.
                        || ((type == TRANSIT_OPEN || type == TRANSIT_TO_FRONT)
                                && !triggerTask.supportsMultiWindow);
                // In both cases, dismiss the primary
                if (shouldDismiss) {
                    WindowManagerProxy.buildDismissSplit(out, mListener,
                            mSplitScreen.getSplitLayout(), true /* dismiss */);
                    if (type == TRANSIT_OPEN || type == TRANSIT_TO_FRONT) {
                        out.reorder(triggerTask.token, true /* onTop */);
                    }
                    mPendingDismiss = transition;
                }
            }
        } else if (triggerTask != null) {
            // Not in split mode, so look for an open with a trigger task.
            if ((type == TRANSIT_OPEN || type == TRANSIT_TO_FRONT)
                    && triggerTask.configuration.windowConfiguration.getWindowingMode()
                        == WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY) {
                out = new WindowContainerTransaction();
                mSplitScreen.prepareEnterSplitTransition(out);
                mPendingEnter = transition;
            }
        }
        return out;
    }

    // TODO(shell-transitions): real animations
    private void startExampleAnimation(@NonNull SurfaceControl leash, boolean show) {
        final float end = show ? 1.f : 0.f;
        final float start = 1.f - end;
        final SurfaceControl.Transaction transaction = mTransactionPool.acquire();
        final ValueAnimator va = ValueAnimator.ofFloat(start, end);
        va.setDuration(500);
        va.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            transaction.setAlpha(leash, start * (1.f - fraction) + end * fraction);
            transaction.apply();
        });
        final Runnable finisher = () -> {
            transaction.setAlpha(leash, end);
            transaction.apply();
            mTransactionPool.release(transaction);
            mTransitions.getMainExecutor().execute(() -> {
                mAnimations.remove(va);
                onFinish();
            });
        };
        va.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) { }

            @Override
            public void onAnimationEnd(Animator animation) {
                finisher.run();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                finisher.run();
            }

            @Override
            public void onAnimationRepeat(Animator animation) { }
        });
        mAnimations.add(va);
        mTransitions.getAnimExecutor().execute(va::start);
    }

    // TODO(shell-transitions): real animations
    private void startExampleResizeAnimation(@NonNull SurfaceControl leash,
            @NonNull Rect startBounds, @NonNull Rect endBounds) {
        final SurfaceControl.Transaction transaction = mTransactionPool.acquire();
        final ValueAnimator va = ValueAnimator.ofFloat(0.f, 1.f);
        va.setDuration(500);
        va.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            transaction.setWindowCrop(leash,
                    (int) (startBounds.width() * (1.f - fraction) + endBounds.width() * fraction),
                    (int) (startBounds.height() * (1.f - fraction)
                            + endBounds.height() * fraction));
            transaction.setPosition(leash,
                    startBounds.left * (1.f - fraction) + endBounds.left * fraction,
                    startBounds.top * (1.f - fraction) + endBounds.top * fraction);
            transaction.apply();
        });
        final Runnable finisher = () -> {
            transaction.setWindowCrop(leash, 0, 0);
            transaction.setPosition(leash, endBounds.left, endBounds.top);
            transaction.apply();
            mTransactionPool.release(transaction);
            mTransitions.getMainExecutor().execute(() -> {
                mAnimations.remove(va);
                onFinish();
            });
        };
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                finisher.run();
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                finisher.run();
            }
        });
        mAnimations.add(va);
        mTransitions.getAnimExecutor().execute(va::start);
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        if (transition != mPendingDismiss && transition != mPendingEnter) {
            // If we're not in split-mode, just abort
            if (!mSplitScreen.isDividerVisible()) return false;
            // Check to see if HOME is involved
            for (int i = info.getChanges().size() - 1; i >= 0; --i) {
                final TransitionInfo.Change change = info.getChanges().get(i);
                if (change.getTaskInfo() == null
                        || change.getTaskInfo().getActivityType() != ACTIVITY_TYPE_HOME) continue;
                if (change.getMode() == TRANSIT_OPEN || change.getMode() == TRANSIT_TO_FRONT) {
                    mSplitScreen.ensureMinimizedSplit();
                } else if (change.getMode() == TRANSIT_CLOSE
                        || change.getMode() == TRANSIT_TO_BACK) {
                    mSplitScreen.ensureNormalSplit();
                }
            }
            // Use normal animations.
            return false;
        }

        mFinishCallback = finishCallback;
        mFinishTransaction = mTransactionPool.acquire();
        mAnimatingTransition = transition;

        // Play fade animations
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            final SurfaceControl leash = change.getLeash();
            final int mode = info.getChanges().get(i).getMode();

            if (mode == TRANSIT_CHANGE) {
                if (change.getParent() != null) {
                    // This is probably reparented, so we want the parent to be immediately visible
                    final TransitionInfo.Change parentChange = info.getChange(change.getParent());
                    startTransaction.show(parentChange.getLeash());
                    startTransaction.setAlpha(parentChange.getLeash(), 1.f);
                    // and then animate this layer outside the parent (since, for example, this is
                    // the home task animating from fullscreen to part-screen).
                    startTransaction.reparent(leash, info.getRootLeash());
                    startTransaction.setLayer(leash, info.getChanges().size() - i);
                    // build the finish reparent/reposition
                    mFinishTransaction.reparent(leash, parentChange.getLeash());
                    mFinishTransaction.setPosition(leash,
                            change.getEndRelOffset().x, change.getEndRelOffset().y);
                }
                // TODO(shell-transitions): screenshot here
                final Rect startBounds = new Rect(change.getStartAbsBounds());
                final boolean isHome = change.getTaskInfo() != null
                        && change.getTaskInfo().getActivityType() == ACTIVITY_TYPE_HOME;
                if (mPendingDismiss == transition && mDismissFromSnap && !isHome) {
                    // Home is special since it doesn't move during fling. Everything else, though,
                    // when dismissing from snap, the top/left is at 0,0.
                    startBounds.offsetTo(0, 0);
                }
                final Rect endBounds = new Rect(change.getEndAbsBounds());
                startBounds.offset(-info.getRootOffset().x, -info.getRootOffset().y);
                endBounds.offset(-info.getRootOffset().x, -info.getRootOffset().y);
                startExampleResizeAnimation(leash, startBounds, endBounds);
            }
            if (change.getParent() != null) {
                continue;
            }

            if (transition == mPendingEnter
                    && mListener.mPrimary.token.equals(change.getContainer())
                    || mListener.mSecondary.token.equals(change.getContainer())) {
                startTransaction.setWindowCrop(leash, change.getStartAbsBounds().width(),
                        change.getStartAbsBounds().height());
                if (mListener.mPrimary.token.equals(change.getContainer())) {
                    // Move layer to top since we want it above the oversized home task during
                    // animation even though home task is on top in hierarchy.
                    startTransaction.setLayer(leash, info.getChanges().size() + 1);
                }
            }
            boolean isOpening = Transitions.isOpeningType(info.getType());
            if (isOpening && (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT)) {
                // fade in
                startExampleAnimation(leash, true /* show */);
            } else if (!isOpening && (mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK)) {
                // fade out
                if (transition == mPendingDismiss && mDismissFromSnap) {
                    // Dismissing via snap-to-top/bottom means that the dismissed task is already
                    // not-visible (usually cropped to oblivion) so immediately set its alpha to 0
                    // and don't animate it so it doesn't pop-in when reparented.
                    startTransaction.setAlpha(leash, 0.f);
                } else {
                    startExampleAnimation(leash, false /* show */);
                }
            }
        }
        if (transition == mPendingEnter) {
            // If entering, check if we should enter into minimized or normal split
            boolean homeIsVisible = false;
            for (int i = info.getChanges().size() - 1; i >= 0; --i) {
                final TransitionInfo.Change change = info.getChanges().get(i);
                if (change.getTaskInfo() == null
                        || change.getTaskInfo().getActivityType() != ACTIVITY_TYPE_HOME) {
                    continue;
                }
                homeIsVisible = change.getMode() == TRANSIT_OPEN
                        || change.getMode() == TRANSIT_TO_FRONT
                        || change.getMode() == TRANSIT_CHANGE;
                break;
            }
            mSplitScreen.finishEnterSplitTransition(homeIsVisible);
        }
        startTransaction.apply();
        onFinish();
        return true;
    }

    @ExternalThread
    void dismissSplit(LegacySplitScreenTaskListener tiles, LegacySplitDisplayLayout layout,
            boolean dismissOrMaximize, boolean snapped) {
        final WindowContainerTransaction wct = new WindowContainerTransaction();
        WindowManagerProxy.buildDismissSplit(wct, tiles, layout, dismissOrMaximize);
        mTransitions.getMainExecutor().execute(() -> {
            mDismissFromSnap = snapped;
            mPendingDismiss = mTransitions.startTransition(TRANSIT_SPLIT_DISMISS_SNAP, wct, this);
        });
    }

    private void onFinish() {
        if (!mAnimations.isEmpty()) return;
        mFinishTransaction.apply();
        mTransactionPool.release(mFinishTransaction);
        mFinishTransaction = null;
        mFinishCallback.onTransitionFinished(null /* wct */, null /* wctCB */);
        mFinishCallback = null;
        if (mAnimatingTransition == mPendingEnter) {
            mPendingEnter = null;
        }
        if (mAnimatingTransition == mPendingDismiss) {
            mSplitScreen.onDismissSplit();
            mPendingDismiss = null;
        }
        mDismissFromSnap = false;
        mAnimatingTransition = null;
    }
}
