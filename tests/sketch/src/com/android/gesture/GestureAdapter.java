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

public class GestureAdapter implements GestureListener {

    public static final int SINGLE_STROKE = 0;

    public static final int MULTIPLE_STROKE = 1;

    private static final float STROKE_LENGTH_THRESHOLD = 100;

    private static final float SQUARENESS_THRESHOLD = 0.24f;

    private static final int UNCERTAIN_GESTURE_COLOR = Color.argb(60, 255, 255, 0);

    private boolean mIsGesturing = false;

    private float mTotalLength;

    private float mX, mY;

    private View mModel;

    private int mGestureType = SINGLE_STROKE;

    private ArrayList<GestureActionListener> mActionListeners = new ArrayList<GestureActionListener>();

    public GestureAdapter(View model) {
        mModel = model;
    }

    public void setGestureType(int type) {
        mGestureType = type;
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
            overlay.setGestureColor(UNCERTAIN_GESTURE_COLOR);
        }
        mModel.dispatchTouchEvent(event);
    }

    public void onGesture(GestureOverlay overlay, MotionEvent event) {
        if (mIsGesturing) {
            return;
        }
        float x = event.getX();
        float y = event.getY();
        float dx = x - mX;
        float dy = y - mY;
        mTotalLength += (float)Math.sqrt(dx * dx + dy * dy);
        mX = x;
        mY = y;

        if (mTotalLength > STROKE_LENGTH_THRESHOLD) {
            OrientedBoundingBox bbx = GestureUtils.computeOrientedBBX(overlay.getCurrentStroke());
            if (bbx.squareness > SQUARENESS_THRESHOLD) {
                mIsGesturing = true;
                overlay.setGestureColor(GestureOverlay.DEFAULT_GESTURE_COLOR);
                event = MotionEvent.obtain(event.getDownTime(), System.currentTimeMillis(),
                        MotionEvent.ACTION_UP, x, y, event.getPressure(), event.getSize(), event
                                .getMetaState(), event.getXPrecision(), event.getYPrecision(),
                        event.getDeviceId(), event.getEdgeFlags());
            }
        }
        mModel.dispatchTouchEvent(event);
    }

    public void onFinishGesture(GestureOverlay overlay, MotionEvent event) {
        if (mIsGesturing) {
            overlay.clear(true);
            ArrayList<GestureActionListener> listeners = mActionListeners;
            int count = listeners.size();
            for (int i = 0; i < count; i++) {
                GestureActionListener listener = listeners.get(i);
                listener.onGesturePerformed(overlay, overlay.getCurrentGesture());
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
