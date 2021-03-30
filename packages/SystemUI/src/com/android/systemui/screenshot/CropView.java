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

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.IntArray;
import android.util.Log;
import android.util.MathUtils;
import android.util.Range;
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
        NONE, TOP, BOTTOM, LEFT, RIGHT
    }

    private final float mCropTouchMargin;
    private final Paint mShadePaint;
    private final Paint mHandlePaint;

    // Crop rect with each element represented as [0,1] along its proper axis.
    private RectF mCrop = new RectF(0, 0, 1, 1);

    private int mExtraTopPadding;
    private int mExtraBottomPadding;
    private int mImageWidth;

    private CropBoundary mCurrentDraggingBoundary = CropBoundary.NONE;
    // The starting value of mCurrentDraggingBoundary's crop, used to compute touch deltas.
    private float mMovementStartValue;
    private float mStartingY;  // y coordinate of ACTION_DOWN
    private float mStartingX;
    // The allowable values for the current boundary being dragged
    private Range<Float> mMotionRange;

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
        mHandlePaint.setStrokeCap(Paint.Cap.ROUND);
        mHandlePaint.setStrokeWidth(
                t.getDimensionPixelSize(R.styleable.CropView_handleThickness, 20));
        t.recycle();
        // 48 dp touchable region around each handle.
        mCropTouchMargin = 24 * getResources().getDisplayMetrics().density;

        setAccessibilityDelegate(new AccessibilityHelper());
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();

        SavedState ss = new SavedState(superState);
        ss.mCrop = mCrop;
        return ss;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());

        mCrop = ss.mCrop;
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        drawShade(canvas, 0, 0, 1, mCrop.top);
        drawShade(canvas, 0, mCrop.bottom, 1, 1);
        drawShade(canvas, 0, mCrop.top, mCrop.left, mCrop.bottom);
        drawShade(canvas, mCrop.right, mCrop.top, 1, mCrop.bottom);
        drawHorizontalHandle(canvas, mCrop.top, /* draw the handle tab up */ true);
        drawHorizontalHandle(canvas, mCrop.bottom, /* draw the handle tab down */ false);
        drawVerticalHandle(canvas, mCrop.left, /* left */ true);
        drawVerticalHandle(canvas, mCrop.right, /* right */ false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int topPx = fractionToVerticalPixels(mCrop.top);
        int bottomPx = fractionToVerticalPixels(mCrop.bottom);
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mCurrentDraggingBoundary = nearestBoundary(event, topPx, bottomPx,
                        fractionToHorizontalPixels(mCrop.left),
                        fractionToHorizontalPixels(mCrop.right));
                if (mCurrentDraggingBoundary != CropBoundary.NONE) {
                    mStartingY = event.getY();
                    mStartingX = event.getX();
                    mMovementStartValue = getBoundaryPosition(mCurrentDraggingBoundary);
                    updateListener(event);
                    switch (mCurrentDraggingBoundary) {
                        case TOP:
                            mMotionRange = new Range<>(0f,
                                    mCrop.bottom - pixelDistanceToFraction(mCropTouchMargin,
                                            CropBoundary.BOTTOM));
                            break;
                        case BOTTOM:
                            mMotionRange = new Range<>(
                                    mCrop.top + pixelDistanceToFraction(mCropTouchMargin,
                                            CropBoundary.TOP), 1f);
                            break;
                        case LEFT:
                            mMotionRange = new Range<>(0f,
                                    mCrop.right - pixelDistanceToFraction(mCropTouchMargin,
                                            CropBoundary.RIGHT));
                            break;
                        case RIGHT:
                            mMotionRange = new Range<>(
                                    mCrop.left + pixelDistanceToFraction(mCropTouchMargin,
                                            CropBoundary.LEFT), 1f);
                            break;
                    }
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mCurrentDraggingBoundary != CropBoundary.NONE) {
                    float deltaPx = isVertical(mCurrentDraggingBoundary) ? event.getY() - mStartingY
                            : event.getX() - mStartingX;
                    float delta = pixelDistanceToFraction((int) deltaPx, mCurrentDraggingBoundary);
                    setBoundaryPosition(mCurrentDraggingBoundary,
                            mMotionRange.clamp(mMovementStartValue + delta));
                    updateListener(event);
                    invalidate();
                    return true;
                }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                if (mCurrentDraggingBoundary != CropBoundary.NONE) {
                    updateListener(event);
                }
        }
        return super.onTouchEvent(event);
    }

    /**
     * Set the given boundary to the given value without animation.
     */
    public void setBoundaryPosition(CropBoundary boundary, float position) {
        switch (boundary) {
            case TOP:
                mCrop.top = position;
                break;
            case BOTTOM:
                mCrop.bottom = position;
                break;
            case LEFT:
                mCrop.left = position;
                break;
            case RIGHT:
                mCrop.right = position;
                break;
            case NONE:
                Log.w(TAG, "No boundary selected");
                break;
        }

        invalidate();
    }

    private float getBoundaryPosition(CropBoundary boundary) {
        switch (boundary) {
            case TOP:
                return mCrop.top;
            case BOTTOM:
                return mCrop.bottom;
            case LEFT:
                return mCrop.left;
            case RIGHT:
                return mCrop.right;
        }
        return 0;
    }

    private static boolean isVertical(CropBoundary boundary) {
        return boundary == CropBoundary.TOP || boundary == CropBoundary.BOTTOM;
    }

    /**
     * Animate the given boundary to the given value.
     */
    public void animateBoundaryTo(CropBoundary boundary, float value) {
        if (boundary == CropBoundary.NONE) {
            Log.w(TAG, "No boundary selected for animation");
            return;
        }
        float start = getBoundaryPosition(boundary);
        ValueAnimator animator = new ValueAnimator();
        animator.addUpdateListener(animation -> {
            setBoundaryPosition(boundary,
                    MathUtils.lerp(start, value, animation.getAnimatedFraction()));
            invalidate();
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
     * Set the pixel width of the image on the screen (on-screen dimension, not actual bitmap
     * dimension)
     */
    public void setImageWidth(int width) {
        mImageWidth = width;
        invalidate();
    }

    /**
     * @return RectF with values [0,1] representing the position of the boundaries along image axes.
     */
    public Rect getCropBoundaries(int imageWidth, int imageHeight) {
        return new Rect((int) (mCrop.left * imageWidth), (int) (mCrop.top * imageHeight),
                (int) (mCrop.right * imageWidth), (int) (mCrop.bottom * imageHeight));
    }

    public void setCropInteractionListener(CropInteractionListener listener) {
        mCropInteractionListener = listener;
    }

    private void updateListener(MotionEvent event) {
        if (mCropInteractionListener != null && (isVertical(mCurrentDraggingBoundary))) {
            float boundaryPosition = getBoundaryPosition(mCurrentDraggingBoundary);
            mCropInteractionListener.onCropMotionEvent(event, mCurrentDraggingBoundary,
                    boundaryPosition, fractionToVerticalPixels(boundaryPosition),
                    (mCrop.left + mCrop.right) / 2);
        }
    }

    /**
     * Draw a shade to the given canvas with the given [0,1] fractional image bounds.
     */
    private void drawShade(Canvas canvas, float left, float top, float right, float bottom) {
        canvas.drawRect(fractionToHorizontalPixels(left), fractionToVerticalPixels(top),
                fractionToHorizontalPixels(right),
                fractionToVerticalPixels(bottom), mShadePaint);
    }

    private void drawHorizontalHandle(Canvas canvas, float frac, boolean handleTabUp) {
        int y = fractionToVerticalPixels(frac);
        canvas.drawLine(fractionToHorizontalPixels(mCrop.left), y,
                fractionToHorizontalPixels(mCrop.right), y, mHandlePaint);
        float radius = 8 * getResources().getDisplayMetrics().density;
        int x = (fractionToHorizontalPixels(mCrop.left) + fractionToHorizontalPixels(mCrop.right))
                / 2;
        canvas.drawArc(x - radius, y - radius, x + radius, y + radius, handleTabUp ? 180 : 0, 180,
                true, mHandlePaint);
    }

    private void drawVerticalHandle(Canvas canvas, float frac, boolean handleTabLeft) {
        int x = fractionToHorizontalPixels(frac);
        canvas.drawLine(x, fractionToVerticalPixels(mCrop.top), x,
                fractionToVerticalPixels(mCrop.bottom), mHandlePaint);
        float radius = 8 * getResources().getDisplayMetrics().density;
        int y = (fractionToVerticalPixels(getBoundaryPosition(CropBoundary.TOP))
                + fractionToVerticalPixels(
                getBoundaryPosition(CropBoundary.BOTTOM))) / 2;
        canvas.drawArc(x - radius, y - radius, x + radius, y + radius, handleTabLeft ? 90 : 270,
                180,
                true, mHandlePaint);
    }

    /**
     * Convert the given fraction position to pixel position within the View.
     */
    private int fractionToVerticalPixels(float frac) {
        return (int) (mExtraTopPadding + frac * getImageHeight());
    }

    private int fractionToHorizontalPixels(float frac) {
        return (int) ((getWidth() - mImageWidth) / 2 + frac * mImageWidth);
    }

    private int getImageHeight() {
        return getHeight() - mExtraTopPadding - mExtraBottomPadding;
    }

    /**
     * Convert the given pixel distance to fraction of the image.
     */
    private float pixelDistanceToFraction(float px, CropBoundary boundary) {
        if (isVertical(boundary)) {
            return px / getImageHeight();
        } else {
            return px / mImageWidth;
        }
    }

    private CropBoundary nearestBoundary(MotionEvent event, int topPx, int bottomPx, int leftPx,
            int rightPx) {
        if (Math.abs(event.getY() - topPx) < mCropTouchMargin) {
            return CropBoundary.TOP;
        }
        if (Math.abs(event.getY() - bottomPx) < mCropTouchMargin) {
            return CropBoundary.BOTTOM;
        }
        if (event.getY() > topPx || event.getY() < bottomPx) {
            if (Math.abs(event.getX() - leftPx) < mCropTouchMargin) {
                return CropBoundary.LEFT;
            }
            if (Math.abs(event.getX() - rightPx) < mCropTouchMargin) {
                return CropBoundary.RIGHT;
            }
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
            if (Math.abs(y - fractionToVerticalPixels(mCrop.top)) < mCropTouchMargin) {
                return TOP_HANDLE_ID;
            }
            if (Math.abs(y - fractionToVerticalPixels(mCrop.bottom)) < mCropTouchMargin) {
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
            switch (virtualViewId) {
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
            switch (virtualViewId) {
                case TOP_HANDLE_ID:
                    node.setContentDescription(
                            getResources().getString(R.string.screenshot_top_boundary));
                    setNodePositions(mCrop.top, node);
                    break;
                case BOTTOM_HANDLE_ID:
                    node.setContentDescription(
                            getResources().getString(R.string.screenshot_bottom_boundary));
                    setNodePositions(mCrop.bottom, node);
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
            int pixels = fractionToVerticalPixels(fraction);
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
                int boundaryPositionPx, float horizontalCenter);
    }

    static class SavedState extends BaseSavedState {
        RectF mCrop;

        /**
         * Constructor called from {@link CropView#onSaveInstanceState()}
         */
        SavedState(Parcelable superState) {
            super(superState);
        }

        /**
         * Constructor called from {@link #CREATOR}
         */
        private SavedState(Parcel in) {
            super(in);
            mCrop = in.readParcelable(ClassLoader.getSystemClassLoader());
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeParcelable(mCrop, 0);
        }

        public static final Parcelable.Creator<SavedState> CREATOR
                = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }
}
