/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.accessibility;

import static android.view.WindowManager.LayoutParams;

import android.annotation.UiContext;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.AttachedSurfaceControl;
import android.view.LayoutInflater;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.annotation.UiThread;

import com.android.systemui.res.R;

import java.util.function.Supplier;

class FullscreenMagnificationController {

    private final Context mContext;
    private final AccessibilityManager mAccessibilityManager;
    private final WindowManager mWindowManager;
    private Supplier<SurfaceControlViewHost> mScvhSupplier;
    private SurfaceControlViewHost mSurfaceControlViewHost;
    private Rect mWindowBounds;
    private SurfaceControl.Transaction mTransaction;
    private View mFullscreenBorder = null;
    private int mBorderOffset;
    private final int mDisplayId;
    private static final Region sEmptyRegion = new Region();

    FullscreenMagnificationController(
            @UiContext Context context,
            AccessibilityManager accessibilityManager,
            WindowManager windowManager,
            Supplier<SurfaceControlViewHost> scvhSupplier) {
        mContext = context;
        mAccessibilityManager = accessibilityManager;
        mWindowManager = windowManager;
        mWindowBounds = mWindowManager.getCurrentWindowMetrics().getBounds();
        mTransaction = new SurfaceControl.Transaction();
        mScvhSupplier = scvhSupplier;
        mBorderOffset = mContext.getResources().getDimensionPixelSize(
                R.dimen.magnifier_border_width_fullscreen_with_offset)
                - mContext.getResources().getDimensionPixelSize(
                R.dimen.magnifier_border_width_fullscreen);
        mDisplayId = mContext.getDisplayId();
    }

    @UiThread
    void onFullscreenMagnificationActivationChanged(boolean activated) {
        if (activated) {
            createFullscreenMagnificationBorder();
        } else {
            removeFullscreenMagnificationBorder();
        }
    }

    @UiThread
    private void removeFullscreenMagnificationBorder() {
        if (mSurfaceControlViewHost != null) {
            mSurfaceControlViewHost.release();
            mSurfaceControlViewHost = null;
        }

        if (mFullscreenBorder != null) {
            mFullscreenBorder = null;
        }
    }

    /**
     * Since the device corners are not perfectly rounded, we would like to create a thick stroke,
     * and set negative offset to the border view to fill up the spaces between the border and the
     * device corners.
     */
    @UiThread
    private void createFullscreenMagnificationBorder() {
        mFullscreenBorder = LayoutInflater.from(mContext)
                .inflate(R.layout.fullscreen_magnification_border, null);
        mSurfaceControlViewHost = mScvhSupplier.get();
        mSurfaceControlViewHost.setView(mFullscreenBorder, getBorderLayoutParams());

        SurfaceControl surfaceControl = mSurfaceControlViewHost
                .getSurfacePackage().getSurfaceControl();

        mTransaction
                .setPosition(surfaceControl, -mBorderOffset, -mBorderOffset)
                .setLayer(surfaceControl, Integer.MAX_VALUE)
                .show(surfaceControl)
                .apply();

        mAccessibilityManager.attachAccessibilityOverlayToDisplay(mDisplayId, surfaceControl);

        applyTouchableRegion();
    }

    private LayoutParams getBorderLayoutParams() {
        LayoutParams params =  new LayoutParams(
                mWindowBounds.width() + 2 * mBorderOffset,
                mWindowBounds.height() + 2 * mBorderOffset,
                LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                LayoutParams.FLAG_NOT_TOUCH_MODAL | LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT);
        params.setTrustedOverlay();
        return params;
    }

    private void applyTouchableRegion() {
        // Sometimes this can get posted and run after deleteWindowMagnification() is called.
        if (mFullscreenBorder == null) return;

        AttachedSurfaceControl surfaceControl = mSurfaceControlViewHost.getRootSurfaceControl();

        // The touchable region of the mFullscreenBorder will be empty since we are going to allow
        // all touch events to go through this view.
        surfaceControl.setTouchableRegion(sEmptyRegion);
    }
}
