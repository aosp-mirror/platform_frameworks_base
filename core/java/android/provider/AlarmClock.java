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

package android.provider;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;

/**
 * The AlarmClock provider contains an Intent action and extras that can be used
 * to start an Activity to set a new alarm in an alarm clock application.
 *
 * Applications that wish to receive the ACTION_SET_ALARM Intent should create
 * an activity to handle the Intent that requires the permission
 * com.android.alarm.permission.SET_ALARM.  Applications that wish to create a
 * new alarm should use
 * {@link android.content.Context#startActivity Context.startActivity()} so that
 * the user has the option of choosing which alarm clock application to use.
 */
public final class AlarmClock {
    /**
     * Activity Action: Set an alarm.
     * <p>
     * Input: Nothing.
     * <p>
     * Output: Nothing.
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SET_ALARM = "android.intent.action.SET_ALARM";

    /**
     * Activity Extra: Provide a custom message for the alarm.
     * <p>
     * This can be passed as an extra field in the Intent created with
     * ACTION_SET_ALARM.
     */
    public static final String EXTRA_MESSAGE = "android.intent.extra.alarm.MESSAGE";

    /**
     * Activity Extra: The hour of the alarm being set.
     * <p>
     * This value can be passed as an extra field to the Intent created with
     * ACTION_SET_ALARM.  If it is not provided, the behavior is undefined and
     * is up to the application.  The value is an integer and ranges from 0 to
     * 23.
     */
    public static final String EXTRA_HOUR = "android.intent.extra.alarm.HOUR";

    /**
     * Activity Extra: The minutes of the alarm being set.
     * <p>
     * This value can be passed as an extra field to the Intent created with
     * ACTION_SET_ALARM.  If it is not provided, the behavior is undefined and
     * is up to the application.  The value is an integer and ranges from 0 to
     * 59.
     */
    public static final String EXTRA_MINUTES = "android.intent.extra.alarm.MINUTES";

    /**
     * Activity Extra: Optionally skip the application UI.
     * <p>
     * This value can be passed as an extra field to the Intent created with
     * ACTION_SET_ALARM.  If true, the application is asked to bypass any
     * intermediate UI and instead pop a toast indicating the result then
     * finish the Activity.  If false, the application may display intermediate
     * UI like a confirmation dialog or alarm settings.  The default is false.
     */
    public static final String EXTRA_SKIP_UI = "android.intent.extra.alarm.SKIP_UI";
}
