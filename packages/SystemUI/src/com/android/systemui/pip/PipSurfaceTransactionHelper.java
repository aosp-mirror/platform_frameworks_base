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

package com.android.systemui.pip;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.view.SurfaceControl;

import com.android.systemui.R;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Abstracts the common operations on {@link SurfaceControl.Transaction} for PiP transition.
 */
@Singleton
public class PipSurfaceTransactionHelper {

    private final boolean mEnableCornerRadius;
    private final int mCornerRadius;

    @Inject
    public PipSurfaceTransactionHelper(Context context) {
        final Resources res = context.getResources();
        mEnableCornerRadius = res.getBoolean(R.bool.config_pipEnableRoundCorner);
        mCornerRadius = res.getDimensionPixelSize(R.dimen.pip_corner_radius);
    }

    /**
     * Operates the alpha on a given transaction and leash
     * @return same {@link PipSurfaceTransactionHelper} instance for method chaining
     */
    PipSurfaceTransactionHelper alpha(SurfaceControl.Transaction tx, SurfaceControl leash,
            float alpha) {
        tx.setAlpha(leash, alpha);
        return this;
    }

    /**
     * Operates the crop (and position) on a given transaction and leash
     * @return same {@link PipSurfaceTransactionHelper} instance for method chaining
     */
    PipSurfaceTransactionHelper crop(SurfaceControl.Transaction tx, SurfaceControl leash,
            Rect destinationBounds) {
        tx.setWindowCrop(leash, destinationBounds.width(), destinationBounds.height())
                .setPosition(leash, destinationBounds.left, destinationBounds.top);
        return this;
    }

    /**
     * Operates the round corner radius on a given transaction and leash
     * @return same {@link PipSurfaceTransactionHelper} instance for method chaining
     */
    PipSurfaceTransactionHelper round(SurfaceControl.Transaction tx, SurfaceControl leash,
            boolean applyCornerRadius) {
        if (mEnableCornerRadius) {
            tx.setCornerRadius(leash, applyCornerRadius ? mCornerRadius : 0);
        }
        return this;
    }

    interface SurfaceControlTransactionFactory {
        SurfaceControl.Transaction getTransaction();
    }
}
