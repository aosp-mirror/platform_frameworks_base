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

package com.android.internal.view;

import android.annotation.UiThread;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.HardwareRenderer;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.os.CancellationSignal;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display.ColorMode;
import android.view.ScrollCaptureCallback;
import android.view.ScrollCaptureSession;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;

import com.android.internal.view.ScrollCaptureViewHelper.ScrollResult;

import java.lang.ref.WeakReference;
import java.util.function.Consumer;

/**
 * Provides a base ScrollCaptureCallback implementation to handle arbitrary View-based scrolling
 * containers. This class handles the bookkeeping aspects of {@link ScrollCaptureCallback}
 * including rendering output using HWUI. Adaptable to any {@link View} using
 * {@link ScrollCaptureViewHelper}.
 *
 * @param <V> the specific View subclass handled
 * @see ScrollCaptureViewHelper
 */
@UiThread
public class ScrollCaptureViewSupport<V extends View> implements ScrollCaptureCallback {

    private static final String TAG = "ScrollCaptureViewSupport";

    private static final String SETTING_CAPTURE_DELAY = "screenshot.scroll_capture_delay";
    private static final long SETTING_CAPTURE_DELAY_DEFAULT = 60L; // millis

    private final WeakReference<V> mWeakView;
    private final ScrollCaptureViewHelper<V> mViewHelper;
    private final ViewRenderer mRenderer;
    private final long mPostScrollDelayMillis;

    private boolean mStarted;
    private boolean mEnded;

    ScrollCaptureViewSupport(V containingView, ScrollCaptureViewHelper<V> viewHelper) {
        mWeakView = new WeakReference<>(containingView);
        mRenderer = new ViewRenderer();
        // TODO(b/177649144): provide access to color space from android.media.Image
        mViewHelper = viewHelper;
        Context context = containingView.getContext();
        ContentResolver contentResolver = context.getContentResolver();
        mPostScrollDelayMillis = Settings.Global.getLong(contentResolver,
                SETTING_CAPTURE_DELAY, SETTING_CAPTURE_DELAY_DEFAULT);
    }

    /** Based on ViewRootImpl#updateColorModeIfNeeded */
    @ColorMode
    private static int getColorMode(View containingView) {
        Context context = containingView.getContext();
        int colorMode = containingView.getViewRootImpl().mWindowAttributes.getColorMode();
        if (!context.getResources().getConfiguration().isScreenWideColorGamut()) {
            colorMode = ActivityInfo.COLOR_MODE_DEFAULT;
        }
        return colorMode;
    }

    /**
     * Maps a rect in request bounds relative space  (relative to requestBounds) to container-local
     * space, accounting for the provided value of scrollY.
     *
     * @param scrollY the current scroll offset to apply to rect
     * @param requestBounds defines the local coordinate space of rect, within the container
     * @param requestRect the rectangle to transform to container-local coordinates
     * @return the same rectangle mapped to container bounds
     */
    public static Rect transformFromRequestToContainer(int scrollY, Rect requestBounds,
            Rect requestRect) {
        Rect requestedContainerBounds = new Rect(requestRect);
        requestedContainerBounds.offset(0, -scrollY);
        requestedContainerBounds.offset(requestBounds.left, requestBounds.top);
        return requestedContainerBounds;
    }

    /**
     * Maps a rect in container-local coordinate space to request space (relative to
     * requestBounds), accounting for the provided value of scrollY.
     *
     * @param scrollY the current scroll offset of the container
     * @param requestBounds defines the local coordinate space of rect, within the container
     * @param containerRect the rectangle within the container local coordinate space
     * @return the same rectangle mapped to within request bounds
     */
    public static Rect transformFromContainerToRequest(int scrollY, Rect requestBounds,
            Rect containerRect) {
        Rect requestRect = new Rect(containerRect);
        requestRect.offset(-requestBounds.left, -requestBounds.top);
        requestRect.offset(0, scrollY);
        return requestRect;
    }

    /**
     * Implements the core contract of requestRectangleOnScreen. Given a bounding rect and
     * another rectangle, return the minimum scroll distance that will maximize the visible area
     * of the requested rectangle.
     *
     * @param parentVisibleBounds the visible area
     * @param requested the requested area
     */
    public static int computeScrollAmount(Rect parentVisibleBounds, Rect requested) {
        final int height = parentVisibleBounds.height();
        final int top = parentVisibleBounds.top;
        final int bottom = parentVisibleBounds.bottom;
        int scrollYDelta = 0;

        if (requested.bottom > bottom && requested.top > top) {
            // need to scroll DOWN (move views up) to get it in view:
            // move just enough so that the entire rectangle is in view
            // (or at least the first screen size chunk).

            if (requested.height() > height) {
                // just enough to get screen size chunk on
                scrollYDelta += (requested.top - top);
            } else {
                // entire rect at bottom
                scrollYDelta += (requested.bottom - bottom);
            }
        } else if (requested.top < top && requested.bottom < bottom) {
            // need to scroll UP (move views down) to get it in view:
            // move just enough so that entire rectangle is in view
            // (or at least the first screen size chunk of it).

            if (requested.height() > height) {
                // screen size chunk
                scrollYDelta -= (bottom - requested.bottom);
            } else {
                // entire rect at top
                scrollYDelta -= (top - requested.top);
            }
        }
        return scrollYDelta;
    }

