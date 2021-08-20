/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.people.widget;

import static com.android.systemui.people.PeopleSpaceUtils.INVALID_USER_ID;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.util.Log;

import com.android.systemui.people.PeopleSpaceUtils;

import javax.inject.Inject;

/** Called when a People Tile widget is added via {@link AppWidgetManager.requestPinAppWidget()}. */
public class PeopleSpaceWidgetPinnedReceiver extends BroadcastReceiver {
    private static final String TAG = "PeopleSpaceWgtPinReceiver";
    private static final int BROADCAST_ID = 0;
    private static final int INVALID_WIDGET_ID = -1;
    private static final boolean DEBUG = PeopleSpaceUtils.DEBUG;

    private final PeopleSpaceWidgetManager mPeopleSpaceWidgetManager;

    @Inject
    public PeopleSpaceWidgetPinnedReceiver(PeopleSpaceWidgetManager peopleSpaceWidgetManager) {
        mPeopleSpaceWidgetManager = peopleSpaceWidgetManager;
    }

    /** Creates a {@link PendingIntent} that is passed onto this receiver when a widget is added. */
    public static PendingIntent getPendingIntent(Context context, ShortcutInfo shortcutInfo) {
        Intent intent = new Intent(context, PeopleSpaceWidgetPinnedReceiver.class)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        intent.putExtra(Intent.EXTRA_SHORTCUT_ID, shortcutInfo.getId());
        intent.putExtra(Intent.EXTRA_USER_ID, shortcutInfo.getUserId());
        intent.putExtra(Intent.EXTRA_PACKAGE_NAME, shortcutInfo.getPackage());

        // Intent needs to be mutable because App Widget framework populates it with app widget id.
        return PendingIntent.getBroadcast(context, BROADCAST_ID, intent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (DEBUG) Log.d(TAG, "Add widget broadcast received");
        if (context == null || intent == null) {
            if (DEBUG) Log.w(TAG, "Skipping: context or intent are null");
            return;
        }

        int widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, INVALID_WIDGET_ID);
        if (widgetId == INVALID_WIDGET_ID) {
            if (DEBUG) Log.w(TAG, "Skipping: invalid widgetId");
            return;
        }

        String shortcutId = intent.getStringExtra(Intent.EXTRA_SHORTCUT_ID);
        String packageName = intent.getStringExtra(Intent.EXTRA_PACKAGE_NAME);
        int userId = intent.getIntExtra(Intent.EXTRA_USER_ID, INVALID_USER_ID);
        PeopleTileKey key = new PeopleTileKey(shortcutId, userId, packageName);
        if (!PeopleTileKey.isValid(key)) {
            if (DEBUG) Log.w(TAG, "Skipping: key is not valid: " + key.toString());
            return;
        }

        if (DEBUG) Log.d(TAG, "Adding widget: " + widgetId + ", key:" + key.toString());
        mPeopleSpaceWidgetManager.addNewWidget(widgetId, key);
    }
}
