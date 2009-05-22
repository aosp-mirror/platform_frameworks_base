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

package android.gesture;

import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.lang.ref.WeakReference;

/**
 * TouchThroughGesturing implements the interaction behavior that allows a user
 * to gesture over a regular UI widget such as ListView and at the same time,
 * still allows a user to perform basic interactions (clicking, scrolling and panning) 
 * with the underlying widget.
 */
public class TouchThroughGestureListener implements GestureOverlayView.OnGestureListener {
    public static final int SINGLE_STROKE = 0;
    public static final int MULTIPLE_STROKE = 1;

    // TODO: Add properties for all these
    private static final float STROKE_LENGTH_THRESHOLD = 30;
    private static final float SQUARENESS_THRESHOLD = 0.275f;
    private static final float ANGLE_THRESHOLD = 40;

    private boolean mIsGesturing = false;

    private float mTotalLength;

    private float mX;
    private float mY;

    private WeakReference<View> mModel;

    private int mGestureType = SINGLE_STROKE;

    // TODO: Use WeakReferences
    private final ArrayList<OnGesturePerformedListener> mPerformedListeners =
            new ArrayList<OnGesturePerformedListener>();

    private boolean mStealEvents = false;

    public TouchThroughGestureListener(View model) {
        this(model, false);
    }

    public TouchThroughGestureListener(View model, boolean stealEvents) {
        mModel = new WeakReference<View>(model);
        mStealEvents = stealEvents;
    }

    /**
     * 
     * @param type SINGLE_STROKE or MULTIPLE_STROKE
     */
    public void setGestureType(int type) {
        mGestureType = type;
    }
    
    public void onGestureStarted(GestureOverlayView overlay, MotionEvent event) {
        if (mGestureType == MULTIPLE_STROKE) {
            overlay.cancelFadingOut();
        }

        mX = event.getX();
        mY = event.getY();
        mTotalLength = 0;
        mIsGesturing = false;

        if (mGestureType == SINGLE_STROKE || overlay.getCurrentGesture() == null
                || overlay.getCurrentGesture().getStrokesCount() == 0) {
            overlay.setGestureDrawingColor(overlay.getUncertainGestureColor());
        }

        dispatchEventToModel(event);
    }

    private void dispatchEventToModel(MotionEvent event) {
        View v = mModel.get();
        if (v != null) v.dispatchTouchEvent(event);
    }

    public void onGesture(GestureOverlayView overlay, MotionEvent event) {
        //noinspection PointlessBooleanExpression
        if (!mStealEvents) {
            dispatchEventToModel(event);
        }

        if (mIsGesturing) {
            return;
        }

        final float x = event.getX();
        final float y = event.getY();
        final float dx = x - mX;
        final float dy = y - mY;

        mTotalLength += (float) Math.sqrt(dx * dx + dy * dy);
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
                overlay.setGestureDrawingColor(overlay.getGestureColor());
                if (mStealEvents) {
                    event = MotionEvent.obtain(event.getDownTime(), System.currentTimeMillis(),
                            MotionEvent.ACTION_UP, x, y, event.getPressure(), event.getSize(),
                            event.getMetaState(), event.getXPrecision(), event.getYPrecision(),
                            event.getDeviceId(), event.getEdgeFlags());
                }
            }
        }

        if (mStealEvents) {
            dispatchEventToModel(event);
        }
    }

    public void onGestureEnded(GestureOverlayView overlay, MotionEvent event) {
        if (mIsGesturing) {
            overlay.clear(true);

            final ArrayList<OnGesturePerformedListener> listeners = mPerformedListeners;
            final int count = listeners.size();

            for (int i = 0; i < count; i++) {
                listeners.get(i).onGesturePerformed(overlay, overlay.getCurrentGesture());
            }
        } else {
            dispatchEventToModel(event);
            overlay.clear(false);
        }
    }

    public void addOnGestureActionListener(OnGesturePerformedListener listener) {
        mPerformedListeners.add(listener);
    }

    public void removeOnGestureActionListener(OnGesturePerformedListener listener) {
        mPerformedListeners.remove(listener);
    }

    public boolean isGesturing() {
        return mIsGesturing;
    }

    public static interface OnGesturePerformedListener {
        public void onGesturePerformed(GestureOverlayView overlay, Gesture gesture);
    }
}
