/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.view;

import android.content.Context;
import android.util.Log;
import android.view.GestureDetector.SimpleOnGestureListener;

/**
 * Detects transformation gestures involving more than one pointer ("multitouch")
 * using the supplied {@link MotionEvent}s. The {@link OnGestureListener} callback
 * will notify users when a particular gesture event has occurred. This class
 * should only be used with {@link MotionEvent}s reported via touch.
 * 
 * To use this class:
 * <ul>
 *  <li>Create an instance of the {@code TransformGestureDetector} for your
 *      {@link View}
 *  <li>In the {@link View#onTouchEvent(MotionEvent)} method ensure you call
 *          {@link #onTouchEvent(MotionEvent)}. The methods defined in your
 *          callback will be executed when the events occur.
 * </ul>
 * @hide Pending API approval
 */
public class TransformGestureDetector {
    /**
     * The listener for receiving notifications when gestures occur.
     * If you want to listen for all the different gestures then implement
     * this interface. If you only want to listen for a subset it might
     * be easier to extend {@link SimpleOnGestureListener}.
     * 
     * An application will receive events in the following order:
     * One onTransformBegin()
     * Zero or more onTransform()
     * One onTransformEnd() or onTransformFling()
     */
    public interface OnTransformGestureListener {
        /**
         * Responds to transformation events for a gesture in progress.
         * Reported by pointer motion.
         * 
         * @param detector The detector reporting the event - use this to
         *          retrieve extended info about event state.
         * @return true if the event was handled, false otherwise.
         */
        public boolean onTransform(TransformGestureDetector detector);
        
        /**
         * Responds to the beginning of a transformation gesture. Reported by
         * new pointers going down.
         * 
         * @param detector The detector reporting the event - use this to
         *          retrieve extended info about event state.
         * @return true if the event was handled, false otherwise.
         */
        public boolean onTransformBegin(TransformGestureDetector detector);
 
        /**
         * Responds to the end of a transformation gesture. Reported by existing
         * pointers going up. If the end of a gesture would result in a fling,
         * onTransformFling is called instead.
         * 
         * @param detector The detector reporting the event - use this to
         *          retrieve extended info about event state.
         * @return true if the event was handled, false otherwise.
         */
        public boolean onTransformEnd(TransformGestureDetector detector);

        /**
         * Responds to the end of a transformation gesture that begins a fling.
         * Reported by existing pointers going up. If the end of a gesture 
         * would not result in a fling, onTransformEnd is called instead.
         * 
         * @param detector The detector reporting the event - use this to
         *          retrieve extended info about event state.
         * @return true if the event was handled, false otherwise.
         */
        public boolean onTransformFling(TransformGestureDetector detector);
    }
    
    private static final boolean DEBUG = false;
    
    private static final int INITIAL_EVENT_IGNORES = 2;
    
    private Context mContext;
    private float mTouchSizeScale;
    private OnTransformGestureListener mListener;
    private int mVelocityTimeUnits;
    private MotionEvent mInitialEvent;
    
    private MotionEvent mPrevEvent;
    private MotionEvent mCurrEvent;
    private VelocityTracker mVelocityTracker;

    private float mCenterX;
    private float mCenterY;
    private float mTransX;
    private float mTransY;
    private float mPrevFingerDiffX;
    private float mPrevFingerDiffY;
    private float mCurrFingerDiffX;
    private float mCurrFingerDiffY;
    private float mRotateDegrees;
    private float mCurrLen;
    private float mPrevLen;
    private float mScaleFactor;
    
    // Units in pixels. Current value is pulled out of thin air for debugging only.
    private float mPointerJumpLimit = 30;
    
    private int mEventIgnoreCount;
    
   public TransformGestureDetector(Context context, OnTransformGestureListener listener,
            int velocityTimeUnits) {
        mContext = context;
        mListener = listener;
        mTouchSizeScale = context.getResources().getDisplayMetrics().widthPixels/3;
        mVelocityTimeUnits = velocityTimeUnits;
        mEventIgnoreCount = INITIAL_EVENT_IGNORES;
    }
    
    public TransformGestureDetector(Context context, OnTransformGestureListener listener) {
        this(context, listener, 1000);
    }
    
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getAction();
        boolean handled = true;

