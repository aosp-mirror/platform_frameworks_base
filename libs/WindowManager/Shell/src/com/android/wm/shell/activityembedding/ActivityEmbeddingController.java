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

import android.content.Context;
import android.os.IBinder;
import android.view.SurfaceControl;
import android.window.TransitionInfo;
import android.window.TransitionRequestInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wm.shell.transition.Transitions;

/**
 * Responsible for handling ActivityEmbedding related transitions.
 */
public class ActivityEmbeddingController implements Transitions.TransitionHandler {

    private final Context mContext;
    private final Transitions mTransitions;

    public ActivityEmbeddingController(Context context, Transitions transitions) {
        mContext = context;
        mTransitions = transitions;
    }

    /** Registers to handle transitions. */
    public void init() {
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

        // TODO(b/207070762) Implement AE animation.
        startTransaction.apply();
        finishCallback.onTransitionFinished(null /* wct */, null /* wctCB */);
        return true;
    }

    @Nullable
    @Override
    public WindowContainerTransaction handleRequest(@NonNull IBinder transition,
            @NonNull TransitionRequestInfo request) {
        return null;
    }

    private static boolean isEmbedded(@NonNull TransitionInfo.Change change) {
        return (change.getFlags() & FLAG_IS_EMBEDDED) != 0;
    }
}
