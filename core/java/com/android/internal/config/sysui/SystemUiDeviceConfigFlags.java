/**
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

package com.android.internal.config.sysui;

/**
 * Keeps the flags related to the SystemUI namespace in {@link DeviceConfig}.
 *
 * @hide
 */
public final class SystemUiDeviceConfigFlags {

    // Flags related to NotificationAssistant

    /**
     * Whether the Notification Assistant should generate replies for notifications.
     */
    public static final String NAS_GENERATE_REPLIES = "nas_generate_replies";

    /**
     * Whether the Notification Assistant should generate contextual actions for notifications.
     */
    public static final String NAS_GENERATE_ACTIONS = "nas_generate_actions";

    /**
     * The maximum number of messages the Notification Assistant should extract from a
     * conversation when constructing responses for that conversation.
     */
    public static final String NAS_MAX_MESSAGES_TO_EXTRACT = "nas_max_messages_to_extract";

    /**
     * The maximum number of suggestions the Notification Assistant should provide for a
     * messaging conversation.
     */
    public static final String NAS_MAX_SUGGESTIONS = "nas_max_suggestions";

    // Flags related to Smart Suggestions - these are read in SmartReplyConstants.

    /** (boolean) Whether to enable smart suggestions in notifications. */
    public static final String SSIN_ENABLED = "ssin_enabled";

    /**
     * (boolean) Whether apps need to target at least P to provide their own smart replies (this
     * doesn't apply to actions!).
     */
    public static final String SSIN_REQUIRES_TARGETING_P = "ssin_requires_targeting_p";

    /**
     * (int) The number of times we'll try to find a better line-break for double-line smart
     * suggestion buttons.
     */
    public static final String SSIN_MAX_SQUEEZE_REMEASURE_ATTEMPTS =
            "ssin_max_squeeze_remeasure_attempts";

    /** (boolean) Whether to let the user edit smart replies before sending. */
    public static final String SSIN_EDIT_CHOICES_BEFORE_SENDING =
            "ssin_edit_choices_before_sending";

    /** (boolean) Whether smart suggestions should be enabled in heads-up notifications. */
    public static final String SSIN_SHOW_IN_HEADS_UP = "ssin_show_in_heads_up";

    /** (int) Minimum number of system generated replies to show in a notification. */
    public static final String SSIN_MIN_NUM_SYSTEM_GENERATED_REPLIES =
            "ssin_min_num_system_generated_replies";

    /**
     * (int) Maximum number of actions to show in a notification, -1 if there shouldn't be a limit
     */
    public static final String SSIN_MAX_NUM_ACTIONS = "ssin_max_num_actions";

    /**
     * (int) The amount of time (ms) before smart suggestions are clickable, since the suggestions
     * were added.
     */
    public static final String SSIN_ONCLICK_INIT_DELAY = "ssin_onclick_init_delay";

    /**
     * The default component of
     * {@link android.service.notification.NotificationAssistantService}.
     */
    public static final String NAS_DEFAULT_SERVICE = "nas_default_service";

    // Flags related to media notifications

    /**
     * (boolean) If {@code true}, enables the seekbar in compact media notifications.
     */
    public static final String COMPACT_MEDIA_SEEKBAR_ENABLED =
            "compact_media_notification_seekbar_enabled";

    /**
     * (int) Maximum number of days to retain the salt for hashing direct share targets in logging
     */
    public static final String HASH_SALT_MAX_DAYS = "hash_salt_max_days";

    // Flag related to Privacy Indicators

    /**
     * Whether the Permissions Hub is showing.
     */
    public static final String PROPERTY_PERMISSIONS_HUB_ENABLED = "permissions_hub_enabled";

    // Flags related to Assistant Handles

    /**
     * (String) Which behavior mode for the Assistant Handles to use.
     */
    public static final String ASSIST_HANDLES_BEHAVIOR_MODE = "assist_handles_behavior_mode";

    /**
     * (long) How long, in milliseconds, to display Assist Handles when showing them temporarily.
     */
    public static final String ASSIST_HANDLES_SHOW_AND_GO_DURATION_MS =
            "assist_handles_show_and_go_duration_ms";

    /**
     * (long) How long, in milliseconds, to wait before showing the Assist Handles temporarily when
     * performing a short delayed show.
     */
    public static final String ASSIST_HANDLES_SHOW_AND_GO_DELAYED_SHORT_DELAY_MS =
            "assist_handles_show_and_go_delayed_short_delay_ms";

    /**
     * (long) How long, in milliseconds, to wait before showing the Assist Handles temporarily when
     * performing a long delayed show.
     */
    public static final String ASSIST_HANDLES_SHOW_AND_GO_DELAYED_LONG_DELAY_MS =
            "assist_handles_show_and_go_delayed_long_delay_ms";

    /**
     * (long) How long, in milliseconds, to wait before resetting delayed show delay times.
     */
    public static final String ASSIST_HANDLES_SHOW_AND_GO_DELAY_RESET_TIMEOUT_MS =
            "assist_handles_show_and_go_delay_reset_timeout_ms";

    /**
     * (long) How long, in milliseconds, to wait before displaying Assist Handles temporarily after
     * hiding them.
     */
    public static final String ASSIST_HANDLES_SHOWN_FREQUENCY_THRESHOLD_MS =
            "assist_handles_shown_frequency_threshold_ms";

