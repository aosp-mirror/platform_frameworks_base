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

/**
 * Android magnifier widget. Can be used by any view which is attached to window.
 */
public final class Magnifier {
    private static final String LOG_TAG = "magnifier";
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
     * @param centerXOnScreen horizontal coordinate of the center point of the magnifier source
     * @param centerYOnScreen vertical coordinate of the center point of the magnifier source
     * @param scale the scale at which the magnifier zooms on the source content
     */
    public void show(@FloatRange(from=0) float centerXOnScreen,
            @FloatRange(from=0) float centerYOnScreen,
            @FloatRange(from=1, to=10) float scale) {
        maybeResizeBitmap(scale);
        configureCoordinates(centerXOnScreen, centerYOnScreen);
        performPixelCopy();

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

    private void maybeResizeBitmap(float scale) {
        final int bitmapWidth = (int) (mWindowWidth / scale);
        final int bitmapHeight = (int) (mWindowHeight / scale);
        if (mBitmap.getWidth() != bitmapWidth || mBitmap.getHeight() != bitmapHeight) {
            mBitmap.reconfigure(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
            getImageView().setImageBitmap(mBitmap);
        }
    }

    private void configureCoordinates(float posXOnScreen, float posYOnScreen) {
        mCenterZoomCoords.x = (int) posXOnScreen;
        mCenterZoomCoords.y = (int) posYOnScreen;

        final int verticalMagnifierOffset = mView.getContext().getResources().getDimensionPixelSize(
                R.dimen.magnifier_offset);
        final int availableTopSpace = (mCenterZoomCoords.y - mWindowHeight / 2)
                - verticalMagnifierOffset - (mBitmap.getHeight() / 2);

        mWindowCoords.x = mCenterZoomCoords.x - mWindowWidth / 2;
        mWindowCoords.y = mCenterZoomCoords.y - mWindowHeight / 2
                + verticalMagnifierOffset * (availableTopSpace > 0 ? -1 : 1);
    }

    private void performPixelCopy() {
        int startX = mCenterZoomCoords.x - mBitmap.getWidth() / 2;
        // Clamp startX value to avoid distorting the rendering of the magnifier content.
        if (startX < 0) {
            startX = 0;
        } else if (startX + mBitmap.getWidth() > mView.getWidth()) {
            startX = mView.getWidth() - mBitmap.getWidth();
        }

        final int startY = mCenterZoomCoords.y - mBitmap.getHeight() / 2;
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
