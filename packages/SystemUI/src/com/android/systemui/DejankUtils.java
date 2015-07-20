/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui;

import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.view.Choreographer;

import java.util.ArrayList;

/**
 * Utility class for methods used to dejank the UI.
 */
public class DejankUtils {

    private static final Choreographer sChoreographer = Choreographer.getInstance();
    private static final Handler sHandler = new Handler();

    private static final ArrayList<Runnable> sPendingRunnables = new ArrayList<>();

    private static final Runnable sAnimationCallbackRunnable = new Runnable() {
        @Override
        public void run() {
            for (int i = 0; i < sPendingRunnables.size(); i++) {
                sHandler.post(sPendingRunnables.get(i));
            }
            sPendingRunnables.clear();
        }
    };

    /**
     * Executes {@code r} after performTraversals. Use this do to CPU heavy work for which the
     * timing is not critical for animation. The work is then scheduled at the same time
     * RenderThread is doing its thing, leading to better parallelization.
     *
     * <p>Needs to be called from the main thread.
     */
    public static void postAfterTraversal(Runnable r) {
        throwIfNotCalledOnMainThread();
        sPendingRunnables.add(r);
        postAnimationCallback();
    }

    /**
     * Removes a previously scheduled runnable.
     *
     * <p>Needs to be called from the main thread.
     */
    public static void removeCallbacks(Runnable r) {
        throwIfNotCalledOnMainThread();
        sPendingRunnables.remove(r);
        sHandler.removeCallbacks(r);
    }

    private static void postAnimationCallback() {
        sChoreographer.postCallback(Choreographer.CALLBACK_ANIMATION, sAnimationCallbackRunnable,
                null);
    }

    private static void throwIfNotCalledOnMainThread() {
        if (!Looper.getMainLooper().isCurrentThread()) {
            throw new IllegalStateException("should be called from the main thread.");
        }
    }
}
