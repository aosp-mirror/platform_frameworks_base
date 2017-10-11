/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.plugins.statusbar;

import com.android.systemui.plugins.annotations.DependsOn;
import com.android.systemui.plugins.annotations.ProvidesInterface;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper.SnoozeOption;

import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.View;

@ProvidesInterface(version = NotificationSwipeActionHelper.VERSION)
@DependsOn(target = SnoozeOption.class)
public interface NotificationSwipeActionHelper {
    public static final String ACTION = "com.android.systemui.action.PLUGIN_NOTIFICATION_SWIPE_ACTION";

    public static final int VERSION = 1;

    /**
     * Call this to dismiss a notification.
     */
    public void dismiss(View animView, float velocity);

    /**
     * Call this to snap a notification to provided {@code targetLeft}.
     */
    public void snap(View animView, float velocity, float targetLeft);

    /**
     * Call this to snooze a notification based on the provided {@link SnoozeOption}.
     */
    public void snooze(StatusBarNotification sbn, SnoozeOption snoozeOption);

    public float getMinDismissVelocity();

    public boolean isDismissGesture(MotionEvent ev);

    public boolean isFalseGesture(MotionEvent ev);

    public boolean swipedFarEnough(float translation, float viewSize);

    public boolean swipedFastEnough(float translation, float velocity);

    @ProvidesInterface(version = SnoozeOption.VERSION)
    public static class SnoozeOption {
        public static final int VERSION = 1;
        public int snoozeForMinutes;
        public SnoozeCriterion criterion;
        public CharSequence description;
        public CharSequence confirmation;

        public SnoozeOption(SnoozeCriterion crit, int minsToSnoozeFor, CharSequence desc,
                CharSequence confirm) {
            criterion = crit;
            snoozeForMinutes = minsToSnoozeFor;
            description = desc;
            confirmation = confirm;
        }
    }
}
