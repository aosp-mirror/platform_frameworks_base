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

package com.android.systemui.statusbar.tv.notifications;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.Intent;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.View;

import androidx.leanback.widget.VerticalGridView;

import com.android.systemui.R;

import java.util.function.Consumer;

import javax.inject.Inject;

/**
 * This Activity shows a notification panel for tv. It is used if no other app (e.g. a launcher) can
 * be found to show the notifications.
 */
public class TvNotificationPanelActivity extends Activity implements
        TvNotificationHandler.Listener {
    private final TvNotificationHandler mTvNotificationHandler;
    private TvNotificationAdapter mTvNotificationAdapter;
    private VerticalGridView mNotificationListView;
    private View mNotificationPlaceholder;
    private boolean mPanelAlreadyOpen = false;
    private final Consumer<Boolean> mBlurConsumer = this::enableBlur;

    @Inject
    public TvNotificationPanelActivity(TvNotificationHandler tvNotificationHandler) {
        super();
        mTvNotificationHandler = tvNotificationHandler;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (maybeClosePanel(getIntent())) {
            return;
        }
        mPanelAlreadyOpen = true;

        setContentView(R.layout.tv_notification_panel);

        mNotificationPlaceholder = findViewById(R.id.no_tv_notifications);
        mTvNotificationAdapter = new TvNotificationAdapter();

        mNotificationListView = findViewById(R.id.notifications_list);
        mNotificationListView.setAdapter(mTvNotificationAdapter);
        mNotificationListView.setColumnWidth(R.dimen.tv_notification_panel_width);

        mTvNotificationHandler.setTvNotificationListener(this);
        notificationsUpdated(mTvNotificationHandler.getCurrentNotifications());
    }

    @Override
    public void notificationsUpdated(@NonNull SparseArray<StatusBarNotification> notificationList) {
        mTvNotificationAdapter.setNotifications(notificationList);

        boolean noNotifications = notificationList.size() == 0;
        mNotificationListView.setVisibility(noNotifications ? View.GONE : View.VISIBLE);
        mNotificationPlaceholder.setVisibility(noNotifications ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        maybeClosePanel(intent);
    }

    /**
     * Handles intents from onCreate and onNewIntent.
     *
     * @return true if the panel is being closed, false if it is being opened
     */
    private boolean maybeClosePanel(Intent intent) {
        if (NotificationManager.ACTION_CLOSE_NOTIFICATION_HANDLER_PANEL.equals(intent.getAction())
                || (mPanelAlreadyOpen
                && NotificationManager.ACTION_TOGGLE_NOTIFICATION_HANDLER_PANEL.equals(
                intent.getAction()))) {
            finish();
            return true;
        }
        return false;
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        getWindow().setGravity(Gravity.END);
        getWindowManager().addCrossWindowBlurEnabledListener(mBlurConsumer);
    }

    private void enableBlur(boolean enabled) {
        if (enabled) {
            int blurRadius = getResources().getDimensionPixelSize(
                    R.dimen.tv_notification_blur_radius);
            getWindow().setBackgroundDrawable(
                    new ColorDrawable(getColor(R.color.tv_notification_blur_background_color)));
            getWindow().setBackgroundBlurRadius(blurRadius);
        } else {
            getWindow().setBackgroundDrawable(
                    new ColorDrawable(getColor(R.color.tv_notification_default_background_color)));
            getWindow().setBackgroundBlurRadius(0);
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        getWindowManager().removeCrossWindowBlurEnabledListener(mBlurConsumer);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mTvNotificationHandler.setTvNotificationListener(null);
    }
}
