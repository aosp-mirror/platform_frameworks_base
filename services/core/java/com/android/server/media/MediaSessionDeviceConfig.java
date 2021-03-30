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
    }
}
