/*
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
package android.view.contentcapture;

import static android.view.contentcapture.ContentCaptureManager.DEVICE_CONFIG_PROPERTY_LOGGING_LEVEL;
import static android.view.contentcapture.ContentCaptureManager.LOGGING_LEVEL_DEBUG;
import static android.view.contentcapture.ContentCaptureManager.LOGGING_LEVEL_OFF;
import static android.view.contentcapture.ContentCaptureManager.LOGGING_LEVEL_VERBOSE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Build;
import android.provider.DeviceConfig;
import android.util.Log;
import android.view.contentcapture.ContentCaptureManager.LoggingLevel;

/**
 * Helper class for this package and server's.
 *
 * @hide
 */
public final class ContentCaptureHelper {

    private static final String TAG = ContentCaptureHelper.class.getSimpleName();

    public static boolean sVerbose = false;
    public static boolean sDebug = true;

    /**
     * Used to log text that could contain PII.
     */
    @Nullable
    public static String getSanitizedString(@Nullable CharSequence text) {
        return text == null ? null : text.length() + "_chars";
    }

    /**
     * Gets the value of a device config property from the Content Capture namespace.
     */
    public static int getIntDeviceConfigProperty(@NonNull String key, int defaultValue) {
        final String value = DeviceConfig.getProperty(DeviceConfig.NAMESPACE_CONTENT_CAPTURE, key);
        if (value == null) return defaultValue;

        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            Log.w(TAG, "error parsing value (" + value + ") of property " + key + ": " + e);
            return defaultValue;
        }
    }

    /**
     * Sets the value of the static logging level constants based on device config.
     */
    public static void setLoggingLevel() {
        final int defaultLevel = Build.IS_DEBUGGABLE ? LOGGING_LEVEL_DEBUG : LOGGING_LEVEL_OFF;
        final int level = getIntDeviceConfigProperty(DEVICE_CONFIG_PROPERTY_LOGGING_LEVEL,
                defaultLevel);
        Log.i(TAG, "Setting logging level to " + getLoggingLevelAsString(level));
        sVerbose = sDebug = false;
        switch (level) {
            case LOGGING_LEVEL_VERBOSE:
                sVerbose = true;
                // fall through
            case LOGGING_LEVEL_DEBUG:
                sDebug = true;
                return;
            case LOGGING_LEVEL_OFF:
                // You log nothing, Jon Snow!
                return;
            default:
                Log.w(TAG, "setLoggingLevel(): invalud level: " + level);
        }
    }

    /**
     * Gets a user-friendly value for a content capture logging level.
     */
    public static String getLoggingLevelAsString(@LoggingLevel int level) {
        switch (level) {
            case LOGGING_LEVEL_OFF:
                return "OFF";
            case LOGGING_LEVEL_DEBUG:
                return "DEBUG";
            case LOGGING_LEVEL_VERBOSE:
                return "VERBOSE";
            default:
                return "UNKNOWN-" + level;
        }
    }

    private ContentCaptureHelper() {
        throw new UnsupportedOperationException("contains only static methods");
    }
}
