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
import android.annotation.UiThread;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewRootImpl;
import android.widget.ImageView;
import android.widget.PopupWindow;

import com.android.internal.R;
import com.android.internal.util.Preconditions;

/**
 * Android magnifier widget. Can be used by any view which is attached to window.
 */
public final class Magnifier {
    private static final String LOG_TAG = "magnifier";
    // Use this to specify that a previous configuration value does not exist.
    private static final int INEXISTENT_PREVIOUS_CONFIG_VALUE = -1;
    // The view for which this magnifier is attached.
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

    private RectF mTmpRectF;

    // Variables holding previous states, used for detecting redundant calls and invalidation.
    private Point mPrevStartCoordsOnScreen = new Point(
            INEXISTENT_PREVIOUS_CONFIG_VALUE, INEXISTENT_PREVIOUS_CONFIG_VALUE);
    private PointF mPrevCenterCoordsOnScreen = new PointF(
            INEXISTENT_PREVIOUS_CONFIG_VALUE, INEXISTENT_PREVIOUS_CONFIG_VALUE);
    private float mPrevScale = INEXISTENT_PREVIOUS_CONFIG_VALUE;

    /**
     * Initializes a magnifier.
     *
     * @param view the view for which this magnifier is attached
     */
    @UiThread
    public Magnifier(@NonNull View view) {
        mView = Preconditions.checkNotNull(view);
        final Context context = mView.getContext();
        final View content = LayoutInflater.from(context).inflate(R.layout.magnifier, null);
        mWindowWidth = context.getResources().getDimensionPixelSize(R.dimen.magnifier_width);
        mWindowHeight = context.getResources().getDimensionPixelSize(R.dimen.magnifier_height);
        final float elevation = context.getResources().getDimension(R.dimen.magnifier_elevation);

        mWindow = new PopupWindow(context);
        mWindow.setContentView(content);
        mWindow.setWidth(mWindowWidth);
        mWindow.setHeight(mWindowHeight);
        mWindow.setElevation(elevation);
        mWindow.setTouchable(false);
        mWindow.setBackgroundDrawable(null);

        mBitmap = Bitmap.createBitmap(mWindowWidth, mWindowHeight, Bitmap.Config.ARGB_8888);
        getImageView().setImageBitmap(mBitmap);
    }

    /**
     * Shows the magnifier on the screen.
     *
     * @param centerXOnScreen horizontal coordinate of the center point of the magnifier source. The
     *        lower end is clamped to 0
     * @param centerYOnScreen vertical coordinate of the center point of the magnifier source. The
     *        lower end is clamped to 0
     * @param scale the scale at which the magnifier zooms on the source content. The
     *        lower end is clamped to 1 and the higher end to 4
     */
    public void show(@FloatRange(from=0) float centerXOnScreen,
            @FloatRange(from=0) float centerYOnScreen,
            @FloatRange(from=1, to=4) float scale) {
        if (scale > 4) {
            scale = 4;
        }

        if (scale < 1) {
            scale = 1;
        }

        if (centerXOnScreen < 0) {
            centerXOnScreen = 0;
        }

        if (centerYOnScreen < 0) {
            centerYOnScreen = 0;
        }

        showInternal(centerXOnScreen, centerYOnScreen, scale, false);
    }

    private void showInternal(@FloatRange(from=0) float centerXOnScreen,
            @FloatRange(from=0) float centerYOnScreen,
            @FloatRange(from=1, to=4) float scale,
            boolean forceShow) {
        if (mPrevScale != scale) {
            resizeBitmap(scale);
            mPrevScale = scale;
        }
        configureCoordinates(centerXOnScreen, centerYOnScreen);
        maybePerformPixelCopy(scale, forceShow);

        if (mWindow.isShowing()) {
            mWindow.update(mWindowCoords.x, mWindowCoords.y, mWindow.getWidth(),
                    mWindow.getHeight());
        } else {
            mWindow.showAtLocation(mView.getRootView(), Gravity.NO_GRAVITY,
                    mWindowCoords.x, mWindowCoords.y);
        }

        mPrevCenterCoordsOnScreen.x = centerXOnScreen;
        mPrevCenterCoordsOnScreen.y = centerYOnScreen;
    }

