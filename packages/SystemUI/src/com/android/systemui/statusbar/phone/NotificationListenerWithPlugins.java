/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.content.ComponentName;
import android.content.Context;
import android.os.RemoteException;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import com.android.systemui.Dependency;
import com.android.systemui.plugins.NotificationListenerController;
import com.android.systemui.plugins.NotificationListenerController.NotificationProvider;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.shared.plugins.PluginManager;

import java.util.ArrayList;

/**
 * A version of NotificationListenerService that passes all info to
 * any plugins connected. Also allows those plugins the chance to cancel
 * any incoming callbacks or to trigger new ones.
 */
public class NotificationListenerWithPlugins extends NotificationListenerService implements
        PluginListener<NotificationListenerController> {

    private ArrayList<NotificationListenerController> mPlugins = new ArrayList<>();
    private boolean mConnected;

    @Override
    public void registerAsSystemService(Context context, ComponentName componentName,
            int currentUser) throws RemoteException {
        super.registerAsSystemService(context, componentName, currentUser);
        Dependency.get(PluginManager.class).addPluginListener(this,
                NotificationListenerController.class);
    }

    @Override
    public void unregisterAsSystemService() throws RemoteException {
        super.unregisterAsSystemService();
        Dependency.get(PluginManager.class).removePluginListener(this);
    }

    @Override
    public StatusBarNotification[] getActiveNotifications() {
        StatusBarNotification[] activeNotifications = super.getActiveNotifications();
        for (NotificationListenerController plugin : mPlugins) {
            activeNotifications = plugin.getActiveNotifications(activeNotifications);
        }
        return activeNotifications;
    }

    @Override
    public RankingMap getCurrentRanking() {
        RankingMap currentRanking = super.getCurrentRanking();
        for (NotificationListenerController plugin : mPlugins) {
            currentRanking = plugin.getCurrentRanking(currentRanking);
        }
        return currentRanking;
    }

    public void onPluginConnected() {
        mConnected = true;
        mPlugins.forEach(p -> p.onListenerConnected(getProvider()));
    }

    /**
     * Called when listener receives a onNotificationPosted.
     * Returns true to indicate this callback should be skipped.
     */
    public boolean onPluginNotificationPosted(StatusBarNotification sbn,
            final RankingMap rankingMap) {
        for (NotificationListenerController plugin : mPlugins) {
            if (plugin.onNotificationPosted(sbn, rankingMap)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Called when listener receives a onNotificationRemoved.
     * Returns true to indicate this callback should be skipped.
     */
    public boolean onPluginNotificationRemoved(StatusBarNotification sbn,
            final RankingMap rankingMap) {
        for (NotificationListenerController plugin : mPlugins) {
            if (plugin.onNotificationRemoved(sbn, rankingMap)) {
                return true;
            }
        }
        return false;
    }

    public RankingMap onPluginRankingUpdate(RankingMap rankingMap) {
        return getCurrentRanking();
    }

    @Override
    public void onPluginConnected(NotificationListenerController plugin, Context pluginContext) {
        mPlugins.add(plugin);
        if (mConnected) {
            plugin.onListenerConnected(getProvider());
        }
    }

    @Override
    public void onPluginDisconnected(NotificationListenerController plugin) {
        mPlugins.remove(plugin);
    }

    private NotificationProvider getProvider() {
        return new NotificationProvider() {
            @Override
            public StatusBarNotification[] getActiveNotifications() {
                return NotificationListenerWithPlugins.super.getActiveNotifications();
            }

            @Override
            public RankingMap getRankingMap() {
                return NotificationListenerWithPlugins.super.getCurrentRanking();
            }

            @Override
            public void addNotification(StatusBarNotification sbn) {
                onNotificationPosted(sbn, getRankingMap());
            }

            @Override
            public void removeNotification(StatusBarNotification sbn) {
                onNotificationRemoved(sbn, getRankingMap());
            }

            @Override
            public void updateRanking() {
                onNotificationRankingUpdate(getRankingMap());
            }
        };
    }
}
