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

package android.widget.inline;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.ref.WeakReference;
import java.util.function.Consumer;

/**
 * This class represents a view that holds opaque content from another app that you can inline in
 * your UI.
 *
 * <p>Since the content presented by this view is from another security domain,it is
 * shown on a remote surface preventing the host application from accessing that content. Also the
 * host application cannot interact with the inlined content by injecting touch events or clicking
 * programmatically.
 *
 * <p>This view can be overlaid by other windows, i.e. redressed, but if this is the case
 * the inlined UI would not be interactive. Sometimes this is desirable, e.g. animating transitions.
 *
 * <p>By default the surface backing this view is shown on top of the hosting window such
 * that the inlined content is interactive. However, you can temporarily move the surface under the
 * hosting window which could be useful in some cases, e.g. animating transitions. At this point the
 * inlined content will not be interactive and the touch events would be delivered to your app.
 *
 * <p> Instances of this class are created by the platform and can be programmatically attached to
 * your UI. Once the view is attached to the window, you may detach and reattach it to the window.
 * It should work seamlessly from the hosting process's point of view.
 */
public class InlineContentView extends ViewGroup {

    private static final String TAG = "InlineContentView";

    private static final boolean DEBUG = false;

    /**
     * Callback for observing the lifecycle of the surface control that manipulates the backing
     * secure embedded UI surface.
     */
    public interface SurfaceControlCallback {
        /**
         * Called when the backing surface is being created.
         *
         * @param surfaceControl The surface control to manipulate the surface.
         */
        void onCreated(@NonNull SurfaceControl surfaceControl);

        /**
         * Called when the backing surface is being destroyed.
         *
         * @param surfaceControl The surface control to manipulate the surface.
         */
        void onDestroyed(@NonNull SurfaceControl surfaceControl);
    }

    /**
     * Callback for sending an updated surface package in case the previous one is released
     * from the detached from window event, and for getting notified of such event.
     *
     * This is expected to be provided to the {@link InlineContentView} so it can get updates
     * from and send updates to the remote content (i.e. surface package) provider.
     *
     * @hide
     */
    @TestApi
    public interface SurfacePackageUpdater {


        /**
         * Called when the previous surface package is released due to view being detached
         * from the window.
         */
        void onSurfacePackageReleased();

        /**
         * Called to request an updated surface package.
         *
         * @param consumer consumes the updated surface package.
         */
        void getSurfacePackage(@NonNull Consumer<SurfaceControlViewHost.SurfacePackage> consumer);
    }

    @NonNull
    private final SurfaceHolder.Callback mSurfaceCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(@NonNull SurfaceHolder holder) {
            final SurfaceControl surfaceControl = mSurfaceView.getSurfaceControl();
            surfaceControl.addOnReparentListener(mOnReparentListener);
            mSurfaceControlCallback.onCreated(surfaceControl);
        }

