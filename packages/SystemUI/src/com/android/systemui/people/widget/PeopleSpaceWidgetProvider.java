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

package com.android.systemui.people.widget;

import android.annotation.NonNull;
import android.app.people.ConversationChannel;
import android.app.people.PeopleManager;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.people.PeopleSpaceUtils;

/** People Space Widget Provider class. */
public class PeopleSpaceWidgetProvider extends AppWidgetProvider {
    private static final String TAG = "PeopleSpaceWidgetPvd";
    private static final boolean DEBUG = PeopleSpaceUtils.DEBUG;

    public static final String EXTRA_TILE_ID = "extra_tile_id";
    public static final String EXTRA_PACKAGE_NAME = "extra_package_name";
    public static final String EXTRA_USER_HANDLE = "extra_user_handle";
    public static final String EXTRA_NOTIFICATION_KEY = "extra_notification_key";

    public PeopleSpaceWidgetManager peopleSpaceWidgetManager;

    /** Listener for the shortcut data changes. */
    public class TileConversationListener implements PeopleManager.ConversationListener {

        @Override
        public void onConversationUpdate(@NonNull ConversationChannel conversation) {
            if (DEBUG) {
                Log.d(TAG,
                        "Received updated conversation: "
                                + conversation.getShortcutInfo().getLabel());
            }
            if (peopleSpaceWidgetManager == null) {
                // This shouldn't happen since onUpdate is called at reboot.
                Log.e(TAG, "Skipping conversation update: WidgetManager uninitialized");
                return;
            }
            peopleSpaceWidgetManager.updateWidgetsWithConversationChanged(conversation);
        }
    }

    /** Called when widget updates. */
    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        super.onUpdate(context, appWidgetManager, appWidgetIds);

        if (DEBUG) Log.d(TAG, "onUpdate called");
        ensurePeopleSpaceWidgetManagerInitialized(context);
        peopleSpaceWidgetManager.updateWidgets(appWidgetIds);
        for (int appWidgetId : appWidgetIds) {
            PeopleSpaceWidgetProvider.TileConversationListener
                    newListener = new PeopleSpaceWidgetProvider.TileConversationListener();
            peopleSpaceWidgetManager.registerConversationListenerIfNeeded(appWidgetId,
                    newListener);
        }
        return;
    }

    private void ensurePeopleSpaceWidgetManagerInitialized(Context context) {
        if (peopleSpaceWidgetManager == null) {
            peopleSpaceWidgetManager = new PeopleSpaceWidgetManager(context);
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        ensurePeopleSpaceWidgetManagerInitialized(context);
        peopleSpaceWidgetManager.deleteWidgets(appWidgetIds);
    }

    @VisibleForTesting
    public void setPeopleSpaceWidgetManager(PeopleSpaceWidgetManager manager) {
        peopleSpaceWidgetManager = manager;
    }
}
