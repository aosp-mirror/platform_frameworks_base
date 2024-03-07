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


import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_DOUBLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SINGLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_TRIPLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_DOUBLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SINGLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_TRIPLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_3_FINGER_TRIPLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_DOUBLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SINGLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SWIPE_DOWN;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SWIPE_LEFT;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SWIPE_RIGHT;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_SWIPE_UP;
import static android.accessibilityservice.AccessibilityService.GESTURE_4_FINGER_TRIPLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_DOUBLE_TAP;
import static android.accessibilityservice.AccessibilityService.GESTURE_DOUBLE_TAP_AND_HOLD;
import static android.accessibilityservice.AccessibilityService.GESTURE_PASSTHROUGH;
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
import static android.accessibilityservice.AccessibilityService.GESTURE_TOUCH_EXPLORATION;
import static android.accessibilityservice.AccessibilityService.GESTURE_UNKNOWN;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.TestApi;
import android.content.pm.ParceledListSlice;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.MotionEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;

/**
 * This class describes the gesture event including gesture id and which display it happens
 * on.
 * <p>
 * <strong>Note:</strong> Accessibility services setting the
 * {@link android.accessibilityservice.AccessibilityServiceInfo#FLAG_REQUEST_TOUCH_EXPLORATION_MODE}
 * flag can receive gestures.
 *
 * @see AccessibilityService#onGesture(AccessibilityGestureEvent)
 */

public final class AccessibilityGestureEvent implements Parcelable {

