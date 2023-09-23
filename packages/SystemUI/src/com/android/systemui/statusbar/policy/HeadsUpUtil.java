/*
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

package com.android.systemui.statusbar.policy;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;
import android.view.View;

import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.util.Compile;

/**
 * A class of utility static methods for heads up notifications.
 */
public final class HeadsUpUtil {
    private static final int TAG_CLICKED_NOTIFICATION = R.id.is_clicked_heads_up_tag;

    private static final String LOG_TAG = "HeadsUpUtil";
    private static final boolean LOG_DEBUG = Compile.IS_DEBUG && Log.isLoggable(LOG_TAG, Log.DEBUG);

    /**
     * Set the given view as clicked or not-clicked.
     * @param view The view to be set the flag to.
     * @param clicked True to set as clicked. False to not-clicked.
     */
    public static void setNeedsHeadsUpDisappearAnimationAfterClick(View view, boolean clicked) {
        if (LOG_DEBUG) {
            logTagClickedNotificationChanged(view, clicked);
        }
        view.setTag(TAG_CLICKED_NOTIFICATION, clicked ? true : null);
    }

    /**
     * Check if the given view has the flag of "clicked notification"
     * @param view The view to be checked.
     * @return True if the view has clicked. False othrewise.
     */
    public static boolean isClickedHeadsUpNotification(View view) {
        Boolean clicked = (Boolean) view.getTag(TAG_CLICKED_NOTIFICATION);
        return clicked != null && clicked;
    }

    private static void logTagClickedNotificationChanged(@Nullable View view, boolean isClicked) {
        if (view == null) {
            return;
        }

        final boolean wasClicked = isClickedHeadsUpNotification(view);
        if (isClicked == wasClicked) {
            return;
        }

        Log.d(LOG_TAG, getViewKey(view) + ": TAG_CLICKED_NOTIFICATION set to " + isClicked);
    }

    private static @NonNull String getViewKey(@NonNull View view) {
        if (!(view instanceof ExpandableNotificationRow)) {
            return "(not a row)";
        }

        final ExpandableNotificationRow row = (ExpandableNotificationRow) view;
        final NotificationEntry entry = row.getEntry();
        if (entry == null) {
            return "(null entry)";
        }

        final String key = entry.getKey();
        if (key == null) {
            return "(null key)";
        }

        return key;
    }
}
