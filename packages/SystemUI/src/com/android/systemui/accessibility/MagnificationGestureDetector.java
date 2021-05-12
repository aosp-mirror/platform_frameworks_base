/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.accessibility;

import android.annotation.DisplayContext;
import android.annotation.NonNull;
import android.content.Context;
import android.graphics.PointF;
import android.os.Handler;
import android.view.Display;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

/**
 * Detects single tap and drag gestures using the supplied {@link MotionEvent}s. The {@link
 * OnGestureListener} callback will notify users when a particular motion event has occurred. This
 * class should only be used with {@link MotionEvent}s reported via touch (don't use for trackball
 * events).
 */
class MagnificationGestureDetector {

    interface OnGestureListener {
        /**
         * Called when a tap is completed within {@link ViewConfiguration#getLongPressTimeout()} and
         * the offset between {@link MotionEvent}s and the down event doesn't exceed {@link
         * ViewConfiguration#getScaledTouchSlop()}.
         *
         * @return {@code true} if this gesture is handled.
         */
        boolean onSingleTap();

        /**
         * Called when the user is performing dragging gesture. It is started after the offset
         * between the down location and the move event location exceed
         * {@link ViewConfiguration#getScaledTouchSlop()}.
         *
         * @param offsetX The X offset in screen coordinate.
         * @param offsetY The Y offset in screen coordinate.
         * @return {@code true} if this gesture is handled.
         */
        boolean onDrag(float offsetX, float offsetY);

        /**
         * Notified when a tap occurs with the down {@link MotionEvent} that triggered it. This will
         * be triggered immediately for every down event. All other events should be preceded by
         * this.
         *
         * @param x The X coordinate of the down event.
         * @param y The Y coordinate of the down event.
         * @return {@code true} if the down event is handled, otherwise the events won't be sent to
         * the view.
         */
        boolean onStart(float x, float y);

        /**
         * Called when the detection is finished. In other words, it is called when up/cancel {@link
         * MotionEvent} is received. It will be triggered after single-tap
         *
         * @param x The X coordinate on the screen of the up event or the cancel event.
         * @param y The Y coordinate on the screen of the up event or the cancel event.
         * @return {@code true} if the event is handled.
         */
        boolean onFinish(float x, float y);
    }

    private final PointF mPointerDown = new PointF();
    private final PointF mPointerLocation = new PointF(Float.NaN, Float.NaN);
    private final Handler mHandler;
    private final Runnable mCancelTapGestureRunnable;
    private final OnGestureListener mOnGestureListener;
    private int mTouchSlopSquare;
    // Assume the gesture default is a single-tap. Set it to false if the gesture couldn't be a
    // single-tap anymore.
    private boolean mDetectSingleTap = true;
    private boolean mDraggingDetected = false;

    /**
     * @param context  {@link Context} that is from {@link Context#createDisplayContext(Display)}.
     * @param handler  The handler to post the runnable.
     * @param listener The listener invoked for all the callbacks.
     */
    MagnificationGestureDetector(@DisplayContext Context context, @NonNull Handler handler,
            @NonNull OnGestureListener listener) {
        final int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        mTouchSlopSquare = touchSlop * touchSlop;
        mHandler = handler;
        mOnGestureListener = listener;
        mCancelTapGestureRunnable = () -> mDetectSingleTap = false;
    }

    /**
     * Analyzes the given motion event and if applicable to trigger the appropriate callbacks on the
     * {@link OnGestureListener} supplied.
     *
     * @param event The current motion event.
     * @return {@code True} if the {@link OnGestureListener} consumes the event, else false.
     */
    boolean onTouch(MotionEvent event) {
        final float rawX = event.getRawX();
        final float rawY = event.getRawY();
        boolean handled = false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mPointerDown.set(rawX, rawY);
                mHandler.postAtTime(mCancelTapGestureRunnable,
                        event.getDownTime() + ViewConfiguration.getLongPressTimeout());
                handled |= mOnGestureListener.onStart(rawX, rawY);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                stopSingleTapDetection();
                break;
            case MotionEvent.ACTION_MOVE:
                stopSingleTapDetectionIfNeeded(rawX, rawY);
                handled |= notifyDraggingGestureIfNeeded(rawX, rawY);
                break;
            case MotionEvent.ACTION_UP:
                stopSingleTapDetectionIfNeeded(rawX, rawY);
                if (mDetectSingleTap) {
                    handled |= mOnGestureListener.onSingleTap();
                }
                // Fall through
            case MotionEvent.ACTION_CANCEL:
                handled |= mOnGestureListener.onFinish(rawX, rawY);
                reset();
                break;
        }
        return handled;
    }

    private void stopSingleTapDetectionIfNeeded(float x, float y) {
        if (mDraggingDetected) {
            return;
        }
        if (!isLocationValid(mPointerDown)) {
            return;
        }

        final int deltaX = (int) (mPointerDown.x - x);
        final int deltaY = (int) (mPointerDown.y - y);
        final int distanceSquare = (deltaX * deltaX) + (deltaY * deltaY);
        if (distanceSquare > mTouchSlopSquare) {
            mDraggingDetected = true;
            stopSingleTapDetection();
        }
    }

    private void stopSingleTapDetection() {
        mHandler.removeCallbacks(mCancelTapGestureRunnable);
        mDetectSingleTap = false;
    }

    private boolean notifyDraggingGestureIfNeeded(float x, float y) {
        if (!mDraggingDetected) {
            return false;
        }
        if (!isLocationValid(mPointerLocation)) {
            mPointerLocation.set(mPointerDown);
        }
        final float offsetX = x - mPointerLocation.x;
        final float offsetY = y - mPointerLocation.y;
        mPointerLocation.set(x, y);
        return mOnGestureListener.onDrag(offsetX, offsetY);
    }

    private void reset() {
        resetPointF(mPointerDown);
        resetPointF(mPointerLocation);
        mHandler.removeCallbacks(mCancelTapGestureRunnable);
        mDetectSingleTap = true;
        mDraggingDetected = false;
    }

    private static void resetPointF(PointF pointF) {
        pointF.x = Float.NaN;
        pointF.y = Float.NaN;
    }

    private static boolean isLocationValid(PointF location) {
        return !Float.isNaN(location.x) && !Float.isNaN(location.y);
    }
}
