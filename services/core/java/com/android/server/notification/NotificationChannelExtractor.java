/**
* Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.notification;

import static android.app.Flags.restrictAudioAttributesAlarm;
import static android.app.Flags.restrictAudioAttributesCall;
import static android.app.Flags.restrictAudioAttributesMedia;
import static android.app.Notification.CATEGORY_ALARM;
import static android.media.AudioAttributes.USAGE_NOTIFICATION;

import android.app.Notification;
import android.app.NotificationChannel;
import android.compat.annotation.ChangeId;
import android.compat.annotation.LoggingOnly;
import android.content.Context;
import android.media.AudioAttributes;
import android.os.Binder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;
import com.android.internal.compat.IPlatformCompat;

/**
 * Stores the latest notification channel information for this notification
 */
public class NotificationChannelExtractor implements NotificationSignalExtractor {
    private static final String TAG = "ChannelExtractor";
    private static final boolean DBG = false;

    /**
     * Corrects audio attributes for notifications based on characteristics of the notifications.
     */
    @ChangeId
    @LoggingOnly
    static final long RESTRICT_AUDIO_ATTRIBUTES = 331793339L;

    private RankingConfig mConfig;
    private Context mContext;
    private IPlatformCompat mPlatformCompat;

    public void initialize(Context ctx, NotificationUsageStats usageStats) {
        mContext = ctx;
        if (DBG) Slog.d(TAG, "Initializing  " + getClass().getSimpleName() + ".");
    }

    public void setCompatChangeLogger(IPlatformCompat platformCompat) {
        mPlatformCompat = platformCompat;
    }

    public RankingReconsideration process(NotificationRecord record) {
        if (record == null || record.getNotification() == null) {
            if (DBG) Slog.d(TAG, "skipping empty notification");
            return null;
        }

        if (mConfig == null) {
            if (DBG) Slog.d(TAG, "missing config");
            return null;
        }
        NotificationChannel updatedChannel = mConfig.getConversationNotificationChannel(
                record.getSbn().getPackageName(),
                record.getSbn().getUid(), record.getChannel().getId(),
                record.getSbn().getShortcutId(), true, false);
        record.updateNotificationChannel(updatedChannel);

        if (restrictAudioAttributesCall() || restrictAudioAttributesAlarm()
                || restrictAudioAttributesMedia()) {
            AudioAttributes attributes = record.getChannel().getAudioAttributes();
            boolean updateAttributes =  false;
            if (restrictAudioAttributesCall()
                    && !record.getNotification().isStyle(Notification.CallStyle.class)
                    && attributes.getUsage() == AudioAttributes.USAGE_NOTIFICATION_RINGTONE) {
                updateAttributes = true;
            }
            if (restrictAudioAttributesAlarm()
                    && record.getNotification().category != CATEGORY_ALARM
                    && attributes.getUsage() == AudioAttributes.USAGE_ALARM) {
                updateAttributes = true;
            }

            if (restrictAudioAttributesMedia()
                    && (attributes.getUsage() == AudioAttributes.USAGE_UNKNOWN
                    || attributes.getUsage() == AudioAttributes.USAGE_MEDIA)) {
                updateAttributes = true;
            }

            if (updateAttributes) {
                reportAudioAttributesChanged(record.getUid());
                NotificationChannel clone = record.getChannel().copy();
                clone.setSound(clone.getSound(), new AudioAttributes.Builder(attributes)
                        .setUsage(USAGE_NOTIFICATION)
                        .build());
                record.updateNotificationChannel(clone);
            }
        }

        return null;
    }

    private void reportAudioAttributesChanged(int uid) {
        final long id = Binder.clearCallingIdentity();
        try {
            mPlatformCompat.reportChangeByUid(RESTRICT_AUDIO_ATTRIBUTES, uid);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unexpected exception while reporting to changecompat", e);
        } finally {
            Binder.restoreCallingIdentity(id);
        }
    }

    @Override
    public void setConfig(RankingConfig config) {
        mConfig = config;
    }

    @Override
    public void setZenHelper(ZenModeHelper helper) {

    }
}
