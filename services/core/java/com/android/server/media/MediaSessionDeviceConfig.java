/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.media;

import android.content.Context;
import android.provider.DeviceConfig;
import android.text.TextUtils;

import java.io.PrintWriter;
import java.util.Set;

class MediaSessionDeviceConfig {
    /**
     * Denotes the duration for which a media button receiver will be exempted from
     * FGS-from-BG restriction and so will be allowed to start an FGS even if it is in the
     * background state while it receives a media key event.
     */
    private static final String KEY_MEDIA_BUTTON_RECEIVER_FGS_ALLOWLIST_DURATION_MS =
            "media_button_receiver_fgs_allowlist_duration_ms";
    private static final long DEFAULT_MEDIA_BUTTON_RECEIVER_FGS_ALLOWLIST_DURATION_MS = 10_000;
    private static volatile long sMediaButtonReceiverFgsAllowlistDurationMs =
            DEFAULT_MEDIA_BUTTON_RECEIVER_FGS_ALLOWLIST_DURATION_MS;

    /**
     * Denotes the duration for which an app receiving a media session callback will be
     * exempted from FGS-from-BG restriction and so will be allowed to start an FGS even if
     * it is in the background state while it receives a media session callback.
     */
    private static final String KEY_MEDIA_SESSION_CALLBACK_FGS_ALLOWLIST_DURATION_MS =
            "media_session_calback_fgs_allowlist_duration_ms";
    private static final long DEFAULT_MEDIA_SESSION_CALLBACK_FGS_ALLOWLIST_DURATION_MS = 10_000;
    private static volatile long sMediaSessionCallbackFgsAllowlistDurationMs =
            DEFAULT_MEDIA_SESSION_CALLBACK_FGS_ALLOWLIST_DURATION_MS;


    /**
     * Denotes the duration for which an app receiving a media session callback and the FGS started
     * there can be temporarily allowed to have while-in-use permissions such as
     * location/camera/microphone for a duration of time.
     */
    private static final String KEY_MEDIA_SESSION_CALLBACK_FGS_WHILE_IN_USE_TEMP_ALLOW_DURATION_MS =
            "media_session_callback_fgs_while_in_use_temp_allow_duration_ms";
    private static final long DEFAULT_MEDIA_SESSION_CALLBACK_FGS_WHILE_IN_USE_TEMP_ALLOW_DURATION_MS
            = 10_000;
    private static volatile long sMediaSessionCallbackFgsWhileInUseTempAllowDurationMs =
            DEFAULT_MEDIA_SESSION_CALLBACK_FGS_WHILE_IN_USE_TEMP_ALLOW_DURATION_MS;

    /**
     * Denotes the duration (in milliseconds) that a media session can remain in an engaged state,
     * where it is only considered engaged if transitioning from active playback.
     */
    private static final String KEY_MEDIA_SESSION_TEMP_USER_ENGAGED_DURATION_MS =
            "media_session_temp_user_engaged_duration_ms";
    private static final long DEFAULT_MEDIA_SESSION_TEMP_USER_ENGAGED_DURATION_MS = 600_000;
    private static volatile long sMediaSessionTempUserEngagedDurationMs =
            DEFAULT_MEDIA_SESSION_TEMP_USER_ENGAGED_DURATION_MS;

    private static void refresh(DeviceConfig.Properties properties) {
        final Set<String> keys = properties.getKeyset();
        properties.getKeyset().forEach(key -> {
            switch (key) {
                case KEY_MEDIA_BUTTON_RECEIVER_FGS_ALLOWLIST_DURATION_MS:
                    sMediaButtonReceiverFgsAllowlistDurationMs = properties.getLong(key,
                            DEFAULT_MEDIA_BUTTON_RECEIVER_FGS_ALLOWLIST_DURATION_MS);
                    break;
                case KEY_MEDIA_SESSION_CALLBACK_FGS_ALLOWLIST_DURATION_MS:
                    sMediaSessionCallbackFgsAllowlistDurationMs = properties.getLong(key,
                            DEFAULT_MEDIA_SESSION_CALLBACK_FGS_ALLOWLIST_DURATION_MS);
                    break;
                case KEY_MEDIA_SESSION_CALLBACK_FGS_WHILE_IN_USE_TEMP_ALLOW_DURATION_MS:
                    sMediaSessionCallbackFgsWhileInUseTempAllowDurationMs = properties.getLong(key,
                            DEFAULT_MEDIA_SESSION_CALLBACK_FGS_WHILE_IN_USE_TEMP_ALLOW_DURATION_MS);
                    break;
                case KEY_MEDIA_SESSION_TEMP_USER_ENGAGED_DURATION_MS:
                    sMediaSessionTempUserEngagedDurationMs = properties.getLong(key,
                            DEFAULT_MEDIA_SESSION_TEMP_USER_ENGAGED_DURATION_MS);
                    break;
            }
        });
    }

