/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.screenshot;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import com.android.systemui.R;

/**
 * Draws a selection rectangle while taking screenshot
 */
public class ScreenshotSelectorView extends FrameLayout implements View.OnTouchListener {
    private final Paint mPaintSelection, mPaintBackground;
    private final Paint mPaintSelectionBorder, mPaintSelectionCorner;
    private Rect mSelectionRect;
    private Rect mDrawingRect;

    private ResizingHandle mResizingHandle = ResizingHandle.INVALID;
    private final int mBorderWidth;
    private final int mCornerWidth;
    private final int mTouchWidth;

    private boolean mIsFirstSelection;
    private int mMovingOffsetX;
    private int mMovingOffsetY;
    private int mResizingOffsetX;
    private int mResizingOffsetY;
    private boolean mIsMoving;

    private OnSelectionListener mListener;

    private enum ResizingHandle {
        // Sorted by detection priority
        INVALID,
        BOTTOM_RIGHT,
        BOTTOM_LEFT,
        TOP_RIGHT,
        TOP_LEFT,
        RIGHT,
        BOTTOM,
        LEFT,
        TOP;

        public boolean isValid() {
            return this != INVALID;
        }

        public boolean isLeft() {
            return this == LEFT || this == TOP_LEFT || this == BOTTOM_LEFT;
        }

        public boolean isTop() {
            return this == TOP || this == TOP_LEFT || this == TOP_RIGHT;
        }

        public boolean isRight() {
            return this == RIGHT || this == TOP_RIGHT || this == BOTTOM_RIGHT;
        }

        public boolean isBottom() {
            return this == BOTTOM || this == BOTTOM_RIGHT || this == BOTTOM_LEFT;
        }
    }

    public ScreenshotSelectorView(Context context) {
        this(context, null);
    }

    public ScreenshotSelectorView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        mBorderWidth = (int) context.getResources()
                .getDimension(R.dimen.global_screenshot_selector_border_width);
        mCornerWidth = (int) context.getResources()
                .getDimension(R.dimen.global_screenshot_selector_corner_width);
        mTouchWidth = (int) context.getResources()
                .getDimension(R.dimen.global_screenshot_selector_touch_width);

        mPaintBackground = new Paint(Color.BLACK);
        mPaintBackground.setAlpha(160);
        mPaintSelection = new Paint(Color.TRANSPARENT);
        mPaintSelection.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
        mPaintSelectionBorder = new Paint();
        mPaintSelectionBorder.setStyle(Paint.Style.STROKE);
        mPaintSelectionBorder.setStrokeWidth(mBorderWidth);
        mPaintSelectionBorder.setColor(Color.WHITE);
        mPaintSelectionBorder.setAntiAlias(true);
        mPaintSelectionCorner = new Paint();
        mPaintSelectionCorner.setStyle(Paint.Style.STROKE);
        mPaintSelectionCorner.setStrokeWidth(mCornerWidth);
        mPaintSelectionCorner.setColor(Color.WHITE);
        mPaintSelectionCorner.setAntiAlias(true);
        mDrawingRect = new Rect();

