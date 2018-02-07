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
import android.annotation.TestApi;
import android.annotation.UiThread;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.view.Display;
import android.view.DisplayListCanvas;
import android.view.LayoutInflater;
import android.view.PixelCopy;
import android.view.RenderNode;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceSession;
import android.view.SurfaceView;
import android.view.ThreadedRenderer;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewRootImpl;

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
    private InternalPopupWindow mWindow;
    // The center coordinates of the window containing the magnifier.
    private final Point mWindowCoords = new Point();
    // The width of the window containing the magnifier.
    private final int mWindowWidth;
    // The height of the window containing the magnifier.
    private final int mWindowHeight;
    // The bitmap used to display the contents of the magnifier.
    private final Bitmap mBitmap;
    // The elevation of the window containing the magnifier.
    private final float mWindowElevation;
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
    // Rectangle defining the view surface area we pixel copy content from.
    private final Rect mPixelCopyRequestRect = new Rect();

    /**
     * Initializes a magnifier.
     *
     * @param view the view for which this magnifier is attached
     */
    public Magnifier(@NonNull View view) {
        mView = Preconditions.checkNotNull(view);
        final Context context = mView.getContext();
        final View content = LayoutInflater.from(context).inflate(
                com.android.internal.R.layout.magnifier, null);
        content.findViewById(com.android.internal.R.id.magnifier_inner).setClipToOutline(true);
        mWindowWidth = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.magnifier_width);
        mWindowHeight = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.magnifier_height);
        mWindowElevation = context.getResources().getDimension(
                com.android.internal.R.dimen.magnifier_elevation);
        mZoomScale = context.getResources().getFloat(
                com.android.internal.R.dimen.magnifier_zoom_scale);
        // The view's surface coordinates will not be updated until the magnifier is first shown.
        mViewCoordinatesInSurface = new int[2];

        final int bitmapWidth = Math.round(mWindowWidth / mZoomScale);
        final int bitmapHeight = Math.round(mWindowHeight / mZoomScale);
        mBitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888);
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

        // Clamp the startX value to avoid magnifying content which does not belong to the magnified
        // view. This will not take into account overlapping views.
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
            if (mWindow == null) {
                mWindow = new InternalPopupWindow(mView.getContext(), mView.getDisplay(),
                        getValidViewSurface(), mWindowWidth, mWindowHeight, mWindowElevation);
            }
            performPixelCopy(startX, startY, true /* update window position */);
            mPrevPosInView.x = xPosInView;
            mPrevPosInView.y = yPosInView;
        }
    }

    @Nullable
    private Surface getValidViewSurface() {
        // TODO: deduplicate this against the first part of #performPixelCopy
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

    /**
     * Dismisses the magnifier from the screen. Calling this on a dismissed magnifier is a no-op.
     */
    public void dismiss() {
        if (mWindow != null) {
            mWindow.destroy();
            mWindow = null;
        }
    }

    /**
     * Forces the magnifier to update its content. It uses the previous coordinates passed to
     * {@link #show(float, float)}. This only happens if the magnifier is currently showing.
     */
    public void update() {
        if (mWindow != null) {
            // Update the content shown in the magnifier.
            performPixelCopy(mPrevStartCoordsInSurface.x, mPrevStartCoordsInSurface.y,
                    false /* update window position */);
        }
    }

    private void configureCoordinates(final float xPosInView, final float yPosInView) {
        // Compute the coordinates of the center of the content going to be displayed in the
        // magnifier. These are relative to the surface the content is copied from.
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

        // Compute the position of the magnifier window. Again, this has to be relative to the
        // surface of the magnified view, as this surface is the parent of the magnifier surface.
        final int verticalOffset = mView.getContext().getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.magnifier_offset);
        mWindowCoords.x = mCenterZoomCoords.x - mWindowWidth / 2;
        mWindowCoords.y = mCenterZoomCoords.y - mWindowHeight / 2 - verticalOffset;
    }

    private void performPixelCopy(final int startXInSurface, final int startYInSurface,
            final boolean updateWindowPosition) {
        // Get the view surface where the content will be copied from.
        final Surface surface;
        final int surfaceWidth;
        final int surfaceHeight;
        if (mView instanceof SurfaceView) {
            final SurfaceHolder surfaceHolder = ((SurfaceView) mView).getHolder();
            surface = surfaceHolder.getSurface();
            surfaceWidth = surfaceHolder.getSurfaceFrame().right;
            surfaceHeight = surfaceHolder.getSurfaceFrame().bottom;
        } else if (mView.getViewRootImpl() != null) {
            final ViewRootImpl viewRootImpl = mView.getViewRootImpl();
            surface = viewRootImpl.mSurface;
            surfaceWidth = viewRootImpl.getWidth();
            surfaceHeight = viewRootImpl.getHeight();
        } else {
            surface = null;
            surfaceWidth = NONEXISTENT_PREVIOUS_CONFIG_VALUE;
            surfaceHeight = NONEXISTENT_PREVIOUS_CONFIG_VALUE;
        }

        if (surface == null || !surface.isValid()) {
            return;
        }

        // Clamp copy coordinates inside the surface to avoid displaying distorted content.
        final int clampedStartXInSurface = Math.max(0,
                Math.min(startXInSurface, surfaceWidth - mWindowWidth));
        final int clampedStartYInSurface = Math.max(0,
                Math.min(startYInSurface, surfaceHeight - mWindowHeight));

        // Perform the pixel copy.
        mPixelCopyRequestRect.set(clampedStartXInSurface,
                clampedStartYInSurface,
                clampedStartXInSurface + mBitmap.getWidth(),
                clampedStartYInSurface + mBitmap.getHeight());
        final int windowCoordsX = mWindowCoords.x;
        final int windowCoordsY = mWindowCoords.y;
        PixelCopy.request(surface, mPixelCopyRequestRect, mBitmap,
                result -> {
                    if (mWindow != null) {
                        if (updateWindowPosition) {
                            // TODO: pull the magnifier position update outside #performPixelCopy
                            mWindow.setContentPositionForNextDraw(windowCoordsX, windowCoordsY);
                        }
                        mWindow.updateContent(mBitmap);
                    }
                },
                mPixelCopyHandler);
        mPrevStartCoordsInSurface.x = startXInSurface;
        mPrevStartCoordsInSurface.y = startYInSurface;
    }

    /**
     * Magnifier's own implementation of PopupWindow-similar floating window.
     * This exists to ensure frame-synchronization between window position updates and window
     * content updates. By using a PopupWindow, these events would happen in different frames,
     * producing a shakiness effect for the magnifier content.
     */
    private static class InternalPopupWindow {
        // Display associated to the view the magnifier is attached to.
        private final Display mDisplay;
        // The size of the content of the magnifier.
        private final int mContentWidth;
        private final int mContentHeight;
        // The size of the allocated surface.
        private final int mSurfaceWidth;
        private final int mSurfaceHeight;
        // The insets of the content inside the allocated surface.
        private final int mOffsetX;
        private final int mOffsetY;
        // Whether the next draw will be the first one for the current instance.
        private boolean mFirstDraw = true;
        // The window position in the parent surface. Might be applied during the next draw,
        // when mPendingWindowPositionUpdate is true.
        private int mWindowPositionX;
        private int mWindowPositionY;
        private boolean mPendingWindowPositionUpdate;
        // The surface we allocate for the magnifier content + shadow.
        private final SurfaceSession mSurfaceSession;
        private final SurfaceControl mSurfaceControl;
        private final Surface mSurface;
        // The renderer used for the allocated surface.
        private final ThreadedRenderer.SimpleRenderer mRenderer;
        // The RenderNode used to draw the magnifier content in the surface.
        private final RenderNode mBitmapRenderNode;

        InternalPopupWindow(final Context context, final Display display,
                final Surface parentSurface,
                final int width, final int height, final float elevation) {
            mDisplay = display;

            mContentWidth = width;
            mContentHeight = height;
            mOffsetX = (int) (0.1f * width);
            mOffsetY = (int) (0.1f * height);
            // Setup the surface we will use for drawing the content and shadow.
            mSurfaceWidth = mContentWidth + 2 * mOffsetX;
            mSurfaceHeight = mContentHeight + 2 * mOffsetY;
            mSurfaceSession = new SurfaceSession(parentSurface);
            mSurfaceControl = new SurfaceControl.Builder(mSurfaceSession)
                    .setFormat(PixelFormat.TRANSLUCENT)
                    .setSize(mSurfaceWidth, mSurfaceHeight)
                    .setName("magnifier surface")
                    .setFlags(SurfaceControl.HIDDEN)
                    .build();
            mSurface = new Surface();
            mSurface.copyFrom(mSurfaceControl);

            // Setup the RenderNode tree. The root has only one child, which contains the bitmap.
            mRenderer = new ThreadedRenderer.SimpleRenderer(
                    context,
                    "magnifier renderer",
                    mSurface
            );
            mBitmapRenderNode = createRenderNodeForBitmap(
                    "magnifier content",
                    elevation
            );
            final DisplayListCanvas canvas = mRenderer.getRootNode().start(width, height);
            try {
                canvas.insertReorderBarrier();
                canvas.drawRenderNode(mBitmapRenderNode);
                canvas.insertInorderBarrier();
            } finally {
                mRenderer.getRootNode().end(canvas);
            }
        }

        private RenderNode createRenderNodeForBitmap(final String name, final float elevation) {
            final RenderNode bitmapRenderNode = RenderNode.create(name, null);

            // Define the position of the bitmap in the parent render node. The surface regions
            // outside the bitmap are used to draw elevation.
            bitmapRenderNode.setLeftTopRightBottom(mOffsetX, mOffsetY,
                    mOffsetX + mContentWidth, mOffsetY + mContentHeight);
            bitmapRenderNode.setElevation(elevation);

            final Outline outline = new Outline();
            outline.setRoundRect(0, 0, mContentWidth, mContentHeight, 3);
            outline.setAlpha(1.0f);
            bitmapRenderNode.setOutline(outline);
            bitmapRenderNode.setClipToOutline(true);

            // Create a dummy draw, which will be replaced later with real drawing.
            final DisplayListCanvas canvas = bitmapRenderNode.start(mContentWidth, mContentHeight);
            try {
                canvas.drawColor(0xFF00FF00);
            } finally {
                bitmapRenderNode.end(canvas);
            }

            return bitmapRenderNode;
        }

        /**
         * Sets the position of the magnifier content relative to the parent surface.
         * The position update will happen in the same frame with the next draw.
         *
         * @param contentX the x coordinate of the content
         * @param contentY the y coordinate of the content
         */
        public void setContentPositionForNextDraw(final int contentX, final int contentY) {
            mWindowPositionX = contentX - mOffsetX;
            mWindowPositionY = contentY - mOffsetY;
            mPendingWindowPositionUpdate = true;
        }

        /**
         * Sets the content that should be displayed in the magnifier.
         * The update happens immediately, and possibly triggers a pending window movement set
         * by {@link #setContentPositionForNextDraw(int, int)}.
         *
         * @param bitmap the content bitmap
         */
        public void updateContent(final Bitmap bitmap) {
            final DisplayListCanvas canvas = mBitmapRenderNode.start(mContentWidth, mContentHeight);
            try {
                final Rect srcRect = new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                final Rect dstRect = new Rect(0, 0, mContentWidth, mContentHeight);
                final Paint paint = new Paint();
                paint.setFilterBitmap(true);
                canvas.drawBitmap(bitmap, srcRect, dstRect, paint);
            } finally {
                mBitmapRenderNode.end(canvas);
            }

            doDraw();
        }

        /**
         * Destroys this instance.
         */
        public void destroy() {
            mRenderer.destroy();
            mSurface.destroy();
            mSurfaceControl.destroy();
            mSurfaceSession.kill();
            mBitmapRenderNode.destroy();
        }

        private void doDraw() {
            final ThreadedRenderer.FrameDrawingCallback callback;
            if (mPendingWindowPositionUpdate || mFirstDraw) {
                // If the window has to be shown or moved, defer this until the next draw.
                final boolean firstDraw = mFirstDraw;
                mFirstDraw = false;
                final boolean updateWindowPosition = mPendingWindowPositionUpdate;
                mPendingWindowPositionUpdate = false;
                final int pendingX = mWindowPositionX;
                final int pendingY = mWindowPositionY;

                callback = frame -> {
                    mRenderer.setLightCenter(mDisplay, pendingX, pendingY);
                    // Show or move the window at the content draw frame.
                    SurfaceControl.openTransaction();
                    mSurfaceControl.deferTransactionUntil(mSurface, frame);
                    if (updateWindowPosition) {
                        mSurfaceControl.setPosition(pendingX, pendingY);
                    }
                    if (firstDraw) {
                        mSurfaceControl.show();
                    }
                    SurfaceControl.closeTransaction();
                };
            } else {
                callback = null;
            }

            mRenderer.draw(callback);
        }
    }

    /**
     * @return the content being currently displayed in the magnifier, as bitmap
     *
     * @hide
     */
    @TestApi
    public Bitmap getContent() {
        return mBitmap;
    }

    /**
     * @return the position of the magnifier window relative to the screen
     *
     * @hide
     */
    @TestApi
    public Rect getWindowPositionOnScreen() {
        final int[] viewLocationOnScreen = new int[2];
        mView.getLocationOnScreen(viewLocationOnScreen);
        final int[] viewLocationInSurface = new int[2];
        mView.getLocationInSurface(viewLocationInSurface);

        final int left = mWindowCoords.x + viewLocationOnScreen[0] - viewLocationInSurface[0];
        final int top = mWindowCoords.y + viewLocationOnScreen[1] - viewLocationInSurface[1];
        return new Rect(left, top, left + mWindowWidth, top + mWindowHeight);
    }

    /**
     * @return the size of the magnifier window in dp
     *
     * @hide
     */
    @TestApi
    public static PointF getMagnifierDefaultSize() {
        final Resources resources = Resources.getSystem();
        final float density = resources.getDisplayMetrics().density;
        final PointF size = new PointF();
        size.x = resources.getDimension(com.android.internal.R.dimen.magnifier_width) / density;
        size.y = resources.getDimension(com.android.internal.R.dimen.magnifier_height) / density;
        return size;
    }
}
