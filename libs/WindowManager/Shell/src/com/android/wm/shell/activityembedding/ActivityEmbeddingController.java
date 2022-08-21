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

import static android.window.TransitionInfo.FLAG_IS_EMBEDDED;

import static java.util.Objects.requireNonNull;

import android.content.Context;
import android.os.IBinder;
import android.util.ArrayMap;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.transition.Transitions;

/**
 * Responsible for handling ActivityEmbedding related transitions.
 */
public class ActivityEmbeddingController implements Transitions.TransitionHandler {

    private final Context mContext;
    @VisibleForTesting
    final Transitions mTransitions;
    @VisibleForTesting
    final ActivityEmbeddingAnimationRunner mAnimationRunner;

    /**
     * Keeps track of the currently-running transition callback associated with each transition
     * token.
     */
    private final ArrayMap<IBinder, Transitions.TransitionFinishCallback> mTransitionCallbacks =
            new ArrayMap<>();

    private ActivityEmbeddingController(@NonNull Context context, @NonNull ShellInit shellInit,
            @NonNull Transitions transitions) {
        mContext = requireNonNull(context);
        mTransitions = requireNonNull(transitions);
        mAnimationRunner = new ActivityEmbeddingAnimationRunner(context, this);

        shellInit.addInitCallback(this::onInit, this);
    }

    /**
     * Creates {@link ActivityEmbeddingController}, returns {@code null} if the feature is not
     * supported.
     */
    @Nullable
    public static ActivityEmbeddingController create(@NonNull Context context,
            @NonNull ShellInit shellInit, @NonNull Transitions transitions) {
        return Transitions.ENABLE_SHELL_TRANSITIONS
                ? new ActivityEmbeddingController(context, shellInit, transitions)
                : null;
    }

    /** Registers to handle transitions. */
    public void onInit() {
        mTransitions.addHandler(this);
    }

    @Override
    public boolean startAnimation(@NonNull IBinder transition, @NonNull TransitionInfo info,
            @NonNull SurfaceControl.Transaction startTransaction,
            @NonNull SurfaceControl.Transaction finishTransaction,
            @NonNull Transitions.TransitionFinishCallback finishCallback) {
        // TODO(b/207070762) Handle AE animation as a part of other transitions.
        // Only handle the transition if all containers are embedded.
        for (TransitionInfo.Change change : info.getChanges()) {
            if (!isEmbedded(change)) {
                return false;
            }
        }

        // Start ActivityEmbedding animation.
        mTransitionCallbacks.put(transition, finishCallback);
        mAnimationRunner.startAnimation(transition, info, startTransaction, finishTransaction);
        return true;
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        return null;
    }

    @Override
    public void setAnimScaleSetting(float scale) {
        mAnimationRunner.setAnimScaleSetting(scale);
    }

    /** Called when the animation is finished. */
    void onAnimationFinished(@NonNull IBinder transition) {
        final Transitions.TransitionFinishCallback callback =
                mTransitionCallbacks.remove(transition);
        if (callback == null) {
            throw new IllegalStateException("No finish callback found");
        }
        callback.onTransitionFinished(null /* wct */, null /* wctCB */);
    }

    private static boolean isEmbedded(@NonNull TransitionInfo.Change change) {
        return (change.getFlags() & FLAG_IS_EMBEDDED) != 0;
    }
}
