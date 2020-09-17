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

import java.lang.ref.WeakReference;
import java.util.function.Consumer;

/**
 * Provides a ScrollCaptureCallback implementation for to handle arbitrary View-based scrolling
 * containers.
 * <p>
 * To use this class, supply the target view and an implementation of {@ScrollCaptureViewHelper}
 * to the callback.
 *
 * @param <V> the specific View subclass handled
 * @hide
 */
public class ScrollCaptureViewSupport<V extends View> implements ScrollCaptureCallback {

    private final WeakReference<V> mWeakView;
    private final ScrollCaptureViewHelper<V> mViewHelper;
    private ViewRenderer mRenderer;
    private Handler mUiHandler;
    private boolean mStarted;
    private boolean mEnded;

    static <V extends View> ScrollCaptureCallback createCallback(V view,
            ScrollCaptureViewHelper<V> impl) {
        return new ScrollCaptureViewSupport<>(view, impl);
    }

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
        Rect captureArea = mViewHelper.onScrollRequested(view, session.getScrollBounds(),
                requestRect);
        mRenderer.renderFrame(view, captureArea, mUiHandler,
                () -> session.notifyBufferSent(0, captureArea));
    }

    @Override
    public final void onScrollCaptureEnd(Runnable onReady) {
        V view = mWeakView.get();
        if (mStarted && !mEnded) {
            mViewHelper.onPrepareForEnd(view);
            /* empty */
            mEnded = true;
            mRenderer.trimMemory();
            mRenderer.setSurface(null);
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
        private RenderNode mRootRenderNode;
        private final RectF mTempRectF = new RectF();
        private final Rect mSourceRect = new Rect();
        private final Rect mTempRect = new Rect();
        private final Matrix mTempMatrix = new Matrix();
        private final int[] mTempLocation = new int[2];
        private long mLastRenderedSourceDrawingId = -1;


        ViewRenderer() {
            mRenderer = new HardwareRenderer();
            mRootRenderNode = new RenderNode("ScrollCaptureRoot");
            mRenderer.setContentRoot(mRootRenderNode);

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

        public void renderFrame(View localReference, Rect sourceRect, Handler handler,
                Runnable onFrameCommitted) {
            if (updateForView(localReference)) {
                setupLighting(localReference);
            }
            buildRootDisplayList(localReference, sourceRect);
            HardwareRenderer.FrameRenderRequest request = mRenderer.createRenderRequest();
            request.setVsyncTime(SystemClock.elapsedRealtimeNanos());
            request.setFrameCommitCallback(handler::post, onFrameCommitted);
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

        private void buildRootDisplayList(View source, Rect localSourceRect) {
            final View captureSource = source.getRootView();
            transformToRoot(source, localSourceRect, mTempRect);
            mRootRenderNode.setPosition(0, 0, mTempRect.width(), mTempRect.height());
            RecordingCanvas canvas = mRootRenderNode.beginRecording(mTempRect.width(),
                    mTempRect.height());
            canvas.translate(-mTempRect.left, -mTempRect.top);
            canvas.drawRenderNode(captureSource.updateDisplayListIfDirty());
            mRootRenderNode.endRecording();
        }
    }
}
