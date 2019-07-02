/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.app.Notification;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;

import com.android.systemui.DejankUtils;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.phone.ShadeController;

/**
 * Click handler for generic clicks on notifications. Clicks on specific areas (expansion caret,
 * app ops icon, etc) are handled elsewhere.
 */
public final class NotificationClicker implements View.OnClickListener {
    private static final String TAG = "NotificationClicker";

    private final ShadeController mShadeController;
    private final BubbleController mBubbleController;
    private final NotificationActivityStarter mNotificationActivityStarter;

    public NotificationClicker(ShadeController shadeController,
            BubbleController bubbleController,
            NotificationActivityStarter notificationActivityStarter) {
        mShadeController = shadeController;
        mBubbleController = bubbleController;
        mNotificationActivityStarter = notificationActivityStarter;
    }

    @Override
    public void onClick(final View v) {
        if (!(v instanceof ExpandableNotificationRow)) {
            Log.e(TAG, "NotificationClicker called on a view that is not a notification row.");
            return;
        }

        mShadeController.wakeUpIfDozing(SystemClock.uptimeMillis(), v, "NOTIFICATION_CLICK");

        final ExpandableNotificationRow row = (ExpandableNotificationRow) v;
        final StatusBarNotification sbn = row.getStatusBarNotification();
        if (sbn == null) {
            Log.e(TAG, "NotificationClicker called on an unclickable notification,");
            return;
        }

        // Check if the notification is displaying the menu, if so slide notification back
        if (isMenuVisible(row)) {
            row.animateTranslateNotification(0);
            return;
        } else if (row.isChildInGroup() && isMenuVisible(row.getNotificationParent())) {
            row.getNotificationParent().animateTranslateNotification(0);
            return;
        } else if (row.isSummaryWithChildren() && row.areChildrenExpanded()) {
            // We never want to open the app directly if the user clicks in between
            // the notifications.
            return;
        }

        // Mark notification for one frame.
        row.setJustClicked(true);
        DejankUtils.postAfterTraversal(() -> row.setJustClicked(false));

        if (!row.getEntry().isBubble()) {
            mBubbleController.collapseStack();
        }

        mNotificationActivityStarter.onNotificationClicked(sbn, row);
    }

    private boolean isMenuVisible(ExpandableNotificationRow row) {
        return row.getProvider() != null && row.getProvider().isMenuVisible();
    }

    /**
     * Attaches the click listener to the row if appropriate.
     */
    public void register(ExpandableNotificationRow row, StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        if (notification.contentIntent != null || notification.fullScreenIntent != null
                || row.getEntry().isBubble()) {
            row.setOnClickListener(this);
        } else {
            row.setOnClickListener(null);
        }
    }
}
