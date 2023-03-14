/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.classifier;

import android.annotation.IntDef;
import android.hardware.SensorEvent;
import android.view.MotionEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * An abstract class for classifiers for touch and sensor events.
 */
public abstract class Classifier {
    public static final int QUICK_SETTINGS = 0;
    public static final int NOTIFICATION_DISMISS = 1;
    public static final int NOTIFICATION_DRAG_DOWN = 2;
    public static final int NOTIFICATION_DOUBLE_TAP = 3;
    public static final int UNLOCK = 4;
    public static final int LEFT_AFFORDANCE = 5;
    public static final int RIGHT_AFFORDANCE = 6;
    public static final int GENERIC = 7;
    public static final int BOUNCER_UNLOCK = 8;
    public static final int PULSE_EXPAND = 9;
    public static final int BRIGHTNESS_SLIDER = 10;
    public static final int SHADE_DRAG = 11;
    public static final int QS_COLLAPSE = 12;
    public static final int UDFPS_AUTHENTICATION = 13;
    public static final int LOCK_ICON = 14;
    public static final int QS_SWIPE_SIDE = 15;
    public static final int BACK_GESTURE = 16;
    public static final int QS_SWIPE_NESTED = 17;
    public static final int MEDIA_SEEKBAR = 18;

    @IntDef({
            QUICK_SETTINGS,
            NOTIFICATION_DISMISS,
            NOTIFICATION_DRAG_DOWN,
            NOTIFICATION_DOUBLE_TAP,
            UNLOCK,
            LEFT_AFFORDANCE,
            RIGHT_AFFORDANCE,
            GENERIC,
            BOUNCER_UNLOCK,
            PULSE_EXPAND,
            BRIGHTNESS_SLIDER,
            SHADE_DRAG,
            QS_COLLAPSE,
            BRIGHTNESS_SLIDER,
            UDFPS_AUTHENTICATION,
            LOCK_ICON,
            QS_SWIPE_SIDE,
            QS_SWIPE_NESTED,
            BACK_GESTURE,
            MEDIA_SEEKBAR,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface InteractionType {}

    /**
     * Informs the classifier that a new touch event has occurred
     */
    public void onTouchEvent(MotionEvent event) {
    }

    /**
     * Informs the classifier that a sensor change occurred
     */
    public void onSensorChanged(SensorEvent event) {
    }

    public abstract String getTag();
}
