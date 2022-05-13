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

    /**
     * Whether the Notification Assistant can change ranking.
     */
    public static final String ENABLE_NAS_RANKING = "enable_nas_ranking";

    /**
     * Whether the Notification Assistant can prioritize notification.
     */
    public static final String ENABLE_NAS_PRIORITIZER = "enable_nas_prioritizer";

    /**
     * Whether to enable feedback UI for Notification Assistant
     */
    public static final String ENABLE_NAS_FEEDBACK = "enable_nas_feedback";

    /**
     * Whether the Notification Assistant can label a notification not a conversation
     */
    public static final String ENABLE_NAS_NOT_CONVERSATION = "enable_nas_not_conversation";

    // Flags related to screenshot intelligence

    /**
     * (bool) Whether to enable smart actions in screenshot notifications.
     */
    public static final String ENABLE_SCREENSHOT_NOTIFICATION_SMART_ACTIONS =
            "enable_screenshot_notification_smart_actions";

    /**
     * (int) Timeout value in ms to get smart actions for screenshot notification.
     */
    public static final String SCREENSHOT_NOTIFICATION_SMART_ACTIONS_TIMEOUT_MS =
            "screenshot_notification_smart_actions_timeout_ms";

    /**
     * (int) Timeout value in ms to get Quick Share actions for screenshot notification.
     */
    public static final String SCREENSHOT_NOTIFICATION_QUICK_SHARE_ACTIONS_TIMEOUT_MS =
            "screenshot_notification_quick_share_actions_timeout_ms";

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
     * (int) Maximum number of days to retain the salt for hashing direct share targets in logging
     */
    public static final String HASH_SALT_MAX_DAYS = "hash_salt_max_days";

    // Flag related to Privacy Indicators

    /**
     * Whether to show the complete ongoing app ops chip.
     */
    public static final String PROPERTY_PERMISSIONS_HUB_ENABLED = "permissions_hub_2_enabled";

    /**
     * Whether to show app ops chip for just microphone + camera.
     */
    public static final String PROPERTY_MIC_CAMERA_ENABLED = "camera_mic_icons_enabled";

    /**
     * Whether to show app ops chip for location.
     */
    public static final String PROPERTY_LOCATION_INDICATORS_ENABLED = "location_indicators_enabled";

    /**
     * Whether to show privacy chip for media projection.
     */
    public static final String PROPERTY_MEDIA_PROJECTION_INDICATORS_ENABLED =
            "media_projection_indicators_enabled";

    /**
     * Whether to show old location indicator on all location accesses.
     */
    public static final String PROPERTY_LOCATION_INDICATORS_SMALL_ENABLED =
            "location_indicators_small_enabled";

    /**
     * Whether to show the location indicator for system apps.
     */
    public static final String PROPERTY_LOCATION_INDICATORS_SHOW_SYSTEM =
            "location_indicators_show_system";

    // Flags related to Assistant

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
     * Allow touch passthrough above assist area during a session.
     */
    public static final String ASSIST_TAP_PASSTHROUGH = "assist_tap_passthrough";

    /**
     * (bool) Whether to show handles when taught.
     */
    public static final String ASSIST_HANDLES_SHOW_WHEN_TAUGHT = "assist_handles_show_when_taught";

    /**
     * (long) Duration per pixel, in milliseconds, of scrolling text at fast speed.
     */
    public static final String ASSIST_TRANSCRIPTION_DURATION_PER_PX_FAST =
            "assist_transcription_duration_per_px_fast";

    /**
     * (long) Duration per pixel, in milliseconds, of scrolling text at regular speed.
     */
    public static final String ASSIST_TRANSCRIPTION_DURATION_PER_PX_REGULAR =
            "assist_transcription_duration_per_px_regular";

    /**
     * (long) Duration, in milliseconds, over which text fades in.
     */
    public static final String ASSIST_TRANSCRIPTION_FADE_IN_DURATION =
            "assist_transcription_fade_in_duration";

    /**
     * (long) Maximum total duration, in milliseconds, for a given transcription.
     */
    public static final String ASSIST_TRANSCRIPTION_MAX_DURATION =
            "assist_transcription_max_duration";

    /**
     * (long) Minimum total duration, in milliseconds, for a given transcription.
     */
    public static final String ASSIST_TRANSCRIPTION_MIN_DURATION =
            "assist_transcription_min_duration";

    /**
     * (boolean) Whether or not to enable an extra section in the notification shade which
     * filters for "people" related messages.
     */
    public static final String NOTIFICATIONS_USE_PEOPLE_FILTERING =
            "notifications_use_people_filtering";

    /**
     * (boolean) Whether or not to enable user dismissing of foreground service notifications
     * into a new section at the bottom of the notification shade.
     */
    public static final String NOTIFICATIONS_ALLOW_FGS_DISMISSAL =
            "notifications_allow_fgs_dismissal";

    // Flags related to brightline falsing

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


    // Flags related to screenshots

    /**
     * (boolean) Whether screenshot flow going to the corner (instead of shown in a notification)
     * is enabled.
     */
    public static final String SCREENSHOT_CORNER_FLOW = "enable_screenshot_corner_flow";

    // Flags related to Nav Bar

    /**
     * (boolean) Whether to force the Nav Bar handle to remain opaque.
     */
    public static final String NAV_BAR_HANDLE_FORCE_OPAQUE = "nav_bar_handle_force_opaque";

    /**
     * (boolean) Whether to force the Nav Bar handle to remain visible over the lockscreen.
     */
    public static final String NAV_BAR_HANDLE_SHOW_OVER_LOCKSCREEN =
            "nav_bar_handle_show_over_lockscreen";

    /**
     * (int) Timeout threshold, in millisecond, that Sharesheet waits for direct share targets.
     */
    public static final String SHARE_SHEET_DIRECT_SHARE_TIMEOUT =
            "share_sheet_direct_share_timeout";

    /**
     * (boolean) Whether append direct share on Sharesheet is enabled.
     */
    public static final String APPEND_DIRECT_SHARE_ENABLED = "append_direct_share_enabled";

    /**
     * (boolean) Whether ChooserTargets ranking on Sharesheet is enabled.
     */
    public static final String CHOOSER_TARGET_RANKING_ENABLED = "chooser_target_ranking_enabled";

    /**
     * (boolean) Whether dark launch of remote prediction service is enabled.
     */
    public static final String DARK_LAUNCH_REMOTE_PREDICTION_SERVICE_ENABLED =
            "dark_launch_remote_prediction_service_enabled";

    /**
     * (boolean) Whether to enable pinch resizing for PIP.
     */
    public static final String PIP_PINCH_RESIZE = "pip_pinch_resize";

    /**
     * (boolean) Whether to enable stashing for PIP.
     */
    public static final String PIP_STASHING = "pip_stashing";

    /**
     * (float) The threshold velocity to cause PiP to be stashed when flinging from one edge to the
     * other.
     */
    public static final String PIP_STASH_MINIMUM_VELOCITY_THRESHOLD = "pip_velocity_threshold";

    /**
     * (float) Bottom height in DP for Back Gesture.
     */
    public static final String BACK_GESTURE_BOTTOM_HEIGHT = "back_gesture_bottom_height";

    /**
     * (float) Edge width in DP where touch down is allowed for Back Gesture.
     */
    public static final String BACK_GESTURE_EDGE_WIDTH = "back_gesture_edge_width";

    /**
     * (float) Slop multiplier for Back Gesture.
     */
    public static final String BACK_GESTURE_SLOP_MULTIPLIER = "back_gesture_slop_multiplier";

    /**
     * (long) Screenshot keychord delay (how long the buttons must be pressed), in ms
     */
    public static final String SCREENSHOT_KEYCHORD_DELAY = "screenshot_keychord_delay";

    /**
     * (boolean) Whether to use an ML model for the Back Gesture.
     */
    public static final String USE_BACK_GESTURE_ML_MODEL = "use_back_gesture_ml_model";

    /**
     * (string) The name of the ML model for Back Gesture.
     */
    public static final String BACK_GESTURE_ML_MODEL_NAME = "back_gesture_ml_model_name";

    /**
     * (float) Threshold for Back Gesture ML model prediction.
     */
    public static final String BACK_GESTURE_ML_MODEL_THRESHOLD = "back_gesture_ml_model_threshold";

    /**
     * (boolean) Sharesheet - Whether to use the deprecated
     * {@link android.service.chooser.ChooserTargetService} API for
     *  direct share targets. If true, both CTS and Shortcuts will be used to find Direct
     *  Share targets. If false, only Shortcuts will be used.
     */
    public static final String SHARE_USE_SERVICE_TARGETS = "share_use_service_targets";

    /**
     * (boolean) If true, SysUI provides guardrails for app usage of Direct Share by enforcing
     * limits on number of targets per app & adjusting scores for apps providing many targets. If
     * false, this step is skipped. This should be true unless the ranking provider configured by
     * [some other flag] is expected to manage these incentives.
     */
    public static final String APPLY_SHARING_APP_LIMITS_IN_SYSUI =
            "apply_sharing_app_limits_in_sysui";

    /*
     * (long) The duration that the home button must be pressed before triggering Assist
     */
    public static final String HOME_BUTTON_LONG_PRESS_DURATION_MS =
            "home_button_long_press_duration_ms";

    /**
     * (boolean) Whether shortcut integration over app search service is enabled.
     */
    public static final String SHORTCUT_APPSEARCH_INTEGRATION =
            "shortcut_appsearch_integration";

    /**
     * (boolean) Whether nearby share should be the first target in ranked apps.
     */
    public static final String IS_NEARBY_SHARE_FIRST_TARGET_IN_RANKED_APP =
            "is_nearby_share_first_target_in_ranked_app";

    /**
     * (boolean) Whether to enable the new unbundled sharesheet
     * (com.android.intentresolver.ChooserActivity).
     */
    public static final String USE_UNBUNDLED_SHARESHEET = "use_unbundled_sharesheet";

    /**
     * (int) The delay (in ms) before refreshing the Sharesheet UI after a change to the share
     * target data model. For more info see go/sharesheet-list-view-update-delay.
     */
    public static final String SHARESHEET_LIST_VIEW_UPDATE_DELAY =
            "sharesheet_list_view_update_delay";

    /**
     * (string) Name of the default QR code scanner activity. On the eligible devices this activity
     * is provided by GMS core.
     */
    public static final String DEFAULT_QR_CODE_SCANNER = "default_qr_code_scanner";

    /**
     * (boolean) Whether the task manager entrypoint is enabled.
     */
    public static final String TASK_MANAGER_ENABLED = "task_manager_enabled";


    /**
     * (boolean) Whether the clipboard overlay is enabled.
     */
    public static final String CLIPBOARD_OVERLAY_ENABLED = "clipboard_overlay_enabled";

    /**
     * (boolean) Whether widget provider info would be saved to / loaded from system persistence
     * layer as opposed to individual manifests in respective apps.
     */
    public static final String PERSISTS_WIDGET_PROVIDER_INFO = "persists_widget_provider_info";

    private SystemUiDeviceConfigFlags() {
    }
}
