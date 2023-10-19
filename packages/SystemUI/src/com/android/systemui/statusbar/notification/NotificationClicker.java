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
import android.os.PowerManager;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.View;

import com.android.systemui.DejankUtils;
import com.android.systemui.power.domain.interactor.PowerInteractor;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.wm.shell.bubbles.Bubbles;

import java.util.Optional;

import javax.inject.Inject;

/**
 * Click handler for generic clicks on notifications. Clicks on specific areas (expansion caret,
 * app ops icon, etc) are handled elsewhere.
 */
public final class NotificationClicker implements View.OnClickListener {
    private static final String TAG = "NotificationClicker";

    private final NotificationClickerLogger mLogger;
    private final PowerInteractor mPowerInteractor;
    private final Optional<Bubbles> mBubblesOptional;
    private final NotificationActivityStarter mNotificationActivityStarter;

    private ExpandableNotificationRow.OnDragSuccessListener mOnDragSuccessListener =
            new ExpandableNotificationRow.OnDragSuccessListener() {
                @Override
                public void onDragSuccess(NotificationEntry entry) {
                    mNotificationActivityStarter.onDragSuccess(entry);
                }
            };

    private NotificationClicker(
            NotificationClickerLogger logger,
            PowerInteractor powerInteractor,
            Optional<Bubbles> bubblesOptional,
            NotificationActivityStarter notificationActivityStarter) {
        mLogger = logger;
        mPowerInteractor = powerInteractor;
        mBubblesOptional = bubblesOptional;
        mNotificationActivityStarter = notificationActivityStarter;
    }

    @Override
    public void onClick(final View v) {
        if (!(v instanceof ExpandableNotificationRow)) {
            Log.e(TAG, "NotificationClicker called on a view that is not a notification row.");
            return;
        }

        mPowerInteractor.wakeUpIfDozing("NOTIFICATION_CLICK", PowerManager.WAKE_REASON_GESTURE);

        final ExpandableNotificationRow row = (ExpandableNotificationRow) v;
        final NotificationEntry entry = row.getEntry();
        mLogger.logOnClick(entry);

        // Check if the notification is displaying the menu, if so slide notification back
        if (isMenuVisible(row)) {
            mLogger.logMenuVisible(entry);
            row.animateResetTranslation();
            return;
        } else if (row.isChildInGroup() && isMenuVisible(row.getNotificationParent())) {
            mLogger.logParentMenuVisible(entry);
            row.getNotificationParent().animateResetTranslation();
            return;
        } else if (row.isSummaryWithChildren() && row.areChildrenExpanded()) {
            // We never want to open the app directly if the user clicks in between
            // the notifications.
            mLogger.logChildrenExpanded(entry);
            return;
        } else if (row.areGutsExposed()) {
            // ignore click if guts are exposed
            mLogger.logGutsExposed(entry);
            return;
        }

        // Mark notification for one frame.
        row.setJustClicked(true);
        DejankUtils.postAfterTraversal(() -> row.setJustClicked(false));

        if (!row.getEntry().isBubble() && mBubblesOptional.isPresent()) {
            mBubblesOptional.get().collapseStack();
        }

        mNotificationActivityStarter.onNotificationClicked(entry, row);
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
            row.setOnDragSuccessListener(mOnDragSuccessListener);
        } else {
            row.setOnClickListener(null);
            row.setOnDragSuccessListener(null);
        }
    }

    /** Daggerized builder for NotificationClicker. */
    public static class Builder {
        private final NotificationClickerLogger mLogger;
        private final PowerInteractor mPowerInteractor;

        @Inject
        public Builder(NotificationClickerLogger logger, PowerInteractor powerInteractor) {
            mLogger = logger;
            mPowerInteractor = powerInteractor;
        }

        /** Builds an instance. */
        public NotificationClicker build(
                Optional<Bubbles> bubblesOptional,
                NotificationActivityStarter notificationActivityStarter
        ) {
            return new NotificationClicker(
                    mLogger,
                    mPowerInteractor,
                    bubblesOptional,
                    notificationActivityStarter);
        }
    }
}
