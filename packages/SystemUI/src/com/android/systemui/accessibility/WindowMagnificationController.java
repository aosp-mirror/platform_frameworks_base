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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.IWindow;
import android.view.IWindowSession;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;

import com.android.systemui.R;
import com.android.systemui.shared.system.WindowManagerWrapper;

/**
 * Class to handle adding and removing a window magnification.
 */
class WindowMagnificationController implements View.OnTouchListener, SurfaceHolder.Callback,
        MirrorWindowControl.MirrorWindowDelegate {

    private static final String TAG = "WindowMagnificationController";
    private final Context mContext;
    private final Resources mResources;
    private final Handler mHandler;
    private final Point mDisplaySize = new Point();
    private final int mDisplayId;
    @Surface.Rotation
    private int mRotation;
    private final Rect mMagnificationFrame = new Rect();
    private final SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();

    private final WindowManager mWm;

    private float mScale;

    private final Rect mTmpRect = new Rect();
    private final Rect mMirrorViewBounds = new Rect();

    // The root of the mirrored content
    private SurfaceControl mMirrorSurface;

    private View mDragView;
    private View mLeftDrag;
    private View mTopDrag;
    private View mRightDrag;
    private View mBottomDrag;

    private final PointF mLastDrag = new PointF();
    @NonNull
    private final WindowMagnifierCallback mWindowMagnifierCallback;

    private final View.OnLayoutChangeListener mMirrorViewLayoutChangeListener;
    private final View.OnLayoutChangeListener mMirrorSurfaceViewLayoutChangeListener;
    private final Runnable mMirrorViewRunnable;
    private View mMirrorView;
    private SurfaceView mMirrorSurfaceView;
    private int mMirrorSurfaceMargin;
    private int mBorderDragSize;
    private int mOuterBorderSize;
    // The boundary of magnification frame.
    private final Rect mMagnificationFrameBoundary = new Rect();

    @Nullable
    private MirrorWindowControl mMirrorWindowControl;

    WindowMagnificationController(Context context,
            @NonNull Handler handler,
            MirrorWindowControl mirrorWindowControl,
            @NonNull WindowMagnifierCallback callback) {
        mContext = context;
        mHandler = handler;
        mWindowMagnifierCallback = callback;
        Display display = mContext.getDisplay();
        display.getRealSize(mDisplaySize);
        mDisplayId = mContext.getDisplayId();
        mRotation = display.getRotation();

        mWm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        mResources = mContext.getResources();
        mScale = mResources.getInteger(R.integer.magnification_default_scale);
        updateDimensions();

        mMirrorWindowControl = mirrorWindowControl;
        if (mMirrorWindowControl != null) {
            mMirrorWindowControl.setWindowDelegate(this);
        }
        setInitialStartBounds();

        // Initialize listeners.
        mMirrorViewRunnable = () -> {
            if (mMirrorView != null) {
                mMirrorView.getBoundsOnScreen(mMirrorViewBounds);
                mWindowMagnifierCallback.onWindowMagnifierBoundsChanged(
                        mDisplayId, mMirrorViewBounds);
            }
        };
        mMirrorViewLayoutChangeListener =
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        mHandler.post(mMirrorViewRunnable);
        mMirrorSurfaceViewLayoutChangeListener =
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom)
                        -> applyTapExcludeRegion();
    }

    private void updateDimensions() {
        mMirrorSurfaceMargin = mResources.getDimensionPixelSize(
                R.dimen.magnification_mirror_surface_margin);
        mBorderDragSize = mResources.getDimensionPixelSize(
                R.dimen.magnification_border_drag_size);
        mOuterBorderSize = mResources.getDimensionPixelSize(
                R.dimen.magnification_outer_border_margin);
    }

    /**
     * Deletes the magnification window.
     */
    void deleteWindowMagnification() {
        if (mMirrorSurface != null) {
            mTransaction.remove(mMirrorSurface).apply();
            mMirrorSurface = null;
        }

        if (mMirrorSurfaceView != null) {
            mMirrorSurfaceView.removeOnLayoutChangeListener(mMirrorSurfaceViewLayoutChangeListener);
        }

        if (mMirrorView != null) {
            mHandler.removeCallbacks(mMirrorViewRunnable);
            mMirrorView.removeOnLayoutChangeListener(mMirrorViewLayoutChangeListener);
            mWm.removeView(mMirrorView);
            mMirrorView = null;
        }

        if (mMirrorWindowControl != null) {
            mMirrorWindowControl.destroyControl();
        }
    }

    /**
     * Called when the configuration has changed, and it updates window magnification UI.
     *
     * @param configDiff a bit mask of the differences between the configurations
     */
    void onConfigurationChanged(int configDiff) {
        if ((configDiff & ActivityInfo.CONFIG_DENSITY) != 0) {
            updateDimensions();
            // TODO(b/145780606): update toggle button UI.
            if (mMirrorView != null) {
                mWm.removeView(mMirrorView);
                createMirrorWindow();
            }
        } else if ((configDiff & ActivityInfo.CONFIG_ORIENTATION) != 0) {
            onRotate();
        }
    }

    /** Handles MirrorWindow position when the device rotation changed. */
    private void onRotate() {
        Display display = mContext.getDisplay();
        display.getRealSize(mDisplaySize);
        setMagnificationFrameBoundary();

        // Keep MirrorWindow position on the screen unchanged when device rotates 90°
        // clockwise or anti-clockwise.
        final int rotationDegree = getDegreeFromRotation(display.getRotation(), mRotation);
        final Matrix matrix = new Matrix();
        matrix.setRotate(rotationDegree);
        mRotation = display.getRotation();
        if (rotationDegree == 90) {
            matrix.postTranslate(mDisplaySize.x, 0);
        } else if (rotationDegree == 270) {
            matrix.postTranslate(0, mDisplaySize.y);
        } else {
            Log.w(TAG, "Invalid rotation change. " + rotationDegree);
            return;
        }
        // The rect of MirrorView is going to be transformed.
        WindowManager.LayoutParams params =
                (WindowManager.LayoutParams) mMirrorView.getLayoutParams();
        mTmpRect.set(params.x, params.y, params.x + params.width, params.y + params.height);
        final RectF transformedRect = new RectF(mTmpRect);
        matrix.mapRect(transformedRect);
        moveWindowMagnifier(transformedRect.left - mTmpRect.left,
                transformedRect.top - mTmpRect.top);
    }

    /** Returns the rotation degree change of two {@link Surface.Rotation} */
    private int getDegreeFromRotation(@Surface.Rotation int newRotation,
            @Surface.Rotation int oldRotation) {
        final int rotationDiff = oldRotation - newRotation;
        final int degree = (rotationDiff + 4) % 4 * 90;
        return degree;
    }

    private void createMirrorWindow() {
        // The window should be the size the mirrored surface will be but also add room for the
        // border and the drag handle.
        int windowWidth = mMagnificationFrame.width() + 2 * mMirrorSurfaceMargin;
        int windowHeight = mMagnificationFrame.height() + 2 * mMirrorSurfaceMargin;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                windowWidth, windowHeight,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSPARENT);
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = mMagnificationFrame.left;
        params.y = mMagnificationFrame.top;
        params.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        params.setTitle(mContext.getString(R.string.magnification_window_title));

        mMirrorView = LayoutInflater.from(mContext).inflate(R.layout.window_magnifier_view, null);
        mMirrorSurfaceView = mMirrorView.findViewById(R.id.surface_view);

        // Allow taps to go through to the mirror SurfaceView below.
        mMirrorSurfaceView.addOnLayoutChangeListener(mMirrorSurfaceViewLayoutChangeListener);

        mMirrorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        mMirrorView.addOnLayoutChangeListener(mMirrorViewLayoutChangeListener);
        mWm.addView(mMirrorView, params);

        SurfaceHolder holder = mMirrorSurfaceView.getHolder();
        holder.addCallback(this);
        holder.setFormat(PixelFormat.RGBA_8888);

        addDragTouchListeners();
    }

    private void applyTapExcludeRegion() {
        final Region tapExcludeRegion = calculateTapExclude();
        final IWindow window = IWindow.Stub.asInterface(mMirrorView.getWindowToken());
        try {
            IWindowSession session = WindowManagerGlobal.getWindowSession();
            session.updateTapExcludeRegion(window, tapExcludeRegion);
        } catch (RemoteException e) {
        }
    }

    private Region calculateTapExclude() {
        Region regionInsideDragBorder = new Region(mBorderDragSize, mBorderDragSize,
                mMirrorView.getWidth() - mBorderDragSize,
                mMirrorView.getHeight() - mBorderDragSize);
        return regionInsideDragBorder;
    }

    private void showControls() {
        if (mMirrorWindowControl != null) {
            mMirrorWindowControl.showControl();
        }
    }

    private void setInitialStartBounds() {
        // Sets the initial frame area for the mirror and places it in the center of the display.
        final int initSize = Math.min(mDisplaySize.x, mDisplaySize.y) / 2
                + 2 * mMirrorSurfaceMargin;
        final int initX = mDisplaySize.x / 2 - initSize / 2;
        final int initY = mDisplaySize.y / 2 - initSize / 2;
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
     * Modifies the placement of the mirrored content when the position of mMirrorView is updated.
     */
    private void modifyWindowMagnification(SurfaceControl.Transaction t) {
        Rect sourceBounds = getSourceBounds(mMagnificationFrame, mScale);
        // The final destination for the magnification surface should be at 0,0 since the
        // ViewRootImpl's position will change
        mTmpRect.set(0, 0, mMagnificationFrame.width(), mMagnificationFrame.height());

        updateMirrorViewLayout();

        t.setGeometry(mMirrorSurface, sourceBounds, mTmpRect, Surface.ROTATION_0);
    }

    /**
     * Updates the layout params of MirrorView and translates MirrorView position when the view is
     * moved close to the screen edges.
     */
    private void updateMirrorViewLayout() {
        WindowManager.LayoutParams params =
                (WindowManager.LayoutParams) mMirrorView.getLayoutParams();
        params.x = mMagnificationFrame.left - mMirrorSurfaceMargin;
        params.y = mMagnificationFrame.top - mMirrorSurfaceMargin;

        // Translates MirrorView position to make MirrorSurfaceView that is inside MirrorView
        // able to move close to the screen edges.
        final int maxMirrorViewX = mDisplaySize.x - mMirrorView.getWidth();
        final int maxMirrorViewY = mDisplaySize.y - mMirrorView.getHeight();
        final float translationX;
        final float translationY;
        if (params.x < 0) {
            translationX = Math.max(params.x, -mOuterBorderSize);
        } else if (params.x > maxMirrorViewX) {
            translationX = Math.min(params.x - maxMirrorViewX, mOuterBorderSize);
        } else {
            translationX = 0;
        }
        if (params.y < 0) {
            translationY = Math.max(params.y, -mOuterBorderSize);
        } else if (params.y > maxMirrorViewY) {
            translationY = Math.min(params.y - maxMirrorViewY, mOuterBorderSize);
        } else {
            translationY = 0;
        }
        mMirrorView.setTranslationX(translationX);
        mMirrorView.setTranslationY(translationY);
        mWm.updateViewLayout(mMirrorView, params);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (v == mDragView || v == mLeftDrag || v == mTopDrag || v == mRightDrag
                || v == mBottomDrag) {
            return handleDragTouchEvent(event);
        }
        return false;
    }

    private boolean handleDragTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mLastDrag.set(event.getRawX(), event.getRawY());
                return true;
            case MotionEvent.ACTION_MOVE:
                moveWindowMagnifier(event.getRawX() - mLastDrag.x, event.getRawY() - mLastDrag.y);
                mLastDrag.set(event.getRawX(), event.getRawY());
                return true;
        }
        return false;
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

    private void setMagnificationFrameBoundary() {
        // Calculates width and height for magnification frame could exceed out the screen.
        // TODO : re-calculating again when scale is changed.
        // The half width of magnification frame.
        final int halfWidth = mMagnificationFrame.width() / 2;
        // The half height of magnification frame.
        final int halfHeight = mMagnificationFrame.height() / 2;
        // The scaled half width of magnified region.
        final int scaledWidth = (int) (halfWidth / mScale);
        // The scaled half height of magnified region.
        final int scaledHeight = (int) (halfHeight / mScale);
        final int exceededWidth = halfWidth - scaledWidth;
        final int exceededHeight = halfHeight - scaledHeight;

        mMagnificationFrameBoundary.set(-exceededWidth, -exceededHeight,
                mDisplaySize.x + exceededWidth, mDisplaySize.y + exceededHeight);
    }

    /**
     * Calculates and sets the real position of magnification frame based on the magnified region
     * should be limited by the region of the display.
     */
    private boolean updateMagnificationFramePosition(int xOffset, int yOffset) {
        mTmpRect.set(mMagnificationFrame);
        mTmpRect.offset(xOffset, yOffset);

        if (mTmpRect.left < mMagnificationFrameBoundary.left) {
            mTmpRect.offsetTo(mMagnificationFrameBoundary.left, mTmpRect.top);
        } else if (mTmpRect.right > mMagnificationFrameBoundary.right) {
            final int leftOffset = mMagnificationFrameBoundary.right - mMagnificationFrame.width();
            mTmpRect.offsetTo(leftOffset, mTmpRect.top);
        }

        if (mTmpRect.top < mMagnificationFrameBoundary.top) {
            mTmpRect.offsetTo(mTmpRect.left, mMagnificationFrameBoundary.top);
        } else if (mTmpRect.bottom > mMagnificationFrameBoundary.bottom) {
            final int topOffset = mMagnificationFrameBoundary.bottom - mMagnificationFrame.height();
            mTmpRect.offsetTo(mTmpRect.left, topOffset);
        }

        if (!mTmpRect.equals(mMagnificationFrame)) {
            mMagnificationFrame.set(mTmpRect);
            return true;
        }
        return false;
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

    @Override
    public void move(int xOffset, int yOffset) {
        moveWindowMagnifier(xOffset, yOffset);
    }

    /**
     * Enables window magnification with specified parameters.
     *
     * @param scale   the target scale
     * @param centerX the screen-relative X coordinate around which to center,
     *                or {@link Float#NaN} to leave unchanged.
     * @param centerY the screen-relative Y coordinate around which to center,
     *                or {@link Float#NaN} to leave unchanged.
     */
    void enableWindowMagnification(float scale, float centerX, float centerY) {
        final float offsetX = Float.isNaN(centerX) ? 0
                : centerX - mMagnificationFrame.exactCenterX();
        final float offsetY = Float.isNaN(centerY) ? 0
                : centerY - mMagnificationFrame.exactCenterY();
        mScale = scale;
        setMagnificationFrameBoundary();
        updateMagnificationFramePosition((int) offsetX, (int) offsetY);
        if (mMirrorView == null) {
            createMirrorWindow();
            showControls();
        } else {
            modifyWindowMagnification(mTransaction);
            mTransaction.apply();
        }
    }

    /**
     * Sets the scale of the magnified region if it's visible.
     *
     * @param scale the target scale
     */
    void setScale(float scale) {
        if (mMirrorView == null || mScale == scale) {
            return;
        }
        enableWindowMagnification(scale, Float.NaN, Float.NaN);
    }

    /**
     * Moves the window magnifier with specified offset in pixels unit.
     *
     * @param offsetX the amount in pixels to offset the window magnifier in the X direction, in
     *                current screen pixels.
     * @param offsetY the amount in pixels to offset the window magnifier in the Y direction, in
     *                current screen pixels.
     */
    void moveWindowMagnifier(float offsetX, float offsetY) {
        if (mMirrorSurfaceView == null) {
            return;
        }
        if (updateMagnificationFramePosition((int) offsetX, (int) offsetY)) {
            modifyWindowMagnification(mTransaction);
            mTransaction.apply();
        }
    }
}
