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

package com.android.wm.shell.transition;

import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;
import static android.window.TransitionInfo.FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.IBinder;
import android.util.ArrayMap;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;

import java.util.ArrayList;

/** The default handler that handles anything not already handled. */
public class DefaultTransitionHandler implements Transitions.TransitionHandler {
    private final TransactionPool mTransactionPool;
    private final ShellExecutor mMainExecutor;
    private final ShellExecutor mAnimExecutor;

    /** Keeps track of the currently-running animations associated with each transition. */
    private final ArrayMap<IBinder, ArrayList<Animator>> mAnimations = new ArrayMap<>();

    DefaultTransitionHandler(@NonNull TransactionPool transactionPool,
            @NonNull ShellExecutor mainExecutor, @NonNull ShellExecutor animExecutor) {
        mTransactionPool = transactionPool;
        mMainExecutor = mainExecutor;
        mAnimExecutor = animExecutor;
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        if (mAnimations.containsKey(transition)) {
            throw new IllegalStateException("Got a duplicate startAnimation call for "
                    + transition);
        }
        final ArrayList<Animator> animations = new ArrayList<>();
        mAnimations.put(transition, animations);
        final boolean isOpening = Transitions.isOpeningType(info.getType());

        final Runnable onAnimFinish = () -> {
            if (!animations.isEmpty()) return;
            mAnimations.remove(transition);
            finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */);
        };
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);

            // Don't animate anything with an animating parent
            if (change.getParent() != null) continue;

            final int mode = change.getMode();
            if (isOpening && (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT)) {
                if ((change.getFlags() & FLAG_STARTING_WINDOW_TRANSFER_RECIPIENT) != 0) {
                    // This received a transferred starting window, so don't animate
                    continue;
                }
                // fade in
                startExampleAnimation(
                        animations, change.getLeash(), true /* show */, onAnimFinish);
            } else if (!isOpening && (mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK)) {
                // fade out
                startExampleAnimation(
                        animations, change.getLeash(), false /* show */, onAnimFinish);
            }
        }
        t.apply();
        // run finish now in-case there are no animations
        onAnimFinish.run();
        return true;
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        return null;
    }

    // TODO(shell-transitions): real animations
    private void startExampleAnimation(@NonNull ArrayList<Animator> animations,
            @NonNull SurfaceControl leash, boolean show, @NonNull Runnable finishCallback) {
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
            mMainExecutor.execute(() -> {
                animations.remove(va);
                finishCallback.run();
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
        animations.add(va);
        mAnimExecutor.execute(va::start);
    }
}
