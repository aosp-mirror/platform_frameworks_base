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

package com.android.wm.shell.pip;

import android.graphics.Matrix;
import android.view.SurfaceControl;

/**
 * A dummy {@link SurfaceControl.Transaction} class for testing purpose and supports
 * method chaining.
 */
public class PipDummySurfaceControlTx extends SurfaceControl.Transaction {
    @Override
    public SurfaceControl.Transaction setAlpha(SurfaceControl leash, float alpha) {
        return this;
    }

    @Override
    public SurfaceControl.Transaction setPosition(SurfaceControl leash, float x, float y) {
        return this;
    }

    @Override
    public SurfaceControl.Transaction setWindowCrop(SurfaceControl leash, int w, int h) {
        return this;
    }

    @Override
    public SurfaceControl.Transaction setCornerRadius(SurfaceControl leash, float radius) {
        return this;
    }

    @Override
    public SurfaceControl.Transaction setShadowRadius(SurfaceControl leash, float radius) {
        return this;
    }

    @Override
    public SurfaceControl.Transaction setMatrix(SurfaceControl leash, Matrix matrix,
            float[] float9) {
        return this;
    }

    @Override
    public SurfaceControl.Transaction setFrameTimelineVsync(long frameTimelineVsyncId) {
        return this;
    }

    @Override
    public void apply() {}
}