    /**
     * (long) How long, in milliseconds, for teaching behaviors to wait before considering the user
     * taught.
     */
    public static final String ASSIST_HANDLES_LEARN_TIME_MS = "assist_handles_learn_time_ms";

    /**
     * (int) How many times for teaching behaviors to see the user perform an action to consider it
     * taught.
     */
    public static final String ASSIST_HANDLES_LEARN_COUNT = "assist_handles_learn_count";

    /**
     * (bool) Whether to suppress handles on lockscreen."
     */
    public static final String ASSIST_HANDLES_SUPPRESS_ON_LOCKSCREEN =
            "assist_handles_suppress_on_lockscreen";

    /**
     * (bool) Whether to suppress handles on launcher."
     */
    public static final String ASSIST_HANDLES_SUPPRESS_ON_LAUNCHER =
            "assist_handles_suppress_on_launcher";

    /**
     * (bool) Whether to suppress handles on apps."
     */
    public static final String ASSIST_HANDLES_SUPPRESS_ON_APPS =
            "assist_handles_suppress_on_apps";

    /**
     * (bool) Whether to show handles when taught.
     */
    public static final String ASSIST_HANDLES_SHOW_WHEN_TAUGHT = "assist_handles_show_when_taught";

    /**
     * (bool) Whether to use the new BrightLineFalsingManager.
     */
    public static final String BRIGHTLINE_FALSING_MANAGER_ENABLED =
            "brightline_falsing_manager_enabled";
    /**
     * (float) Maximum fraction of the screen required to qualify as a real swipe.
     */
    public static final String BRIGHTLINE_FALSING_DISTANCE_SCREEN_FRACTION_MAX_DISTANCE =
            "brightline_falsing_distance_screen_fraction_max_distance";

    /**
     * (float) Multiplier for swipe velocity to convert it to pixels for a fling.
     */
    public static final String BRIGHTLINE_FALSING_DISTANCE_VELOCITY_TO_DISTANCE =
            "brightline_falsing_distance_velcoity_to_distance";

    /**
     * (float) How far, in inches, must a fling travel horizontally to qualify as intentional.
     */
    public static final String BRIGHTLINE_FALSING_DISTANCE_HORIZONTAL_FLING_THRESHOLD_IN =
            "brightline_falsing_distance_horizontal_fling_threshold_in";

    /**
     * (float) Maximum fraction of the screen required to qualify as a real swipe.
     */
    public static final String BRIGHTLINE_FALSING_DISTANCE_VERTICAL_FLING_THRESHOLD_IN =
            "brightline_falsing_distance_vertical_fling_threshold_in";

    /**
     * (float) How far, in inches, must a continuous swipe travel horizontally to be intentional.
     */
    public static final String BRIGHTLINE_FALSING_DISTANCE_HORIZONTAL_SWIPE_THRESHOLD_IN =
            "brightline_falsing_distance_horizontal_swipe_threshold_in";

    /**
     * (float) How far, in inches, must a continuous swipe travel vertically to be intentional.
     */
    public static final String BRIGHTLINE_FALSING_DISTANCE_VERTICAL_SWIPE_THRESHOLD_IN =
            "brightline_falsing_distance_horizontal_swipe_threshold_in";

    /**
     * (float) Percentage of swipe with the proximity sensor covered that triggers a higher
     * swipe distance requirement.
     */
    public static final String BRIGHTLINE_FALSING_PROXIMITY_PERCENT_COVERED_THRESHOLD =
            "brightline_falsing_proximity_percent_covered_threshold";

    /**
     * (float) Angle, in radians, that a swipe can vary from horizontal and sill be intentional.
     */
    public static final String BRIGHTLINE_FALSING_DIAGONAL_HORIZONTAL_ANGLE_RANGE =
            "brightline_falsing_diagonal_horizontal_angle_range";

    /**
     * (float) Angle, in radians, that a swipe can vary from vertical and sill be intentional.
     */
    public static final String BRIGHTLINE_FALSING_DIAGONAL_VERTICAL_ANGLE_RANGE =
            "brightline_falsing_diagonal_horizontal_angle_range";

    /**
     * (float) Distance, in inches, that a swipe is allowed to vary in the horizontal direction for
     * horizontal swipes.
     */
    public static final String BRIGHTLINE_FALSING_ZIGZAG_X_PRIMARY_DEVIANCE =
            "brightline_falsing_zigzag_x_primary_deviance";

    /**
     * (float) Distance, in inches, that a swipe is allowed to vary in the vertical direction for
     * vertical swipes.
     */
    public static final String BRIGHTLINE_FALSING_ZIGZAG_Y_PRIMARY_DEVIANCE =
            "brightline_falsing_zigzag_y_primary_deviance";

    /**
     * (float) Distance, in inches, that a swipe is allowed to vary in the horizontal direction for
     * horizontal swipes.
     */
    public static final String BRIGHTLINE_FALSING_ZIGZAG_X_SECONDARY_DEVIANCE =
            "brightline_falsing_zigzag_x_secondary_deviance";

    /**
     * (float) Distance, in inches, that a swipe is allowed to vary in the vertical direction for
     * vertical swipes.
     */
    public static final String BRIGHTLINE_FALSING_ZIGZAG_Y_SECONDARY_DEVIANCE =
            "brightline_falsing_zigzag_y_secondary_deviance";


    private SystemUiDeviceConfigFlags() { }
}
