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
 * limitations under the License.
 */

package android.widget;

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
import android.view.ViewParent;

import com.android.internal.util.Preconditions;

/**
 * Android magnifier widget. Can be used by any view which is attached to a window.
 */
@UiThread
public final class Magnifier {
    // Use this to specify that a previous configuration value does not exist.
    private static final int NONEXISTENT_PREVIOUS_CONFIG_VALUE = -1;
    // The view to which this magnifier is attached.
    private final View mView;
    // The coordinates of the view in the surface.
    private final int[] mViewCoordinatesInSurface;
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
    public Magnifier(@NonNull View view) {
        mView = Preconditions.checkNotNull(view);
        final Context context = mView.getContext();
        final float elevation = context.getResources().getDimension(
                com.android.internal.R.dimen.magnifier_elevation);
        final View content = LayoutInflater.from(context).inflate(
                com.android.internal.R.layout.magnifier, null);
        content.findViewById(com.android.internal.R.id.magnifier_inner).setClipToOutline(true);
        mWindowWidth = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.magnifier_width);
        mWindowHeight = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.magnifier_height);
        mZoomScale = context.getResources().getFloat(
                com.android.internal.R.dimen.magnifier_zoom_scale);
        // The view's surface coordinates will not be updated until the magnifier is first shown.
        mViewCoordinatesInSurface = new int[2];

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
     *        to the view. The lower end is clamped to 0 and the higher end is clamped to the view
     *        width.
     * @param yPosInView vertical coordinate of the center point of the magnifier source
     *        relative to the view. The lower end is clamped to 0 and the higher end is clamped to
     *        the view height.
     */
    public void show(@FloatRange(from = 0) float xPosInView,
            @FloatRange(from = 0) float yPosInView) {
        xPosInView = Math.max(0, Math.min(xPosInView, mView.getWidth()));
        yPosInView = Math.max(0, Math.min(yPosInView, mView.getHeight()));

        configureCoordinates(xPosInView, yPosInView);

        // Clamp startX value to avoid distorting the rendering of the magnifier content.
        // For this, we compute:
        // - zeroScrollXInSurface: this is the start x of mView, where this is not masked by a
        //                         potential scrolling container. For example, if mView is a
        //                         TextView contained in a HorizontalScrollView,
        //                         mViewCoordinatesInSurface will reflect the surface position of
        //                         the first text character, rather than the position of the first
        //                         visible one. Therefore, we need to add back the amount of
        //                         scrolling from the parent containers.
        // - actualWidth: similarly, the width of a View will be larger than its actually visible
        //                width when it is contained in a scrolling container. We need to use
        //                the minimum width of a scrolling container which contains this view.
        int zeroScrollXInSurface = mViewCoordinatesInSurface[0];
        int actualWidth = mView.getWidth();
        ViewParent viewParent = mView.getParent();
        while (viewParent instanceof View) {
            final View container = (View) viewParent;
            if (container.canScrollHorizontally(-1 /* left scroll */)
                    || container.canScrollHorizontally(1 /* right scroll */)) {
                zeroScrollXInSurface += container.getScrollX();
                actualWidth = Math.min(actualWidth, container.getWidth()
                        - container.getPaddingLeft() - container.getPaddingRight());
            }
            viewParent = viewParent.getParent();
        }

        final int startX = Math.max(zeroScrollXInSurface, Math.min(
                mCenterZoomCoords.x - mBitmap.getWidth() / 2,
                zeroScrollXInSurface + actualWidth - mBitmap.getWidth()));
        final int startY = mCenterZoomCoords.y - mBitmap.getHeight() / 2;

        if (xPosInView != mPrevPosInView.x || yPosInView != mPrevPosInView.y) {
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
     * Dismisses the magnifier from the screen. Calling this on a dismissed magnifier is a no-op.
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

    private void configureCoordinates(float xPosInView, float yPosInView) {
        final float posX;
        final float posY;

        if (mView instanceof SurfaceView) {
            // No offset required if the backing Surface matches the size of the SurfaceView.
            posX = xPosInView;
            posY = yPosInView;
        } else {
            mView.getLocationInSurface(mViewCoordinatesInSurface);
            posX = xPosInView + mViewCoordinatesInSurface[0];
            posY = yPosInView + mViewCoordinatesInSurface[1];
        }

        mCenterZoomCoords.x = Math.round(posX);
        mCenterZoomCoords.y = Math.round(posY);

        final int verticalMagnifierOffset = mView.getContext().getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.magnifier_offset);
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
        return mWindow.getContentView().findViewById(
                com.android.internal.R.id.magnifier_image);
    }
}