    /** @hide */
    @IntDef(prefix = { "GESTURE_" }, value = {
          GESTURE_UNKNOWN,
          GESTURE_TOUCH_EXPLORATION,
            GESTURE_2_FINGER_SINGLE_TAP,
            GESTURE_2_FINGER_DOUBLE_TAP,
            GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD,
            GESTURE_2_FINGER_TRIPLE_TAP,
            GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD,
            GESTURE_3_FINGER_SINGLE_TAP,
            GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD,
            GESTURE_3_FINGER_DOUBLE_TAP,
            GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD,
            GESTURE_3_FINGER_TRIPLE_TAP,
            GESTURE_3_FINGER_TRIPLE_TAP_AND_HOLD,
            GESTURE_DOUBLE_TAP,
            GESTURE_DOUBLE_TAP_AND_HOLD,
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
            GESTURE_SWIPE_RIGHT_AND_DOWN,
            GESTURE_2_FINGER_SWIPE_DOWN,
            GESTURE_2_FINGER_SWIPE_LEFT,
            GESTURE_2_FINGER_SWIPE_RIGHT,
            GESTURE_2_FINGER_SWIPE_UP,
            GESTURE_3_FINGER_SWIPE_DOWN,
            GESTURE_3_FINGER_SWIPE_LEFT,
            GESTURE_3_FINGER_SWIPE_RIGHT,
            GESTURE_3_FINGER_SWIPE_UP,
            GESTURE_4_FINGER_DOUBLE_TAP,
            GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD,
            GESTURE_4_FINGER_SINGLE_TAP,
            GESTURE_4_FINGER_SWIPE_DOWN,
            GESTURE_4_FINGER_SWIPE_LEFT,
            GESTURE_4_FINGER_SWIPE_RIGHT,
            GESTURE_4_FINGER_SWIPE_UP,
            GESTURE_4_FINGER_TRIPLE_TAP
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface GestureId {}

    @GestureId
    private final int mGestureId;
    private final int mDisplayId;
    private List<MotionEvent> mMotionEvents = new ArrayList<>();

    /**
     * Constructs an AccessibilityGestureEvent to be dispatched to an accessibility service.
     *
     * @param gestureId    the id number of the gesture.
     * @param displayId    the display on which this gesture was performed.
     * @param motionEvents the motion events that lead to this gesture.
     */
    public AccessibilityGestureEvent(
            int gestureId, int displayId, @NonNull List<MotionEvent> motionEvents) {
        mGestureId = gestureId;
        mDisplayId = displayId;
        mMotionEvents.addAll(motionEvents);
    }

    /** @hide */
    @TestApi
    public AccessibilityGestureEvent(int gestureId, int displayId) {
        this(gestureId, displayId, new ArrayList<MotionEvent>());
    }

    private AccessibilityGestureEvent(@NonNull Parcel parcel) {
        mGestureId = parcel.readInt();
        mDisplayId = parcel.readInt();
        ParceledListSlice<MotionEvent> slice = parcel.readParcelable(getClass().getClassLoader(), android.content.pm.ParceledListSlice.class);
        mMotionEvents = slice.getList();
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
    @GestureId public int getGestureId() {
        return mGestureId;
    }

    /**
     * Returns the motion events that lead to this gesture.
     *
     */
    @NonNull
    public List<MotionEvent> getMotionEvents() {
        return mMotionEvents;
    }

    /**
     * When we asynchronously use {@link AccessibilityGestureEvent}, we should make a copy,
     * because motionEvent may be recycled before we use async.
     *
     * @hide
     */
    @NonNull
    public AccessibilityGestureEvent copyForAsync() {
        return new AccessibilityGestureEvent(mGestureId, mDisplayId,
                mMotionEvents.stream().map(MotionEvent::copy).toList());
    }

    /**
     * After we use {@link AccessibilityGestureEvent} asynchronously, we should recycle the
     * MotionEvent, avoid memory leaks.
     *
     * @hide
     */
    public void recycle() {
        mMotionEvents.forEach(MotionEvent::recycle);
        mMotionEvents.clear();
    }

    @NonNull
    @Override
    public String toString() {
        StringBuilder stringBuilder = new StringBuilder("AccessibilityGestureEvent[");
        stringBuilder.append("gestureId: ").append(gestureIdToString(mGestureId));
        stringBuilder.append(", ");
        stringBuilder.append("displayId: ").append(mDisplayId);
        stringBuilder.append(", ");
        stringBuilder.append("Motion Events: [");
        for (int i = 0; i < mMotionEvents.size(); ++i) {
            String action = MotionEvent.actionToString(mMotionEvents.get(i).getActionMasked());
            stringBuilder.append(action);
            if (i < (mMotionEvents.size() - 1)) {
                stringBuilder.append(", ");
            } else {
                stringBuilder.append("]");
            }
        }
        stringBuilder.append(']');
        return stringBuilder.toString();
    }

    /**
     * Returns a string representation of the specified gesture id.
     */
    @NonNull
    public static String gestureIdToString(int id) {
        switch (id) {
            case GESTURE_UNKNOWN: return "GESTURE_UNKNOWN";
            case GESTURE_PASSTHROUGH: return "GESTURE_PASSTHROUGH";
            case GESTURE_TOUCH_EXPLORATION: return "GESTURE_TOUCH_EXPLORATION";
            case GESTURE_2_FINGER_SINGLE_TAP: return "GESTURE_2_FINGER_SINGLE_TAP";
            case GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD:
                return "GESTURE_2_FINGER_TRIPLE_TAP_AND_HOLD";
            case GESTURE_2_FINGER_DOUBLE_TAP: return "GESTURE_2_FINGER_DOUBLE_TAP";
            case GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD:
                return "GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD";
            case GESTURE_2_FINGER_TRIPLE_TAP: return "GESTURE_2_FINGER_TRIPLE_TAP";
            case GESTURE_3_FINGER_SINGLE_TAP: return "GESTURE_3_FINGER_SINGLE_TAP";
            case GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD:
                return "GESTURE_3_FINGER_SINGLE_TAP_AND_HOLD";
            case GESTURE_3_FINGER_DOUBLE_TAP: return "GESTURE_3_FINGER_DOUBLE_TAP";
            case GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD:
                return "GESTURE_3_FINGER_DOUBLE_TAP_AND_HOLD";
            case GESTURE_3_FINGER_TRIPLE_TAP: return "GESTURE_3_FINGER_TRIPLE_TAP";
            case GESTURE_3_FINGER_TRIPLE_TAP_AND_HOLD:
                return "GESTURE_3_FINGER_TRIPLE_TAP_AND_HOLD";
            case GESTURE_4_FINGER_SINGLE_TAP: return "GESTURE_4_FINGER_SINGLE_TAP";
            case GESTURE_4_FINGER_DOUBLE_TAP: return "GESTURE_4_FINGER_DOUBLE_TAP";
            case GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD:
                return "GESTURE_4_FINGER_DOUBLE_TAP_AND_HOLD";
            case GESTURE_4_FINGER_TRIPLE_TAP: return "GESTURE_4_FINGER_TRIPLE_TAP";
            case GESTURE_DOUBLE_TAP: return "GESTURE_DOUBLE_TAP";
            case GESTURE_DOUBLE_TAP_AND_HOLD: return "GESTURE_DOUBLE_TAP_AND_HOLD";
            case GESTURE_SWIPE_DOWN: return "GESTURE_SWIPE_DOWN";
            case GESTURE_SWIPE_DOWN_AND_LEFT: return "GESTURE_SWIPE_DOWN_AND_LEFT";
            case GESTURE_SWIPE_DOWN_AND_UP: return "GESTURE_SWIPE_DOWN_AND_UP";
            case GESTURE_SWIPE_DOWN_AND_RIGHT: return "GESTURE_SWIPE_DOWN_AND_RIGHT";
            case GESTURE_SWIPE_LEFT: return "GESTURE_SWIPE_LEFT";
            case GESTURE_SWIPE_LEFT_AND_UP: return "GESTURE_SWIPE_LEFT_AND_UP";
            case GESTURE_SWIPE_LEFT_AND_RIGHT: return "GESTURE_SWIPE_LEFT_AND_RIGHT";
            case GESTURE_SWIPE_LEFT_AND_DOWN: return "GESTURE_SWIPE_LEFT_AND_DOWN";
            case GESTURE_SWIPE_RIGHT: return "GESTURE_SWIPE_RIGHT";
            case GESTURE_SWIPE_RIGHT_AND_UP: return "GESTURE_SWIPE_RIGHT_AND_UP";
            case GESTURE_SWIPE_RIGHT_AND_LEFT: return "GESTURE_SWIPE_RIGHT_AND_LEFT";
            case GESTURE_SWIPE_RIGHT_AND_DOWN: return "GESTURE_SWIPE_RIGHT_AND_DOWN";
            case GESTURE_SWIPE_UP: return "GESTURE_SWIPE_UP";
            case GESTURE_SWIPE_UP_AND_LEFT: return "GESTURE_SWIPE_UP_AND_LEFT";
            case GESTURE_SWIPE_UP_AND_DOWN: return "GESTURE_SWIPE_UP_AND_DOWN";
            case GESTURE_SWIPE_UP_AND_RIGHT: return "GESTURE_SWIPE_UP_AND_RIGHT";
            case GESTURE_2_FINGER_SWIPE_DOWN: return "GESTURE_2_FINGER_SWIPE_DOWN";
            case GESTURE_2_FINGER_SWIPE_LEFT: return "GESTURE_2_FINGER_SWIPE_LEFT";
            case GESTURE_2_FINGER_SWIPE_RIGHT: return "GESTURE_2_FINGER_SWIPE_RIGHT";
            case GESTURE_2_FINGER_SWIPE_UP: return "GESTURE_2_FINGER_SWIPE_UP";
            case GESTURE_3_FINGER_SWIPE_DOWN: return "GESTURE_3_FINGER_SWIPE_DOWN";
            case GESTURE_3_FINGER_SWIPE_LEFT: return "GESTURE_3_FINGER_SWIPE_LEFT";
            case GESTURE_3_FINGER_SWIPE_RIGHT: return "GESTURE_3_FINGER_SWIPE_RIGHT";
            case GESTURE_3_FINGER_SWIPE_UP: return "GESTURE_3_FINGER_SWIPE_UP";
            case GESTURE_4_FINGER_SWIPE_DOWN: return "GESTURE_4_FINGER_SWIPE_DOWN";
            case GESTURE_4_FINGER_SWIPE_LEFT: return "GESTURE_4_FINGER_SWIPE_LEFT";
            case GESTURE_4_FINGER_SWIPE_RIGHT: return "GESTURE_4_FINGER_SWIPE_RIGHT";
            case GESTURE_4_FINGER_SWIPE_UP: return "GESTURE_4_FINGER_SWIPE_UP";
            default: return Integer.toHexString(id);
        }
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
        parcel.writeParcelable(new ParceledListSlice<MotionEvent>(mMotionEvents), 0);
    }

    /**
     * @see Parcelable.Creator
     */
    public static final @NonNull Parcelable.Creator<AccessibilityGestureEvent> CREATOR =
            new Parcelable.Creator<AccessibilityGestureEvent>() {
        public AccessibilityGestureEvent createFromParcel(Parcel parcel) {
            return new AccessibilityGestureEvent(parcel);
        }

        public AccessibilityGestureEvent[] newArray(int size) {
            return new AccessibilityGestureEvent[size];
        }
    };

}
