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

package com.android.wm.shell;

import static android.window.TransitionInfo.TRANSIT_CLOSE;
import static android.window.TransitionInfo.TRANSIT_HIDE;
import static android.window.TransitionInfo.TRANSIT_OPEN;
import static android.window.TransitionInfo.TRANSIT_SHOW;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.annotation.MainThread;
import android.annotation.NonNull;
import android.os.IBinder;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.ITransitionPlayer;
import android.window.TransitionInfo;
import android.window.WindowOrganizer;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.ArrayList;

/** Plays transition animations */
public class Transitions extends ITransitionPlayer.Stub {
    private static final String TAG = "ShellTransitions";

    /** Set to {@code true} to enable shell transitions. */
    public static final boolean ENABLE_SHELL_TRANSITIONS =
            SystemProperties.getBoolean("persist.debug.shell_transit", false);

    private final WindowOrganizer mOrganizer;
    private final TransactionPool mTransactionPool;
    private final ShellExecutor mMainExecutor;
    private final ShellExecutor mAnimExecutor;

    /** Keeps track of currently tracked transitions and all the animations associated with each */
    private final ArrayMap<IBinder, ArrayList<Animator>> mActiveTransitions = new ArrayMap<>();

    Transitions(@NonNull WindowOrganizer organizer, @NonNull TransactionPool pool,
            @NonNull ShellExecutor mainExecutor, @NonNull ShellExecutor animExecutor) {
        mOrganizer = organizer;
        mTransactionPool = pool;
        mMainExecutor = mainExecutor;
        mAnimExecutor = animExecutor;
    }

    // TODO(shell-transitions): real animations
    private void startExampleAnimation(@NonNull IBinder transition, @NonNull SurfaceControl leash,
            boolean show) {
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
                mActiveTransitions.get(transition).remove(va);
                onFinish(transition);
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
        mActiveTransitions.get(transition).add(va);
        mAnimExecutor.execute(va::start);
    }

    private static boolean isOpeningType(@WindowManager.TransitionOldType int legacyType) {
        // TODO(shell-transitions): consider providing and using z-order vs the global type for
        //                          this determination.
        return legacyType == WindowManager.TRANSIT_OLD_TASK_OPEN
                || legacyType == WindowManager.TRANSIT_OLD_TASK_TO_FRONT
                || legacyType == WindowManager.TRANSIT_OLD_TASK_OPEN_BEHIND
                || legacyType == WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY;
    }

    @Override
    public void onTransitionReady(@NonNull IBinder transitionToken, TransitionInfo info,
            @NonNull SurfaceControl.Transaction t) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "onTransitionReady %s: %s",
                transitionToken, info);
        // start task
        mMainExecutor.execute(() -> {
            if (!mActiveTransitions.containsKey(transitionToken)) {
                Slog.e(TAG, "Got transitionReady for non-active transition " + transitionToken
                        + " expecting one of " + mActiveTransitions.keySet());
            }
            if (mActiveTransitions.get(transitionToken) != null) {
                throw new IllegalStateException("Got a duplicate onTransitionReady call for "
                        + transitionToken);
            }
            mActiveTransitions.put(transitionToken, new ArrayList<>());
            for (int i = 0; i < info.getChanges().size(); ++i) {
                final SurfaceControl leash = info.getChanges().get(i).getLeash();
                final int mode = info.getChanges().get(i).getMode();
                if (mode == TRANSIT_OPEN || mode == TRANSIT_SHOW) {
                    t.show(leash);
                    t.setMatrix(leash, 1, 0, 0, 1);
                    if (isOpeningType(info.getType())) {
                        t.setAlpha(leash, 0.f);
                        startExampleAnimation(transitionToken, leash, true /* show */);
                    } else {
                        t.setAlpha(leash, 1.f);
                    }
                } else if (mode == TRANSIT_CLOSE || mode == TRANSIT_HIDE) {
                    if (!isOpeningType(info.getType())) {
                        startExampleAnimation(transitionToken, leash, false /* show */);
                    }
                }
            }
            t.apply();
            onFinish(transitionToken);
        });
    }

    @MainThread
    private void onFinish(IBinder transition) {
        if (!mActiveTransitions.get(transition).isEmpty()) return;
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                "Transition animations finished, notifying core %s", transition);
        mActiveTransitions.remove(transition);
        mOrganizer.finishTransition(transition, null, null);
    }

    @Override
    public void requestStartTransition(int type, @NonNull IBinder transitionToken) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Transition requested: type=%d %s",
                type, transitionToken);
        mMainExecutor.execute(() -> {
            if (mActiveTransitions.containsKey(transitionToken)) {
                throw new RuntimeException("Transition already started " + transitionToken);
            }
            IBinder transition = mOrganizer.startTransition(type, transitionToken, null /* wct */);
            mActiveTransitions.put(transition, null);
        });
    }
}
