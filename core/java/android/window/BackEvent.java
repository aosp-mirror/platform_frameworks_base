/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.window;

import android.annotation.FloatRange;
import android.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Object used to report back gesture progress.
 * Holds information about the touch event, swipe direction and the animation progress that
 * predictive back animations should seek to.
 */
public final class BackEvent {
    /** Indicates that the edge swipe starts from the left edge of the screen */
    public static final int EDGE_LEFT = 0;
    /** Indicates that the edge swipe starts from the right edge of the screen */
    public static final int EDGE_RIGHT = 1;

    /** @hide */
    @IntDef({
            EDGE_LEFT,
            EDGE_RIGHT,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SwipeEdge{}

    private final float mTouchX;
    private final float mTouchY;
    private final float mProgress;

    @SwipeEdge
    private final int mSwipeEdge;

    /**
     * Creates a new {@link BackMotionEvent} instance.
     *
     * @param touchX Absolute X location of the touch point of this event.
     * @param touchY Absolute Y location of the touch point of this event.
     * @param progress Value between 0 and 1 on how far along the back gesture is.
     * @param swipeEdge Indicates which edge the swipe starts from.
     */
    public BackEvent(float touchX, float touchY, float progress, @SwipeEdge int swipeEdge) {
        mTouchX = touchX;
        mTouchY = touchY;
        mProgress = progress;
        mSwipeEdge = swipeEdge;
    }

    /**
     * Returns a value between 0 and 1 on how far along the back gesture is. This value is
     * driven by the horizontal location of the touch point, and should be used as the fraction to
     * seek the predictive back animation with. Specifically,
     * <ol>
     * <li>The progress is 0 when the touch is at the starting edge of the screen (left or right),
     * and animation should seek to its start state.
     * <li>The progress is approximately 1 when the touch is at the opposite side of the screen,
     * and animation should seek to its end state. Exact end value may vary depending on
     * screen size.
     * </ol>
     * <li> After the gesture finishes in cancel state, this method keeps getting invoked until the
     * progress value animates back to 0.
     * </ol>
     * In-between locations are linearly interpolated based on horizontal distance from the starting
     * edge and smooth clamped to 1 when the distance exceeds a system-wide threshold.
     */
    @FloatRange(from = 0, to = 1)
    public float getProgress() {
        return mProgress;
    }

    /**
     * Returns the absolute X location of the touch point, or NaN if the event is from
     * a button press.
     */
    public float getTouchX() {
        return mTouchX;
    }

    /**
     * Returns the absolute Y location of the touch point, or NaN if the event is from
     * a button press.
     */
    public float getTouchY() {
        return mTouchY;
    }

    /**
     * Returns the screen edge that the swipe starts from.
     */
    @SwipeEdge
    public int getSwipeEdge() {
        return mSwipeEdge;
    }

    @Override
    public String toString() {
        return "BackEvent{"
                + "mTouchX=" + mTouchX
                + ", mTouchY=" + mTouchY
                + ", mProgress=" + mProgress
                + ", mSwipeEdge" + mSwipeEdge
                + "}";
    }
}
