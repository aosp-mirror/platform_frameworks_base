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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.RemoteAnimationTarget;

/**
 * Object used to report back gesture progress. Holds information about a {@link BackEvent} plus
 * any {@link RemoteAnimationTarget} the gesture manipulates.
 *
 * @see BackEvent
 * @hide
 */
public final class BackMotionEvent implements Parcelable {
    private final float mTouchX;
    private final float mTouchY;
    private final float mProgress;

    @BackEvent.SwipeEdge
    private final int mSwipeEdge;
    @Nullable
    private final RemoteAnimationTarget mDepartingAnimationTarget;

    /**
     * Creates a new {@link BackMotionEvent} instance.
     *
     * @param touchX Absolute X location of the touch point of this event.
     * @param touchY Absolute Y location of the touch point of this event.
     * @param progress Value between 0 and 1 on how far along the back gesture is.
     * @param swipeEdge Indicates which edge the swipe starts from.
     * @param departingAnimationTarget The remote animation target of the departing
     *                                 application window.
     */
    public BackMotionEvent(float touchX, float touchY, float progress,
            @BackEvent.SwipeEdge int swipeEdge,
            @Nullable RemoteAnimationTarget departingAnimationTarget) {
        mTouchX = touchX;
        mTouchY = touchY;
        mProgress = progress;
        mSwipeEdge = swipeEdge;
        mDepartingAnimationTarget = departingAnimationTarget;
    }

    private BackMotionEvent(@NonNull Parcel in) {
        mTouchX = in.readFloat();
        mTouchY = in.readFloat();
        mProgress = in.readFloat();
        mSwipeEdge = in.readInt();
        mDepartingAnimationTarget = in.readTypedObject(RemoteAnimationTarget.CREATOR);
    }

    @NonNull
    public static final Creator<BackMotionEvent> CREATOR = new Creator<BackMotionEvent>() {
        @Override
        public BackMotionEvent createFromParcel(Parcel in) {
            return new BackMotionEvent(in);
        }

        @Override
        public BackMotionEvent[] newArray(int size) {
            return new BackMotionEvent[size];
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
        dest.writeTypedObject(mDepartingAnimationTarget, flags);
    }

    /**
     * Returns the progress of a {@link BackEvent}.
     *
     * @see BackEvent#getProgress()
     */
    @FloatRange(from = 0, to = 1)
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
    @BackEvent.SwipeEdge
    public int getSwipeEdge() {
        return mSwipeEdge;
    }

    /**
     * Returns the {@link RemoteAnimationTarget} of the top departing application window,
     * or {@code null} if the top window should not be moved for the current type of back
     * destination.
     */
    @Nullable
    public RemoteAnimationTarget getDepartingAnimationTarget() {
        return mDepartingAnimationTarget;
    }

    @Override
    public String toString() {
        return "BackMotionEvent{"
                + "mTouchX=" + mTouchX
                + ", mTouchY=" + mTouchY
                + ", mProgress=" + mProgress
                + ", mSwipeEdge" + mSwipeEdge
                + ", mDepartingAnimationTarget" + mDepartingAnimationTarget
                + "}";
    }
}
