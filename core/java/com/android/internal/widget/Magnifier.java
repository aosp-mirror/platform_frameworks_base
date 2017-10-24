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
import android.graphics.Rect;
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

import java.util.Timer;
import java.util.TimerTask;

/**
 * Android magnifier widget. Can be used by any view which is attached to window.
 */
public final class Magnifier {
    private static final String LOG_TAG = "magnifier";
    private static final int MAGNIFIER_REFRESH_RATE_MS = 33; // ~30fps
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
    // Current magnification scale.
    private final float mZoomScale;
    // Timer used to schedule the copy task.
    private Timer mTimer;

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

        final int bitmapWidth = (int) (mWindowWidth / mZoomScale);
        final int bitmapHeight = (int) (mWindowHeight / mZoomScale);
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
        if (xPosInView < 0) {
            xPosInView = 0;
        }

        if (yPosInView < 0) {
            yPosInView = 0;
        }

        configureCoordinates(xPosInView, yPosInView);

        if (mTimer == null) {
            mTimer = new Timer();
            mTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    performPixelCopy();
                }
            }, 0 /* delay */, MAGNIFIER_REFRESH_RATE_MS);
        }

        if (mWindow.isShowing()) {
            mWindow.update(mWindowCoords.x, mWindowCoords.y, mWindow.getWidth(),
                    mWindow.getHeight());
        } else {
            mWindow.showAtLocation(mView.getRootView(), Gravity.NO_GRAVITY,
                    mWindowCoords.x, mWindowCoords.y);
        }
    }

    /**
     * Dismisses the magnifier from the screen.
     */
    public void dismiss() {
        mWindow.dismiss();

        if (mTimer != null) {
            mTimer.cancel();
            mTimer.purge();
            mTimer = null;
        }
    }

    /**
     * @return the height of the magnifier window.
     */
    @NonNull
    public int getHeight() {
        return mWindowHeight;
    }

    /**
     * @return the width of the magnifier window.
     */
    @NonNull
    public int getWidth() {
        return mWindowWidth;
    }

    /**
     * @return the zoom scale of the magnifier.
     */
    @NonNull
    public float getZoomScale() {
        return mZoomScale;
    }

    private void configureCoordinates(float xPosInView, float yPosInView) {
        final int[] coordinatesOnScreen = new int[2];
        mView.getLocationOnScreen(coordinatesOnScreen);
        final float posXOnScreen = xPosInView + coordinatesOnScreen[0];
        final float posYOnScreen = yPosInView + coordinatesOnScreen[1];

        mCenterZoomCoords.x = (int) posXOnScreen;
        mCenterZoomCoords.y = (int) posYOnScreen;

        final int verticalMagnifierOffset = mView.getContext().getResources().getDimensionPixelSize(
                R.dimen.magnifier_offset);
        mWindowCoords.x = mCenterZoomCoords.x - mWindowWidth / 2;
        mWindowCoords.y = mCenterZoomCoords.y - mWindowHeight / 2 - verticalMagnifierOffset;
    }

    private void performPixelCopy() {
        final int startY = mCenterZoomCoords.y - mBitmap.getHeight() / 2;
        int rawStartX = mCenterZoomCoords.x - mBitmap.getWidth() / 2;

        // Clamp startX value to avoid distorting the rendering of the magnifier content.
        if (rawStartX < 0) {
            rawStartX = 0;
        } else if (rawStartX + mBitmap.getWidth() > mView.getWidth()) {
            rawStartX = mView.getWidth() - mBitmap.getWidth();
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
                    result -> getImageView().invalidate(),
                    mPixelCopyHandler);
        } else {
            Log.d(LOG_TAG, "Could not perform PixelCopy request");
        }
    }

    private ImageView getImageView() {
        return mWindow.getContentView().findViewById(R.id.magnifier_image);
    }
}