    /**
     * Dismisses the magnifier from the screen.
     */
    public void dismiss() {
        mWindow.dismiss();

        mPrevStartCoordsOnScreen.x = INEXISTENT_PREVIOUS_CONFIG_VALUE;
        mPrevStartCoordsOnScreen.y = INEXISTENT_PREVIOUS_CONFIG_VALUE;
        mPrevCenterCoordsOnScreen.x = INEXISTENT_PREVIOUS_CONFIG_VALUE;
        mPrevCenterCoordsOnScreen.y = INEXISTENT_PREVIOUS_CONFIG_VALUE;
        mPrevScale = INEXISTENT_PREVIOUS_CONFIG_VALUE;
    }

    /**
     * Forces the magnifier to update content by taking and showing a new snapshot using the
     * previous coordinates. It does this only if the magnifier is showing and the dirty rectangle
     * intersects the rectangle which holds the content to be magnified.
     *
     * @param dirtyRectOnScreen the rectangle representing the screen bounds of the dirty region
     */
    public void invalidate(RectF dirtyRectOnScreen) {
        if (mWindow.isShowing() && mPrevCenterCoordsOnScreen.x != INEXISTENT_PREVIOUS_CONFIG_VALUE
                && mPrevCenterCoordsOnScreen.y != INEXISTENT_PREVIOUS_CONFIG_VALUE
                && mPrevScale != INEXISTENT_PREVIOUS_CONFIG_VALUE) {
            // Update the current showing RectF.
            mTmpRectF = new RectF(mPrevStartCoordsOnScreen.x,
                    mPrevStartCoordsOnScreen.y,
                    mPrevStartCoordsOnScreen.x + mBitmap.getWidth(),
                    mPrevStartCoordsOnScreen.y + mBitmap.getHeight());

            // Update only if we are currently showing content that has been declared as invalid.
            if (RectF.intersects(dirtyRectOnScreen, mTmpRectF)) {
                // Update the contents shown in the magnifier.
                showInternal(mPrevCenterCoordsOnScreen.x, mPrevCenterCoordsOnScreen.y, mPrevScale,
                        true /* forceShow */);
            }
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

    private void resizeBitmap(float scale) {
        final int bitmapWidth = (int) (mWindowWidth / scale);
        final int bitmapHeight = (int) (mWindowHeight / scale);
        mBitmap.reconfigure(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
        getImageView().setImageBitmap(mBitmap);
    }

    private void configureCoordinates(float posXOnScreen, float posYOnScreen) {
        mCenterZoomCoords.x = (int) posXOnScreen;
        mCenterZoomCoords.y = (int) posYOnScreen;

        final int verticalMagnifierOffset = mView.getContext().getResources().getDimensionPixelSize(
                R.dimen.magnifier_offset);
        mWindowCoords.x = mCenterZoomCoords.x - mWindowWidth / 2;
        mWindowCoords.y = mCenterZoomCoords.y - mWindowHeight / 2 - verticalMagnifierOffset;
    }

    private void maybePerformPixelCopy(final float scale, final boolean forceShow) {
        final int startY = mCenterZoomCoords.y - mBitmap.getHeight() / 2;
        int rawStartX = mCenterZoomCoords.x - mBitmap.getWidth() / 2;

        // Clamp startX value to avoid distorting the rendering of the magnifier content.
        if (rawStartX < 0) {
            rawStartX = 0;
        } else if (rawStartX + mBitmap.getWidth() > mView.getWidth()) {
            rawStartX = mView.getWidth() - mBitmap.getWidth();
        }

        if (!forceShow && rawStartX == mPrevStartCoordsOnScreen.x
                && startY == mPrevStartCoordsOnScreen.y
                && scale == mPrevScale) {
            // Skip, we are already showing the desired content.
            return;
        }

        final int startX = rawStartX;
        final ViewRootImpl viewRootImpl = mView.getViewRootImpl();

        if (viewRootImpl != null && viewRootImpl.mSurface != null
                && viewRootImpl.mSurface.isValid()) {
            PixelCopy.request(
                    viewRootImpl.mSurface,
                    new Rect(startX, startY, startX + mBitmap.getWidth(),
                            startY + mBitmap.getHeight()),
                    mBitmap,
                    result -> {
                        getImageView().invalidate();
                        mPrevStartCoordsOnScreen.x = startX;
                        mPrevStartCoordsOnScreen.y = startY;
                    },
                    mPixelCopyHandler);
        } else {
            Log.d(LOG_TAG, "Could not perform PixelCopy request");
        }
    }

    private ImageView getImageView() {
        return mWindow.getContentView().findViewById(R.id.magnifier_image);
    }
}
