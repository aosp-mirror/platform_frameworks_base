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
 * limitations under the License
 */

package com.android.server.wm;

import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.IBinder;
import android.os.Parcel;
import android.view.Surface;
import android.view.Surface.OutOfResourcesException;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.LAST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;

/**
 * SurfaceControl extension that has background sized to match its container.
 */
class SurfaceControlWithBackground extends SurfaceControl {
    // SurfaceControl that holds the background behind opaque letterboxed app windows.
    private SurfaceControl mBackgroundControl;

    // Flag that defines whether the background should be shown.
    private boolean mVisible;

    // Way to communicate with corresponding window.
    private WindowSurfaceController mWindowSurfaceController;

    // Rect to hold task bounds when computing metrics for background.
    private Rect mTmpContainerRect = new Rect();

    // Last metrics applied to the main SurfaceControl.
    private float mLastWidth, mLastHeight;
    private float mLastDsDx = 1, mLastDsDy = 1;
    private float mLastX, mLastY;

    // Will skip alpha animation for background of starting window.
    private boolean mIsStartingWindow;

    public SurfaceControlWithBackground(SurfaceControlWithBackground other) {
        super(other);
        mBackgroundControl = other.mBackgroundControl;
        mVisible = other.mVisible;
        mWindowSurfaceController = other.mWindowSurfaceController;
    }

