/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.screenshot;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.MathUtils;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.systemui.R;

/**
 * CropView has top and bottom draggable crop handles, with a scrim to darken the areas being
 * cropped out.
 */
public class CropView extends View {
    public enum CropBoundary {
        NONE, TOP, BOTTOM
    }

    private final float mCropTouchMargin;
    private final Paint mShadePaint;
    private final Paint mHandlePaint;

    // Top and bottom crops are stored as floats [0, 1], representing the top and bottom of the
    // view, respectively.
    private float mTopCrop = 0f;
    private float mBottomCrop = 1f;

    // When the user is dragging a handle, these variables store the distance between the top/bottom
    // crop values and
    private float mTopDelta = 0f;
    private float mBottomDelta = 0f;

    private CropBoundary mCurrentDraggingBoundary = CropBoundary.NONE;
    private float mStartingY;  // y coordinate of ACTION_DOWN
    private CropInteractionListener mCropInteractionListener;

    public CropView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CropView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        TypedArray t = context.getTheme().obtainStyledAttributes(
                attrs, R.styleable.CropView, 0, 0);
        mShadePaint = new Paint();
        mShadePaint.setColor(t.getColor(R.styleable.CropView_scrimColor, Color.TRANSPARENT));
        mHandlePaint = new Paint();
        mHandlePaint.setColor(t.getColor(R.styleable.CropView_handleColor, Color.BLACK));
        mHandlePaint.setStrokeWidth(
                t.getDimensionPixelSize(R.styleable.CropView_handleThickness, 20));
        t.recycle();
        // 48 dp touchable region around each handle.
        mCropTouchMargin = 24 * getResources().getDisplayMetrics().density;
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float top = mTopCrop + mTopDelta;
        float bottom = mBottomCrop + mBottomDelta;
        drawShade(canvas, 0, top);
        drawShade(canvas, bottom, 1f);
        drawHandle(canvas, top);
        drawHandle(canvas, bottom);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int topPx = fractionToPixels(mTopCrop);
        int bottomPx = fractionToPixels(mBottomCrop);
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mCurrentDraggingBoundary = nearestBoundary(event, topPx, bottomPx);
                if (mCurrentDraggingBoundary != CropBoundary.NONE) {
                    mStartingY = event.getY();
                    updateListener(event);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mCurrentDraggingBoundary != CropBoundary.NONE) {
                    float delta = event.getY() - mStartingY;
                    if (mCurrentDraggingBoundary == CropBoundary.TOP) {
                        mTopDelta = pixelsToFraction((int) MathUtils.constrain(delta, -topPx,
                                bottomPx - 2 * mCropTouchMargin - topPx));
                    } else {  // Bottom
                        mBottomDelta = pixelsToFraction((int) MathUtils.constrain(delta,
                                topPx + 2 * mCropTouchMargin - bottomPx,
                                getMeasuredHeight() - bottomPx));
                    }
                    updateListener(event);
                    invalidate();
                    return true;
                }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                if (mCurrentDraggingBoundary != CropBoundary.NONE) {
                    // Commit the delta to the stored crop values.
                    mTopCrop += mTopDelta;
                    mBottomCrop += mBottomDelta;
                    mTopDelta = 0;
                    mBottomDelta = 0;
                    updateListener(event);
                }
        }
        return super.onTouchEvent(event);
    }

    /**
     * @return value [0,1] representing the position of the top crop boundary. Does not reflect
     * changes from any in-progress touch input.
     */
    public float getTopBoundary() {
        return mTopCrop;
    }

    /**
     * @return value [0,1] representing the position of the bottom crop boundary. Does not reflect
     * changes from any in-progress touch input.
     */
    public float getBottomBoundary() {
        return mBottomCrop;
    }

    public void setCropInteractionListener(CropInteractionListener listener) {
        mCropInteractionListener = listener;
    }

    private void updateListener(MotionEvent event) {
        if (mCropInteractionListener != null) {
            float boundaryPosition = (mCurrentDraggingBoundary == CropBoundary.TOP)
                    ? mTopCrop + mTopDelta : mBottomCrop + mBottomDelta;
            mCropInteractionListener.onCropMotionEvent(event, mCurrentDraggingBoundary,
                    boundaryPosition, fractionToPixels(boundaryPosition));
        }
    }

    private void drawShade(Canvas canvas, float fracStart, float fracEnd) {
        canvas.drawRect(0, fractionToPixels(fracStart), getMeasuredWidth(),
                fractionToPixels(fracEnd), mShadePaint);
    }

    private void drawHandle(Canvas canvas, float frac) {
        int y = fractionToPixels(frac);
        canvas.drawLine(0, y, getMeasuredWidth(), y, mHandlePaint);
    }

    private int fractionToPixels(float frac) {
        return (int) (frac * getMeasuredHeight());
    }

    private float pixelsToFraction(int px) {
        return px / (float) getMeasuredHeight();
    }

    private CropBoundary nearestBoundary(MotionEvent event, int topPx, int bottomPx) {
        if (Math.abs(event.getY() - topPx) < mCropTouchMargin) {
            return CropBoundary.TOP;
        }
        if (Math.abs(event.getY() - bottomPx) < mCropTouchMargin) {
            return CropBoundary.BOTTOM;
        }
        return CropBoundary.NONE;
    }

    /**
     * Listen for crop motion events and state.
     */
    public interface CropInteractionListener {
        /**
         * Called whenever CropView has a MotionEvent that can impact the position of the crop
         * boundaries.
         */
        void onCropMotionEvent(MotionEvent event, CropBoundary boundary, float boundaryPosition,
                int boundaryPositionPx);

    }
}
