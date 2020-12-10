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

import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.annotation.Nullable;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import javax.inject.Inject;

/**
 * Determines whether to show any indicators or controls related to notification assistant.
 *
 * Flags protect any changes from being shown. Notifications that are adjusted by the assistant
 * should show an indicator.
 */
@SysUISingleton
public class AssistantFeedbackController extends ContentObserver {
    private final Uri FEEDBACK_URI
            = Settings.Global.getUriFor(Settings.Global.NOTIFICATION_FEEDBACK_ENABLED);
    private ContentResolver mResolver;

    public static final int STATUS_UNCHANGED = 0;
    public static final int STATUS_ALERTED = 1;
    public static final int STATUS_SILENCED = 2;
    public static final int STATUS_PROMOTED = 3;
    public static final int STATUS_DEMOTED = 4;

    private boolean mFeedbackEnabled;

    /** Injected constructor */
    @Inject
    public AssistantFeedbackController(Context context) {
        super(new Handler(Looper.getMainLooper()));
        mResolver = context.getContentResolver();
        mResolver.registerContentObserver(FEEDBACK_URI, false, this, UserHandle.USER_ALL);
        update(null);
    }

    @Override
    public void onChange(boolean selfChange, @Nullable Uri uri, int flags) {
        update(uri);
    }

    @VisibleForTesting
    public void update(@Nullable Uri uri) {
        if (uri == null || FEEDBACK_URI.equals(uri)) {
            mFeedbackEnabled = Settings.Global.getInt(mResolver,
                    Settings.Global.NOTIFICATION_FEEDBACK_ENABLED, 0)
                    != 0;
        }
    }

    /**
     * Determines whether to show any user controls related to the assistant. This is based on the
     * settings flag {@link Settings.Global.NOTIFICATION_FEEDBACK_ENABLED}
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
                || ranking.getRankingAdjustment() == ranking.RANKING_PROMOTED) {
            return STATUS_PROMOTED;
        } else if (oldImportance > newImportance
                || ranking.getRankingAdjustment() == ranking.RANKING_DEMOTED) {
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
     * Get the feedback indicator image resource according to assistant's changes on this
     * notification's rank or importance.
     *
     * @param entry Notification Entry to show feedback for
     */
    public int getFeedbackImageResource(NotificationEntry entry) {
        int feedbackStatus = getFeedbackStatus(entry);
        switch (feedbackStatus) {
            case STATUS_ALERTED:
                return R.drawable.ic_feedback_alerted;
            case STATUS_SILENCED:
                return R.drawable.ic_feedback_silenced;
            case STATUS_PROMOTED:
                return R.drawable.ic_feedback_uprank;
            case STATUS_DEMOTED:
                return R.drawable.ic_feedback_downrank;
            default:
                return 0;
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