    public SurfaceControlWithBackground(SurfaceSession s, String name, int w, int h, int format,
            int flags, int windowType, int ownerUid,
            WindowSurfaceController windowSurfaceController) throws OutOfResourcesException {
        super(s, name, w, h, format, flags, windowType, ownerUid);

        // We should only show background behind app windows that are letterboxed in a task.
        if (!windowSurfaceController.mAnimator.mWin.isLetterboxedAppWindow()
                || windowType < FIRST_APPLICATION_WINDOW
                || windowType > LAST_APPLICATION_WINDOW) {
            return;
        }
        mWindowSurfaceController = windowSurfaceController;
        mLastWidth = w;
        mLastHeight = h;
        mWindowSurfaceController.getContainerRect(mTmpContainerRect);
        mIsStartingWindow = windowType == TYPE_APPLICATION_STARTING;
        mBackgroundControl = new SurfaceControl(s, "Background for - " + name,
                mTmpContainerRect.width(), mTmpContainerRect.height(), PixelFormat.OPAQUE,
                flags | SurfaceControl.FX_SURFACE_DIM);
    }

    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);

        if (mBackgroundControl == null) {
            return;
        }
        // We won't animate alpha for starting window because it will be visible as a flash for user
        // when fading out to reveal real app window.
        final float backgroundAlpha = mIsStartingWindow && alpha < 1.f ? 0 : alpha;
        mBackgroundControl.setAlpha(backgroundAlpha);
    }

    @Override
    public void setLayer(int zorder) {
        super.setLayer(zorder);

        if (mBackgroundControl == null) {
            return;
        }
        // TODO: Use setRelativeLayer(Integer.MIN_VALUE) when it's fixed.
        mBackgroundControl.setLayer(zorder - 1);
    }

    @Override
    public void setPosition(float x, float y) {
        super.setPosition(x, y);

        if (mBackgroundControl == null) {
            return;
        }
        mLastX = x;
        mLastY = y;
        updateBgPosition();
    }

    private void updateBgPosition() {
        mWindowSurfaceController.getContainerRect(mTmpContainerRect);
        final Rect winFrame = mWindowSurfaceController.mAnimator.mWin.mFrame;
        final float offsetX = (mTmpContainerRect.left - winFrame.left) * mLastDsDx;
        final float offsetY = (mTmpContainerRect.top - winFrame.top) * mLastDsDy;
        mBackgroundControl.setPosition(mLastX + offsetX, mLastY + offsetY);
    }

    @Override
    public void setSize(int w, int h) {
        super.setSize(w, h);

        if (mBackgroundControl == null) {
            return;
        }
        mLastWidth = w;
        mLastHeight = h;
        mWindowSurfaceController.getContainerRect(mTmpContainerRect);
        mBackgroundControl.setSize(mTmpContainerRect.width(), mTmpContainerRect.height());
    }

    @Override
    public void setWindowCrop(Rect crop) {
        super.setWindowCrop(crop);

        if (mBackgroundControl == null) {
            return;
        }
        if (crop.width() < mLastWidth || crop.height() < mLastHeight) {
            // We're animating and cropping window, compute the appropriate crop for background.
            calculateBgCrop(crop);
            mBackgroundControl.setWindowCrop(mTmpContainerRect);
        } else {
            // When not animating just set crop to container rect.
            mWindowSurfaceController.getContainerRect(mTmpContainerRect);
            mBackgroundControl.setWindowCrop(mTmpContainerRect);
        }
    }

    @Override
    public void setFinalCrop(Rect crop) {
        super.setFinalCrop(crop);

        if (mBackgroundControl == null) {
            return;
        }
        if (crop.width() < mLastWidth || crop.height() < mLastHeight) {
            // We're animating and cropping window, compute the appropriate crop for background.
            calculateBgCrop(crop);
            mBackgroundControl.setFinalCrop(mTmpContainerRect);
        } else {
            // When not animating just set crop to container rect.
            mWindowSurfaceController.getContainerRect(mTmpContainerRect);
            mBackgroundControl.setFinalCrop(mTmpContainerRect);
        }
    }

    /** Compute background crop based on current animation progress for main surface control. */
    private void calculateBgCrop(Rect crop) {
        // Track overall progress of animation by computing cropped portion of status bar.
        final Rect contentInsets = mWindowSurfaceController.mAnimator.mWin.mContentInsets;
        float d = contentInsets.top == 0 ? 0 : (float) crop.top / contentInsets.top;
        if (d > 1.f) {
            // We're running expand animation from launcher, won't compute custom bg crop here.
            mTmpContainerRect.set(crop);
            return;
        }

        // Compute additional offset for the background when app window is positioned not at (0,0).
        // E.g. landscape with navigation bar on the left.
        final Rect winFrame = mWindowSurfaceController.mAnimator.mWin.mFrame;
        final int offsetX = (int) (winFrame.left * mLastDsDx * d + 0.5);
        final int offsetY = (int) (winFrame.top * mLastDsDy * d + 0.5);

        // Compute new scaled width and height for background that will depend on current animation
        // progress. Those consist of current crop rect for the main surface + scaled areas outside
        // of letterboxed area.
        // TODO: Because the progress is computed with low precision we're getting smaller values
        // for background width/height then screen size at the end of the animation. Will round when
        // the value is smaller then some empiric epsilon. However, this should be fixed by
        // computing correct frames for letterboxed windows in WindowState.
        d = d < 0.025f ? 0 : d;
        mWindowSurfaceController.getContainerRect(mTmpContainerRect);
        final int backgroundWidth =
                (int) (crop.width() + (mTmpContainerRect.width() - mLastWidth) * (1 - d) + 0.5);
        final int backgroundHeight =
                (int) (crop.height() + (mTmpContainerRect.height() - mLastHeight) * (1 - d) + 0.5);

        mTmpContainerRect.set(crop);
        // Make sure that part of background to left/top is visible and scaled.
        mTmpContainerRect.offset(offsetX, offsetY);
        // Set correct width/height, so that area to right/bottom is cropped properly.
        mTmpContainerRect.right = mTmpContainerRect.left + backgroundWidth;
        mTmpContainerRect.bottom = mTmpContainerRect.top + backgroundHeight;
    }

    @Override
    public void setLayerStack(int layerStack) {
        super.setLayerStack(layerStack);

        if (mBackgroundControl == null) {
            return;
        }
        mBackgroundControl.setLayerStack(layerStack);
    }

    @Override
    public void setOpaque(boolean isOpaque) {
        super.setOpaque(isOpaque);
        updateBackgroundVisibility();
    }

    @Override
    public void setSecure(boolean isSecure) {
        super.setSecure(isSecure);
    }

    @Override
    public void setMatrix(float dsdx, float dtdx, float dtdy, float dsdy) {
        super.setMatrix(dsdx, dtdx, dtdy, dsdy);

        if (mBackgroundControl == null) {
            return;
        }
        mBackgroundControl.setMatrix(dsdx, dtdx, dtdy, dsdy);
        mLastDsDx = dsdx;
        mLastDsDy = dsdy;
        updateBgPosition();
    }

    @Override
    public void hide() {
        super.hide();
        mVisible = false;
        updateBackgroundVisibility();
    }

    @Override
    public void show() {
        super.show();
        mVisible = true;
        updateBackgroundVisibility();
    }

    @Override
    public void destroy() {
        super.destroy();

        if (mBackgroundControl == null) {
            return;
        }
        mBackgroundControl.destroy();
    }

    @Override
    public void release() {
        super.release();

        if (mBackgroundControl == null) {
            return;
        }
        mBackgroundControl.release();
    }

    @Override
    public void setTransparentRegionHint(Region region) {
        super.setTransparentRegionHint(region);

        if (mBackgroundControl == null) {
            return;
        }
        mBackgroundControl.setTransparentRegionHint(region);
    }

    @Override
    public void deferTransactionUntil(IBinder handle, long frame) {
        super.deferTransactionUntil(handle, frame);

        if (mBackgroundControl == null) {
            return;
        }
        mBackgroundControl.deferTransactionUntil(handle, frame);
    }

    @Override
    public void deferTransactionUntil(Surface barrier, long frame) {
        super.deferTransactionUntil(barrier, frame);

        if (mBackgroundControl == null) {
            return;
        }
        mBackgroundControl.deferTransactionUntil(barrier, frame);
    }

    private void updateBackgroundVisibility() {
        if (mBackgroundControl == null) {
            return;
        }
        final AppWindowToken appWindowToken = mWindowSurfaceController.mAnimator.mWin.mAppToken;
        if (appWindowToken != null && appWindowToken.fillsParent() && mVisible) {
            mBackgroundControl.show();
        } else {
            mBackgroundControl.hide();
        }
    }
}
