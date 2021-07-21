/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification;

import static android.service.notification.NotificationListenerService.Ranking;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.ENABLE_NAS_FEEDBACK;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Handler;
import android.provider.DeviceConfig;
import android.util.Pair;

import com.android.internal.R;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.util.DeviceConfigProxy;

import javax.inject.Inject;

/**
 * Determines whether to show any indicators or controls related to notification assistant.
 *
 * Flags protect any changes from being shown. Notifications that are adjusted by the assistant
 * should show an indicator.
 */
@SysUISingleton
public class AssistantFeedbackController {
    private final Context mContext;
    private final Handler mHandler;
    private final DeviceConfigProxy mDeviceConfigProxy;

    public static final int STATUS_UNCHANGED = 0;
    public static final int STATUS_ALERTED = 1;
    public static final int STATUS_SILENCED = 2;
    public static final int STATUS_PROMOTED = 3;
    public static final int STATUS_DEMOTED = 4;

    private volatile boolean mFeedbackEnabled;

    private final DeviceConfig.OnPropertiesChangedListener mPropertiesChangedListener =
            new DeviceConfig.OnPropertiesChangedListener() {
                @Override
                public void onPropertiesChanged(DeviceConfig.Properties properties) {
                    if (properties.getKeyset().contains(ENABLE_NAS_FEEDBACK)) {
                        mFeedbackEnabled = properties.getBoolean(
                                ENABLE_NAS_FEEDBACK, false);
                    }
                }
            };

    /** Injected constructor */
    @Inject
    public AssistantFeedbackController(@Main Handler handler,
            Context context, DeviceConfigProxy proxy) {
        mHandler = handler;
        mContext = context;
        mDeviceConfigProxy = proxy;
        mFeedbackEnabled = mDeviceConfigProxy.getBoolean(DeviceConfig.NAMESPACE_SYSTEMUI,
                ENABLE_NAS_FEEDBACK, false);
        mDeviceConfigProxy.addOnPropertiesChangedListener(DeviceConfig.NAMESPACE_SYSTEMUI,
                this::postToHandler, mPropertiesChangedListener);
    }

    private void postToHandler(Runnable r) {
        this.mHandler.post(r);
    }

    /**
     * Determines whether to show any user controls related to the assistant based on the
     * DeviceConfig flag value
     */
    public boolean isFeedbackEnabled() {
        return mFeedbackEnabled;
    }

    /**
     * Get the feedback status according to assistant's adjustments
     *
     * @param entry Notification Entry to show feedback for
     */
    public int getFeedbackStatus(NotificationEntry entry) {
        if (!isFeedbackEnabled()) {
            return STATUS_UNCHANGED;
        }
        Ranking ranking = entry.getRanking();
        int oldImportance = ranking.getChannel().getImportance();
        int newImportance = ranking.getImportance();
        if (oldImportance < NotificationManager.IMPORTANCE_DEFAULT
                && newImportance >= NotificationManager.IMPORTANCE_DEFAULT) {
            return STATUS_ALERTED;
        } else if (oldImportance >= NotificationManager.IMPORTANCE_DEFAULT
                && newImportance < NotificationManager.IMPORTANCE_DEFAULT) {
            return STATUS_SILENCED;
        } else if (oldImportance < newImportance
                || ranking.getRankingAdjustment() == Ranking.RANKING_PROMOTED) {
            return STATUS_PROMOTED;
        } else if (oldImportance > newImportance
                || ranking.getRankingAdjustment() == Ranking.RANKING_DEMOTED) {
            return STATUS_DEMOTED;
        } else {
            return STATUS_UNCHANGED;
        }
    }

    /**
     * Determines whether to show feedback indicator. The feedback indicator will be shown
     * if {@link #isFeedbackEnabled()} is enabled and assistant has changed this notification's rank
     * or importance.
     *
     * @param entry Notification Entry to show feedback for
     */
    public boolean showFeedbackIndicator(NotificationEntry entry) {
        return getFeedbackStatus(entry) != STATUS_UNCHANGED;
    }

    /**
     * Get the feedback indicator image and content description resources according to assistant's
     * changes on this notification's rank or importance.
     *
     * @param entry Notification Entry to show feedback for
     */
    public Pair<Integer, Integer> getFeedbackResources(NotificationEntry entry) {
        int feedbackStatus = getFeedbackStatus(entry);
        switch (feedbackStatus) {
            case STATUS_ALERTED:
                return new Pair(R.drawable.ic_feedback_alerted,
                        R.string.notification_feedback_indicator_alerted);
            case STATUS_SILENCED:
                return new Pair(R.drawable.ic_feedback_silenced,
                        R.string.notification_feedback_indicator_silenced);
            case STATUS_PROMOTED:
                return new Pair(R.drawable.ic_feedback_uprank,
                        R.string.notification_feedback_indicator_promoted);
            case STATUS_DEMOTED:
                return new Pair(R.drawable.ic_feedback_downrank,
                        R.string.notification_feedback_indicator_demoted);
            default:
                return new Pair(0, 0);
        }
    }

    /**
     * Get the inline settings description resource according to assistant's changes on this
     * notification's rank or importance.
     *
     * @param entry Notification Entry to show feedback for
     */
    public int getInlineDescriptionResource(NotificationEntry entry) {
        int feedbackStatus = getFeedbackStatus(entry);
        switch (feedbackStatus) {
            case STATUS_ALERTED:
                return com.android.systemui.R.string.notification_channel_summary_automatic_alerted;
            case STATUS_SILENCED:
                return com.android.systemui.R.string
                        .notification_channel_summary_automatic_silenced;
            case STATUS_PROMOTED:
                return com.android.systemui.R.string
                        .notification_channel_summary_automatic_promoted;
            case STATUS_DEMOTED:
                return com.android.systemui.R.string.notification_channel_summary_automatic_demoted;
            default:
                return com.android.systemui.R.string.notification_channel_summary_automatic;
        }
    }
}
