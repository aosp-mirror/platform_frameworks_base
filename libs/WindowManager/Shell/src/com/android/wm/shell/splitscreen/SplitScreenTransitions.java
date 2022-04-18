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

package com.android.wm.shell.splitscreen;

import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import static com.android.wm.shell.splitscreen.SplitScreen.stageTypeToString;
import static com.android.wm.shell.splitscreen.SplitScreenController.EXIT_REASON_DRAG_DIVIDER;
import static com.android.wm.shell.splitscreen.SplitScreenController.exitReasonToString;
import static com.android.wm.shell.transition.Transitions.TRANSIT_SPLIT_DISMISS;
import static com.android.wm.shell.transition.Transitions.TRANSIT_SPLIT_DISMISS_SNAP;
import static com.android.wm.shell.transition.Transitions.TRANSIT_SPLIT_SCREEN_OPEN_TO_SIDE;
import static com.android.wm.shell.transition.Transitions.TRANSIT_SPLIT_SCREEN_PAIR_OPEN;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.RemoteTransition;
import android.window.TransitionInfo;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;
import android.window.WindowContainerTransactionCallback;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.transition.OneShotRemoteHandler;
import com.android.wm.shell.transition.Transitions;

import java.util.ArrayList;

/** Manages transition animations for split-screen. */
class SplitScreenTransitions {
    private static final String TAG = "SplitScreenTransitions";

    private final TransactionPool mTransactionPool;
    private final Transitions mTransitions;
    private final Runnable mOnFinish;

    DismissTransition mPendingDismiss = null;
    IBinder mPendingEnter = null;
    IBinder mPendingRecent = null;

    private IBinder mAnimatingTransition = null;
    OneShotRemoteHandler mPendingRemoteHandler = null;
    private OneShotRemoteHandler mActiveRemoteHandler = null;

    private final Transitions.TransitionFinishCallback mRemoteFinishCB = this::onFinish;

    /** Keeps track of currently running animations */
    private final ArrayList<Animator> mAnimations = new ArrayList<>();
    private final StageCoordinator mStageCoordinator;

    private Transitions.TransitionFinishCallback mFinishCallback = null;
    private SurfaceControl.Transaction mFinishTransaction;

    SplitScreenTransitions(@NonNull TransactionPool pool, @NonNull Transitions transitions,
            @NonNull Runnable onFinishCallback, StageCoordinator stageCoordinator) {
        mTransactionPool = pool;
        mTransitions = transitions;
        mOnFinish = onFinishCallback;
        mStageCoordinator = stageCoordinator;
    }

