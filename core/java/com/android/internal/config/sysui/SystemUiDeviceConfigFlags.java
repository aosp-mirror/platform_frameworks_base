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

    private SystemUiDeviceConfigFlags() { }
}
