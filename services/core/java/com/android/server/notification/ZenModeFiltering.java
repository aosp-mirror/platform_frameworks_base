/**
 * Copyright (c) 2015, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.service.notification.ZenModeConfig;
import android.telecom.TelecomManager;
import android.util.Slog;

import java.util.Objects;

public class ZenModeFiltering {
    private static final String TAG = ZenModeHelper.TAG;
    private static final boolean DEBUG = ZenModeHelper.DEBUG;

    private final Context mContext;

    private ComponentName mDefaultPhoneApp;

    public ZenModeFiltering(Context context) {
        mContext = context;
    }

    public ComponentName getDefaultPhoneApp() {
        return mDefaultPhoneApp;
    }

    /**
     * @param extras extras of the notification with EXTRA_PEOPLE populated
     * @param contactsTimeoutMs timeout in milliseconds to wait for contacts response
     * @param timeoutAffinity affinity to return when the timeout specified via
     *                        <code>contactsTimeoutMs</code> is hit
     */
    public static boolean matchesCallFilter(int zen, ZenModeConfig config, UserHandle userHandle,
            Bundle extras, ValidateNotificationPeople validator, int contactsTimeoutMs,
            float timeoutAffinity) {
        if (zen == Global.ZEN_MODE_NO_INTERRUPTIONS) return false; // nothing gets through
        if (zen == Global.ZEN_MODE_ALARMS) return false; // not an alarm
        if (zen == Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS) {
            if (!config.allowCalls) return false; // no calls get through
            if (validator != null) {
                final float contactAffinity = validator.getContactAffinity(userHandle, extras,
                        contactsTimeoutMs, timeoutAffinity);
                return audienceMatches(config, contactAffinity);
            }
        }
        return true;
    }

    public boolean shouldIntercept(int zen, ZenModeConfig config, NotificationRecord record) {
        if (isSystem(record)) {
            return false;
        }
        switch (zen) {
            case Global.ZEN_MODE_NO_INTERRUPTIONS:
                // #notevenalarms
                ZenLog.traceIntercepted(record, "none");
                return true;
            case Global.ZEN_MODE_ALARMS:
                if (isAlarm(record)) {
                    // Alarms only
                    return false;
                }
                ZenLog.traceIntercepted(record, "alarmsOnly");
                return true;
            case Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS:
                if (isAlarm(record)) {
                    // Alarms are always priority
                    return false;
                }
                // allow user-prioritized packages through in priority mode
                if (record.getPackagePriority() == Notification.PRIORITY_MAX) {
                    ZenLog.traceNotIntercepted(record, "priorityApp");
                    return false;
                }
                if (isCall(record)) {
                    if (!config.allowCalls) {
                        ZenLog.traceIntercepted(record, "!allowCalls");
                        return true;
                    }
                    return shouldInterceptAudience(config, record);
                }
                if (isMessage(record)) {
                    if (!config.allowMessages) {
                        ZenLog.traceIntercepted(record, "!allowMessages");
                        return true;
                    }
                    return shouldInterceptAudience(config, record);
                }
                if (isEvent(record)) {
                    if (!config.allowEvents) {
                        ZenLog.traceIntercepted(record, "!allowEvents");
                        return true;
                    }
                    return false;
                }
                if (isReminder(record)) {
                    if (!config.allowReminders) {
                        ZenLog.traceIntercepted(record, "!allowReminders");
                        return true;
                    }
                    return false;
                }
                ZenLog.traceIntercepted(record, "!priority");
                return true;
            default:
                return false;
        }
    }

    private static boolean shouldInterceptAudience(ZenModeConfig config,
            NotificationRecord record) {
        if (!audienceMatches(config, record.getContactAffinity())) {
            ZenLog.traceIntercepted(record, "!audienceMatches");
            return true;
        }
        return false;
    }

    private static boolean isSystem(NotificationRecord record) {
        return record.isCategory(Notification.CATEGORY_SYSTEM);
    }

    private static boolean isAlarm(NotificationRecord record) {
        return record.isCategory(Notification.CATEGORY_ALARM)
                || record.isAudioStream(AudioManager.STREAM_ALARM)
                || record.isAudioAttributesUsage(AudioAttributes.USAGE_ALARM);
    }

    private static boolean isEvent(NotificationRecord record) {
        return record.isCategory(Notification.CATEGORY_EVENT);
    }

    private static boolean isReminder(NotificationRecord record) {
        return record.isCategory(Notification.CATEGORY_REMINDER);
    }

    public boolean isCall(NotificationRecord record) {
        return record != null && (isDefaultPhoneApp(record.sbn.getPackageName())
                || record.isCategory(Notification.CATEGORY_CALL));
    }

    private boolean isDefaultPhoneApp(String pkg) {
        if (mDefaultPhoneApp == null) {
            final TelecomManager telecomm =
                    (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
            mDefaultPhoneApp = telecomm != null ? telecomm.getDefaultPhoneApp() : null;
            if (DEBUG) Slog.d(TAG, "Default phone app: " + mDefaultPhoneApp);
        }
        return pkg != null && mDefaultPhoneApp != null
                && pkg.equals(mDefaultPhoneApp.getPackageName());
    }

    @SuppressWarnings("deprecation")
    private boolean isDefaultMessagingApp(NotificationRecord record) {
        final int userId = record.getUserId();
        if (userId == UserHandle.USER_NULL || userId == UserHandle.USER_ALL) return false;
        final String defaultApp = Secure.getStringForUser(mContext.getContentResolver(),
                Secure.SMS_DEFAULT_APPLICATION, userId);
        return Objects.equals(defaultApp, record.sbn.getPackageName());
    }

    private boolean isMessage(NotificationRecord record) {
        return record.isCategory(Notification.CATEGORY_MESSAGE) || isDefaultMessagingApp(record);
    }

    private static boolean audienceMatches(ZenModeConfig config, float contactAffinity) {
        switch (config.allowFrom) {
            case ZenModeConfig.SOURCE_ANYONE:
                return true;
            case ZenModeConfig.SOURCE_CONTACT:
                return contactAffinity >= ValidateNotificationPeople.VALID_CONTACT;
            case ZenModeConfig.SOURCE_STAR:
                return contactAffinity >= ValidateNotificationPeople.STARRED_CONTACT;
            default:
                Slog.w(TAG, "Encountered unknown source: " + config.allowFrom);
                return true;
        }
    }
}