        setOnTouchListener(this);
        setWillNotDraw(false);
    }

    public void startSelection(int x, int y) {
        mSelectionRect = new Rect(x, y, x, y);
        invalidate();
    }

    public Rect getSelectionRect() {
        return mSelectionRect;
    }

    public void sortSelectionRect() {
        // The coordinates of the rect can end up being unsorted if the
        // user drags one side over the opposite side. Fix it
        mSelectionRect.sort();
    }

    public void stopSelection() {
        mSelectionRect = null;
        invalidate();
    }

    public void delegateSelection() {
        if (mListener != null) {
            mListener.onSelectionChanged(mSelectionRect, mIsFirstSelection);
        }
    }

    public void setSelectionListener(OnSelectionListener listener) {
        mListener = listener;
    }

    private boolean isTouchingCenteredSquare(int cx, int cy, int x, int y) {
        return x >= cx - mTouchWidth && x <= cx + mTouchWidth &&
                y >= cy - mTouchWidth && y <= cy + mTouchWidth;
    }

    private boolean isTouchingBorder(int sl, int el, int o, int x, int y, boolean vertical) {
        int fx = x;
        int fy = y;

        if (vertical) {
            fx = y;
            fy = x;
        }

        return fy >= o - mTouchWidth && fy <= o + mTouchWidth &&
                fx >= sl && fx <= el;
    }

    public boolean isTouching(ResizingHandle handle, int x, int y) {
        switch (handle) {
            case LEFT:
                return isTouchingBorder(mSelectionRect.top, mSelectionRect.bottom,
                        mSelectionRect.left, x, y, true);
            case TOP_LEFT:
                return isTouchingCenteredSquare(mSelectionRect.left, mSelectionRect.top, x, y);
            case TOP:
                return isTouchingBorder(mSelectionRect.left, mSelectionRect.right,
                        mSelectionRect.top, x, y, false);
            case TOP_RIGHT:
                return isTouchingCenteredSquare(mSelectionRect.right, mSelectionRect.top, x, y);
            case RIGHT:
                return isTouchingBorder(mSelectionRect.top, mSelectionRect.bottom,
                        mSelectionRect.right, x, y, true);
            case BOTTOM_RIGHT:
                return isTouchingCenteredSquare(mSelectionRect.right, mSelectionRect.bottom, x, y);
            case BOTTOM:
                return isTouchingBorder(mSelectionRect.left, mSelectionRect.right,
                        mSelectionRect.bottom, x, y, false);
            case BOTTOM_LEFT:
                return isTouchingCenteredSquare(mSelectionRect.left, mSelectionRect.bottom, x, y);
            default:
                return false;
        }
    }

    private ResizingHandle getTouchedResizingHandle(int x, int y) {
        for (ResizingHandle handle : ResizingHandle.values()) {
            if (isTouching(handle, x, y)) {
                return handle;
            }
        }

        return ResizingHandle.INVALID;
    }

    public boolean isInsideSelection(int x, int y) {
        return mSelectionRect.contains(x, y);
    }

    private void resizeSelection(int x, int y) {
        if (mResizingHandle.isLeft()) {
            mSelectionRect.left = Math.max(x - mResizingOffsetX, 0);
        }

        if (mResizingHandle.isTop()) {
            mSelectionRect.top = Math.max(y - mResizingOffsetY, 0);
        }

        if (mResizingHandle.isRight()) {
            mSelectionRect.right = Math.min(x - mResizingOffsetX, getMeasuredWidth());
        }

        if (mResizingHandle.isBottom()) {
            mSelectionRect.bottom = Math.min(y - mResizingOffsetY, getMeasuredHeight());
        }

        invalidate();
    }

    private void setMovingOffset(int x, int y) {
        mMovingOffsetX = x - mSelectionRect.left;
        mMovingOffsetY = y - mSelectionRect.top;
    }

    private void setResizingOffset(int x, int y) {
        mResizingOffsetX = 0;
        mResizingOffsetY = 0;

        if (mResizingHandle.isLeft()) {
            mResizingOffsetX = x - mSelectionRect.left;
        }

        if (mResizingHandle.isTop()) {
            mResizingOffsetY = y - mSelectionRect.top;
        }

        if (mResizingHandle.isRight()) {
            mResizingOffsetX = x - mSelectionRect.right;
        }

        if (mResizingHandle.isBottom()) {
            mResizingOffsetY = y - mSelectionRect.bottom;
        }
    }

    private void moveSelection(int x, int y) {
        int left = x - mMovingOffsetX;
        int top = y - mMovingOffsetY;
        int right = left + mSelectionRect.width();
        int bottom = top + mSelectionRect.height();

        if (left < 0) {
            right += -left;
            left = 0;
        }

        if (top < 0) {
            bottom += -top;
            top = 0;
        }

        int maxRight = getMeasuredWidth();
        int rightOffset = right - maxRight;
        if (rightOffset > 0) {
            left -= rightOffset;
            right = maxRight;
        }

        int maxBottom = getMeasuredHeight();
        int bottomOffset = bottom - maxBottom;
        if (bottomOffset > 0) {
            top -= bottomOffset;
            bottom = maxBottom;
        }

        mSelectionRect.set(left, top, right, bottom);

        invalidate();
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        canvas.drawRect(mLeft, mTop, mRight, mBottom, mPaintBackground);
        if (mSelectionRect != null) {
            // The coordinates of the selection rect can end up being unsorted if the
            // user drags one side over the opposite side
            // We cannot sort the selection rect because the user might still be resizing it
            // Copy the values and sort it without affecting the selection rect
            mDrawingRect.set(mSelectionRect);
            mDrawingRect.sort();

            // Border
            canvas.drawRect(mDrawingRect, mPaintSelectionBorder);

            // Top-left corner
            canvas.drawRect(mDrawingRect.left,
                    mDrawingRect.top,
                    Math.min(mDrawingRect.left + mTouchWidth, mDrawingRect.right),
                    Math.min(mDrawingRect.top + mTouchWidth, mDrawingRect.bottom),
                    mPaintSelectionCorner);

            // Top-right corner
            canvas.drawRect(Math.max(mDrawingRect.right - mTouchWidth, mDrawingRect.left),
                    mDrawingRect.top,
                    mDrawingRect.right,
                    Math.min(mDrawingRect.top + mTouchWidth, mDrawingRect.bottom),
                    mPaintSelectionCorner);

            // Bottom-right corner
            canvas.drawRect(Math.max(mDrawingRect.right - mTouchWidth, mDrawingRect.left),
                    Math.max(mDrawingRect.bottom - mTouchWidth, mDrawingRect.top),
                    mDrawingRect.right,
                    mDrawingRect.bottom,
                    mPaintSelectionCorner);

            // Bottom-left corner
            canvas.drawRect(mDrawingRect.left,
                    Math.max(mDrawingRect.bottom - mTouchWidth, mDrawingRect.top),
                    Math.min(mDrawingRect.left + mTouchWidth, mDrawingRect.right),
                    mDrawingRect.bottom,
                    mPaintSelectionCorner);

            // Clear out the insides of the selection to be sure there
            // are no white borders visible
            canvas.drawRect(mDrawingRect, mPaintSelection);
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        int x = (int) event.getX();
        int y = (int) event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (mSelectionRect == null) {
                    startSelection(x, y);
                    mIsFirstSelection = true;
                    mResizingHandle = ResizingHandle.BOTTOM_RIGHT;
                } else {
                    mResizingHandle = getTouchedResizingHandle(x, y);
                    if (mResizingHandle.isValid()) {
                        setResizingOffset(x, y);
                    } else if (isInsideSelection(x, y)) {
                        mIsMoving = true;
                        setMovingOffset(x, y);
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mResizingHandle.isValid()) {
                    resizeSelection(x, y);
                } else if (mIsMoving) {
                    moveSelection(x, y);
                }
                break;
            case MotionEvent.ACTION_UP:
                sortSelectionRect();
                delegateSelection();
                mResizingHandle = ResizingHandle.INVALID;
                mIsFirstSelection = false;
                mIsMoving = false;
                break;
        }

        return true;
    }

    public interface OnSelectionListener {
        void onSelectionChanged(Rect rect, boolean firstSelection);
    }
}
