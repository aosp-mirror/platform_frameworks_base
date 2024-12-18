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

package com.android.systemui.accessibility;

import static android.view.WindowManager.LayoutParams;

import static com.android.app.viewcapture.ViewCaptureFactory.getViewCaptureAwareWindowManagerInstance;
import static com.android.systemui.Flags.enableViewCaptureTracing;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.util.Log;
import android.util.MathUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;

import com.android.app.viewcapture.ViewCaptureAwareWindowManager;
import com.android.systemui.res.R;

/**
 * Contains a movable control UI to manipulate mirrored window's position, size and scale. The
 * window type of the UI is {@link LayoutParams#TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY} to
 * ensure it won't be magnified. It is not movable to the navigation bar.
 */
public abstract class MirrorWindowControl {
    private static final String TAG = "MirrorWindowControl";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG) | false;

    /**
     * A delegate handling a mirrored window's offset.
     */
    public interface MirrorWindowDelegate {
        /**
         * Moves the window with specified offset.
         *
         * @param xOffset the amount in pixels to offset the window in the X coordinate, in current
         *                display pixels.
         * @param yOffset the amount in pixels to offset the window in the Y coordinate, in current
         *                display pixels.
         */
        void move(int xOffset, int yOffset);
    }

    protected final Context mContext;
    private final Rect mDraggableBound = new Rect();
    final Point mTmpPoint = new Point();

    @Nullable
    protected MirrorWindowDelegate mMirrorWindowDelegate;
    protected View mControlsView;
    /**
     * The left top position of the control UI. Initialized when the control UI is visible.
     *
     * @see #setDefaultPosition(LayoutParams)
     */
    private final Point mControlPosition = new Point();
    private final ViewCaptureAwareWindowManager mWindowManager;

    MirrorWindowControl(Context context) {
        mContext = context;
        mWindowManager = getViewCaptureAwareWindowManagerInstance(mContext,
                enableViewCaptureTracing());
    }

    public void setWindowDelegate(@Nullable MirrorWindowDelegate windowDelegate) {
        mMirrorWindowDelegate = windowDelegate;
    }

    /**
     * Shows the control UI.
     *
     * {@link LayoutParams#TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY}  window.
     */
    public final void showControl() {
        if (mControlsView != null) {
            Log.w(TAG, "control view is visible");
            return;
        }
        final Point  viewSize = mTmpPoint;
        mControlsView = onCreateView(LayoutInflater.from(mContext), viewSize);

        final LayoutParams lp = new LayoutParams();
        final int defaultSize = mContext.getResources().getDimensionPixelSize(
                R.dimen.magnification_controls_size);
        lp.width = viewSize.x <= 0 ? defaultSize : viewSize.x;
        lp.height = viewSize.y <= 0 ? defaultSize : viewSize.y;
        setDefaultParams(lp);
        setDefaultPosition(lp);
        mWindowManager.addView(mControlsView, lp);
        updateDraggableBound(lp.width, lp.height);
    }

    private void setDefaultParams(LayoutParams lp) {
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.flags = LayoutParams.FLAG_NOT_TOUCH_MODAL
                | LayoutParams.FLAG_NOT_FOCUSABLE;
        lp.type = LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;
        lp.format = PixelFormat.RGBA_8888;
        lp.setTitle(getWindowTitle());
    }

    private void setDefaultPosition(LayoutParams layoutParams) {
        final Point displaySize = mTmpPoint;
        mContext.getDisplay().getSize(displaySize);
        layoutParams.x = displaySize.x - layoutParams.width;
        layoutParams.y = displaySize.y - layoutParams.height;
        mControlPosition.set(layoutParams.x, layoutParams.y);
    }

    /**
     * Removes the UI from the scene.
     */
    public final void destroyControl() {
        if (mControlsView != null) {
            mWindowManager.removeView(mControlsView);
            mControlsView = null;
        }
    }

    /**
     * Moves the control view with specified offset.
     *
     * @param xOffset the amount in pixels to offset the UI in the X coordinate, in current
     *                display pixels.
     * @param yOffset the amount in pixels to offset the UI in the Y coordinate, in current
     *                display pixels.
     */
    public void move(int xOffset, int yOffset) {
        if (mControlsView == null) {
            Log.w(TAG, "control view is not available yet or destroyed");
            return;
        }
        final Point nextPosition = mTmpPoint;
        nextPosition.set(mControlPosition.x, mControlPosition.y);
        mTmpPoint.offset(xOffset, yOffset);
        setPosition(mTmpPoint);
    }

    private void setPosition(Point point) {
        constrainFrameToDraggableBound(point);
        if (point.equals(mControlPosition)) {
            return;
        }
        mControlPosition.set(point.x, point.y);
        LayoutParams lp = (LayoutParams) mControlsView.getLayoutParams();
        lp.x = mControlPosition.x;
        lp.y = mControlPosition.y;
        mWindowManager.updateViewLayout(mControlsView, lp);
    }

    private void constrainFrameToDraggableBound(Point point) {
        point.x = MathUtils.constrain(point.x, mDraggableBound.left, mDraggableBound.right);
        point.y = MathUtils.constrain(point.y, mDraggableBound.top, mDraggableBound.bottom);
    }

    private void updateDraggableBound(int viewWidth, int viewHeight) {
        final Point size = mTmpPoint;
        mContext.getDisplay().getSize(size);
        mDraggableBound.set(0, 0, size.x - viewWidth, size.y - viewHeight);
        if (DBG) {
            Log.d(TAG, "updateDraggableBound :" + mDraggableBound);
        }
    }

    abstract String getWindowTitle();

    /**
     * Called when the UI is going to show.
     *
     * @param inflater The LayoutInflater object used to inflate the view.
     * @param viewSize The {@link Point} to specify view's width with {@link Point#x)} and height
     *                with {@link Point#y)} .The value should be greater than 0, otherwise will
     *                 fall back to the default size.
     * @return the View for the control's UI.
     */
    @NonNull
    abstract View onCreateView(@NonNull LayoutInflater inflater, @NonNull Point viewSize);
}
