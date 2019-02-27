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

    private SystemUiDeviceConfigFlags() { }
}
