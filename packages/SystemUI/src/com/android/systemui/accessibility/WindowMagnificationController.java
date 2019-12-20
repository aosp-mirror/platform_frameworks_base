/*
 * Copyright (C) 2019 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Handler;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;

import com.android.systemui.R;
import com.android.systemui.shared.system.WindowManagerWrapper;

/**
 * Class to handle adding and removing a window magnification.
 */
public class WindowMagnificationController implements View.OnClickListener,
        View.OnLongClickListener, View.OnTouchListener, SurfaceHolder.Callback {
    private final int mBorderSize;
    private final int mMoveFrameAmountShort;
    private final int mMoveFrameAmountLong;

    private final Context mContext;
    private final Point mDisplaySize = new Point();
    private final int mDisplayId;
    private final Handler mHandler;
    private final Rect mMagnificationFrame = new Rect();
    private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();

    private final WindowManager mWm;

    private float mScale;

    private final Rect mTmpRect = new Rect();

    // The root of the mirrored content
    private SurfaceControl mMirrorSurface;

    private boolean mIsPressedDown;

    private View mLeftControl;
    private View mUpControl;
    private View mRightControl;
    private View mBottomControl;

    private View mDragView;
    private View mLeftDrag;
    private View mTopDrag;
    private View mRightDrag;
    private View mBottomDrag;

    private final PointF mLastDrag = new PointF();
    private final Point mMoveWindowOffset = new Point();

    private View mMirrorView;
    private SurfaceView mMirrorSurfaceView;
    private View mControlsView;
    private View mOverlayView;

    private MoveMirrorRunnable mMoveMirrorRunnable = new MoveMirrorRunnable();

    WindowMagnificationController(Context context, Handler handler) {
        mContext = context;
        mHandler = handler;
        Display display = mContext.getDisplay();
        display.getSize(mDisplaySize);
        mDisplayId = mContext.getDisplayId();

        mWm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        Resources r = context.getResources();
        mBorderSize = (int) r.getDimension(R.dimen.magnification_border_size);
        mMoveFrameAmountShort = (int) r.getDimension(R.dimen.magnification_frame_move_short);
        mMoveFrameAmountLong = (int) r.getDimension(R.dimen.magnification_frame_move_long);

        mScale = r.getInteger(R.integer.magnification_default_scale);
    }

    /**
     * Creates a magnification window if it doesn't already exist.
     */
    void createWindowMagnification() {
        if (mMirrorView != null) {
            return;
        }
        createOverlayWindow();
    }

    private void createOverlayWindow() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.token = new Binder();
        params.setTitle(mContext.getString(R.string.magnification_overlay_title));

        mOverlayView = new View(mContext);
        mOverlayView.getViewTreeObserver().addOnWindowAttachListener(
                new ViewTreeObserver.OnWindowAttachListener() {
                    @Override
                    public void onWindowAttached() {
                        mOverlayView.getViewTreeObserver().removeOnWindowAttachListener(this);
                        createMirrorWindow();
                        createControls();
                    }

                    @Override
                    public void onWindowDetached() {

                    }
                });

        mOverlayView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        mWm.addView(mOverlayView, params);
    }

    /**
     * Deletes the magnification window.
     */
    void deleteWindowMagnification() {
        if (mMirrorSurface != null) {
            mTransaction.remove(mMirrorSurface).apply();
            mMirrorSurface = null;
        }

        if (mOverlayView != null) {
            mWm.removeView(mOverlayView);
            mOverlayView = null;
        }

        if (mMirrorView != null) {
            mWm.removeView(mMirrorView);
            mMirrorView = null;
        }

        if (mControlsView != null) {
            mWm.removeView(mControlsView);
            mControlsView = null;
        }
    }

    private void createMirrorWindow() {
        setInitialStartBounds();

        // The window should be the size the mirrored surface will be but also add room for the
        // border and the drag handle.
        int dragViewHeight = (int) mContext.getResources().getDimension(
                R.dimen.magnification_drag_view_height);
        int windowWidth = mMagnificationFrame.width() + 2 * mBorderSize;
        int windowHeight = mMagnificationFrame.height() + dragViewHeight + 2 * mBorderSize;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                windowWidth, windowHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.token = mOverlayView.getWindowToken();
        params.x = mMagnificationFrame.left;
        params.y = mMagnificationFrame.top;
        params.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        params.setTitle(mContext.getString(R.string.magnification_window_title));

        mMirrorView = LayoutInflater.from(mContext).inflate(R.layout.window_magnifier_view, null);
        mMirrorSurfaceView = mMirrorView.findViewById(R.id.surface_view);
        // This places the SurfaceView's SurfaceControl above the ViewRootImpl's SurfaceControl to
        // ensure the mirrored area can get touch instead of going to the window
        mMirrorSurfaceView.setZOrderOnTop(true);

        mMirrorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        mWm.addView(mMirrorView, params);

        SurfaceHolder holder = mMirrorSurfaceView.getHolder();
        holder.addCallback(this);
        holder.setFormat(PixelFormat.RGBA_8888);

        addDragTouchListeners();
    }

    private void createControls() {
        int controlsSize = (int) mContext.getResources().getDimension(
                R.dimen.magnification_controls_size);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(controlsSize, controlsSize,
                WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.RGBA_8888);
        lp.gravity = Gravity.BOTTOM | Gravity.RIGHT;
        lp.token = mOverlayView.getWindowToken();
        lp.setTitle(mContext.getString(R.string.magnification_controls_title));

        mControlsView = LayoutInflater.from(mContext).inflate(R.layout.magnifier_controllers, null);
        mWm.addView(mControlsView, lp);

        mLeftControl = mControlsView.findViewById(R.id.left_control);
        mUpControl = mControlsView.findViewById(R.id.up_control);
        mRightControl = mControlsView.findViewById(R.id.right_control);
        mBottomControl = mControlsView.findViewById(R.id.down_control);

        mLeftControl.setOnClickListener(this);
        mUpControl.setOnClickListener(this);
        mRightControl.setOnClickListener(this);
        mBottomControl.setOnClickListener(this);

        mLeftControl.setOnLongClickListener(this);
        mUpControl.setOnLongClickListener(this);
        mRightControl.setOnLongClickListener(this);
        mBottomControl.setOnLongClickListener(this);

        mLeftControl.setOnTouchListener(this);
        mUpControl.setOnTouchListener(this);
        mRightControl.setOnTouchListener(this);
        mBottomControl.setOnTouchListener(this);
    }

    private void setInitialStartBounds() {
        // Sets the initial frame area for the mirror and places it in the center of the display.
        int initSize = Math.min(mDisplaySize.x, mDisplaySize.y) / 2;
        int initX = mDisplaySize.x / 2 - initSize / 2;
        int initY = mDisplaySize.y / 2 - initSize / 2;
        mMagnificationFrame.set(initX, initY, initX + initSize, initY + initSize);
    }

    /**
     * This is called once the surfaceView is created so the mirrored content can be placed as a
     * child of the surfaceView.
     */
    private void createMirror() {
        mMirrorSurface = WindowManagerWrapper.getInstance().mirrorDisplay(mDisplayId);
        if (!mMirrorSurface.isValid()) {
            return;
        }
        mTransaction.show(mMirrorSurface)
                .reparent(mMirrorSurface, mMirrorSurfaceView.getSurfaceControl());

        modifyWindowMagnification(mTransaction);
        mTransaction.apply();
    }

    private void addDragTouchListeners() {
        mDragView = mMirrorView.findViewById(R.id.drag_handle);
        mLeftDrag = mMirrorView.findViewById(R.id.left_handle);
        mTopDrag = mMirrorView.findViewById(R.id.top_handle);
        mRightDrag = mMirrorView.findViewById(R.id.right_handle);
        mBottomDrag = mMirrorView.findViewById(R.id.bottom_handle);

        mDragView.setOnTouchListener(this);
        mLeftDrag.setOnTouchListener(this);
        mTopDrag.setOnTouchListener(this);
        mRightDrag.setOnTouchListener(this);
        mBottomDrag.setOnTouchListener(this);
    }

    /**
     * Modifies the placement of the mirrored content.
     */
    private void modifyWindowMagnification(SurfaceControl.Transaction t) {
        Rect sourceBounds = getSourceBounds(mMagnificationFrame, mScale);
        // The final destination for the magnification surface should be at 0,0 since the
        // ViewRootImpl's position will change
        mTmpRect.set(0, 0, mMagnificationFrame.width(), mMagnificationFrame.height());

        WindowManager.LayoutParams params =
                (WindowManager.LayoutParams) mMirrorView.getLayoutParams();
        params.x = mMagnificationFrame.left;
        params.y = mMagnificationFrame.top;
        mWm.updateViewLayout(mMirrorView, params);

        t.setGeometry(mMirrorSurface, sourceBounds, mTmpRect, Surface.ROTATION_0);
    }

    @Override
    public void onClick(View v) {
        setMoveOffset(v, mMoveFrameAmountShort);
        moveMirrorFromControls();
    }

    @Override
    public boolean onLongClick(View v) {
        mIsPressedDown = true;
        setMoveOffset(v, mMoveFrameAmountLong);
        mHandler.post(mMoveMirrorRunnable);
        return true;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (v == mLeftControl || v == mUpControl || v == mRightControl || v == mBottomControl) {
            return handleControlTouchEvent(event);
        } else if (v == mDragView || v == mLeftDrag || v == mTopDrag || v == mRightDrag
                || v == mBottomDrag) {
            return handleDragTouchEvent(event);
        }
        return false;
    }

    private boolean handleControlTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mIsPressedDown = false;
                break;
        }
        return false;
    }

    private boolean handleDragTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mLastDrag.set(event.getRawX(), event.getRawY());
                return true;
            case MotionEvent.ACTION_MOVE:
                int xDiff = (int) (event.getRawX() - mLastDrag.x);
                int yDiff = (int) (event.getRawY() - mLastDrag.y);
                mMagnificationFrame.offset(xDiff, yDiff);
                mLastDrag.set(event.getRawX(), event.getRawY());
                modifyWindowMagnification(mTransaction);
                mTransaction.apply();
                return true;
        }
        return false;
    }

    private void setMoveOffset(View v, int moveFrameAmount) {
        mMoveWindowOffset.set(0, 0);

        if (v == mLeftControl) {
            mMoveWindowOffset.x = -moveFrameAmount;
        } else if (v == mUpControl) {
            mMoveWindowOffset.y = -moveFrameAmount;
        } else if (v == mRightControl) {
            mMoveWindowOffset.x = moveFrameAmount;
        } else if (v == mBottomControl) {
            mMoveWindowOffset.y = moveFrameAmount;
        }
    }

    private void moveMirrorFromControls() {
        mMagnificationFrame.offset(mMoveWindowOffset.x, mMoveWindowOffset.y);

        modifyWindowMagnification(mTransaction);
        mTransaction.apply();
    }

    /**
     * Calculates the desired source bounds. This will be the area under from the center of  the
     * displayFrame, factoring in scale.
     */
    private Rect getSourceBounds(Rect displayFrame, float scale) {
        int halfWidth = displayFrame.width() / 2;
        int halfHeight = displayFrame.height() / 2;
        int left = displayFrame.left + (halfWidth - (int) (halfWidth / scale));
        int right = displayFrame.right - (halfWidth - (int) (halfWidth / scale));
        int top = displayFrame.top + (halfHeight - (int) (halfHeight / scale));
        int bottom = displayFrame.bottom - (halfHeight - (int) (halfHeight / scale));
        return new Rect(left, top, right, bottom);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        createMirror();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
    }

    class MoveMirrorRunnable implements Runnable {
        @Override
        public void run() {
            if (mIsPressedDown) {
                moveMirrorFromControls();
                mHandler.postDelayed(mMoveMirrorRunnable, 100);
            }
        }
    }
}
