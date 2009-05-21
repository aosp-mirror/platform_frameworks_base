/*
 * Copyright (C) 2008-2009 The Android Open Source Project
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

package com.android.gesture;

import android.graphics.Color;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/**
 * TouchThroughGesturing implements the interaction behavior that allows a user
 * to gesture over a regular UI widget such as ListView and at the same time,
 * still allows a user to perform basic interactions (clicking, scrolling and panning) 
 * with the underlying widget.
 */

public class TouchThroughGesturing implements GestureListener {
    public static final int SINGLE_STROKE = 0;
    public static final int MULTIPLE_STROKE = 1;

    private static final float STROKE_LENGTH_THRESHOLD = 30;
    private static final float SQUARENESS_THRESHOLD = 0.275f;
    private static final float ANGLE_THRESHOLD = 40;

    private static final boolean STEAL_EVENTS = false;

    public static final int DEFAULT_UNCERTAIN_GESTURE_COLOR = Color.argb(60, 255, 255, 0);

    private boolean mIsGesturing = false;

    private float mTotalLength;

    private float mX;
    private float mY;

    // TODO: Use WeakReference?
    private View mModel;

    private int mGestureType = SINGLE_STROKE;
    private int mUncertainGestureColor = DEFAULT_UNCERTAIN_GESTURE_COLOR;

    // TODO: Use WeakReferences
    private final ArrayList<GestureActionListener> mActionListeners =
            new ArrayList<GestureActionListener>();

    public TouchThroughGesturing(View model) {
        mModel = model;
    }

    /**
     * 
     * @param type SINGLE_STROKE or MULTIPLE_STROKE
     */
    public void setGestureType(int type) {
        mGestureType = type;
    }
    
    public void setUncertainGestureColor(int color) {
        mUncertainGestureColor = color;
    }

    public void onStartGesture(GestureOverlay overlay, MotionEvent event) {
        if (mGestureType == MULTIPLE_STROKE) {
            overlay.cancelFadingOut();
        }

        mX = event.getX();
        mY = event.getY();
        mTotalLength = 0;
        mIsGesturing = false;

        if (mGestureType == SINGLE_STROKE || overlay.getCurrentGesture() == null
                || overlay.getCurrentGesture().getStrokesCount() == 0) {
            overlay.setGestureColor(mUncertainGestureColor);
        }

        mModel.dispatchTouchEvent(event);
    }

    public void onGesture(GestureOverlay overlay, MotionEvent event) {
        //noinspection PointlessBooleanExpression
        if (!STEAL_EVENTS) {
            mModel.dispatchTouchEvent(event);
        }

        if (mIsGesturing) {
            return;
        }

        final float x = event.getX();
        final float y = event.getY();
        final float dx = x - mX;
        final float dy = y - mY;

        mTotalLength += (float)Math.sqrt(dx * dx + dy * dy);
        mX = x;
        mY = y;

        if (mTotalLength > STROKE_LENGTH_THRESHOLD) {
            final OrientedBoundingBox box =
                    GestureUtilities.computeOrientedBoundingBox(overlay.getCurrentStroke());
            float angle = Math.abs(box.orientation);
            if (angle > 90) {
                angle = 180 - angle;
            }
            if (box.squareness > SQUARENESS_THRESHOLD || angle < ANGLE_THRESHOLD) {
                mIsGesturing = true;
                overlay.setGestureColor(GestureOverlay.DEFAULT_GESTURE_COLOR);
                if (STEAL_EVENTS) {
                    event = MotionEvent.obtain(event.getDownTime(), System.currentTimeMillis(),
                            MotionEvent.ACTION_UP, x, y, event.getPressure(), event.getSize(),
                            event.getMetaState(), event.getXPrecision(), event.getYPrecision(),
                            event.getDeviceId(), event.getEdgeFlags());
                }
            }
        }

        if (STEAL_EVENTS) {
            mModel.dispatchTouchEvent(event);
        }
    }

    public void onFinishGesture(GestureOverlay overlay, MotionEvent event) {
        if (mIsGesturing) {
            overlay.clear(true);

            final ArrayList<GestureActionListener> listeners = mActionListeners;
            final int count = listeners.size();

            for (int i = 0; i < count; i++) {
                listeners.get(i).onGesturePerformed(overlay, overlay.getCurrentGesture());
            }
        } else {
            mModel.dispatchTouchEvent(event);
            overlay.clear(false);
        }
    }

    public void addGestureActionListener(GestureActionListener listener) {
        mActionListeners.add(listener);
    }

    public void removeGestureActionListener(GestureActionListener listener) {
        mActionListeners.remove(listener);
    }

    public boolean isGesturing() {
        return mIsGesturing;
    }
}