    /**
     * Locate a view to use as a reference, given an anticipated scrolling movement.
     * <p>
     * This view will be used to measure the actual movement of child views after scrolling.
     * When scrolling down, the last (max(y)) view is used, otherwise the first (min(y)
     * view. This helps to avoid recycling the reference view as a side effect of scrolling.
     *
     * @param parent the scrolling container
     * @param expectedScrollDistance the amount of scrolling to perform
     */
    public static View findScrollingReferenceView(ViewGroup parent, int expectedScrollDistance) {
        View selected = null;
        Rect parentLocalVisible = new Rect();
        parent.getLocalVisibleRect(parentLocalVisible);

        final int childCount = parent.getChildCount();
        for (int i = 0; i < childCount; i++) {
            View child = parent.getChildAt(i);
            if (selected == null) {
                selected = child;
            } else if (expectedScrollDistance < 0) {
                if (child.getTop() < selected.getTop()) {
                    selected = child;
                }
            } else if (child.getBottom() > selected.getBottom()) {
                selected = child;
            }
        }
        return selected;
    }

    @Override
    public final void onScrollCaptureSearch(CancellationSignal signal, Consumer<Rect> onReady) {
        if (signal.isCanceled()) {
            return;
        }
        V view = mWeakView.get();
        mStarted = false;
        mEnded = false;

        if (view != null && view.isVisibleToUser() && mViewHelper.onAcceptSession(view)) {
            onReady.accept(mViewHelper.onComputeScrollBounds(view));
            return;
        }
        onReady.accept(null);
    }

    @Override
    public final void onScrollCaptureStart(ScrollCaptureSession session, CancellationSignal signal,
            Runnable onReady) {
        if (signal.isCanceled()) {
            return;
        }
        V view = mWeakView.get();

        mEnded = false;
        mStarted = true;

        // Note: If somehow the view is already gone or detached, the first call to
        // {@code onScrollCaptureImageRequest} will return an error and request the session to
        // end.
        if (view != null && view.isVisibleToUser()) {
            mRenderer.setSurface(session.getSurface());
            mViewHelper.onPrepareForStart(view, session.getScrollBounds());
        }
        onReady.run();
    }

    @Override
    public final void onScrollCaptureImageRequest(ScrollCaptureSession session,
            CancellationSignal signal, Rect requestRect, Consumer<Rect> onComplete) {
        if (signal.isCanceled()) {
            Log.w(TAG, "onScrollCaptureImageRequest: cancelled!");
            return;
        }

        V view = mWeakView.get();
        if (view == null || !view.isVisibleToUser()) {
            // Signal to the controller that we have a problem and can't continue.
            onComplete.accept(new Rect());
            return;
        }

        // Ask the view to scroll as needed to bring this area into view.
        ScrollResult scrollResult = mViewHelper.onScrollRequested(view, session.getScrollBounds(),
                requestRect);

        if (scrollResult.availableArea.isEmpty()) {
            onComplete.accept(scrollResult.availableArea);
            return;
        }

        // For image capture, shift back by scrollDelta to arrive at the location within the view
        // where the requested content will be drawn
        Rect viewCaptureArea = new Rect(scrollResult.availableArea);
        viewCaptureArea.offset(0, -scrollResult.scrollDelta);

        Runnable captureAction = () -> {
            if (signal.isCanceled()) {
                Log.w(TAG, "onScrollCaptureImageRequest: cancelled! skipping render.");
            } else {
                mRenderer.renderView(view, viewCaptureArea);
                onComplete.accept(new Rect(scrollResult.availableArea));
            }
        };

        view.postOnAnimationDelayed(captureAction, mPostScrollDelayMillis);
    }

    @Override
    public final void onScrollCaptureEnd(Runnable onReady) {
        V view = mWeakView.get();
        if (mStarted && !mEnded) {
            if (view != null) {
                mViewHelper.onPrepareForEnd(view);
                view.invalidate();
            }
            mEnded = true;
            mRenderer.destroy();
        }
        onReady.run();
    }

    /**
     * Internal helper class which assists in rendering sections of the view hierarchy relative to a
     * given view.
     */
    static final class ViewRenderer {
        // alpha, "reasonable default" from Javadoc
        private static final float AMBIENT_SHADOW_ALPHA = 0.039f;
        private static final float SPOT_SHADOW_ALPHA = 0.039f;

        // Default values:
        //    lightX = (screen.width() / 2) - windowLeft
        //    lightY = 0 - windowTop
        //    lightZ = 600dp
        //    lightRadius = 800dp
        private static final float LIGHT_Z_DP = 400;
        private static final float LIGHT_RADIUS_DP = 800;
        private static final String TAG = "ViewRenderer";

