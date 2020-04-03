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

package com.android.systemui.statusbar.notification.row;

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.service.notification.NotificationListenerService.Ranking.RANKING_DEMOTED;
import static android.service.notification.NotificationListenerService.Ranking.RANKING_PROMOTED;
import static android.service.notification.NotificationListenerService.Ranking.RANKING_UNCHANGED;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dependency;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.AssistantFeedbackController;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.logging.NotificationLogger;

public class FeedbackInfo extends LinearLayout implements NotificationGuts.GutsContent {

    private static final String TAG = "FeedbackInfo";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String FEEDBACK_KEY = "feedback_key";

    private NotificationGuts mGutsContainer;
    private NotificationListenerService.Ranking mRanking;
    private PackageManager mPm;
    private String mAppName;
    private String mPkg;
    private NotificationEntry mEntry;

    private NotificationEntryManager mNotificationEntryManager;
    private IStatusBarService mStatusBarService;
    private AssistantFeedbackController mFeedbackController;

    public FeedbackInfo(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void bindGuts(
            final PackageManager pm,
            final StatusBarNotification sbn,
            final NotificationEntry entry,
            final AssistantFeedbackController controller) {
        mPkg = sbn.getPackageName();
        mPm = pm;
        mEntry = entry;
        mRanking = entry.getRanking();
        mFeedbackController = controller;
        mAppName = mPkg;
        mNotificationEntryManager = Dependency.get(NotificationEntryManager.class);
        mStatusBarService = Dependency.get(IStatusBarService.class);

        bindHeader();
        bindPrompt();
        bindButton();
    }

    private void bindHeader() {
        // Package name
        Drawable pkgicon = null;
        ApplicationInfo info;
        try {
            info = mPm.getApplicationInfo(mPkg,
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                            | PackageManager.MATCH_DISABLED_COMPONENTS
                            | PackageManager.MATCH_DIRECT_BOOT_UNAWARE
                            | PackageManager.MATCH_DIRECT_BOOT_AWARE);
            if (info != null) {
                mAppName = String.valueOf(mPm.getApplicationLabel(info));
                pkgicon = mPm.getApplicationIcon(info);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // app is gone, just show package name and generic icon
            pkgicon = mPm.getDefaultActivityIcon();
        }
        ((ImageView) findViewById(R.id.pkg_icon)).setImageDrawable(pkgicon);
        ((TextView) findViewById(R.id.pkg_name)).setText(mAppName);
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (mGutsContainer != null
                && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (mGutsContainer.isExposed()) {
                event.getText().add(mContext.getString(
                        R.string.notification_channel_controls_opened_accessibility, mAppName));
            } else {
                event.getText().add(mContext.getString(
                        R.string.notification_channel_controls_closed_accessibility, mAppName));
            }
        }
    }

    private void bindPrompt() {
        final TextView prompt = findViewById(R.id.prompt);
        final TextView yes = findViewById(R.id.yes);
        final TextView no = findViewById(R.id.no);
        yes.setVisibility(View.VISIBLE);
        no.setVisibility(View.VISIBLE);
        yes.setOnClickListener(this::positiveFeedback);
        no.setOnClickListener(this::negativeFeedback);
        prompt.setText(getPrompt());
    }

    @SuppressLint("DefaultLocale")
    private String getPrompt() {
        StringBuilder sb = new StringBuilder();
        int oldImportance = mRanking.getChannel().getImportance();
        int newImportance = mRanking.getImportance();
        int ranking = mRanking.getRankingAdjustment();
        if (DEBUG) {
            sb.append(String.format(
                    "[DEBUG]: oldImportance=%d, newImportance=%d, ranking=%d\n\n",
                    mRanking.getChannel().getImportance(), mRanking.getImportance(),
                    mRanking.getRankingAdjustment()));
        }
        if (oldImportance >= IMPORTANCE_DEFAULT && newImportance < IMPORTANCE_DEFAULT) {
            sb.append(mContext.getString(R.string.feedback_silenced));
        } else if (newImportance > oldImportance || ranking == RANKING_PROMOTED) {
            sb.append(mContext.getString(R.string.feedback_promoted));
        } else if (newImportance < oldImportance || ranking == RANKING_DEMOTED) {
            sb.append(mContext.getString(R.string.feedback_demoted));
        }
        sb.append(" ");
        sb.append(mContext.getString(R.string.feedback_prompt));

        return sb.toString();
    }

    private void bindButton() {
        TextView ok = findViewById(R.id.ok);
        ok.setOnClickListener(this::closeControls);
    }

    private void positiveFeedback(View v) {
        handleFeedback(true);
    }

    private void negativeFeedback(View v) {
        handleFeedback(false);
    }

    private void handleFeedback(boolean positive) {
        TextView prompt = findViewById(R.id.prompt);
        prompt.setText(mContext.getString(R.string.feedback_response));
        TextView yes = findViewById(R.id.yes);
        yes.setVisibility(View.GONE);
        TextView no = findViewById(R.id.no);
        no.setVisibility(View.GONE);

        Bundle feedback = new Bundle();
        feedback.putBoolean(FEEDBACK_KEY, positive);
        sendFeedbackToAssistant(feedback);
    }

    private void sendFeedbackToAssistant(Bundle feedback) {
        if (!mFeedbackController.isFeedbackEnabled()) {
            return;
        }

        //TODO(b/154257994): remove this when feedback apis are in place
        final int count = mNotificationEntryManager.getActiveNotificationsCount();
        final int rank = mEntry.getRanking().getRank();
        NotificationVisibility.NotificationLocation location =
                NotificationLogger.getNotificationLocation(mEntry);
        final NotificationVisibility nv = NotificationVisibility.obtain(
                mEntry.getKey(), rank, count, true, location);
        Notification.Action action = new Notification.Action.Builder(null, null,
                null)
                .addExtras(feedback)
                .build();
        try {
            mStatusBarService.onNotificationActionClick(mRanking.getKey(), -1, action, nv, true);
        } catch (RemoteException e) {
            if (DEBUG) {
                Log.e(TAG, "Failed to send feedback to assistant", e);
            }
        }
    }

    private void closeControls(View v) {
        mGutsContainer.closeControls(v, false);
    }

    @Override
    public void setGutsParent(NotificationGuts guts) {
        mGutsContainer = guts;
    }

    @Override
    public View getContentView() {
        return this;
    }

    @Override
    public int getActualHeight() {
        return getHeight();
    }

    @Override
    public boolean handleCloseControls(boolean save, boolean force) {
        return false;
    }

    @Override
    public boolean willBeRemoved() {
        return false;
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean needsFalsingProtection() {
        return false;
    }
}
