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

package com.android.wm.shell.util;

import android.view.SurfaceControl;

/**
 * Utility class that takes care of counter-rotating surfaces during a transition animation.
 */
public class CounterRotator {
    private SurfaceControl mSurface = null;

    /** Gets the surface with the counter-rotation. */
    public SurfaceControl getSurface() {
        return mSurface;
    }

    /**
     * Sets up this rotator.
     *
     * @param rotateDelta is the forward rotation change (the rotation the display is making).
     * @param displayW (and H) Is the size of the rotating display.
     */
    public void setup(SurfaceControl.Transaction t, SurfaceControl parent, int rotateDelta,
            float displayW, float displayH) {
        if (rotateDelta == 0) return;
        // We want to counter-rotate, so subtract from 4
        rotateDelta = 4 - (rotateDelta + 4) % 4;
        mSurface = new SurfaceControl.Builder()
                .setName("Transition Unrotate")
                .setContainerLayer()
                .setParent(parent)
                .build();
        // column-major
        if (rotateDelta == 1) {
            t.setMatrix(mSurface, 0, 1, -1, 0);
            t.setPosition(mSurface, displayW, 0);
        } else if (rotateDelta == 2) {
            t.setMatrix(mSurface, -1, 0, 0, -1);
            t.setPosition(mSurface, displayW, displayH);
        } else if (rotateDelta == 3) {
            t.setMatrix(mSurface, 0, -1, 1, 0);
            t.setPosition(mSurface, 0, displayH);
        }
        t.show(mSurface);
    }

    /**
     * Adds a surface that needs to be counter-rotate.
     */
    public void addChild(SurfaceControl.Transaction t, SurfaceControl child) {
        if (mSurface == null) return;
        t.reparent(child, mSurface);
    }

    /**
     * Clean-up. Since finishTransaction should reset all change leashes, we only need to remove the
     * counter rotation surface.
     */
    public void cleanUp(SurfaceControl.Transaction finishTransaction) {
        if (mSurface == null) return;
        finishTransaction.remove(mSurface);
    }
}
