/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.settingslib.media;

/**
 * Class to access MediaOutput constants.
 */
public class MediaOutputSliceConstants {

    /**
     * Key for the Media output setting.
     */
    public static final String KEY_MEDIA_OUTPUT = "media_output";

    /**
     * Key for the Media output group setting.
     */
    public static final String KEY_MEDIA_OUTPUT_GROUP = "media_output_group";

    /**
     * Key for the Remote Media slice.
     */
    public static final String KEY_REMOTE_MEDIA = "remote_media";

    /**
     * Key for the {@link android.media.session.MediaSession.Token}.
     */
    public static final String KEY_MEDIA_SESSION_TOKEN = "key_media_session_token";

    /**
     * Key for the {@link android.media.RoutingSessionInfo#getId()}
     */
    public static final String KEY_SESSION_INFO_ID = "key_session_info_id";

    /**
     * Activity Action: Show a settings dialog containing {@link MediaDevice} to transfer media.
     */
    public static final String ACTION_MEDIA_OUTPUT =
            "com.android.settings.panel.action.MEDIA_OUTPUT";

    /**
     * Activity Action: Show a settings dialog containing {@link MediaDevice} to handle media group
     * operation.
     */
    public static final String ACTION_MEDIA_OUTPUT_GROUP =
            "com.android.settings.panel.action.MEDIA_OUTPUT_GROUP";

    /**
     * An string extra specifying a media package name.
     */
    public static final String EXTRA_PACKAGE_NAME =
            "com.android.settings.panel.extra.PACKAGE_NAME";
}
