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

package com.android.internal.widget;

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupWindow;

import com.android.internal.R;
import com.android.internal.util.Preconditions;

/**
 * Android magnifier widget. Can be used by any view which is attached to window.
 */
public final class Magnifier {
    // Use this to specify that a previous configuration value does not exist.
    private static final int NONEXISTENT_PREVIOUS_CONFIG_VALUE = -1;
    // The view to which this magnifier is attached.
    private final View mView;
    // The window containing the magnifier.
    private final PopupWindow mWindow;
    // The center coordinates of the window containing the magnifier.
    private final Point mWindowCoords = new Point();
    // The width of the window containing the magnifier.
    private final int mWindowWidth;
    // The height of the window containing the magnifier.
    private final int mWindowHeight;
    // The bitmap used to display the contents of the magnifier.
    private final Bitmap mBitmap;
    // The center coordinates of the content that is to be magnified.
    private final Point mCenterZoomCoords = new Point();
    // The callback of the pixel copy request will be invoked on this Handler when
    // the copy is finished.
    private final Handler mPixelCopyHandler = Handler.getMain();
    // Current magnification scale.
    private final float mZoomScale;
    // Variables holding previous states, used for detecting redundant calls and invalidation.
    private final Point mPrevStartCoordsInSurface = new Point(
            NONEXISTENT_PREVIOUS_CONFIG_VALUE, NONEXISTENT_PREVIOUS_CONFIG_VALUE);
    private final PointF mPrevPosInView = new PointF(
            NONEXISTENT_PREVIOUS_CONFIG_VALUE, NONEXISTENT_PREVIOUS_CONFIG_VALUE);
    private final Rect mPixelCopyRequestRect = new Rect();

    /**
     * Initializes a magnifier.
     *
     * @param view the view for which this magnifier is attached
     */
    @UiThread
    public Magnifier(@NonNull View view) {
        mView = Preconditions.checkNotNull(view);
        final Context context = mView.getContext();
        final float elevation = context.getResources().getDimension(R.dimen.magnifier_elevation);
        final View content = LayoutInflater.from(context).inflate(R.layout.magnifier, null);
        content.findViewById(R.id.magnifier_inner).setClipToOutline(true);
        mWindowWidth = context.getResources().getDimensionPixelSize(R.dimen.magnifier_width);
        mWindowHeight = context.getResources().getDimensionPixelSize(R.dimen.magnifier_height);
        mZoomScale = context.getResources().getFloat(R.dimen.magnifier_zoom_scale);

        mWindow = new PopupWindow(context);
        mWindow.setContentView(content);
        mWindow.setWidth(mWindowWidth);
        mWindow.setHeight(mWindowHeight);
        mWindow.setElevation(elevation);
        mWindow.setTouchable(false);
        mWindow.setBackgroundDrawable(null);

        final int bitmapWidth = Math.round(mWindowWidth / mZoomScale);
        final int bitmapHeight = Math.round(mWindowHeight / mZoomScale);
        mBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        getImageView().setImageBitmap(mBitmap);
    }

    /**
     * Shows the magnifier on the screen.
     *
     * @param xPosInView horizontal coordinate of the center point of the magnifier source relative
     *        to the view. The lower end is clamped to 0
     * @param yPosInView vertical coordinate of the center point of the magnifier source
     *        relative to the view. The lower end is clamped to 0
     */
    public void show(@FloatRange(from=0) float xPosInView, @FloatRange(from=0) float yPosInView) {
        xPosInView = Math.max(0, xPosInView);
        yPosInView = Math.max(0, yPosInView);

        configureCoordinates(xPosInView, yPosInView);

        // Clamp startX value to avoid distorting the rendering of the magnifier content.
        final int startX = Math.max(0, Math.min(
                mCenterZoomCoords.x - mBitmap.getWidth() / 2,
                mView.getWidth() - mBitmap.getWidth()));
        final int startY = mCenterZoomCoords.y - mBitmap.getHeight() / 2;

        if (startX != mPrevStartCoordsInSurface.x || startY != mPrevStartCoordsInSurface.y) {
            performPixelCopy(startX, startY);

            mPrevPosInView.x = xPosInView;
            mPrevPosInView.y = yPosInView;

            if (mWindow.isShowing()) {
                mWindow.update(mWindowCoords.x, mWindowCoords.y, mWindow.getWidth(),
                        mWindow.getHeight());
            } else {
                mWindow.showAtLocation(mView, Gravity.NO_GRAVITY, mWindowCoords.x, mWindowCoords.y);
            }
        }
    }

    /**
     * Dismisses the magnifier from the screen.
     */
    public void dismiss() {
        mWindow.dismiss();
    }

    /**
     * Forces the magnifier to update its content. It uses the previous coordinates passed to
     * {@link #show(float, float)}. This only happens if the magnifier is currently showing.
     *
     * @hide
     */
    public void update() {
        if (mWindow.isShowing()) {
            // Update the contents shown in the magnifier.
            performPixelCopy(mPrevStartCoordsInSurface.x, mPrevStartCoordsInSurface.y);
        }
    }

    /**
     * @return the height of the magnifier window.
     */
    public int getHeight() {
        return mWindowHeight;
    }

    /**
     * @return the width of the magnifier window.
     */
    public int getWidth() {
        return mWindowWidth;
    }

    /**
     * @return the zoom scale of the magnifier.
     */
    public float getZoomScale() {
        return mZoomScale;
    }

    private void configureCoordinates(float xPosInView, float yPosInView) {
        final float posX;
        final float posY;

        if (mView instanceof SurfaceView) {
            // No offset required if the backing Surface matches the size of the SurfaceView.
            posX = xPosInView;
            posY = yPosInView;
        } else {
            final int[] coordinatesInSurface = new int[2];
            mView.getLocationInSurface(coordinatesInSurface);
            posX = xPosInView + coordinatesInSurface[0];
            posY = yPosInView + coordinatesInSurface[1];
        }

        mCenterZoomCoords.x = Math.round(posX);
        mCenterZoomCoords.y = Math.round(posY);

        final int verticalMagnifierOffset = mView.getContext().getResources().getDimensionPixelSize(
                R.dimen.magnifier_offset);
        mWindowCoords.x = mCenterZoomCoords.x - mWindowWidth / 2;
        mWindowCoords.y = mCenterZoomCoords.y - mWindowHeight / 2 - verticalMagnifierOffset;
    }

    private void performPixelCopy(final int startXInSurface, final int startYInSurface) {
        final Surface surface = getValidViewSurface();
        if (surface != null) {
            mPixelCopyRequestRect.set(startXInSurface, startYInSurface,
                    startXInSurface + mBitmap.getWidth(), startYInSurface + mBitmap.getHeight());

            PixelCopy.request(surface, mPixelCopyRequestRect, mBitmap,
                    result -> {
                        getImageView().invalidate();
                        mPrevStartCoordsInSurface.x = startXInSurface;
                        mPrevStartCoordsInSurface.y = startYInSurface;
                    },
                    mPixelCopyHandler);
        }
    }

    @Nullable
    private Surface getValidViewSurface() {
        final Surface surface;
        if (mView instanceof SurfaceView) {
            surface = ((SurfaceView) mView).getHolder().getSurface();
        } else if (mView.getViewRootImpl() != null) {
            surface = mView.getViewRootImpl().mSurface;
        } else {
            surface = null;
        }

        return (surface != null && surface.isValid()) ? surface : null;
    }

    private ImageView getImageView() {
        return mWindow.getContentView().findViewById(R.id.magnifier_image);
    }
}