        @Override
        public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width,
                int height) {
            /* do nothing */
        }

        @Override
        public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
            final SurfaceControl surfaceControl = mSurfaceView.getSurfaceControl();
            surfaceControl.removeOnReparentListener(mOnReparentListener);
            mSurfaceControlCallback.onDestroyed(surfaceControl);
        }
    };

    @NonNull
    private final SurfaceControl.OnReparentListener mOnReparentListener =
            new SurfaceControl.OnReparentListener() {
                @Override
                public void onReparent(SurfaceControl.Transaction transaction,
                        SurfaceControl parent) {
                    final View parentSurfaceOwnerView = (parent != null)
                            ? parent.getLocalOwnerView() : null;
                    if (parentSurfaceOwnerView instanceof SurfaceView) {
                        mParentSurfaceOwnerView = new WeakReference<>(
                                (SurfaceView) parentSurfaceOwnerView);
                    } else {
                        mParentSurfaceOwnerView = null;
                    }
                }
            };

    @NonNull
    private final ViewTreeObserver.OnDrawListener mOnDrawListener =
            new ViewTreeObserver.OnDrawListener() {
        @Override
        public void onDraw() {
            computeParentPositionAndScale();
            final int visibility = InlineContentView.this.isShown() ? VISIBLE : GONE;
            mSurfaceView.setVisibility(visibility);
        }
    };

    @NonNull
    private final SurfaceView mSurfaceView;

    @Nullable
    private WeakReference<SurfaceView> mParentSurfaceOwnerView;

    @Nullable
    private int[] mParentPosition;

    @Nullable
    private PointF mParentScale;

    @Nullable
    private SurfaceControlCallback mSurfaceControlCallback;

    @Nullable
    private SurfacePackageUpdater mSurfacePackageUpdater;

    /**
     * @inheritDoc
     * @hide
     */
    public InlineContentView(@NonNull Context context) {
        this(context, null);
    }

    /**
     * @inheritDoc
     * @hide
     */
    public InlineContentView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * @inheritDoc
     * @hide
     */
    public InlineContentView(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
        mSurfaceView.setEnableSurfaceClipping(true);
    }

    /**
     * Gets the surface control. If the surface is not created this method returns {@code null}.
     *
     * @return The surface control.
     * @see #setSurfaceControlCallback(SurfaceControlCallback)
     */
    @Nullable
    public SurfaceControl getSurfaceControl() {
        return mSurfaceView.getSurfaceControl();
    }

    @Override
    public void setClipBounds(Rect clipBounds) {
        super.setClipBounds(clipBounds);
        mSurfaceView.setClipBounds(clipBounds);
    }

    /**
     * @inheritDoc
     * @hide
     */
    public InlineContentView(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mSurfaceView = new SurfaceView(context, attrs, defStyleAttr, defStyleRes) {
            // b/219807628
            @Override
            protected void onSetSurfacePositionAndScale(
                    @NonNull SurfaceControl.Transaction transaction,
                    @NonNull SurfaceControl surface, int positionLeft, int positionTop,
                    float postScaleX, float postScaleY) {
                // If we have a parent position, we need to make our coordinates relative
                // to the parent in the rendering space.
                if (mParentPosition != null) {
                    positionLeft = (int) ((positionLeft - mParentPosition[0]) / mParentScale.x);
                    positionTop = (int) ((positionTop - mParentPosition[1]) / mParentScale.y);
                }

                // Any scaling done to the parent or its predecessors would be applied
                // via the surfaces parent -> child relation, so we only propagate any
                // scaling set on the InlineContentView itself.
                postScaleX = InlineContentView.this.getScaleX();
                postScaleY = InlineContentView.this.getScaleY();

                super.onSetSurfacePositionAndScale(transaction, surface, positionLeft,
                        positionTop, postScaleX, postScaleY);
            }
        };
        mSurfaceView.setZOrderOnTop(true);
        mSurfaceView.getHolder().setFormat(PixelFormat.TRANSPARENT);
        addView(mSurfaceView);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /**
     * Sets the embedded UI provider.
     *
     * @hide
     */
    @TestApi
    public void setChildSurfacePackageUpdater(
            @Nullable SurfacePackageUpdater surfacePackageUpdater) {
        mSurfacePackageUpdater = surfacePackageUpdater;
    }

    @Override
    protected void onAttachedToWindow() {
        if (DEBUG) Log.v(TAG, "onAttachedToWindow");
        super.onAttachedToWindow();
        if (mSurfacePackageUpdater != null) {
            mSurfacePackageUpdater.getSurfacePackage(
                    sp -> {
                        if (DEBUG) Log.v(TAG, "Received new SurfacePackage");
                        if (getViewRootImpl() != null) {
                            mSurfaceView.setChildSurfacePackage(sp);
                        }
                    });
        }

        mSurfaceView.setVisibility(getVisibility());
        getViewTreeObserver().addOnDrawListener(mOnDrawListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (DEBUG) Log.v(TAG, "onDetachedFromWindow");
        super.onDetachedFromWindow();
        if (mSurfacePackageUpdater != null) {
            mSurfacePackageUpdater.onSurfacePackageReleased();
        }

        getViewTreeObserver().removeOnDrawListener(mOnDrawListener);
        mSurfaceView.setVisibility(View.GONE);
    }

    @Override
    public void onLayout(boolean changed, int l, int t, int r, int b) {
        mSurfaceView.layout(0, 0, getMeasuredWidth(), getMeasuredHeight());
    }

    /**
     * Sets a callback to observe the lifecycle of the surface control for managing the backing
     * surface.
     *
     * @param callback The callback to set or {@code null} to clear.
     */
    public void setSurfaceControlCallback(@Nullable SurfaceControlCallback callback) {
        if (mSurfaceControlCallback != null) {
            mSurfaceView.getHolder().removeCallback(mSurfaceCallback);
        }
        mSurfaceControlCallback = callback;
        if (mSurfaceControlCallback != null) {
            mSurfaceView.getHolder().addCallback(mSurfaceCallback);
        }
    }

    /**
     * @return Whether the surface backing this view appears on top of its parent.
     * @see #setZOrderedOnTop(boolean)
     */
    public boolean isZOrderedOnTop() {
        return mSurfaceView.isZOrderedOnTop();
    }

    /**
     * Controls whether the backing surface is placed on top of this view's window. Normally, it is
     * placed on top of the window, to allow interaction with the inlined UI. Via this method, you
     * can place the surface below the window. This means that all of the contents of the window
     * this view is in will be visible on top of its surface.
     *
     * <p> The Z ordering can be changed dynamically if the backing surface is
     * created, otherwise the ordering would be applied at surface construction time.
     *
     * @param onTop Whether to show the surface on top of this view's window.
     * @see #isZOrderedOnTop()
     */
    public boolean setZOrderedOnTop(boolean onTop) {
        return mSurfaceView.setZOrderedOnTop(onTop, /*allowDynamicChange*/ true);
    }


    private void computeParentPositionAndScale() {
        boolean contentPositionOrScaleChanged = false;

        // This method can be called on the UI or render thread but for the cases
        // it is called these threads are not running concurrently, so no need to lock.
        final SurfaceView parentSurfaceOwnerView = (mParentSurfaceOwnerView != null)
                ? mParentSurfaceOwnerView.get() : null;

        if (parentSurfaceOwnerView != null) {
            if (mParentPosition == null) {
                mParentPosition = new int[2];
            }
            final int oldParentPositionX = mParentPosition[0];
            final int oldParentPositionY = mParentPosition[1];
            parentSurfaceOwnerView.getLocationInSurface(mParentPosition);
            if (oldParentPositionX != mParentPosition[0]
                    || oldParentPositionY != mParentPosition[1]) {
                contentPositionOrScaleChanged = true;
            }

            if (mParentScale == null) {
                mParentScale = new PointF();
            }

            final float lastParentSurfaceWidth = parentSurfaceOwnerView
                    .getSurfaceRenderPosition().width();
            final float oldParentScaleX = mParentScale.x;
            if (lastParentSurfaceWidth > 0) {
                mParentScale.x = lastParentSurfaceWidth /
                        (float) parentSurfaceOwnerView.getWidth();
            } else {
                mParentScale.x = 1.0f;
            }
            if (!contentPositionOrScaleChanged
                    && Float.compare(oldParentScaleX, mParentScale.x) != 0) {
                contentPositionOrScaleChanged = true;
            }

            final float lastParentSurfaceHeight = parentSurfaceOwnerView
                    .getSurfaceRenderPosition().height();
            final float oldParentScaleY = mParentScale.y;
            if (lastParentSurfaceHeight > 0) {
                mParentScale.y = lastParentSurfaceHeight
                        / (float) parentSurfaceOwnerView.getHeight();
            } else {
                mParentScale.y = 1.0f;
            }
            if (!contentPositionOrScaleChanged
                    && Float.compare(oldParentScaleY, mParentScale.y) != 0) {
                contentPositionOrScaleChanged = true;
            }
        } else if (mParentPosition != null || mParentScale != null) {
            contentPositionOrScaleChanged = true;
            mParentPosition = null;
            mParentScale = null;
        }

        if (contentPositionOrScaleChanged) {
            mSurfaceView.requestUpdateSurfacePositionAndScale();
        }
    }
}
