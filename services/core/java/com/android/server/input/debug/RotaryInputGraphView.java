/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.input.debug;

import static android.util.TypedValue.COMPLEX_UNIT_SP;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.Iterator;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Shows a graph with the rotary input values as a function of time.
 * The graph gets reset if no action is received for a certain amount of time.
 */
public class RotaryInputGraphView extends View {

    private static final int FRAME_COLOR = 0xbf741b47;
    private static final int FRAME_WIDTH_SP = 2;
    private static final int FRAME_BORDER_GAP_SP = 10;
    private static final int FRAME_TEXT_SIZE_SP = 10;
    private static final int FRAME_TEXT_OFFSET_SP = 2;
    private static final int GRAPH_COLOR = 0xffff00ff;
    private static final int GRAPH_LINE_WIDTH_SP = 1;
    private static final int GRAPH_POINT_RADIUS_SP = 4;
    private static final long MAX_SHOWN_TIME_INTERVAL = TimeUnit.SECONDS.toMillis(5);
    private static final float DEFAULT_FRAME_CENTER_POSITION = 0;
    private static final int MAX_GRAPH_VALUES_SIZE = 400;
    /** Maximum time between values so that they are considered part of the same gesture. */
    private static final long MAX_GESTURE_TIME = TimeUnit.SECONDS.toMillis(1);

    private final DisplayMetrics mDm;
    /**
     * Distance in position units (amount scrolled in display pixels) from the center to the
     * top/bottom frame lines.
     */
    private final float mFrameCenterToBorderDistance;
    private final float mScaledVerticalScrollFactor;
    private final Locale mDefaultLocale = Locale.getDefault();
    private final Paint mFramePaint = new Paint();
    private final Paint mFrameTextPaint = new Paint();
    private final Paint mGraphLinePaint = new Paint();
    private final Paint mGraphPointPaint = new Paint();

    private final CyclicBuffer mGraphValues = new CyclicBuffer(MAX_GRAPH_VALUES_SIZE);
    /** Position at which graph values are placed at the center of the graph. */
    private float mFrameCenterPosition = DEFAULT_FRAME_CENTER_POSITION;

