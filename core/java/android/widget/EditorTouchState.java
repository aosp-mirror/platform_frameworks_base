/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.widget;

import static android.widget.Editor.logCursor;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.annotation.IntDef;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Helper class used by {@link Editor} to track state for touch events. Ideally the logic here
 * should be replaced with {@link android.view.GestureDetector}.
 *
 * @hide
 */
@VisibleForTesting(visibility = PACKAGE)
public class EditorTouchState {
    private float mLastDownX, mLastDownY;
    private long mLastDownMillis;
    private float mLastUpX, mLastUpY;
    private long mLastUpMillis;
    private boolean mIsOnHandle;

    @IntDef({MultiTapStatus.NONE, MultiTapStatus.FIRST_TAP, MultiTapStatus.DOUBLE_TAP,
            MultiTapStatus.TRIPLE_CLICK})
    @Retention(RetentionPolicy.SOURCE)
    @VisibleForTesting
    public @interface MultiTapStatus {
        int NONE = 0;
        int FIRST_TAP = 1;
        int DOUBLE_TAP = 2;
        int TRIPLE_CLICK = 3; // Only for mouse input.
    }
    @MultiTapStatus
    private int mMultiTapStatus = MultiTapStatus.NONE;
    private boolean mMultiTapInSameArea;

    private boolean mMovedEnoughForDrag;
    private float mInitialDragDirectionXYRatio;

    public float getLastDownX() {
        return mLastDownX;
    }

    public float getLastDownY() {
        return mLastDownY;
    }

    public float getLastUpX() {
        return mLastUpX;
    }

    public float getLastUpY() {
        return mLastUpY;
    }

    public boolean isDoubleTap() {
        return mMultiTapStatus == MultiTapStatus.DOUBLE_TAP;
    }

    public boolean isTripleClick() {
        return mMultiTapStatus == MultiTapStatus.TRIPLE_CLICK;
    }

    public boolean isMultiTap() {
        return mMultiTapStatus == MultiTapStatus.DOUBLE_TAP
                || mMultiTapStatus == MultiTapStatus.TRIPLE_CLICK;
    }

    public boolean isMultiTapInSameArea() {
        return isMultiTap() && mMultiTapInSameArea;
    }

    public boolean isMovedEnoughForDrag() {
        return mMovedEnoughForDrag;
    }

    /**
     * When {@link #isMovedEnoughForDrag()} is {@code true}, this function returns the x/y ratio for
     * the initial drag direction. Smaller values indicate that the direction is closer to vertical,
     * while larger values indicate that the direction is closer to horizontal. For example:
     * <ul>
     *     <li>if the drag direction is exactly vertical, this returns 0
     *     <li>if the drag direction is exactly horizontal, this returns {@link Float#MAX_VALUE}
     *     <li>if the drag direction is 45 deg from vertical, this returns 1
     *     <li>if the drag direction is 30 deg from vertical, this returns 0.58 (x delta is smaller
     *     than y delta)
     *     <li>if the drag direction is 60 deg from vertical, this returns 1.73 (x delta is bigger
     *     than y delta)
     * </ul>
     * This function never returns negative values, regardless of the direction of the drag.
     */
    public float getInitialDragDirectionXYRatio() {
        return mInitialDragDirectionXYRatio;
    }

    public void setIsOnHandle(boolean onHandle) {
        mIsOnHandle = onHandle;
    }

    public boolean isOnHandle() {
        return mIsOnHandle;
    }

