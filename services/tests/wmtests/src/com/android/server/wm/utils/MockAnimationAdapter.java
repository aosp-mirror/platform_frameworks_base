/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wm.utils;

import android.util.proto.ProtoOutputStream;
import android.view.SurfaceControl;

import androidx.annotation.NonNull;

import com.android.server.wm.AnimationAdapter;
import com.android.server.wm.SurfaceAnimator;

import java.io.PrintWriter;

/**
 * An empty animation adapter which just executes the finish callback
 */
public class MockAnimationAdapter implements AnimationAdapter {

    @Override
    public boolean getShowWallpaper() {
        return false;
    }

    @Override
    public void startAnimation(SurfaceControl animationLeash, SurfaceControl.Transaction t,
            int type, @NonNull SurfaceAnimator.OnAnimationFinishedCallback finishCallback) {
        // As the animation won't run, finish it immediately
        finishCallback.onAnimationFinished(0, null);
    }

    @Override
    public void onAnimationCancelled(SurfaceControl animationLeash) {}

    @Override
    public long getDurationHint() {
        return 0;
    }

    @Override
    public long getStatusBarTransitionsStartTime() {
        return 0;
    }

    @Override
    public void dump(PrintWriter pw, String prefix) {}

    @Override
    public void dumpDebug(ProtoOutputStream proto) {}
}
