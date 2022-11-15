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

package com.google.android.test.mirrorsurface;

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.window.WindowMetricsHelper;

public class MirrorSurfaceActivity extends Activity implements View.OnClickListener,
        View.OnLongClickListener, View.OnTouchListener {
    private static final int BORDER_SIZE = 10;
    private static final int DEFAULT_SCALE = 2;
    private static final int DEFAULT_BORDER_COLOR = Color.argb(255, 255, 153, 0);
    private static final int MOVE_FRAME_AMOUNT = 20;

    private IWindowManager mIWm;
    // An instance of WindowManager that is adjusted for adding windows with type
    // TYPE_APPLICATION_OVERLAY.
    private WindowManager mWm;

    private SurfaceControl mSurfaceControl = new SurfaceControl();
    private SurfaceControl mBorderSc;

    private SurfaceControl.Transaction mTransaction = new SurfaceControl.Transaction();
    private View mOverlayView;
    private View mArrowOverlay;

    private Rect mWindowBounds = new Rect();

    private EditText mScaleText;
    private EditText mDisplayFrameText;
    private TextView mSourcePositionText;

    private Rect mTmpRect = new Rect();
    private final Surface mTmpSurface = new Surface();

    private boolean mHasMirror;

    private Rect mCurrFrame = new Rect();
    private float mCurrScale = DEFAULT_SCALE;

    private final Handler mHandler = new Handler();

    private MoveMirrorRunnable mMoveMirrorRunnable = new MoveMirrorRunnable();
    private boolean mIsPressedDown = false;

    private int mDisplayId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mirror_surface);
        mWm = createWindowContext(TYPE_APPLICATION_OVERLAY, null /* options */)
                .getSystemService(WindowManager.class);
        mIWm = WindowManagerGlobal.getWindowManagerService();

        Rect windowBounds = WindowMetricsHelper.getBoundsExcludingNavigationBarAndCutout(
                mWm.getCurrentWindowMetrics());
        mWindowBounds.set(0, 0, windowBounds.width(), windowBounds.height());

        mScaleText = findViewById(R.id.scale);
        mDisplayFrameText = findViewById(R.id.displayFrame);
        mSourcePositionText = findViewById(R.id.sourcePosition);

        mCurrFrame.set(0, 0, mWindowBounds.width() / 2, mWindowBounds.height() / 2);
        mCurrScale = DEFAULT_SCALE;

        mDisplayId = getDisplay().getDisplayId();
        updateEditTexts();

        findViewById(R.id.mirror_button).setOnClickListener(view -> {
            if (mArrowOverlay == null) {
                createArrowOverlay();
            }
            createOrUpdateMirror();
        });

        findViewById(R.id.remove_mirror_button).setOnClickListener(v -> {
            removeMirror();
            removeArrowOverlay();
        });

        createMirrorOverlay();
    }

    private void updateEditTexts() {
        mDisplayFrameText.setText(
                String.format("%s, %s, %s, %s", mCurrFrame.left, mCurrFrame.top, mCurrFrame.right,
                        mCurrFrame.bottom));
        mScaleText.setText(String.valueOf(mCurrScale));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mOverlayView != null) {
            removeMirror();
            mWm.removeView(mOverlayView);
            mOverlayView = null;
        }
        removeArrowOverlay();
    }

    private void createArrowOverlay() {
        mArrowOverlay = getLayoutInflater().inflate(R.layout.move_view, null);
        WindowManager.LayoutParams arrowParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.RGBA_8888);
        arrowParams.gravity = Gravity.RIGHT | Gravity.BOTTOM;
        mWm.addView(mArrowOverlay, arrowParams);

        View leftArrow = mArrowOverlay.findViewById(R.id.left_arrow);
        View topArrow = mArrowOverlay.findViewById(R.id.up_arrow);
        View rightArrow = mArrowOverlay.findViewById(R.id.right_arrow);
        View bottomArrow = mArrowOverlay.findViewById(R.id.down_arrow);

        leftArrow.setOnClickListener(this);
        topArrow.setOnClickListener(this);
        rightArrow.setOnClickListener(this);
        bottomArrow.setOnClickListener(this);

        leftArrow.setOnLongClickListener(this);
        topArrow.setOnLongClickListener(this);
        rightArrow.setOnLongClickListener(this);
        bottomArrow.setOnLongClickListener(this);

        leftArrow.setOnTouchListener(this);
        topArrow.setOnTouchListener(this);
        rightArrow.setOnTouchListener(this);
        bottomArrow.setOnTouchListener(this);

        mArrowOverlay.findViewById(R.id.zoom_in_button).setOnClickListener(v -> {
            if (mCurrScale <= 1) {
                mCurrScale *= 2;
            } else {
                mCurrScale += 0.5;
            }

            updateMirror(mCurrFrame, mCurrScale);
        });
        mArrowOverlay.findViewById(R.id.zoom_out_button).setOnClickListener(v -> {
            if (mCurrScale <= 1) {
                mCurrScale /= 2;
            } else {
                mCurrScale -= 0.5;
            }

            updateMirror(mCurrFrame, mCurrScale);
        });
    }

    private void removeArrowOverlay() {
        if (mArrowOverlay != null) {
            mWm.removeView(mArrowOverlay);
            mArrowOverlay = null;
        }
    }

    private void createMirrorOverlay() {
        mOverlayView = new LinearLayout(this);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(mWindowBounds.width(),
                mWindowBounds.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.RGBA_8888);
        params.gravity = Gravity.LEFT | Gravity.TOP;
        params.setTitle("Mirror Overlay");
        mOverlayView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);

        mWm.addView(mOverlayView, params);

    }

    private void removeMirror() {
        if (mSurfaceControl.isValid()) {
            mTransaction.remove(mSurfaceControl).apply();
        }
        mHasMirror = false;
    }

    private void createOrUpdateMirror() {
        if (mHasMirror) {
            updateMirror(getDisplayFrame(), getScale());
        } else {
            createMirror(getDisplayFrame(), getScale());
        }

    }

    private Rect getDisplayFrame() {
        mTmpRect.setEmpty();
        String[] frameVals = mDisplayFrameText.getText().toString().split("\\s*,\\s*");
        if (frameVals.length != 4) {
            return mTmpRect;
        }

        try {
            mTmpRect.set(Integer.parseInt(frameVals[0]), Integer.parseInt(frameVals[1]),
                    Integer.parseInt(frameVals[2]), Integer.parseInt(frameVals[3]));
        } catch (Exception e) {
            mTmpRect.setEmpty();
        }

        return mTmpRect;
    }

    private float getScale() {
        try {
            return Float.parseFloat(mScaleText.getText().toString());
        } catch (Exception e) {
            return -1;
        }
    }

    private void createMirror(Rect displayFrame, float scale) {
        boolean success = false;
        try {
            success = mIWm.mirrorDisplay(mDisplayId, mSurfaceControl);
        } catch (RemoteException e) {
        }

        if (!success) {
            return;
        }

        if (!mSurfaceControl.isValid()) {
            return;
        }

        mHasMirror = true;

        mBorderSc = new SurfaceControl.Builder()
                .setName("Mirror Border")
                .setBufferSize(1, 1)
                .setFormat(PixelFormat.TRANSLUCENT)
                .build();

        updateMirror(displayFrame, scale);

        mTransaction
                .show(mSurfaceControl)
                .reparent(mSurfaceControl, mOverlayView.getViewRootImpl().getSurfaceControl())
                .setLayer(mBorderSc, 1)
                .show(mBorderSc)
                .reparent(mBorderSc, mSurfaceControl)
                .apply();
    }

    private void updateMirror(Rect displayFrame, float scale) {
        if (displayFrame.isEmpty()) {
            Rect bounds = mWindowBounds;
            int defaultCropW = bounds.width() / 2;
            int defaultCropH = bounds.height() / 2;
            displayFrame.set(0, 0, defaultCropW, defaultCropH);
        }

        if (scale <= 0) {
            scale = DEFAULT_SCALE;
        }

        mCurrFrame.set(displayFrame);
        mCurrScale = scale;

        int width = (int) Math.ceil(displayFrame.width() / scale);
        int height = (int) Math.ceil(displayFrame.height() / scale);

        Rect sourceBounds = getSourceBounds(displayFrame, scale);

        mTransaction.setGeometry(mSurfaceControl, sourceBounds, displayFrame, Surface.ROTATION_0)
                .setPosition(mBorderSc, sourceBounds.left, sourceBounds.top)
                .setBufferSize(mBorderSc, width, height)
                .apply();

        drawBorder(mBorderSc, width, height, (int) Math.ceil(BORDER_SIZE / scale));

        mSourcePositionText.setText(sourceBounds.left + ", " + sourceBounds.top);
        mDisplayFrameText.setText(
                String.format("%s, %s, %s, %s", mCurrFrame.left, mCurrFrame.top, mCurrFrame.right,
                        mCurrFrame.bottom));
        mScaleText.setText(String.valueOf(mCurrScale));
    }

    private void drawBorder(SurfaceControl borderSc, int width, int height, int borderSize) {
        mTmpSurface.copyFrom(borderSc);

        Canvas c = null;
        try {
            c = mTmpSurface.lockCanvas(null);
        } catch (IllegalArgumentException | Surface.OutOfResourcesException e) {
        }
        if (c == null) {
            return;
        }

        // Top
        c.save();
        c.clipRect(new Rect(0, 0, width, borderSize));
        c.drawColor(DEFAULT_BORDER_COLOR);
        c.restore();
        // Left
        c.save();
        c.clipRect(new Rect(0, 0, borderSize, height));
        c.drawColor(DEFAULT_BORDER_COLOR);
        c.restore();
        // Right
        c.save();
        c.clipRect(new Rect(width - borderSize, 0, width, height));
        c.drawColor(DEFAULT_BORDER_COLOR);
        c.restore();
        // Bottom
        c.save();
        c.clipRect(new Rect(0, height - borderSize, width, height));
        c.drawColor(DEFAULT_BORDER_COLOR);
        c.restore();

        mTmpSurface.unlockCanvasAndPost(c);
    }

    @Override
    public void onClick(View v) {
        Point offset = findOffset(v);
        moveMirrorForArrows(offset.x, offset.y);
    }

    @Override
    public boolean onLongClick(View v) {
        mIsPressedDown = true;
        Point point = findOffset(v);
        mMoveMirrorRunnable.mXOffset = point.x;
        mMoveMirrorRunnable.mYOffset = point.y;
        mHandler.post(mMoveMirrorRunnable);
        return false;
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mIsPressedDown = false;
                break;
        }
        return false;
    }

    private Point findOffset(View v) {
        Point offset = new Point(0, 0);

        switch (v.getId()) {
            case R.id.up_arrow:
                offset.y = -MOVE_FRAME_AMOUNT;
                break;
            case R.id.down_arrow:
                offset.y = MOVE_FRAME_AMOUNT;
                break;
            case R.id.right_arrow:
                offset.x = -MOVE_FRAME_AMOUNT;
                break;
            case R.id.left_arrow:
                offset.x = MOVE_FRAME_AMOUNT;
                break;
        }

        return offset;
    }

    private void moveMirrorForArrows(int xOffset, int yOffset) {
        mCurrFrame.offset(xOffset, yOffset);

        updateMirror(mCurrFrame, mCurrScale);
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

    class MoveMirrorRunnable implements Runnable {
        int mXOffset = 0;
        int mYOffset = 0;

        @Override
        public void run() {
            if (mIsPressedDown) {
                moveMirrorForArrows(mXOffset, mYOffset);
                mHandler.postDelayed(mMoveMirrorRunnable, 150);
            }
        }
    }
}