        private final HardwareRenderer mRenderer;
        private final RenderNode mCaptureRenderNode;
        private final Rect mTempRect = new Rect();
        private final int[] mTempLocation = new int[2];
        private long mLastRenderedSourceDrawingId = -1;
        private Surface mSurface;

        ViewRenderer() {
            mRenderer = new HardwareRenderer();
            mRenderer.setName("ScrollCapture");
            mCaptureRenderNode = new RenderNode("ScrollCaptureRoot");
            mRenderer.setContentRoot(mCaptureRenderNode);

            // TODO: Figure out a way to flip this on when we are sure the source window is opaque
            mRenderer.setOpaque(false);
        }

        public void setSurface(Surface surface) {
            mSurface = surface;
            mRenderer.setSurface(surface);
        }

        /**
         * Cache invalidation check. If the source view is the same as the previous call (which is
         * mostly always the case, then we can skip setting up lighting on each call (for now)
         *
         * @return true if the view changed, false if the view was previously rendered by this class
         */
        private boolean updateForView(View source) {
            if (mLastRenderedSourceDrawingId == source.getUniqueDrawingId()) {
                return false;
            }
            mLastRenderedSourceDrawingId = source.getUniqueDrawingId();
            return true;
        }

        // TODO: may need to adjust lightY based on the virtual canvas position to get
        //       consistent shadow positions across the whole capture. Or possibly just
        //       pull lightZ way back to make shadows more uniform.
        private void setupLighting(View mSource) {
            mLastRenderedSourceDrawingId = mSource.getUniqueDrawingId();
            DisplayMetrics metrics = mSource.getResources().getDisplayMetrics();
            mSource.getLocationOnScreen(mTempLocation);
            final float lightX = metrics.widthPixels / 2f - mTempLocation[0];
            final float lightY = metrics.heightPixels - mTempLocation[1];
            final int lightZ = (int) (LIGHT_Z_DP * metrics.density);
            final int lightRadius = (int) (LIGHT_RADIUS_DP * metrics.density);

            // Enable shadows for elevation/Z
            mRenderer.setLightSourceGeometry(lightX, lightY, lightZ, lightRadius);
            mRenderer.setLightSourceAlpha(AMBIENT_SHADOW_ALPHA, SPOT_SHADOW_ALPHA);
        }

        private void updateRootNode(View source, Rect localSourceRect) {
            final View rootView = source.getRootView();
            transformToRoot(source, localSourceRect, mTempRect);

            mCaptureRenderNode.setPosition(0, 0, mTempRect.width(), mTempRect.height());
            RecordingCanvas canvas = mCaptureRenderNode.beginRecording();
            canvas.enableZ();
            canvas.translate(-mTempRect.left, -mTempRect.top);

            RenderNode rootViewRenderNode = rootView.updateDisplayListIfDirty();
            if (rootViewRenderNode.hasDisplayList()) {
                canvas.drawRenderNode(rootViewRenderNode);
            }
            mCaptureRenderNode.endRecording();
        }

        public void renderView(View view, Rect sourceRect) {
            if (updateForView(view)) {
                setupLighting(view);
            }
            view.invalidate();
            updateRootNode(view, sourceRect);
            HardwareRenderer.FrameRenderRequest request = mRenderer.createRenderRequest();
            long timestamp = System.nanoTime();
            request.setVsyncTime(timestamp);

            // Would be nice to access nextFrameNumber from HwR without having to hold on to Surface
            final long frameNumber = mSurface.getNextFrameNumber();

            // Block until a frame is presented to the Surface
            request.setWaitForPresent(true);

            switch (request.syncAndDraw()) {
                case HardwareRenderer.SYNC_OK:
                case HardwareRenderer.SYNC_REDRAW_REQUESTED:
                    return;

                case HardwareRenderer.SYNC_FRAME_DROPPED:
                    Log.e(TAG, "syncAndDraw(): SYNC_FRAME_DROPPED !");
                    break;
                case HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND:
                    Log.e(TAG, "syncAndDraw(): SYNC_LOST_SURFACE !");
                    break;
                case HardwareRenderer.SYNC_CONTEXT_IS_STOPPED:
                    Log.e(TAG, "syncAndDraw(): SYNC_CONTEXT_IS_STOPPED !");
                    break;
            }
        }

        public void trimMemory() {
            mRenderer.clearContent();
        }

        public void destroy() {
            mSurface = null;
            mRenderer.destroy();
        }

        private void transformToRoot(View local, Rect localRect, Rect outRect) {
            local.getLocationInWindow(mTempLocation);
            outRect.set(localRect);
            outRect.offset(mTempLocation[0], mTempLocation[1]);
        }

        public void setColorMode(@ColorMode int colorMode) {
            mRenderer.setColorMode(colorMode);
        }
    }

    @Override
    public String toString() {
        return "ScrollCaptureViewSupport{"
                + "view=" + mWeakView.get()
                + ", helper=" + mViewHelper
                + '}';
    }
}
