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

package com.android.wm.shell.splitscreen;

import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.view.IWindow;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.WindowlessWindowManager;

import com.android.wm.shell.R;

import java.util.function.Supplier;

/**
 * Handles drawing outline of the bounds of provided root surface. The outline will be drown with
 * the consideration of display insets like status bar, navigation bar and display cutout.
 */
class OutlineManager extends WindowlessWindowManager {
    private static final String WINDOW_NAME = "SplitOutlineLayer";
    private final Context mContext;
    private final int mOutlineColor;
    private final Rect mOutlineBounds = new Rect();
    private final Rect mTmpBounds = new Rect();
    private final Supplier<SurfaceControl> mOutlineSurfaceSupplier;
    private final SurfaceControlViewHost mViewHost;
    private final SurfaceControl mLeash;

    /**
     * Constructs {@link #OutlineManager} with indicated outline color for the provided root
     * surface.
     */
    OutlineManager(Context context, Configuration configuration,
            Supplier<SurfaceControl> outlineSurfaceSupplier, int color) {
        super(configuration, null /* rootSurface */, null /* hostInputToken */);
        mContext = context.createWindowContext(context.getDisplay(), TYPE_APPLICATION_OVERLAY,
                null /* options */);
        mOutlineSurfaceSupplier = outlineSurfaceSupplier;
        mOutlineColor = color;

        mViewHost = new SurfaceControlViewHost(mContext, mContext.getDisplay(), this);
        final OutlineRoot rootView = (OutlineRoot) LayoutInflater.from(mContext)
                .inflate(R.layout.split_outline, null);
        rootView.updateOutlineBounds(mOutlineBounds, mOutlineColor);

        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                0 /* width */, 0 /* height */, TYPE_APPLICATION_OVERLAY,
                FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE, PixelFormat.TRANSLUCENT);
        lp.token = new Binder();
        lp.setTitle(WINDOW_NAME);
        lp.privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION | PRIVATE_FLAG_TRUSTED_OVERLAY;
        // TODO(b/189839391): Set INPUT_FEATURE_NO_INPUT_CHANNEL after WM supports
        //  TRUSTED_OVERLAY for windowless window without input channel.
        mViewHost.setView(rootView, lp);

        mLeash = getSurfaceControl(mViewHost.getWindowToken());
    }

    @Override
    protected void attachToParentSurface(IWindow window, SurfaceControl.Builder b) {
        b.setParent(mOutlineSurfaceSupplier.get());
    }

    boolean updateOutlineBounds(Rect rootBounds) {
        computeOutlineBounds(mContext, rootBounds, mTmpBounds);
        if (mOutlineBounds.equals(mTmpBounds)) {
            return false;
        }
        mOutlineBounds.set(mTmpBounds);

        ((OutlineRoot) mViewHost.getView()).updateOutlineBounds(mOutlineBounds, mOutlineColor);
        final WindowManager.LayoutParams lp =
                (WindowManager.LayoutParams) mViewHost.getView().getLayoutParams();
        lp.width = rootBounds.width();
        lp.height = rootBounds.height();
        mViewHost.relayout(lp);

        return true;
    }

    @Nullable
    SurfaceControl getLeash() {
        return mLeash;
    }

    private static void computeOutlineBounds(Context context, Rect rootBounds, Rect outBounds) {
        computeDisplayStableBounds(context, outBounds);
        outBounds.intersect(rootBounds);
        // Offset the coordinate from screen based to surface based.
        outBounds.offset(-rootBounds.left, -rootBounds.top);
    }

    private static void computeDisplayStableBounds(Context context, Rect outBounds) {
        final WindowMetrics windowMetrics =
                context.getSystemService(WindowManager.class).getMaximumWindowMetrics();
        outBounds.set(windowMetrics.getBounds());
        outBounds.inset(windowMetrics.getWindowInsets().getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()));
    }
}
