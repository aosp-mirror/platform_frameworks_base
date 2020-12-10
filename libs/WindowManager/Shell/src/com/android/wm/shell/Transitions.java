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

import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_BACK;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.util.ArrayMap;
import android.util.Slog;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.window.ITransitionPlayer;
import android.window.TransitionInfo;
import android.window.WindowOrganizer;

import androidx.annotation.BinderThread;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.ArrayList;

/** Plays transition animations */
public class Transitions {
    private static final String TAG = "ShellTransitions";

    /** Set to {@code true} to enable shell transitions. */
    public static final boolean ENABLE_SHELL_TRANSITIONS =
            SystemProperties.getBoolean("persist.debug.shell_transit", false);

    private final WindowOrganizer mOrganizer;
    private final TransactionPool mTransactionPool;
    private final ShellExecutor mMainExecutor;
    private final ShellExecutor mAnimExecutor;
    private final TransitionPlayerImpl mPlayerImpl;

    /** Keeps track of currently tracked transitions and all the animations associated with each */
    private final ArrayMap<IBinder, ArrayList<Animator>> mActiveTransitions = new ArrayMap<>();

    public Transitions(@NonNull WindowOrganizer organizer, @NonNull TransactionPool pool,
            @NonNull ShellExecutor mainExecutor, @NonNull ShellExecutor animExecutor) {
        mOrganizer = organizer;
        mTransactionPool = pool;
        mMainExecutor = mainExecutor;
        mAnimExecutor = animExecutor;
        mPlayerImpl = new TransitionPlayerImpl();
    }

    public void register(ShellTaskOrganizer taskOrganizer) {
        taskOrganizer.registerTransitionPlayer(mPlayerImpl);
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

    private static boolean isOpeningType(@WindowManager.TransitionType int type) {
        return type == TRANSIT_OPEN
                || type == TRANSIT_TO_FRONT
                || type == WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
    }

    private void onTransitionReady(@NonNull IBinder transitionToken, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction t) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "onTransitionReady %s: %s",
                transitionToken, info);
        // start task
        if (!mActiveTransitions.containsKey(transitionToken)) {
            Slog.e(TAG, "Got transitionReady for non-active transition " + transitionToken
                    + " expecting one of " + mActiveTransitions.keySet());
        }
        if (mActiveTransitions.get(transitionToken) != null) {
            throw new IllegalStateException("Got a duplicate onTransitionReady call for "
                    + transitionToken);
        }
        mActiveTransitions.put(transitionToken, new ArrayList<>());
        boolean isOpening = isOpeningType(info.getType());
        if (info.getRootLeash().isValid()) {
            t.show(info.getRootLeash());
        }
        // changes should be ordered top-to-bottom in z
        for (int i = info.getChanges().size() - 1; i >= 0; --i) {
            final TransitionInfo.Change change = info.getChanges().get(i);
            final SurfaceControl leash = change.getLeash();
            final int mode = info.getChanges().get(i).getMode();

            // Don't animate anything with an animating parent
            if (change.getParent() != null) {
                if (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT) {
                    t.show(leash);
                    t.setMatrix(leash, 1, 0, 0, 1);
                }
                continue;
            }

            t.reparent(leash, info.getRootLeash());
            t.setPosition(leash, change.getEndAbsBounds().left - info.getRootOffset().x,
                    change.getEndAbsBounds().top - info.getRootOffset().y);
            // Put all the OPEN/SHOW on top
            if (mode == TRANSIT_OPEN || mode == TRANSIT_TO_FRONT) {
                t.show(leash);
                t.setMatrix(leash, 1, 0, 0, 1);
                if (isOpening) {
                    // put on top and fade in
                    t.setLayer(leash, info.getChanges().size() - i);
                    t.setAlpha(leash, 0.f);
                    startExampleAnimation(transitionToken, leash, true /* show */);
                } else {
                    // put on bottom and leave it visible without fade
                    t.setLayer(leash, -i);
                    t.setAlpha(leash, 1.f);
                }
            } else if (mode == TRANSIT_CLOSE || mode == TRANSIT_TO_BACK) {
                if (isOpening) {
                    // put on bottom and leave visible without fade
                    t.setLayer(leash, -i);
                } else {
                    // put on top and fade out
                    t.setLayer(leash, info.getChanges().size() - i);
                    startExampleAnimation(transitionToken, leash, false /* show */);
                }
            } else {
                t.setLayer(leash, info.getChanges().size() - i);
            }
        }
        t.apply();
        onFinish(transitionToken);
    }

    private void onFinish(IBinder transition) {
        if (!mActiveTransitions.get(transition).isEmpty()) return;
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS,
                "Transition animations finished, notifying core %s", transition);
        mActiveTransitions.remove(transition);
        mOrganizer.finishTransition(transition, null, null);
    }

    private void requestStartTransition(int type, @NonNull IBinder transitionToken) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TRANSITIONS, "Transition requested: type=%d %s",
                type, transitionToken);

        if (mActiveTransitions.containsKey(transitionToken)) {
            throw new RuntimeException("Transition already started " + transitionToken);
        }
        IBinder transition = mOrganizer.startTransition(type, transitionToken, null /* wct */);
        mActiveTransitions.put(transition, null);
    }

    @BinderThread
    private class TransitionPlayerImpl extends ITransitionPlayer.Stub {
        @Override
        public void onTransitionReady(IBinder iBinder, TransitionInfo transitionInfo,
                SurfaceControl.Transaction transaction) throws RemoteException {
            mMainExecutor.execute(() -> {
                Transitions.this.onTransitionReady(iBinder, transitionInfo, transaction);
            });
        }

        @Override
        public void requestStartTransition(int i, IBinder iBinder) throws RemoteException {
            mMainExecutor.execute(() -> {
                Transitions.this.requestStartTransition(i, iBinder);
            });
        }
    }
}