        if (mInitialEvent == null) {
            // No transform gesture in progress
            if ((action == MotionEvent.ACTION_POINTER_1_DOWN ||
                    action == MotionEvent.ACTION_POINTER_2_DOWN) &&
                    event.getPointerCount() >= 2) {
                // We have a new multi-finger gesture
                mInitialEvent = MotionEvent.obtain(event);
                mPrevEvent = MotionEvent.obtain(event);
                mVelocityTracker = VelocityTracker.obtain();
                handled = mListener.onTransformBegin(this);
            }
        } else {
            // Transform gesture in progress - attempt to handle it
            switch (action) {
                case MotionEvent.ACTION_POINTER_1_UP:
                case MotionEvent.ACTION_POINTER_2_UP:
                    // Gesture ended
                    handled = mListener.onTransformEnd(this);

                    reset();
                    break;
                    
                case MotionEvent.ACTION_CANCEL:
                    handled = mListener.onTransformEnd(this);
                    
                    reset();
                    break;
                    
                case MotionEvent.ACTION_MOVE:
                    setContext(event);

                    // Our first few events can be crazy from some touchscreens - drop them.
                    if (mEventIgnoreCount == 0) {
                        mVelocityTracker.addMovement(event);
                        handled = mListener.onTransform(this);
                    } else {
                        mEventIgnoreCount--;
                    }
                    
                    mPrevEvent.recycle();
                    mPrevEvent = MotionEvent.obtain(event);
                    break;
            }
        }
        return handled;
    }
    
    private void setContext(MotionEvent curr) {
        mCurrEvent = MotionEvent.obtain(curr);

        mRotateDegrees = -1;
        mCurrLen = -1;
        mPrevLen = -1;
        mScaleFactor = -1;

        final MotionEvent prev = mPrevEvent;
        
        float px0 = prev.getX(0);
        float py0 = prev.getY(0);
        float px1 = prev.getX(1);
        float py1 = prev.getY(1);
        float cx0 = curr.getX(0);
        float cy0 = curr.getY(0);
        float cx1 = curr.getX(1);
        float cy1 = curr.getY(1);

        // Some touchscreens do weird things with pointer values where points are
        // too close along one axis. Try to detect this here and smooth things out.
        // The main indicator is that we get the X or Y value from the other pointer.
        final float dx0 = cx0 - px0;
        final float dy0 = cy0 - py0;
        final float dx1 = cx1 - px1;
        final float dy1 = cy1 - py1;

        if (cx0 == cx1) {
            if (Math.abs(dx0) > mPointerJumpLimit) {
                 cx0 = px0;
            } else if (Math.abs(dx1) > mPointerJumpLimit) {
                cx1 = px1;
            }
        } else if (cy0 == cy1) {
            if (Math.abs(dy0) > mPointerJumpLimit) {
                cy0 = py0;
            } else if (Math.abs(dy1) > mPointerJumpLimit) {
                cy1 = py1;
            }
        }
        
        final float pvx = px1 - px0;
        final float pvy = py1 - py0;
        final float cvx = cx1 - cx0;
        final float cvy = cy1 - cy0;
        mPrevFingerDiffX = pvx;
        mPrevFingerDiffY = pvy;
        mCurrFingerDiffX = cvx;
        mCurrFingerDiffY = cvy;

        final float pmidx = px0 + pvx * 0.5f;
        final float pmidy = py0 + pvy * 0.5f;
        final float cmidx = cx0 + cvx * 0.5f;
        final float cmidy = cy0 + cvy * 0.5f;

        mCenterX = cmidx;
        mCenterY = cmidy;
        mTransX = cmidx - pmidx;
        mTransY = cmidy - pmidy;
    }
    
    private void reset() {
        if (mInitialEvent != null) {
            mInitialEvent.recycle();
            mInitialEvent = null;
        }
        if (mPrevEvent != null) {
            mPrevEvent.recycle();
            mPrevEvent = null;
        }
        if (mCurrEvent != null) {
            mCurrEvent.recycle();
            mCurrEvent = null;
        }
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
        mEventIgnoreCount = INITIAL_EVENT_IGNORES;
    }
    
    public float getCenterX() {
        return mCenterX;
    }

    public float getCenterY() {
        return mCenterY;
    }

    public float getTranslateX() {
        return mTransX;
    }

    public float getTranslateY() {
        return mTransY;
    }

    public float getCurrentSpan() {
        if (mCurrLen == -1) {
            final float cvx = mCurrFingerDiffX;
            final float cvy = mCurrFingerDiffY;
            mCurrLen = (float)Math.sqrt(cvx*cvx + cvy*cvy);
        }
        return mCurrLen;
    }

    public float getPreviousSpan() {
        if (mPrevLen == -1) {
            final float pvx = mPrevFingerDiffX;
            final float pvy = mPrevFingerDiffY;
            mPrevLen = (float)Math.sqrt(pvx*pvx + pvy*pvy);
        }
        return mPrevLen;
    }

    public float getScaleFactor() {
        if (mScaleFactor == -1) {
            mScaleFactor = getCurrentSpan() / getPreviousSpan();
        }
        return mScaleFactor;
    }

    public float getRotation() {
        throw new UnsupportedOperationException();
    }
}
