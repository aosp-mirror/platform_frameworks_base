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

package android.accessibilityservice;


import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN_AND_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN_AND_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_DOWN_AND_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT_AND_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT_AND_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_LEFT_AND_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_RIGHT_AND_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_SWIPE_UP_AND_RIGHT;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * This class describes the gesture information including gesture id and which display it happens
 * on.
 * <p>
 * <strong>Note:</strong> Accessibility services setting the
 * {@link android.accessibilityservice.AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE}
 * flag can receive gestures.
 *
 * @see AccessibilityService#onGesture(AccessibilityGestureInfo)
 */

public final class AccessibilityGestureInfo implements Parcelable {

    /** @hide */
    @IntDef(prefix = { "GESTURE_" }, value = {
            GESTURE_SWIPE_UP,
            GESTURE_SWIPE_UP_AND_LEFT,
            GESTURE_SWIPE_UP_AND_DOWN,
            GESTURE_SWIPE_UP_AND_RIGHT,
            GESTURE_SWIPE_DOWN,
            GESTURE_SWIPE_DOWN_AND_LEFT,
            GESTURE_SWIPE_DOWN_AND_UP,
            GESTURE_SWIPE_DOWN_AND_RIGHT,
            GESTURE_SWIPE_LEFT,
            GESTURE_SWIPE_LEFT_AND_UP,
            GESTURE_SWIPE_LEFT_AND_RIGHT,
            GESTURE_SWIPE_LEFT_AND_DOWN,
            GESTURE_SWIPE_RIGHT,
            GESTURE_SWIPE_RIGHT_AND_UP,
            GESTURE_SWIPE_RIGHT_AND_LEFT,
            GESTURE_SWIPE_RIGHT_AND_DOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface GestureType {}

    @GestureType
    private final int mGestureId;
    private final int mDisplayId;

    /** @hide */
    @TestApi
    public AccessibilityGestureInfo(int gestureId, int displayId) {
        mGestureId = gestureId;
        mDisplayId = displayId;
    }

    private AccessibilityGestureInfo(@NonNull Parcel parcel) {
        mGestureId = parcel.readInt();
        mDisplayId = parcel.readInt();
    }

    /**
     * Returns the display id of the received-gesture display, for use with
     * {@link android.hardware.display.DisplayManager#getDisplay(int)}.
     *
     * @return the display id.
     */
    public int getDisplayId() {
        return mDisplayId;
    }

    /**
     * Returns performed gesture id.
     *
     * @return the performed gesture id.
     *
     */
    @GestureType public int getGestureId() {
        return mGestureId;
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder("AccessibilityGestureInfo[");
        stringBuilder.append("gestureId: ").append(mGestureId);
        stringBuilder.append(", ");
        stringBuilder.append("displayId: ").append(mDisplayId);
        stringBuilder.append(']');
        return stringBuilder.toString();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel parcel, int flags) {
        parcel.writeInt(mGestureId);
        parcel.writeInt(mDisplayId);
    }

    /**
     * @see Parcelable.Creator
     */
    public static final @NonNull Parcelable.Creator<AccessibilityGestureInfo> CREATOR =
            new Parcelable.Creator<AccessibilityGestureInfo>() {
        public AccessibilityGestureInfo createFromParcel(Parcel parcel) {
            return new AccessibilityGestureInfo(parcel);
        }

        public AccessibilityGestureInfo[] newArray(int size) {
            return new AccessibilityGestureInfo[size];
        }
    };

}
