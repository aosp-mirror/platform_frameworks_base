/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.internal.graphics;

import android.animation.AnimationHandler.AnimationFrameCallbackProvider;
import android.view.Choreographer;

/**
 * Provider of timing pulse that uses SurfaceFlinger Vsync Choreographer for frame callbacks.
 *
 * @hide
 */
// TODO(b/222698397): remove getSfInstance/this class usage and use vsyncId for transactions
public final class SfVsyncFrameCallbackProvider implements AnimationFrameCallbackProvider {

    private final Choreographer mChoreographer;

    public SfVsyncFrameCallbackProvider() {
        mChoreographer = Choreographer.getSfInstance();
    }

    public SfVsyncFrameCallbackProvider(Choreographer choreographer) {
        mChoreographer = choreographer;
    }

    @Override
    public void postFrameCallback(Choreographer.FrameCallback callback) {
        mChoreographer.postFrameCallback(callback);
    }

    @Override
    public void postCommitCallback(Runnable runnable) {
        mChoreographer.postCallback(Choreographer.CALLBACK_COMMIT, runnable, null);
    }

    @Override
    public long getFrameTime() {
        return mChoreographer.getFrameTime();
    }

    @Override
    public long getFrameDelay() {
        return Choreographer.getFrameDelay();
    }

    @Override
    public void setFrameDelay(long delay) {
        Choreographer.setFrameDelay(delay);
    }
}