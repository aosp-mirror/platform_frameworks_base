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

import static android.service.notification.NotificationAssistantService.FEEDBACK_RATING;

import static com.android.systemui.statusbar.notification.AssistantFeedbackController.STATUS_ALERTED;
import static com.android.systemui.statusbar.notification.AssistantFeedbackController.STATUS_DEMOTED;
import static com.android.systemui.statusbar.notification.AssistantFeedbackController.STATUS_PROMOTED;
import static com.android.systemui.statusbar.notification.AssistantFeedbackController.STATUS_SILENCED;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.RemoteException;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.Html;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.statusbar.IStatusBarService;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.AssistantFeedbackController;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.util.Compile;

public class FeedbackInfo extends LinearLayout implements NotificationGuts.GutsContent {

    private static final String TAG = "FeedbackInfo";
    private static final boolean DEBUG = Compile.IS_DEBUG && Log.isLoggable(TAG, Log.DEBUG);

    private NotificationGuts mGutsContainer;
    private NotificationListenerService.Ranking mRanking;
    private PackageManager mPm;
    private String mAppName;
    private String mPkg;
    private NotificationEntry mEntry;

    private IStatusBarService mStatusBarService;
    private AssistantFeedbackController mFeedbackController;
    private NotificationGutsManager mNotificationGutsManager;
    private NotificationMenuRowPlugin mMenuRowPlugin;
    private ExpandableNotificationRow mExpandableNotificationRow;

    public FeedbackInfo(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public void bindGuts(
            final PackageManager pm,
            final StatusBarNotification sbn,
            final NotificationEntry entry,
            final ExpandableNotificationRow row,
            final AssistantFeedbackController controller,
            final IStatusBarService statusBarService,
            final NotificationGutsManager notificationGutsManager) {
        mPkg = sbn.getPackageName();
        mPm = pm;
        mEntry = entry;
        mExpandableNotificationRow = row;
        mRanking = entry.getRanking();
        mFeedbackController = controller;
        mAppName = mPkg;
        mStatusBarService = statusBarService;
        mNotificationGutsManager = notificationGutsManager;

        bindHeader();
        bindPrompt();
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
        prompt.setText(Html.fromHtml(getPrompt()));
    }

    @SuppressLint("DefaultLocale")
    private String getPrompt() {
        StringBuilder sb = new StringBuilder();
        int status = mFeedbackController.getFeedbackStatus(mEntry);
        if (DEBUG) {
            sb.append(String.format(
                    "[DEBUG]: oldImportance=%d, newImportance=%d, ranking=%f\n\n",
                    mRanking.getChannel().getImportance(), mRanking.getImportance(),
                    mRanking.getRankingScore()));
        }
        if (status == STATUS_ALERTED) {
            sb.append(mContext.getText(R.string.feedback_alerted));
        } else if (status == STATUS_SILENCED) {
            sb.append(mContext.getText(R.string.feedback_silenced));
        } else if (status == STATUS_PROMOTED) {
            sb.append(mContext.getText(R.string.feedback_promoted));
        } else if (status == STATUS_DEMOTED) {
            sb.append(mContext.getText(R.string.feedback_demoted));
        }
        sb.append(" ");
        sb.append(mContext.getText(R.string.feedback_prompt));

        return sb.toString();
    }

    private void positiveFeedback(View v) {
        mGutsContainer.closeControls(v, /* save= */ false);
        handleFeedback(true);
    }

    private void negativeFeedback(View v) {
        mMenuRowPlugin = mExpandableNotificationRow.getProvider();
        NotificationMenuRowPlugin.MenuItem menuItem = null;
        if (mMenuRowPlugin != null) {
            menuItem = mMenuRowPlugin.getLongpressMenuItem(mContext);
        }

        mGutsContainer.closeControls(v, /* save= */ false);
        mNotificationGutsManager.openGuts(mExpandableNotificationRow, 0, 0, menuItem);
        handleFeedback(false);
    }

    private void handleFeedback(boolean positive) {
        Bundle feedback = new Bundle();
        feedback.putInt(FEEDBACK_RATING, positive ? 1 : -1);

        sendFeedbackToAssistant(feedback);
    }

    private void sendFeedbackToAssistant(Bundle feedback) {
        if (!mFeedbackController.isFeedbackEnabled()) {
            return;
        }

        try {
            mStatusBarService.onNotificationFeedbackReceived(mRanking.getKey(), feedback);
        } catch (RemoteException e) {
            if (DEBUG) {
                Log.e(TAG, "Failed to send feedback to assistant", e);
            }
        }
    }

    private void closeControls(View v) {
        mGutsContainer.closeControls(v, /* save= */ false);
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
    public boolean shouldBeSavedOnClose() {
        return false;
    }

    @Override
    public boolean needsFalsingProtection() {
        return false;
    }
}
