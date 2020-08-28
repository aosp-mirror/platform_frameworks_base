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

import android.graphics.HardwareRenderer;
import android.graphics.Matrix;
import android.graphics.RecordingCanvas;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.os.Handler;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.ScrollCaptureCallback;
import android.view.ScrollCaptureSession;
import android.view.Surface;
import android.view.View;

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
public class ScrollCaptureViewSupport<V extends View> implements ScrollCaptureCallback {

    private static final String TAG = "ScrollCaptureViewSupport";

    private final WeakReference<V> mWeakView;
    private final ScrollCaptureViewHelper<V> mViewHelper;
    private ViewRenderer mRenderer;
    private Handler mUiHandler;
    private boolean mStarted;
    private boolean mEnded;

    ScrollCaptureViewSupport(V containingView, ScrollCaptureViewHelper<V> viewHelper) {
        mWeakView = new WeakReference<>(containingView);
        mRenderer = new ViewRenderer();
        mUiHandler = containingView.getHandler();
        mViewHelper = viewHelper;
    }

    // Base implementation of ScrollCaptureCallback

    @Override
    public final void onScrollCaptureSearch(Consumer<Rect> onReady) {
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
    public final void onScrollCaptureStart(ScrollCaptureSession session, Runnable onReady) {
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
    public final void onScrollCaptureImageRequest(ScrollCaptureSession session, Rect requestRect) {
        V view = mWeakView.get();
        if (view == null || !view.isVisibleToUser()) {
            // Signal to the controller that we have a problem and can't continue.
            session.notifyBufferSent(0, null);
            return;
        }
        // Ask the view to scroll as needed to bring this area into view.
        ScrollResult scrollResult = mViewHelper.onScrollRequested(view, session.getScrollBounds(),
                requestRect);
        view.invalidate(); // don't wait for vsync

        // For image capture, shift back by scrollDelta to arrive at the location within the view
        // where the requested content will be drawn
        Rect viewCaptureArea = new Rect(scrollResult.availableArea);
        viewCaptureArea.offset(0, -scrollResult.scrollDelta);

        mRenderer.renderView(view, viewCaptureArea, mUiHandler,
                (frameNumber) -> session.notifyBufferSent(frameNumber, scrollResult.availableArea));
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
     * given view. Used by framework implementations of ScrollCaptureHandler to render and dispatch
     * image requests.
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

        private HardwareRenderer mRenderer;
        private RenderNode mCaptureRenderNode;
        private final RectF mTempRectF = new RectF();
        private final Rect mSourceRect = new Rect();
        private final Rect mTempRect = new Rect();
        private final Matrix mTempMatrix = new Matrix();
        private final int[] mTempLocation = new int[2];
        private long mLastRenderedSourceDrawingId = -1;


        public interface FrameCompleteListener {
            void onFrameComplete(long frameNumber);
        }

        ViewRenderer() {
            mRenderer = new HardwareRenderer();
            mCaptureRenderNode = new RenderNode("ScrollCaptureRoot");
            mRenderer.setContentRoot(mCaptureRenderNode);

            // TODO: Figure out a way to flip this on when we are sure the source window is opaque
            mRenderer.setOpaque(false);
        }

        public void setSurface(Surface surface) {
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

        public void renderView(View view, Rect sourceRect, Handler handler,
                FrameCompleteListener frameListener) {
            if (updateForView(view)) {
                setupLighting(view);
            }
            view.invalidate();
            updateRootNode(view, sourceRect);
            HardwareRenderer.FrameRenderRequest request = mRenderer.createRenderRequest();
            request.setVsyncTime(SystemClock.elapsedRealtimeNanos());
            // private API b/c request.setFrameCommitCallback does not provide access to frameNumber
            mRenderer.setFrameCompleteCallback(
                    frameNr -> handler.post(() -> frameListener.onFrameComplete(frameNr)));
            request.setWaitForPresent(true);
            request.syncAndDraw();
        }

        public void trimMemory() {
            mRenderer.clearContent();
        }

        public void destroy() {
            mRenderer.destroy();
        }

        private void transformToRoot(View local, Rect localRect, Rect outRect) {
            mTempMatrix.reset();
            local.transformMatrixToGlobal(mTempMatrix);
            mTempRectF.set(localRect);
            mTempMatrix.mapRect(mTempRectF);
            mTempRectF.round(outRect);
        }

    }
}
