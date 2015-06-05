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
 * to start an Activity to set a new alarm or timer in an alarm clock application.
 *
 * Applications that wish to receive the ACTION_SET_ALARM  and ACTION_SET_TIMER Intents
 * should create an activity to handle the Intent that requires the permission
 * com.android.alarm.permission.SET_ALARM.  Applications that wish to create a
 * new alarm or timer should use
 * {@link android.content.Context#startActivity Context.startActivity()} so that
 * the user has the option of choosing which alarm clock application to use.
 */
public final class AlarmClock {
    /**
     * Activity Action: Set an alarm.
     * <p>
     * Activates an existing alarm or creates a new one.
     * </p><p>
     * This action requests an alarm to be set for a given time of day. If no time of day is
     * specified, an implementation should start an activity that is capable of setting an alarm
     * ({@link #EXTRA_SKIP_UI} is ignored in this case). If a time of day is specified, and
     * {@link #EXTRA_SKIP_UI} is {@code true}, and the alarm is not repeating, the implementation
     * should remove this alarm after it has been dismissed. If an identical alarm exists matching
     * all parameters, the implementation may re-use it instead of creating a new one (in this case,
     * the alarm should not be removed after dismissal).
     * </p><p>
     * This action always enables the alarm.
     * </p><p>
     * This activity could also be started in Voice Interaction mode. The activity should check
     * {@link android.app.Activity#isVoiceInteraction}, and if true, the implementation should
     * report a deeplink of the created/enabled alarm using
     * {@link android.app.VoiceInteractor.CompleteVoiceRequest}. This allows follow-on voice actions
     * such as {@link #ACTION_DISMISS_ALARM} to dismiss the alarm that was just enabled.
     * </p>
     * <h3>Request parameters</h3>
     * <ul>
     * <li>{@link #EXTRA_HOUR} <em>(optional)</em>: The hour of the alarm being set.
     * <li>{@link #EXTRA_MINUTES} <em>(optional)</em>: The minutes of the alarm being set.
     * <li>{@link #EXTRA_DAYS} <em>(optional)</em>: Weekdays for repeating alarm.
     * <li>{@link #EXTRA_MESSAGE} <em>(optional)</em>: A custom message for the alarm.
     * <li>{@link #EXTRA_RINGTONE} <em>(optional)</em>: A ringtone to play with this alarm.
     * <li>{@link #EXTRA_VIBRATE} <em>(optional)</em>: Whether or not to activate the device
     * vibrator for this alarm.
     * <li>{@link #EXTRA_SKIP_UI} <em>(optional)</em>: Whether or not to display an activity for
     * setting this alarm.
     * </ul>
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SET_ALARM = "android.intent.action.SET_ALARM";

    /**
     * Activity Action: Dismiss an alarm.
     * <p>
     * The alarm to dismiss can be specified or searched for in one of the following ways:
     * <ol>
     * <li>The Intent's data URI, which represents a deeplink to the alarm.
     * <li>The extra {@link #EXTRA_ALARM_SEARCH_MODE} to determine how to search for the alarm.
     * </ol>
     * </p><p>
     * If neither of the above are given then:
     * <ul>
     * <li>If exactly one active alarm exists, it is dismissed.
     * <li>If more than one active alarm exists, the user is prompted to choose the alarm to dismiss.
     * </ul>
     * </p><p>
     * If the extra {@link #EXTRA_ALARM_SEARCH_MODE} is used, and the search results contain two or
     * more matching alarms, then the implementation should show an UI with the results and allow
     * the user to select the alarm to dismiss. If the implementation supports
     * {@link android.content.Intent#CATEGORY_VOICE} and the activity is started in Voice
     * Interaction mode (i.e. check {@link android.app.Activity#isVoiceInteraction}), then the
     * implementation should additionally use {@link android.app.VoiceInteractor.PickOptionRequest}
     * to start a voice interaction follow-on flow to help the user disambiguate the alarm by voice.
     * </p><p>
     * If the specified alarm is a single occurrence alarm, then dismissing it effectively disables
     * the alarm; it will never ring again unless explicitly re-enabled.
     * </p><p>
     * If the specified alarm is a repeating alarm, then dismissing it only prevents the upcoming
     * instance from ringing. The alarm remains enabled so that it will still ring on the date and
     * time of the next instance (i.e. the instance after the upcoming one).
     * </p>
     *
     * @see #EXTRA_ALARM_SEARCH_MODE
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_DISMISS_ALARM =
            "android.intent.action.DISMISS_ALARM";

    /**
     * Activity Action: Snooze a currently ringing alarm.
     * <p>
     * Snoozes the currently ringing alarm. The extra {@link #EXTRA_ALARM_SNOOZE_DURATION} can be
     * optionally set to specify the snooze duration; if unset, the implementation should use a
     * reasonable default, for example 10 minutes. The alarm should ring again after the snooze
     * duration.
     * </p><p>
     * Note: setting the extra {@link #EXTRA_ALARM_SNOOZE_DURATION} does not change the default
     * snooze duration; it's only applied to the currently ringing alarm.
     * </p><p>
     * If there is no currently ringing alarm, then this is a no-op.
     * </p>
     *
     * @see #EXTRA_ALARM_SNOOZE_DURATION
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SNOOZE_ALARM =
            "android.intent.action.SNOOZE_ALARM";

    /**
     * Activity Action: Set a timer.
     * <p>
     * Activates an existing timer or creates a new one.
     * </p><p>
     * This action requests a timer to be started for a specific {@link #EXTRA_LENGTH length} of
     * time. If no {@link #EXTRA_LENGTH length} is specified, the implementation should start an
     * activity that is capable of setting a timer ({@link #EXTRA_SKIP_UI} is ignored in this case).
     * If a {@link #EXTRA_LENGTH length} is specified, and {@link #EXTRA_SKIP_UI} is {@code true},
     * the implementation should remove this timer after it has been dismissed. If an identical,
     * unused timer exists matching both parameters, an implementation may re-use it instead of
     * creating a new one (in this case, the timer should not be removed after dismissal).
     *
     * This action always starts the timer.
     * </p>
     *
     * <h3>Request parameters</h3>
     * <ul>
     * <li>{@link #EXTRA_LENGTH} <em>(optional)</em>: The length of the timer being set.
     * <li>{@link #EXTRA_MESSAGE} <em>(optional)</em>: A custom message for the timer.
     * <li>{@link #EXTRA_SKIP_UI} <em>(optional)</em>: Whether or not to display an activity for
     * setting this timer.
     * </ul>
     */
    @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
    public static final String ACTION_SET_TIMER = "android.intent.action.SET_TIMER";

    /**
     * Activity Action: Show the alarms.
     * <p>
     * This action opens the alarms page.
     * </p>
     */
     @SdkConstant(SdkConstantType.ACTIVITY_INTENT_ACTION)
     public static final String ACTION_SHOW_ALARMS = "android.intent.action.SHOW_ALARMS";

    /**
     * Bundle extra: Specify the type of search mode to look up an alarm.
     * <p>
     * For example, used by {@link #ACTION_DISMISS_ALARM} to identify the alarm to dismiss.
     * </p><p>
     * This extra is only used when the alarm is not already identified by a deeplink as
     * specified in the Intent's data URI.
     * </p><p>
     * The value of this extra is a {@link String}, restricted to the following set of supported
     * search modes:
     * <ul>
     * <li><i>Time</i> - {@link #ALARM_SEARCH_MODE_TIME}: Selects the alarm that is most
     * closely matched by the search parameters {@link #EXTRA_HOUR}, {@link #EXTRA_MINUTES},
     * {@link #EXTRA_IS_PM}.
     * <li><i>Next alarm</i> - {@link #ALARM_SEARCH_MODE_NEXT}: Selects the alarm that will
     * ring next, or the alarm that is currently ringing, if any.
     * <li><i>All alarms</i> - {@link #ALARM_SEARCH_MODE_ALL}: Selects all alarms.
     * <li><i>Label</i> - {@link #ALARM_SEARCH_MODE_LABEL}: Search by alarm label. Should return
     * alarms that contain the word or phrase in given label.
     * </ul>
     * </p>
     *
     * @see #ALARM_SEARCH_MODE_TIME
     * @see #ALARM_SEARCH_MODE_NEXT
     * @see #ALARM_SEARCH_MODE_ALL
     * @see #ALARM_SEARCH_MODE_LABEL
     * @see #ACTION_DISMISS_ALARM
     */
    public static final String EXTRA_ALARM_SEARCH_MODE =
        "android.intent.extra.alarm.SEARCH_MODE";

    /**
     * Search for the alarm that is most closely matched by the search parameters
     * {@link #EXTRA_HOUR}, {@link #EXTRA_MINUTES}, {@link #EXTRA_IS_PM}.
     * In this search mode, at least one of these additional extras are required.
     * <ul>
     * <li>{@link #EXTRA_HOUR} - The hour to search for the alarm.
     * <li>{@link #EXTRA_MINUTES} - The minute to search for the alarm.
     * <li>{@link #EXTRA_IS_PM} - Whether the hour is AM or PM.
     * </ul>
     *
     * @see #EXTRA_ALARM_SEARCH_MODE
     */
    public static final String ALARM_SEARCH_MODE_TIME = "android.time";

    /**
     * Selects the alarm that will ring next, or the alarm that is currently ringing, if any.
     *
     * @see #EXTRA_ALARM_SEARCH_MODE
     */
    public static final String ALARM_SEARCH_MODE_NEXT = "android.next";

    /**
     * Selects all alarms.
     *
     * @see #EXTRA_ALARM_SEARCH_MODE
     */
    public static final String ALARM_SEARCH_MODE_ALL = "android.all";

    /**
     * Search by alarm label. Should return alarms that contain the word or phrase in given label.
     *
     * @see #EXTRA_ALARM_SEARCH_MODE
     */
    public static final String ALARM_SEARCH_MODE_LABEL = "android.label";

    /**
     * Bundle extra: The AM/PM of the alarm.
     * <p>
     * Used by {@link #ACTION_DISMISS_ALARM}.
     * </p><p>
     * This extra is optional and only used when {@link #EXTRA_ALARM_SEARCH_MODE} is set to
     * {@link #ALARM_SEARCH_MODE_TIME}. In this search mode, the {@link #EXTRA_IS_PM} is
     * used together with {@link #EXTRA_HOUR} and {@link #EXTRA_MINUTES}. The implementation should
     * look up the alarm that is most closely matched by these search parameters.
     * If {@link #EXTRA_IS_PM} is missing, then the AM/PM of the specified {@link #EXTRA_HOUR} is
     * ambiguous and the implementation should ask for clarification from the user.
     * </p><p>
     * The value is a {@link Boolean}, where false=AM and true=PM.
     * </p>
     *
     * @see #ACTION_DISMISS_ALARM
     * @see #EXTRA_HOUR
     * @see #EXTRA_MINUTES
     */
    public static final String EXTRA_IS_PM = "android.intent.extra.alarm.IS_PM";


    /**
     * Bundle extra: The snooze duration of the alarm in minutes.
     * <p>
     * Used by {@link #ACTION_SNOOZE_ALARM}. This extra is optional and the value is an
     * {@link Integer} that specifies the duration in minutes for which to snooze the alarm.
     * </p>
     *
     * @see #ACTION_SNOOZE_ALARM
     */
    public static final String EXTRA_ALARM_SNOOZE_DURATION =
        "android.intent.extra.alarm.SNOOZE_DURATION";

    /**
     * Bundle extra: Weekdays for repeating alarm.
     * <p>
     * Used by {@link #ACTION_SET_ALARM}.
     * </p><p>
     * The value is an {@code ArrayList<Integer>}. Each item can be:
     * </p>
     * <ul>
     * <li> {@link java.util.Calendar#SUNDAY},
     * <li> {@link java.util.Calendar#MONDAY},
     * <li> {@link java.util.Calendar#TUESDAY},
     * <li> {@link java.util.Calendar#WEDNESDAY},
     * <li> {@link java.util.Calendar#THURSDAY},
     * <li> {@link java.util.Calendar#FRIDAY},
     * <li> {@link java.util.Calendar#SATURDAY}
     * </ul>
     */
    public static final String EXTRA_DAYS = "android.intent.extra.alarm.DAYS";

    /**
     * Bundle extra: The hour of the alarm.
     * <p>
     * Used by {@link #ACTION_SET_ALARM}.
     * </p><p>
     * This extra is optional. If not provided, an implementation should open an activity
     * that allows a user to set an alarm with user provided time.
     * </p><p>
     * The value is an {@link Integer} and ranges from 0 to 23.
     * </p>
     *
     * @see #ACTION_SET_ALARM
     * @see #EXTRA_MINUTES
     * @see #EXTRA_DAYS
     */
    public static final String EXTRA_HOUR = "android.intent.extra.alarm.HOUR";

    /**
     * Bundle extra: The length of the timer in seconds.
     * <p>
     * Used by {@link #ACTION_SET_TIMER}.
     * </p><p>
     * This extra is optional. If not provided, an implementation should open an activity
     * that allows a user to set a timer with user provided length.
     * </p><p>
     * The value is an {@link Integer} and ranges from 1 to 86400 (24 hours).
     * </p>
     *
     * @see #ACTION_SET_TIMER
     */
    public static final String EXTRA_LENGTH = "android.intent.extra.alarm.LENGTH";

    /**
     * Bundle extra: A custom message for the alarm or timer.
     * <p>
     * Used by {@link #ACTION_SET_ALARM} and {@link #ACTION_SET_TIMER}.
     * </p><p>
     * The value is a {@link String}.
     * </p>
     *
     * @see #ACTION_SET_ALARM
     * @see #ACTION_SET_TIMER
     */
    public static final String EXTRA_MESSAGE = "android.intent.extra.alarm.MESSAGE";

    /**
     * Bundle extra: The minutes of the alarm.
     * <p>
     * Used by {@link #ACTION_SET_ALARM}.
     * </p><p>
     * The value is an {@link Integer} and ranges from 0 to 59. If not provided, it defaults to 0.
     * </p>
     *
     * @see #ACTION_SET_ALARM
     * @see #EXTRA_HOUR
     * @see #EXTRA_DAYS
     */
    public static final String EXTRA_MINUTES = "android.intent.extra.alarm.MINUTES";

    /**
     * Bundle extra: A ringtone to be played with this alarm.
     * <p>
     * Used by {@link #ACTION_SET_ALARM}.
     * </p><p>
     * This value is a {@link String} and can either be set to {@link #VALUE_RINGTONE_SILENT} or
     * to a content URI of the media to be played. If not specified or the URI doesn't exist,
     * {@code "content://settings/system/alarm_alert} will be used.
     * </p>
     *
     * @see #ACTION_SET_ALARM
     * @see #VALUE_RINGTONE_SILENT
     * @see #EXTRA_VIBRATE
     */
    public static final String EXTRA_RINGTONE = "android.intent.extra.alarm.RINGTONE";

    /**
     * Bundle extra: Whether or not to display an activity after performing the action.
     * <p>
     * Used by {@link #ACTION_SET_ALARM} and {@link #ACTION_SET_TIMER}.
     * </p><p>
     * If true, the application is asked to bypass any intermediate UI. If false, the application
     * may display intermediate UI like a confirmation dialog or settings.
     * </p><p>
     * The value is a {@link Boolean}. The default is {@code false}.
     * </p>
     *
     * @see #ACTION_SET_ALARM
     * @see #ACTION_SET_TIMER
     */
    public static final String EXTRA_SKIP_UI = "android.intent.extra.alarm.SKIP_UI";

    /**
     * Bundle extra: Whether or not to activate the device vibrator.
     * <p>
     * Used by {@link #ACTION_SET_ALARM}.
     * </p><p>
     * The value is a {@link Boolean}. The default is {@code true}.
     * </p>
     *
     * @see #ACTION_SET_ALARM
     * @see #EXTRA_RINGTONE
     * @see #VALUE_RINGTONE_SILENT
     */
    public static final String EXTRA_VIBRATE = "android.intent.extra.alarm.VIBRATE";

    /**
     * Bundle extra value: Indicates no ringtone should be played.
     * <p>
     * Used by {@link #ACTION_SET_ALARM}, passed in through {@link #EXTRA_RINGTONE}.
     * </p>
     *
     * @see #ACTION_SET_ALARM
     * @see #EXTRA_RINGTONE
     * @see #EXTRA_VIBRATE
     */
    public static final String VALUE_RINGTONE_SILENT = "silent";
}
