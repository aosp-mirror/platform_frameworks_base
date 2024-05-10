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
public class MediaOutputConstants {

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
     * A string extra specifying a media package name.
     */
    public static final String EXTRA_PACKAGE_NAME = "package_name";

    /**
     * An intent action to launch media output dialog.
     */
    public static final String ACTION_LAUNCH_MEDIA_OUTPUT_DIALOG =
            "com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG";

    /**
     * An intent action to launch a media output dialog without any app or playback metadata, which
     * only controls system routing.
     *
     * <p>System routes are those provided by the system, such as built-in speakers, wired headsets,
     * bluetooth devices, and other outputs that require the app to feed media samples to the
     * framework.
     */
    public static final String ACTION_LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG =
            "com.android.systemui.action.LAUNCH_SYSTEM_MEDIA_OUTPUT_DIALOG";

    /**
     * An intent action to launch media output broadcast dialog.
     */
    public static final String ACTION_LAUNCH_MEDIA_OUTPUT_BROADCAST_DIALOG =
            "com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_BROADCAST_DIALOG";

    /**
     * Settings package name.
     */
    public static final String SETTINGS_PACKAGE_NAME = "com.android.settings";

    /**
     * An intent action to launch Bluetooth paring page.
     */
    public static final String ACTION_LAUNCH_BLUETOOTH_PAIRING =
            "com.android.settings.action.LAUNCH_BLUETOOTH_PAIRING";

    /**
     * SystemUi package name.
     */
    public static final String SYSTEMUI_PACKAGE_NAME = "com.android.systemui";

    /**
     * An intent action to close settings panel.
     */
    public static final String ACTION_CLOSE_PANEL =
            "com.android.settings.panel.action.CLOSE_PANEL";
}
