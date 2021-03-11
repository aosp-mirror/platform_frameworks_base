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

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.AttributeSet;
import android.util.IntArray;
import android.util.Log;
import android.util.MathUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.android.internal.widget.ExploreByTouchHelper;
import com.android.systemui.R;

/**
 * CropView has top and bottom draggable crop handles, with a scrim to darken the areas being
 * cropped out.
 */
public class CropView extends View {
    private static final String TAG = "CropView";
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

    private int mExtraTopPadding;
    private int mExtraBottomPadding;

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

        setAccessibilityDelegate(new AccessibilityHelper());
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float top = mTopCrop + mTopDelta;
        float bottom = mBottomCrop + mBottomDelta;
        drawShade(canvas, 0, top);
        drawShade(canvas, bottom, 1f);
        drawHandle(canvas, top, /* draw the handle tab down */ false);
        drawHandle(canvas, bottom, /* draw the handle tab up */ true);
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
                        mTopDelta = pixelDistanceToFraction((int) MathUtils.constrain(delta,
                                -topPx + mExtraTopPadding,
                                bottomPx - 2 * mCropTouchMargin - topPx));
                    } else {  // Bottom
                        mBottomDelta = pixelDistanceToFraction((int) MathUtils.constrain(delta,
                                topPx + 2 * mCropTouchMargin - bottomPx,
                                getHeight() - bottomPx - mExtraBottomPadding));
                    }
                    updateListener(event);
                    invalidate();
                    return true;
                }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                if (mCurrentDraggingBoundary != CropBoundary.NONE) {
                    // Commit the delta to the stored crop values.
                    commitDeltas(mCurrentDraggingBoundary);
                    updateListener(event);
                }
        }
        return super.onTouchEvent(event);
    }

    /**
     * Set the given boundary to the given value without animation.
     */
    public void setBoundaryTo(CropBoundary boundary, float value) {
        switch (boundary) {
            case TOP:
                mTopCrop = value;
                break;
            case BOTTOM:
                mBottomCrop = value;
                break;
            case NONE:
                Log.w(TAG, "No boundary selected for animation");
                break;
        }

        invalidate();
    }

    /**
     * Animate the given boundary to the given value.
     */
    public void animateBoundaryTo(CropBoundary boundary, float value) {
        if (boundary == CropBoundary.NONE) {
            Log.w(TAG, "No boundary selected for animation");
            return;
        }
        float totalDelta = (boundary == CropBoundary.TOP) ? (value - mTopCrop)
                : (value - mBottomCrop);
        ValueAnimator animator = new ValueAnimator();
        animator.addUpdateListener(animation -> {
            if (boundary == CropBoundary.TOP) {
                mTopDelta = animation.getAnimatedFraction() * totalDelta;
            } else {
                mBottomDelta = animation.getAnimatedFraction() * totalDelta;
            }
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                commitDeltas(boundary);
            }

            @Override
            public void onAnimationCancel(Animator animation) {
                commitDeltas(boundary);
            }
        });
        animator.setFloatValues(0f, 1f);
        animator.setDuration(750);
        animator.setInterpolator(new FastOutSlowInInterpolator());
        animator.start();
    }

    /**
     * Set additional top and bottom padding for the image being cropped (used when the
     * corresponding ImageView doesn't take the full height).
     */
    public void setExtraPadding(int top, int bottom) {
        mExtraTopPadding = top;
        mExtraBottomPadding = bottom;
        invalidate();
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

    private void commitDeltas(CropBoundary boundary) {
        if (boundary == CropBoundary.TOP) {
            mTopCrop += mTopDelta;
            mTopDelta = 0;
        } else if (boundary == CropBoundary.BOTTOM) {
            mBottomCrop += mBottomDelta;
            mBottomDelta = 0;
        }
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
        canvas.drawRect(0, fractionToPixels(fracStart), getWidth(),
                fractionToPixels(fracEnd), mShadePaint);
    }

    private void drawHandle(Canvas canvas, float frac, boolean handleTabUp) {
        int y = fractionToPixels(frac);
        canvas.drawLine(0, y, getWidth(), y, mHandlePaint);
        float radius = 15 * getResources().getDisplayMetrics().density;
        float x = getWidth() * .9f;
        canvas.drawArc(x - radius, y - radius, x + radius, y + radius, handleTabUp ? 180 : 0, 180,
                true, mHandlePaint);
    }

    /**
     * Convert the given fraction position to pixel position within the View.
     */
    private int fractionToPixels(float frac) {
        return (int) (mExtraTopPadding + frac * getImageHeight());
    }

    private int getImageHeight() {
        return getHeight() - mExtraTopPadding - mExtraBottomPadding;
    }

    /**
     * Convert the given pixel distance to fraction of the image.
     */
    private float pixelDistanceToFraction(int px) {
        return px / (float) getImageHeight();
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

    private class AccessibilityHelper extends ExploreByTouchHelper {

        private static final int TOP_HANDLE_ID = 1;
        private static final int BOTTOM_HANDLE_ID = 2;

        AccessibilityHelper() {
            super(CropView.this);
        }

        @Override
        protected int getVirtualViewAt(float x, float y) {
            if (Math.abs(y - fractionToPixels(mTopCrop)) < mCropTouchMargin) {
                return TOP_HANDLE_ID;
            }
            if (Math.abs(y - fractionToPixels(mBottomCrop)) < mCropTouchMargin) {
                return BOTTOM_HANDLE_ID;
            }
            return ExploreByTouchHelper.INVALID_ID;
        }

        @Override
        protected void getVisibleVirtualViews(IntArray virtualViewIds) {
            virtualViewIds.add(TOP_HANDLE_ID);
            virtualViewIds.add(BOTTOM_HANDLE_ID);
        }

        @Override
        protected void onPopulateEventForVirtualView(int virtualViewId, AccessibilityEvent event) {
            switch(virtualViewId) {
                case TOP_HANDLE_ID:
                    event.setContentDescription(
                            getResources().getString(R.string.screenshot_top_boundary));
                    break;
                case BOTTOM_HANDLE_ID:
                    event.setContentDescription(
                            getResources().getString(R.string.screenshot_bottom_boundary));
                    break;
            }
        }

        @Override
        protected void onPopulateNodeForVirtualView(int virtualViewId,
                AccessibilityNodeInfo node) {
            switch(virtualViewId) {
                case TOP_HANDLE_ID:
                    node.setContentDescription(
                            getResources().getString(R.string.screenshot_top_boundary));
                    setNodePositions(mTopCrop, node);
                    break;
                case BOTTOM_HANDLE_ID:
                    node.setContentDescription(
                            getResources().getString(R.string.screenshot_bottom_boundary));
                    setNodePositions(mBottomCrop, node);
                    break;
            }

            // TODO: need to figure out the full set of actions to support here.
            node.addAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
            node.setClickable(true);
            node.setFocusable(true);
        }

        @Override
        protected boolean onPerformActionForVirtualView(
                int virtualViewId, int action, Bundle arguments) {
            return false;
        }

        private void setNodePositions(float fraction, AccessibilityNodeInfo node) {
            int pixels = fractionToPixels(fraction);
            Rect rect = new Rect(0, (int) (pixels - mCropTouchMargin),
                    getWidth(), (int) (pixels + mCropTouchMargin));
            node.setBoundsInParent(rect);
            int[] pos = new int[2];
            getLocationOnScreen(pos);
            rect.offset(pos[0], pos[1]);
            node.setBoundsInScreen(rect);
        }
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