    /**
     * Updates the state based on the new event.
     */
    public void update(MotionEvent event, ViewConfiguration config) {
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            final boolean isMouse = event.isFromSource(InputDevice.SOURCE_MOUSE);

            // We check both the time between the last up and current down event, as well as the
            // time between the first down and up events. The latter check is necessary to handle
            // the case when the user taps, drags/holds for some time, and then lifts up and
            // quickly taps in the same area. This scenario should not be treated as a double-tap.
            // This follows the behavior in GestureDetector.
            final long millisSinceLastUp = event.getEventTime() - mLastUpMillis;
            final long millisBetweenLastDownAndLastUp = mLastUpMillis - mLastDownMillis;

            // Detect double tap and triple click.
            if (millisSinceLastUp <= ViewConfiguration.getDoubleTapTimeout()
                    && millisBetweenLastDownAndLastUp <= ViewConfiguration.getDoubleTapTimeout()
                    && (mMultiTapStatus == MultiTapStatus.FIRST_TAP
                    || (mMultiTapStatus == MultiTapStatus.DOUBLE_TAP && isMouse))) {
                if (mMultiTapStatus == MultiTapStatus.FIRST_TAP) {
                    mMultiTapStatus = MultiTapStatus.DOUBLE_TAP;
                } else {
                    mMultiTapStatus = MultiTapStatus.TRIPLE_CLICK;
                }
                mMultiTapInSameArea = isDistanceWithin(mLastDownX, mLastDownY,
                        event.getX(), event.getY(), config.getScaledDoubleTapSlop());
                if (TextView.DEBUG_CURSOR) {
                    String status = isDoubleTap() ? "double" : "triple";
                    String inSameArea = mMultiTapInSameArea ? "in same area" : "not in same area";
                    logCursor("EditorTouchState", "ACTION_DOWN: %s tap detected, %s",
                            status, inSameArea);
                }
            } else {
                mMultiTapStatus = MultiTapStatus.FIRST_TAP;
                mMultiTapInSameArea = false;
                if (TextView.DEBUG_CURSOR) {
                    logCursor("EditorTouchState", "ACTION_DOWN: first tap detected");
                }
            }
            mLastDownX = event.getX();
            mLastDownY = event.getY();
            mLastDownMillis = event.getEventTime();
            mMovedEnoughForDrag = false;
            mInitialDragDirectionXYRatio = 0.0f;
        } else if (action == MotionEvent.ACTION_UP) {
            if (TextView.DEBUG_CURSOR) {
                logCursor("EditorTouchState", "ACTION_UP");
            }
            mLastUpX = event.getX();
            mLastUpY = event.getY();
            mLastUpMillis = event.getEventTime();
            mMovedEnoughForDrag = false;
            mInitialDragDirectionXYRatio = 0.0f;
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (!mMovedEnoughForDrag) {
                float deltaX = event.getX() - mLastDownX;
                float deltaY = event.getY() - mLastDownY;
                float deltaXSquared = deltaX * deltaX;
                float distanceSquared = (deltaXSquared) + (deltaY * deltaY);
                int touchSlop = config.getScaledTouchSlop();
                mMovedEnoughForDrag = distanceSquared > touchSlop * touchSlop;
                if (mMovedEnoughForDrag) {
                    mInitialDragDirectionXYRatio = (deltaY == 0) ? Float.MAX_VALUE :
                            Math.abs(deltaX / deltaY);
                }
            }
        } else if (action == MotionEvent.ACTION_CANCEL) {
            mLastDownMillis = 0;
            mLastUpMillis = 0;
            mMultiTapStatus = MultiTapStatus.NONE;
            mMultiTapInSameArea = false;
            mMovedEnoughForDrag = false;
            mInitialDragDirectionXYRatio = 0.0f;
        }
    }

    /**
     * Returns true if the distance between the given coordinates is <= to the specified max.
     * This is useful to be able to determine e.g. when the user's touch has moved enough in
     * order to be considered a drag (no longer within touch slop).
     */
    public static boolean isDistanceWithin(float x1, float y1, float x2, float y2,
            int maxDistance) {
        float deltaX = x2 - x1;
        float deltaY = y2 - y1;
        float distanceSquared = (deltaX * deltaX) + (deltaY * deltaY);
        return distanceSquared <= maxDistance * maxDistance;
    }

    /**
     * Returns the x/y ratio corresponding to the given angle relative to vertical. Smaller angle
     * values (ie, closer to vertical) will result in a smaller x/y ratio. For example:
     * <ul>
     *     <li>if the angle is 45 deg, the ratio is 1
     *     <li>if the angle is 30 deg, the ratio is 0.58 (x delta is smaller than y delta)
     *     <li>if the angle is 60 deg, the ratio is 1.73 (x delta is bigger than y delta)
     * </ul>
     * If the passed-in value is <= 0, this function returns 0. If the passed-in value is >= 90,
     * this function returns {@link Float#MAX_VALUE}.
     *
     * @see #getInitialDragDirectionXYRatio()
     */
    public static float getXYRatio(int angleFromVerticalInDegrees) {
        if (angleFromVerticalInDegrees <= 0) {
            return 0.0f;
        }
        if (angleFromVerticalInDegrees >= 90) {
            return Float.MAX_VALUE;
        }
        return (float) Math.tan(Math.toRadians(angleFromVerticalInDegrees));
    }
}