    public RotaryInputGraphView(Context c) {
        super(c);

        mDm = mContext.getResources().getDisplayMetrics();
        // This makes the center-to-border distance equivalent to the display height, meaning
        // that the total height of the graph is equivalent to 2x the display height.
        mFrameCenterToBorderDistance = mDm.heightPixels;
        mScaledVerticalScrollFactor = ViewConfiguration.get(c).getScaledVerticalScrollFactor();

        mFramePaint.setColor(FRAME_COLOR);
        mFramePaint.setStrokeWidth(applyDimensionSp(FRAME_WIDTH_SP, mDm));

        mFrameTextPaint.setColor(GRAPH_COLOR);
        mFrameTextPaint.setTextSize(applyDimensionSp(FRAME_TEXT_SIZE_SP, mDm));

        mGraphLinePaint.setColor(GRAPH_COLOR);
        mGraphLinePaint.setStrokeWidth(applyDimensionSp(GRAPH_LINE_WIDTH_SP, mDm));
        mGraphLinePaint.setStrokeCap(Paint.Cap.ROUND);
        mGraphLinePaint.setStrokeJoin(Paint.Join.ROUND);

        mGraphPointPaint.setColor(GRAPH_COLOR);
        mGraphPointPaint.setStrokeWidth(applyDimensionSp(GRAPH_POINT_RADIUS_SP, mDm));
        mGraphPointPaint.setStrokeCap(Paint.Cap.ROUND);
        mGraphPointPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    /**
     * Reads new scroll axis value and updates the list accordingly. Old positions are
     * kept at the front (what you would get with getFirst), while the recent positions are
     * kept at the back (what you would get with getLast). Also updates the frame center
     * position to handle out-of-bounds cases.
     */
    public void addValue(float scrollAxisValue, long eventTime) {
        // Remove values that are too old.
        while (mGraphValues.getSize() > 0
                && (eventTime - mGraphValues.getFirst().mTime) > MAX_SHOWN_TIME_INTERVAL) {
            mGraphValues.removeFirst();
        }

        // If there are no recent values, reset the frame center.
        if (mGraphValues.getSize() == 0) {
            mFrameCenterPosition = DEFAULT_FRAME_CENTER_POSITION;
        }

        // Handle new value. We multiply the scroll axis value by the scaled scroll factor to
        // get the amount of pixels to be scrolled. We also compute the accumulated position
        // by adding the current value to the last one (if not empty).
        final float displacement = scrollAxisValue * mScaledVerticalScrollFactor;
        final float prevPos = (mGraphValues.getSize() == 0 ? 0 : mGraphValues.getLast().mPos);
        final float pos = prevPos + displacement;

        mGraphValues.add(pos, eventTime);

        // The difference between the distance of the most recent position from the center
        // frame (pos - mFrameCenterPosition) and the maximum allowed distance from the center
        // frame (mFrameCenterToBorderDistance).
        final float verticalDiff = Math.abs(pos - mFrameCenterPosition)
                - mFrameCenterToBorderDistance;
        // If needed, translate frame.
        if (verticalDiff > 0) {
            final int sign = pos - mFrameCenterPosition < 0 ? -1 : 1;
            // Here, we update the center frame position by the exact amount needed for us to
            // stay within the maximum allowed distance from the center frame.
            mFrameCenterPosition += sign * verticalDiff;
        }

        // Redraw canvas.
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Note: vertical coordinates in Canvas go from top to bottom,
        // that is bottomY > middleY > topY.
        final int verticalMargin = applyDimensionSp(FRAME_BORDER_GAP_SP, mDm);
        final int topY = verticalMargin;
        final int bottomY = getHeight() - verticalMargin;
        final int middleY = (topY + bottomY) / 2;

        // Note: horizontal coordinates in Canvas go from left to right,
        // that is rightX > leftX.
        final int leftX = 0;
        final int rightX = getWidth();

        // Draw the frame, which includes 3 lines that show the maximum,
        // minimum and middle positions of the graph.
        canvas.drawLine(leftX, topY, rightX, topY, mFramePaint);
        canvas.drawLine(leftX, middleY, rightX, middleY, mFramePaint);
        canvas.drawLine(leftX, bottomY, rightX, bottomY, mFramePaint);

        // Draw the position that each frame line corresponds to.
        final int frameTextOffset = applyDimensionSp(FRAME_TEXT_OFFSET_SP, mDm);
        canvas.drawText(
                String.format(mDefaultLocale, "%.1f",
                        mFrameCenterPosition + mFrameCenterToBorderDistance),
                leftX,
                topY - frameTextOffset, mFrameTextPaint
        );
        canvas.drawText(
                String.format(mDefaultLocale, "%.1f", mFrameCenterPosition),
                leftX,
                middleY - frameTextOffset, mFrameTextPaint
        );
        canvas.drawText(
                String.format(mDefaultLocale, "%.1f",
                        mFrameCenterPosition - mFrameCenterToBorderDistance),
                leftX,
                bottomY - frameTextOffset, mFrameTextPaint
        );

        // If there are no graph values to be drawn, stop here.
        if (mGraphValues.getSize() == 0) {
            return;
        }

        // Draw the graph using the times and positions.
        // We start at the most recent value (which should be drawn at the right) and move
        // to the older values (which should be drawn to the left of more recent ones). Negative
        // indices are handled by circuling back to the end of the buffer.
        final long mostRecentTime = mGraphValues.getLast().mTime;
        float prevCoordX = 0;
        float prevCoordY = 0;
        float prevAge = 0;
        for (Iterator<GraphValue> iter = mGraphValues.reverseIterator(); iter.hasNext();) {
            final GraphValue value = iter.next();

            final int age = (int) (mostRecentTime - value.mTime);
            final float pos = value.mPos;

            // We get the horizontal coordinate in time units from left to right with
            // (MAX_SHOWN_TIME_INTERVAL - age). Then, we rescale it to match the canvas
            // units by dividing it by the time-domain length (MAX_SHOWN_TIME_INTERVAL)
            // and by multiplying it by the canvas length (rightX - leftX). Finally, we
            // offset the coordinate by adding it to leftX.
            final float coordX = leftX + ((float) (MAX_SHOWN_TIME_INTERVAL - age)
                    / MAX_SHOWN_TIME_INTERVAL) * (rightX - leftX);

            // We get the vertical coordinate in position units from middle to top with
            // (pos - mFrameCenterPosition). Then, we rescale it to match the canvas
            // units by dividing it by half of the position-domain length
            // (mFrameCenterToBorderDistance) and by multiplying it by half of the canvas
            // length (middleY - topY). Finally, we offset the coordinate by subtracting
            // it from middleY (we can't "add" here because the coordinate grows from top
            // to bottom).
            final float coordY = middleY - ((pos - mFrameCenterPosition)
                    / mFrameCenterToBorderDistance) * (middleY - topY);

            // Draw a point for this value.
            canvas.drawPoint(coordX, coordY, mGraphPointPaint);

            // If this value is part of the same gesture as the previous one, draw a line
            // between them. We ignore the first value (with age = 0).
            if (age != 0 && (age - prevAge) <= MAX_GESTURE_TIME) {
                canvas.drawLine(prevCoordX, prevCoordY, coordX, coordY, mGraphLinePaint);
            }

            prevCoordX = coordX;
            prevCoordY = coordY;
            prevAge = age;
        }
    }

    public float getFrameCenterPosition() {
        return mFrameCenterPosition;
    }

    /**
     * Converts a dimension in scaled pixel units to integer display pixels.
     */
    private static int applyDimensionSp(int dimensionSp, DisplayMetrics dm) {
        return (int) TypedValue.applyDimension(COMPLEX_UNIT_SP, dimensionSp, dm);
    }

    /**
     * Holds data needed to draw each entry in the graph.
     */
    private static class GraphValue {
        /** Position. */
        float mPos;
        /** Time when this value was added. */
        long mTime;

        GraphValue(float pos, long time) {
            this.mPos = pos;
            this.mTime = time;
        }
    }

    /**
     * Holds the graph values as a cyclic buffer. It has a fixed capacity, and it replaces the
     * old values with new ones to avoid creating new objects.
     */
    private static class CyclicBuffer {
        private final GraphValue[] mValues;
        private final int mCapacity;
        private int mSize = 0;
        private int mLastIndex = 0;

        // The iteration index and counter are here to make it easier to reset them.
        /** Determines the value currently pointed by the iterator. */
        private int mIteratorIndex;
        /** Counts how many values have been iterated through. */
        private int mIteratorCount;

        /** Used traverse the values in reverse order. */
        private final Iterator<GraphValue> mReverseIterator = new Iterator<GraphValue>() {
            @Override
            public boolean hasNext() {
                return mIteratorCount <= mSize;
            }

            @Override
            public GraphValue next() {
                // Returns the value currently pointed by the iterator and moves the iterator to
                // the previous one.
                mIteratorCount++;
                return mValues[(mIteratorIndex-- + mCapacity) % mCapacity];
            }
        };

        CyclicBuffer(int capacity) {
            mCapacity = capacity;
            mValues = new GraphValue[capacity];
        }

        /**
         * Add new graph value. If there is an existing object, we replace its data with the
         * new one. With this, we re-use old objects instead of creating new ones.
         */
        void add(float pos, long time) {
            mLastIndex = (mLastIndex + 1) % mCapacity;
            if (mValues[mLastIndex] == null) {
                mValues[mLastIndex] = new GraphValue(pos, time);
            } else {
                final GraphValue oldValue = mValues[mLastIndex];
                oldValue.mPos = pos;
                oldValue.mTime = time;
            }

            // If needed, account for new value in the buffer size.
            if (mSize != mCapacity) {
                mSize++;
            }
        }

        int getSize() {
            return mSize;
        }

        GraphValue getFirst() {
            final int distanceBetweenLastAndFirst = (mCapacity - mSize) + 1;
            final int firstIndex = (mLastIndex + distanceBetweenLastAndFirst) % mCapacity;
            return mValues[firstIndex];
        }

        GraphValue getLast() {
            return mValues[mLastIndex];
        }

        void removeFirst() {
            mSize--;
        }

        /** Returns an iterator pointing at the last value. */
        Iterator<GraphValue> reverseIterator() {
            mIteratorIndex = mLastIndex;
            mIteratorCount = 1;
            return mReverseIterator;
        }
    }
}