    public static void initialize(Context context) {
        DeviceConfig.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_MEDIA,
                context.getMainExecutor(), properties -> refresh(properties));
        refresh(DeviceConfig.getProperties(DeviceConfig.NAMESPACE_MEDIA));
    }

    /**
     * Returns the duration for which a media button receiver will be exempted from
     * FGS-from-BG restriction and so will be allowed to start an FGS even if it is in the
     * background state while it receives a media key event.
     */
    public static long getMediaButtonReceiverFgsAllowlistDurationMs() {
        return sMediaButtonReceiverFgsAllowlistDurationMs;
    }

    /**
     * Returns the duration for which an app receiving a media session callback will be
     * exempted from FGS-from-BG restriction and so will be allowed to start an FGS even if
     * it is in the background state while it receives a media session callback.
     */
    public static long getMediaSessionCallbackFgsAllowlistDurationMs() {
        return sMediaSessionCallbackFgsAllowlistDurationMs;
    }

    /**
     * Return the duration for which an app receiving a media session callback and the FGS started
     * there can be temporarily allowed to have while-in-use permissions such as
     * location/camera/micrphone.
     */
    public static long getMediaSessionCallbackFgsWhileInUseTempAllowDurationMs() {
        return sMediaSessionCallbackFgsWhileInUseTempAllowDurationMs;
    }

    /**
     * Returns the duration (in milliseconds) that a media session can remain in an engaged state,
     * where it is only considered engaged if transitioning from active playback. After this
     * duration, the session is disengaged until explicit user action triggers active playback.
     */
    public static long getMediaSessionTempUserEngagedDurationMs() {
        return sMediaSessionTempUserEngagedDurationMs;
    }

    public static void dump(PrintWriter pw, String prefix) {
        pw.println("Media session config:");
        final String dumpFormat = prefix + "  %s: [cur: %s, def: %s]";
        pw.println(TextUtils.formatSimple(dumpFormat,
                KEY_MEDIA_BUTTON_RECEIVER_FGS_ALLOWLIST_DURATION_MS,
                sMediaButtonReceiverFgsAllowlistDurationMs,
                DEFAULT_MEDIA_BUTTON_RECEIVER_FGS_ALLOWLIST_DURATION_MS));
        pw.println(TextUtils.formatSimple(dumpFormat,
                KEY_MEDIA_SESSION_CALLBACK_FGS_ALLOWLIST_DURATION_MS,
                sMediaSessionCallbackFgsAllowlistDurationMs,
                DEFAULT_MEDIA_SESSION_CALLBACK_FGS_ALLOWLIST_DURATION_MS));
        pw.println(TextUtils.formatSimple(dumpFormat,
                KEY_MEDIA_SESSION_CALLBACK_FGS_WHILE_IN_USE_TEMP_ALLOW_DURATION_MS,
                sMediaSessionCallbackFgsWhileInUseTempAllowDurationMs,
                DEFAULT_MEDIA_SESSION_CALLBACK_FGS_WHILE_IN_USE_TEMP_ALLOW_DURATION_MS));
        pw.println(TextUtils.formatSimple(dumpFormat,
                KEY_MEDIA_SESSION_TEMP_USER_ENGAGED_DURATION_MS,
                sMediaSessionTempUserEngagedDurationMs,
                DEFAULT_MEDIA_SESSION_TEMP_USER_ENGAGED_DURATION_MS));
    }
}
