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

package com.android.wm.shell.back;

import static android.view.Display.DEFAULT_DISPLAY;

import android.annotation.NonNull;
import android.graphics.Color;
import android.view.SurfaceControl;

import com.android.wm.shell.RootTaskDisplayAreaOrganizer;

/**
 * Controls background surface for the back animations
 */
public class BackAnimationBackground {
    private static final int BACKGROUND_LAYER = -1;
    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    private SurfaceControl mBackgroundSurface;

    public BackAnimationBackground(RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer) {
        mRootTaskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer;
    }

    void ensureBackground(int color, @NonNull SurfaceControl.Transaction transaction) {
        if (mBackgroundSurface != null) {
            return;
        }

        final float[] colorComponents = new float[] { Color.red(color) / 255.f,
                Color.green(color) / 255.f, Color.blue(color) / 255.f };

        final SurfaceControl.Builder colorLayerBuilder = new SurfaceControl.Builder()
                .setName("back-animation-background")
                .setCallsite("BackAnimationBackground")
                .setColorLayer();

        mRootTaskDisplayAreaOrganizer.attachToDisplayArea(DEFAULT_DISPLAY, colorLayerBuilder);
        mBackgroundSurface = colorLayerBuilder.build();
        transaction.setColor(mBackgroundSurface, colorComponents)
                .setLayer(mBackgroundSurface, BACKGROUND_LAYER)
                .show(mBackgroundSurface);
    }

    void removeBackground(@NonNull SurfaceControl.Transaction transaction) {
        if (mBackgroundSurface == null) {
            return;
        }

        if (mBackgroundSurface.isValid()) {
            transaction.remove(mBackgroundSurface);
        }
        mBackgroundSurface = null;
    }
}
