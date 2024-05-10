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
import android.util.Log;
import android.util.MathUtils;
import android.util.Range;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;

import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.customview.widget.ExploreByTouchHelper;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.android.internal.graphics.ColorUtils;
import com.android.systemui.res.R;

import java.util.List;

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
    private final Paint mContainerBackgroundPaint;

    // Crop rect with each element represented as [0,1] along its proper axis.
    private RectF mCrop = new RectF(0, 0, 1, 1);

    private int mExtraTopPadding;
    private int mExtraBottomPadding;
    private int mImageWidth;

    private CropBoundary mCurrentDraggingBoundary = CropBoundary.NONE;
    private int mActivePointerId;
    // The starting value of mCurrentDraggingBoundary's crop, used to compute touch deltas.
    private float mMovementStartValue;
    private float mStartingY;  // y coordinate of ACTION_DOWN
    private float mStartingX;
    // The allowable values for the current boundary being dragged
    private Range<Float> mMotionRange;

    // Value [0,1] indicating progress in animateEntrance()
    private float mEntranceInterpolation = 1f;

    private CropInteractionListener mCropInteractionListener;
    private final ExploreByTouchHelper mExploreByTouchHelper;

    public CropView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CropView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        TypedArray t = context.getTheme().obtainStyledAttributes(
                attrs, R.styleable.CropView, 0, 0);
        mShadePaint = new Paint();
        int alpha = t.getInteger(R.styleable.CropView_scrimAlpha, 255);
        int scrimColor = t.getColor(R.styleable.CropView_scrimColor, Color.TRANSPARENT);
        mShadePaint.setColor(ColorUtils.setAlphaComponent(scrimColor, alpha));
        mContainerBackgroundPaint = new Paint();
        mContainerBackgroundPaint.setColor(t.getColor(R.styleable.CropView_containerBackgroundColor,
                Color.TRANSPARENT));
        mHandlePaint = new Paint();
        mHandlePaint.setColor(t.getColor(R.styleable.CropView_handleColor, Color.BLACK));
        mHandlePaint.setStrokeCap(Paint.Cap.ROUND);
        mHandlePaint.setStrokeWidth(
                t.getDimensionPixelSize(R.styleable.CropView_handleThickness, 20));
        t.recycle();
        // 48 dp touchable region around each handle.
        mCropTouchMargin = 24 * getResources().getDisplayMetrics().density;

        mExploreByTouchHelper = new AccessibilityHelper();
        ViewCompat.setAccessibilityDelegate(this, mExploreByTouchHelper);
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Log.d(TAG, "onSaveInstanceState");
        Parcelable superState = super.onSaveInstanceState();

        SavedState ss = new SavedState(superState);
        ss.mCrop = mCrop;
        Log.d(TAG, "saving mCrop=" + mCrop);

        return ss;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        Log.d(TAG, "onRestoreInstanceState");
        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());
        Log.d(TAG, "restoring mCrop=" + ss.mCrop + " (was " + mCrop + ")");
        mCrop = ss.mCrop;
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Top and bottom borders reflect the boundary between the (scrimmed) image and the
        // opaque container background. This is only meaningful during an entrance transition.
        float topBorder = MathUtils.lerp(mCrop.top, 0, mEntranceInterpolation);
        float bottomBorder = MathUtils.lerp(mCrop.bottom, 1, mEntranceInterpolation);
        drawShade(canvas, 0, topBorder, 1, mCrop.top);
        drawShade(canvas, 0, mCrop.bottom, 1, bottomBorder);
        drawShade(canvas, 0, mCrop.top, mCrop.left, mCrop.bottom);
        drawShade(canvas, mCrop.right, mCrop.top, 1, mCrop.bottom);

        // Entrance transition expects the crop bounds to be full width, so we only draw container
        // background on the top and bottom.
        drawContainerBackground(canvas, 0, 0, 1, topBorder);
        drawContainerBackground(canvas, 0, bottomBorder, 1, 1);

        mHandlePaint.setAlpha((int) (mEntranceInterpolation * 255));

        drawHorizontalHandle(canvas, mCrop.top, /* draw the handle tab up */ true);
        drawHorizontalHandle(canvas, mCrop.bottom, /* draw the handle tab down */ false);
        drawVerticalHandle(canvas, mCrop.left, /* left */ true);
        drawVerticalHandle(canvas, mCrop.right, /* right */ false);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int topPx = fractionToVerticalPixels(mCrop.top);
        int bottomPx = fractionToVerticalPixels(mCrop.bottom);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mCurrentDraggingBoundary = nearestBoundary(event, topPx, bottomPx,
                        fractionToHorizontalPixels(mCrop.left),
                        fractionToHorizontalPixels(mCrop.right));
                if (mCurrentDraggingBoundary != CropBoundary.NONE) {
                    mActivePointerId = event.getPointerId(0);
                    mStartingY = event.getY();
                    mStartingX = event.getX();
                    mMovementStartValue = getBoundaryPosition(mCurrentDraggingBoundary);
                    updateListener(MotionEvent.ACTION_DOWN, event.getX());
                    mMotionRange = getAllowedValues(mCurrentDraggingBoundary);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mCurrentDraggingBoundary != CropBoundary.NONE) {
                    int pointerIndex = event.findPointerIndex(mActivePointerId);
                    if (pointerIndex >= 0) {
                        // Original pointer still active, do the move.
                        float deltaPx = isVertical(mCurrentDraggingBoundary)
                                ? event.getY(pointerIndex) - mStartingY
                                : event.getX(pointerIndex) - mStartingX;
                        float delta = pixelDistanceToFraction((int) deltaPx,
                                mCurrentDraggingBoundary);
                        setBoundaryPosition(mCurrentDraggingBoundary,
                                mMotionRange.clamp(mMovementStartValue + delta));
                        updateListener(MotionEvent.ACTION_MOVE, event.getX(pointerIndex));
                        invalidate();
                    }
                    return true;
                }
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                if (mActivePointerId == event.getPointerId(event.getActionIndex())
                        && mCurrentDraggingBoundary != CropBoundary.NONE) {
                    updateListener(MotionEvent.ACTION_DOWN, event.getX(event.getActionIndex()));
                    return true;
                }
                break;
            case MotionEvent.ACTION_POINTER_UP:
                if (mActivePointerId == event.getPointerId(event.getActionIndex())
                        && mCurrentDraggingBoundary != CropBoundary.NONE) {
                    updateListener(MotionEvent.ACTION_UP, event.getX(event.getActionIndex()));
                    return true;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP:
                if (mCurrentDraggingBoundary != CropBoundary.NONE
                        && mActivePointerId == event.getPointerId(mActivePointerId)) {
                    updateListener(MotionEvent.ACTION_UP, event.getX(0));
                    return true;
                }
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        return mExploreByTouchHelper.dispatchHoverEvent(event)
                || super.dispatchHoverEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return mExploreByTouchHelper.dispatchKeyEvent(event)
                || super.dispatchKeyEvent(event);
    }

    @Override
    public void onFocusChanged(boolean gainFocus, int direction,
            Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
        mExploreByTouchHelper.onFocusChanged(gainFocus, direction, previouslyFocusedRect);
    }

    /**
     * Set the given boundary to the given value without animation.
     */
    public void setBoundaryPosition(CropBoundary boundary, float position) {
        Log.i(TAG, "setBoundaryPosition: " + boundary + ", position=" + position);
        position = (float) getAllowedValues(boundary).clamp(position);
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
        Log.i(TAG,  "Updated mCrop: " + mCrop);

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
     * Fade in crop bounds, animate reveal of cropped-out area from current crop bounds.
     */
    public void animateEntrance() {
        mEntranceInterpolation = 0;
        ValueAnimator animator = new ValueAnimator();
        animator.addUpdateListener(animation -> {
            mEntranceInterpolation = animation.getAnimatedFraction();
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

    private Range<Float> getAllowedValues(CropBoundary boundary) {
        float upper = 0f;
        float lower = 1f;
        switch (boundary) {
            case TOP:
                lower = 0f;
                upper = mCrop.bottom - pixelDistanceToFraction(mCropTouchMargin,
                        CropBoundary.BOTTOM);
                break;
            case BOTTOM:
                lower = mCrop.top + pixelDistanceToFraction(mCropTouchMargin, CropBoundary.TOP);
                upper = 1;
                break;
            case LEFT:
                lower = 0f;
                upper = mCrop.right - pixelDistanceToFraction(mCropTouchMargin, CropBoundary.RIGHT);
                break;
            case RIGHT:
                lower = mCrop.left + pixelDistanceToFraction(mCropTouchMargin, CropBoundary.LEFT);
                upper = 1;
                break;
        }
        Log.i(TAG, "getAllowedValues: " + boundary + ", "
                + "result=[lower=" + lower + ", upper=" + upper + "]");
        return new Range<>(lower, upper);
    }

    /**
     * @param action either ACTION_DOWN, ACTION_UP or ACTION_MOVE.
     * @param x coordinate of the relevant pointer.
     */
    private void updateListener(int action, float x) {
        if (mCropInteractionListener != null && isVertical(mCurrentDraggingBoundary)) {
            float boundaryPosition = getBoundaryPosition(mCurrentDraggingBoundary);
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    mCropInteractionListener.onCropDragStarted(mCurrentDraggingBoundary,
                            boundaryPosition, fractionToVerticalPixels(boundaryPosition),
                            (mCrop.left + mCrop.right) / 2, x);
                    break;
                case MotionEvent.ACTION_MOVE:
                    mCropInteractionListener.onCropDragMoved(mCurrentDraggingBoundary,
                            boundaryPosition, fractionToVerticalPixels(boundaryPosition),
                            (mCrop.left + mCrop.right) / 2, x);
                    break;
                case MotionEvent.ACTION_UP:
                    mCropInteractionListener.onCropDragComplete();
                    break;

            }
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

    private void drawContainerBackground(Canvas canvas, float left, float top, float right,
            float bottom) {
        canvas.drawRect(fractionToHorizontalPixels(left), fractionToVerticalPixels(top),
                fractionToHorizontalPixels(right),
                fractionToVerticalPixels(bottom), mContainerBackgroundPaint);
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
        private static final int LEFT_HANDLE_ID = 3;
        private static final int RIGHT_HANDLE_ID = 4;

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
            if (y > fractionToVerticalPixels(mCrop.top)
                    && y < fractionToVerticalPixels(mCrop.bottom)) {
                if (Math.abs(x - fractionToHorizontalPixels(mCrop.left)) < mCropTouchMargin) {
                    return LEFT_HANDLE_ID;
                }
                if (Math.abs(x - fractionToHorizontalPixels(mCrop.right)) < mCropTouchMargin) {
                    return RIGHT_HANDLE_ID;
                }
            }

            return ExploreByTouchHelper.HOST_ID;
        }

        @Override
        protected void getVisibleVirtualViews(List<Integer> virtualViewIds) {
            // Add views in traversal order
            virtualViewIds.add(TOP_HANDLE_ID);
            virtualViewIds.add(LEFT_HANDLE_ID);
            virtualViewIds.add(RIGHT_HANDLE_ID);
            virtualViewIds.add(BOTTOM_HANDLE_ID);
        }

        @Override
        protected void onPopulateEventForVirtualView(int virtualViewId, AccessibilityEvent event) {
            CropBoundary boundary = viewIdToBoundary(virtualViewId);
            event.setContentDescription(getBoundaryContentDescription(boundary));
        }

        @Override
        protected void onPopulateNodeForVirtualView(int virtualViewId,
                AccessibilityNodeInfoCompat node) {
            CropBoundary boundary = viewIdToBoundary(virtualViewId);
            node.setContentDescription(getBoundaryContentDescription(boundary));
            setNodePosition(getNodeRect(boundary), node);

            // Intentionally set the class name to SeekBar so that TalkBack uses volume control to
            // scroll.
            node.setClassName(SeekBar.class.getName());
            node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD);
            node.addAction(AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD);
        }

        @Override
        protected boolean onPerformActionForVirtualView(
                int virtualViewId, int action, Bundle arguments) {
            if (action != AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    && action != AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                return false;
            }
            CropBoundary boundary = viewIdToBoundary(virtualViewId);
            float delta = pixelDistanceToFraction(mCropTouchMargin, boundary);
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                delta = -delta;
            }
            setBoundaryPosition(boundary, delta + getBoundaryPosition(boundary));
            invalidateVirtualView(virtualViewId);
            sendEventForVirtualView(virtualViewId, AccessibilityEvent.TYPE_VIEW_SELECTED);
            return true;
        }

        private CharSequence getBoundaryContentDescription(CropBoundary boundary) {
            int template;
            switch (boundary) {
                case TOP:
                    template = R.string.screenshot_top_boundary_pct;
                    break;
                case BOTTOM:
                    template = R.string.screenshot_bottom_boundary_pct;
                    break;
                case LEFT:
                    template = R.string.screenshot_left_boundary_pct;
                    break;
                case RIGHT:
                    template = R.string.screenshot_right_boundary_pct;
                    break;
                default:
                    return "";
            }

            return getResources().getString(template,
                    Math.round(getBoundaryPosition(boundary) * 100));
        }

        private CropBoundary viewIdToBoundary(int viewId) {
            switch (viewId) {
                case TOP_HANDLE_ID:
                    return CropBoundary.TOP;
                case BOTTOM_HANDLE_ID:
                    return CropBoundary.BOTTOM;
                case LEFT_HANDLE_ID:
                    return CropBoundary.LEFT;
                case RIGHT_HANDLE_ID:
                    return CropBoundary.RIGHT;
            }
            return CropBoundary.NONE;
        }

        private Rect getNodeRect(CropBoundary boundary) {
            Rect rect;
            if (isVertical(boundary)) {
                int pixels = fractionToVerticalPixels(getBoundaryPosition(boundary));
                rect = new Rect(0, (int) (pixels - mCropTouchMargin),
                        getWidth(), (int) (pixels + mCropTouchMargin));
                // Top boundary can sometimes go beyond the view, shift it down to compensate so
                // the area is big enough.
                if (rect.top < 0) {
                    rect.offset(0, -rect.top);
                }
            } else {
                int pixels = fractionToHorizontalPixels(getBoundaryPosition(boundary));
                rect = new Rect((int) (pixels - mCropTouchMargin),
                        (int) (fractionToVerticalPixels(mCrop.top) + mCropTouchMargin),
                        (int) (pixels + mCropTouchMargin),
                        (int) (fractionToVerticalPixels(mCrop.bottom) - mCropTouchMargin));
            }
            return rect;
        }

        private void setNodePosition(Rect rect, AccessibilityNodeInfoCompat node) {
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
        void onCropDragStarted(CropBoundary boundary, float boundaryPosition,
                int boundaryPositionPx, float horizontalCenter, float x);
        void onCropDragMoved(CropBoundary boundary, float boundaryPosition,
                int boundaryPositionPx, float horizontalCenter, float x);
        void onCropDragComplete();
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
