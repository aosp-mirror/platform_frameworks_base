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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Represents an event that is sent out by the system during back navigation gesture.
 * Holds information about the touch event, swipe direction and overall progress of the gesture
 * interaction.
 *
 * @hide
 */
public class BackEvent implements Parcelable {
    /** Indicates that the edge swipe starts from the left edge of the screen */
    public static final int EDGE_LEFT = 0;
    /** Indicates that the edge swipe starts from the right edge of the screen */
    public static final int EDGE_RIGHT = 1;

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
     * Creates a new {@link BackEvent} instance.
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

    private BackEvent(@NonNull Parcel in) {
        mTouchX = in.readFloat();
        mTouchY = in.readFloat();
        mProgress = in.readFloat();
        mSwipeEdge = in.readInt();
    }

    public static final Creator<BackEvent> CREATOR = new Creator<BackEvent>() {
        @Override
        public BackEvent createFromParcel(Parcel in) {
            return new BackEvent(in);
        }

        @Override
        public BackEvent[] newArray(int size) {
            return new BackEvent[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeFloat(mTouchX);
        dest.writeFloat(mTouchY);
        dest.writeFloat(mProgress);
        dest.writeInt(mSwipeEdge);
    }

    /**
     * Returns a value between 0 and 1 on how far along the back gesture is.
     */
    public float getProgress() {
        return mProgress;
    }

    /**
     * Returns the absolute X location of the touch point.
     */
    public float getTouchX() {
        return mTouchX;
    }

    /**
     * Returns the absolute Y location of the touch point.
     */
    public float getTouchY() {
        return mTouchY;
    }

    /**
     * Returns the screen edge that the swipe starts from.
     */
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