    void playAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback,
            @NonNull WindowContainerToken mainRoot, @NonNull WindowContainerToken sideRoot,
            @NonNull WindowContainerToken topRoot) {
        mFinishCallback = finishCallback;
        mAnimatingTransition = transition;
        if (mPendingRemoteHandler != null) {
            mPendingRemoteHandler.startAnimation(transition, info, startTransaction,
                    finishTransaction, mRemoteFinishCB);
            mActiveRemoteHandler = mPendingRemoteHandler;
            mPendingRemoteHandler = null;
            return;
        }
        playInternalAnimation(transition, info, startTransaction, mainRoot, sideRoot, topRoot);
    }

    private void playInternalAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t, @NonNull WindowContainerToken mainRoot,
            @NonNull WindowContainerToken sideRoot, @NonNull WindowContainerToken topRoot) {
        mFinishTransaction = mTransactionPool.acquire();

        // Play some place-holder fade animations
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            final SurfaceControl leash = change.getLeash();
            final int mode = info.getChanges().get(i).getMode();

            if (mode == TRANSIT_CHANGE) {
                if (change.getParent() != null) {
                    // This is probably reparented, so we want the parent to be immediately visible
                    final TransitionInfo.Change parentChange = info.getChange(change.getParent());
                    t.show(parentChange.getLeash());
                    t.setAlpha(parentChange.getLeash(), 1.f);
                    // and then animate this layer outside the parent (since, for example, this is
                    // the home task animating from fullscreen to part-screen).
                    t.reparent(leash, info.getRootLeash());
                    t.setLayer(leash, info.getChanges().size() - i);
                    // build the finish reparent/reposition
                    mFinishTransaction.reparent(leash, parentChange.getLeash());
                    mFinishTransaction.setPosition(leash,
                            change.getEndRelOffset().x, change.getEndRelOffset().y);
                }
                // TODO(shell-transitions): screenshot here
                final Rect startBounds = new Rect(change.getStartAbsBounds());
                final Rect endBounds = new Rect(change.getEndAbsBounds());
                startBounds.offset(-info.getRootOffset().x, -info.getRootOffset().y);
                endBounds.offset(-info.getRootOffset().x, -info.getRootOffset().y);
                startExampleResizeAnimation(leash, startBounds, endBounds);
            }
            boolean isRootOrSplitSideRoot = change.getParent() == null
                    || topRoot.equals(change.getParent());
            // For enter or exit, we only want to animate the side roots but not the top-root.
            if (!isRootOrSplitSideRoot || topRoot.equals(change.getContainer())) {
                continue;
            }

            if (transition == mPendingEnter && (mainRoot.equals(change.getContainer())
                    || sideRoot.equals(change.getContainer()))) {
                t.setPosition(leash, change.getEndAbsBounds().left, change.getEndAbsBounds().top);
                t.setWindowCrop(leash, change.getEndAbsBounds().width(),
                        change.getEndAbsBounds().height());
            }
            boolean isOpening = isOpeningTransition(info);
            if (isOpening && (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT)) {
                // fade in
                startExampleAnimation(leash, true /* show */);
            } else if (!isOpening && (mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK)) {
                // fade out
                if (info.getType() == TRANSIT_SPLIT_DISMISS_SNAP) {
                    // Dismissing via snap-to-top/bottom means that the dismissed task is already
                    // not-visible (usually cropped to oblivion) so immediately set its alpha to 0
                    // and don't animate it so it doesn't pop-in when reparented.
                    t.setAlpha(leash, 0.f);
                } else {
                    startExampleAnimation(leash, false /* show */);
                }
            }
        }
        t.apply();
        onFinish(null /* wct */, null /* wctCB */);
    }

    /** Starts a transition to enter split with a remote transition animator. */
    IBinder startEnterTransition(@WindowManager.TransitionType int transitType,
            @NonNull WindowContainerTransaction wct, @Nullable RemoteTransition remoteTransition,
            @NonNull Transitions.TransitionHandler handler) {
        final IBinder transition = mTransitions.startTransition(transitType, wct, handler);
        mPendingEnter = transition;

        if (remoteTransition != null) {
            // Wrapping it for ease-of-use (OneShot handles all the binder linking/death stuff)
            mPendingRemoteHandler = new OneShotRemoteHandler(
                    mTransitions.getMainExecutor(), remoteTransition);
            mPendingRemoteHandler.setTransition(transition);
        }
        return transition;
    }

    /** Starts a transition to dismiss split. */
    IBinder startDismissTransition(WindowContainerTransaction wct,
            Transitions.TransitionHandler handler, @SplitScreen.StageType int dismissTop,
            @SplitScreenController.ExitReason int reason) {
        final int type = reason == EXIT_REASON_DRAG_DIVIDER
                ? TRANSIT_SPLIT_DISMISS_SNAP : TRANSIT_SPLIT_DISMISS;
        IBinder transition = mTransitions.startTransition(type, wct, handler);
        setDismissTransition(transition, dismissTop, reason);
        return transition;
    }

    /** Sets a transition to dismiss split. */
    void setDismissTransition(@NonNull IBinder transition, @SplitScreen.StageType int dismissTop,
            @SplitScreenController.ExitReason int reason) {
        mPendingDismiss = new DismissTransition(transition, reason, dismissTop);

        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "  splitTransition "
                        + " deduced Dismiss due to %s. toTop=%s",
                exitReasonToString(reason), stageTypeToString(dismissTop));
    }

    void setRecentTransition(@NonNull IBinder transition,
            @Nullable RemoteTransition remoteTransition) {
        mPendingRecent = transition;

        if (remoteTransition != null) {
            // Wrapping it for ease-of-use (OneShot handles all the binder linking/death stuff)
            mPendingRemoteHandler = new OneShotRemoteHandler(
                    mTransitions.getMainExecutor(), remoteTransition);
            mPendingRemoteHandler.setTransition(transition);
        }

        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "  splitTransition "
                        + " deduced Enter recent panel");
    }

    void mergeAnimation(IBinder transition, TransitionInfo info, SurfaceControl.Transaction t,
            IBinder mergeTarget, Transitions.TransitionFinishCallback finishCallback) {
        if (mergeTarget == mAnimatingTransition && mActiveRemoteHandler != null) {
            mActiveRemoteHandler.mergeAnimation(transition, info, t, mergeTarget, finishCallback);
        }
    }

    void onFinish(WindowContainerTransaction wct, WindowContainerTransactionCallback wctCB) {
        if (!mAnimations.isEmpty()) return;
        if (mAnimatingTransition == mPendingEnter) {
            mPendingEnter = null;
        }
        if (mPendingDismiss != null && mPendingDismiss.mTransition == mAnimatingTransition) {
            mPendingDismiss = null;
        }
        if (mAnimatingTransition == mPendingRecent) {
            // If the clean-up wct is null when finishing recent transition, it indicates it's
            // returning to home and thus no need to reorder tasks.
            final boolean returnToHome = wct == null;
            if (returnToHome) {
                wct = new WindowContainerTransaction();
            }
            mStageCoordinator.onRecentTransitionFinished(returnToHome, wct, mFinishTransaction);
            mPendingRecent = null;
        }
        mPendingRemoteHandler = null;
        mActiveRemoteHandler = null;
        mAnimatingTransition = null;

        mOnFinish.run();
        if (mFinishTransaction != null) {
            mFinishTransaction.apply();
            mTransactionPool.release(mFinishTransaction);
            mFinishTransaction = null;
        }
        if (mFinishCallback != null) {
            mFinishCallback.onTransitionFinished(wct /* wct */, wctCB /* wctCB */);
            mFinishCallback = null;
        }
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
                onFinish(null /* wct */, null /* wctCB */);
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
                onFinish(null /* wct */, null /* wctCB */);
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

    private boolean isOpeningTransition(TransitionInfo info) {
        return Transitions.isOpeningType(info.getType())
                || info.getType() == TRANSIT_SPLIT_SCREEN_OPEN_TO_SIDE
                || info.getType() == TRANSIT_SPLIT_SCREEN_PAIR_OPEN;
    }

    /** Bundled information of dismiss transition. */
    static class DismissTransition {
        IBinder mTransition;

        int mReason;

        @SplitScreen.StageType
        int mDismissTop;

        DismissTransition(IBinder transition, int reason, int dismissTop) {
            this.mTransition = transition;
            this.mReason = reason;
            this.mDismissTop = dismissTop;
        }
    }
}
