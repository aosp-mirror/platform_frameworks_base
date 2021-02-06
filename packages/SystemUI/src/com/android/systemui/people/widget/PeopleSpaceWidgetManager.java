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

import android.app.NotificationChannel;
import android.app.people.IPeopleManager;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.widget.RemoteViews;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.appwidget.IAppWidgetService;
import com.android.systemui.R;
import com.android.systemui.people.PeopleSpaceUtils;
import com.android.systemui.statusbar.NotificationListener;
import com.android.systemui.statusbar.NotificationListener.NotificationHandler;

import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

/** Manager for People Space widget. */
@Singleton
public class PeopleSpaceWidgetManager {
    private static final String TAG = "PeopleSpaceWidgetMgr";
    private static final boolean DEBUG = PeopleSpaceUtils.DEBUG;

    private final Context mContext;
    private IAppWidgetService mAppWidgetService;
    private AppWidgetManager mAppWidgetManager;
    private IPeopleManager mPeopleManager;

    @Inject
    public PeopleSpaceWidgetManager(Context context, IAppWidgetService appWidgetService) {
        if (DEBUG) Log.d(TAG, "constructor");
        mContext = context;
        mAppWidgetService = appWidgetService;
        mAppWidgetManager = AppWidgetManager.getInstance(context);
        mPeopleManager = IPeopleManager.Stub.asInterface(
                ServiceManager.getService(Context.PEOPLE_SERVICE));
    }

    /**
     * Constructor used for testing.
     */
    @VisibleForTesting
    protected PeopleSpaceWidgetManager(Context context) {
        if (DEBUG) Log.d(TAG, "constructor");
        mContext = context;
        mAppWidgetService = IAppWidgetService.Stub.asInterface(
                ServiceManager.getService(Context.APPWIDGET_SERVICE));
    }

    /**
     * AppWidgetManager setter used for testing.
     */
    @VisibleForTesting
    protected void setAppWidgetManager(IAppWidgetService appWidgetService,
            AppWidgetManager appWidgetManager, IPeopleManager peopleManager) {
        mAppWidgetService = appWidgetService;
        mAppWidgetManager = appWidgetManager;
        mPeopleManager = peopleManager;
    }

    /**
     * Updates People Space widgets.
     */
    public void updateWidgets() {
        try {
            if (DEBUG) Log.d(TAG, "updateWidgets called");
            int[] widgetIds = mAppWidgetService.getAppWidgetIds(
                    new ComponentName(mContext, PeopleSpaceWidgetProvider.class)
            );
            if (widgetIds.length == 0) {
                if (DEBUG) Log.d(TAG, "no widgets to update");
                return;
            }

            if (DEBUG) Log.d(TAG, "updating " + widgetIds.length + " widgets");
            boolean showSingleConversation = Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0) == 0;

            if (showSingleConversation) {
                PeopleSpaceUtils.updateSingleConversationWidgets(mContext, widgetIds,
                        mAppWidgetManager, mPeopleManager);
            } else {
                mAppWidgetService
                        .notifyAppWidgetViewDataChanged(mContext.getOpPackageName(), widgetIds,
                                R.id.widget_list_view);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception: " + e);
        }
    }

    /**
     * Check if any existing People tiles match the incoming notification change, and store the
     * change in the tile if so.
     */
    public void updateWidgetWithNotificationChanged(StatusBarNotification sbn,
            PeopleSpaceUtils.NotificationAction notificationAction) {
        RemoteViews views = new RemoteViews(
                mContext.getPackageName(), R.layout.people_space_small_avatar_tile);
        if (DEBUG) Log.d(TAG, "updateWidgetWithNotificationChanged called");
        boolean showSingleConversation = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.PEOPLE_SPACE_CONVERSATION_TYPE, 0) == 0;
        if (!showSingleConversation) {
            return;
        }
        try {
            String sbnShortcutId = sbn.getShortcutId();
            if (sbnShortcutId == null) {
                return;
            }
            int[] widgetIds = mAppWidgetService.getAppWidgetIds(
                    new ComponentName(mContext, PeopleSpaceWidgetProvider.class)
            );
            if (widgetIds.length == 0) {
                return;
            }
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(mContext);
            int userId = UserHandle.getUserHandleForUid(sbn.getUid()).getIdentifier();
            String key = PeopleSpaceUtils.getKey(sbnShortcutId, sbn.getPackageName(), userId);
            Set<String> storedWidgetIds = new HashSet<>(sp.getStringSet(key, new HashSet<>()));
            if (storedWidgetIds.isEmpty()) {
                return;
            }
            for (String widgetIdString : storedWidgetIds) {
                int widgetId = Integer.parseInt(widgetIdString);
                if (DEBUG) Log.d(TAG, "Storing notification change, key:" + sbn.getKey());
                PeopleSpaceUtils.updateWidgetWithNotificationChanged(mPeopleManager, mContext,
                        sbn, notificationAction, mAppWidgetManager, widgetId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception: " + e);
        }
    }

    /**
     * Attaches the manager to the pipeline, making it ready to receive events. Should only be
     * called once.
     */
    public void attach(NotificationListener listenerService) {
        if (DEBUG) Log.d(TAG, "attach");
        listenerService.addNotificationHandler(mListener);
    }

    private final NotificationHandler mListener = new NotificationHandler() {
        @Override
        public void onNotificationPosted(
                StatusBarNotification sbn, NotificationListenerService.RankingMap rankingMap) {
            if (DEBUG) Log.d(TAG, "onNotificationPosted");
            updateWidgetWithNotificationChanged(sbn, PeopleSpaceUtils.NotificationAction.POSTED);
        }

        @Override
        public void onNotificationRemoved(
                StatusBarNotification sbn,
                NotificationListenerService.RankingMap rankingMap
        ) {
            if (DEBUG) Log.d(TAG, "onNotificationRemoved");
            updateWidgetWithNotificationChanged(sbn, PeopleSpaceUtils.NotificationAction.REMOVED);
        }

        @Override
        public void onNotificationRemoved(
                StatusBarNotification sbn,
                NotificationListenerService.RankingMap rankingMap,
                int reason) {
            if (DEBUG) Log.d(TAG, "onNotificationRemoved with reason " + reason);
            updateWidgetWithNotificationChanged(sbn, PeopleSpaceUtils.NotificationAction.REMOVED);
        }

        @Override
        public void onNotificationRankingUpdate(
                NotificationListenerService.RankingMap rankingMap) {
        }

        @Override
        public void onNotificationsInitialized() {
            if (DEBUG) Log.d(TAG, "onNotificationsInitialized");
            updateWidgets();
        }

        @Override
        public void onNotificationChannelModified(
                String pkgName,
                UserHandle user,
                NotificationChannel channel,
                int modificationType) {
            if (DEBUG) Log.d(TAG, "onNotificationChannelModified");
            if (channel.isConversation()) {
                updateWidgets();
            }
        }
    };

}