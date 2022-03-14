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

package com.android.wm.shell.unfold;

import static android.graphics.Color.blue;
import static android.graphics.Color.green;
import static android.graphics.Color.red;
import static android.view.Display.DEFAULT_DISPLAY;

import android.annotation.NonNull;
import android.content.Context;
import android.view.SurfaceControl;

import com.android.wm.shell.R;
import com.android.wm.shell.RootTaskDisplayAreaOrganizer;

/**
 * Controls background color layer for the unfold animations
 */
public class UnfoldBackgroundController {

    private static final int BACKGROUND_LAYER_Z_INDEX = -1;

    private final RootTaskDisplayAreaOrganizer mRootTaskDisplayAreaOrganizer;
    private final float[] mBackgroundColor;
    private SurfaceControl mBackgroundLayer;

    public UnfoldBackgroundController(
            @NonNull Context context,
            @NonNull RootTaskDisplayAreaOrganizer rootTaskDisplayAreaOrganizer) {
        mRootTaskDisplayAreaOrganizer = rootTaskDisplayAreaOrganizer;
        mBackgroundColor = getBackgroundColor(context);
    }

    /**
     * Ensures that unfold animation background color layer is present,
     * @param transaction where we should add the background if it is not added
     */
    public void ensureBackground(@NonNull SurfaceControl.Transaction transaction) {
        if (mBackgroundLayer != null) return;

        SurfaceControl.Builder colorLayerBuilder = new SurfaceControl.Builder()
                .setName("app-unfold-background")
                .setCallsite("AppUnfoldTransitionController")
                .setColorLayer();
        mRootTaskDisplayAreaOrganizer.attachToDisplayArea(DEFAULT_DISPLAY, colorLayerBuilder);
        mBackgroundLayer = colorLayerBuilder.build();

        transaction
                .setColor(mBackgroundLayer, mBackgroundColor)
                .show(mBackgroundLayer)
                .setLayer(mBackgroundLayer, BACKGROUND_LAYER_Z_INDEX);
    }

    /**
     * Ensures that the background is not visible
     * @param transaction as part of which the removal will happen if needed
     */
    public void removeBackground(@NonNull SurfaceControl.Transaction transaction) {
        if (mBackgroundLayer == null) return;
        if (mBackgroundLayer.isValid()) {
            transaction.remove(mBackgroundLayer);
        }
        mBackgroundLayer = null;
    }

    private float[] getBackgroundColor(Context context) {
        int colorInt = context.getResources().getColor(R.color.unfold_transition_background);
        return new float[]{
                (float) red(colorInt) / 255.0F,
                (float) green(colorInt) / 255.0F,
                (float) blue(colorInt) / 255.0F
        };
    }
}
