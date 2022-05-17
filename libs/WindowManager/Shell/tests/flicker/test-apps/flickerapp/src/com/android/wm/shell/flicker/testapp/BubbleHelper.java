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

package com.android.wm.shell.flicker.testapp;


import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.drawable.Icon;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.WindowManager;

import java.util.HashMap;

public class BubbleHelper {

    static final String EXTRA_BUBBLE_NOTIF_ID = "EXTRA_BUBBLE_NOTIF_ID";
    static final String CHANNEL_ID = "bubbles";
    static final String CHANNEL_NAME = "Bubbles";
    static final int DEFAULT_HEIGHT_DP = 300;

    private static BubbleHelper sInstance;

    private final Context mContext;
    private NotificationManager mNotificationManager;
    private float mDisplayHeight;

    private HashMap<Integer, BubbleInfo> mBubbleMap = new HashMap<>();

    private int mNextNotifyId = 0;
    private int mColourIndex = 0;

    public static class BubbleInfo {
        public int id;
        public int height;
        public Icon icon;

        public BubbleInfo(int id, int height, Icon icon) {
            this.id = id;
            this.height = height;
            this.icon = icon;
        }
    }

    public static BubbleHelper getInstance(Context context) {
        if (sInstance == null) {
            sInstance = new BubbleHelper(context);
        }
        return sInstance;
    }

    private BubbleHelper(Context context) {
        mContext = context;
        mNotificationManager = context.getSystemService(NotificationManager.class);

        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Channel that posts bubbles");
        channel.setAllowBubbles(true);
        mNotificationManager.createNotificationChannel(channel);

        Point p = new Point();
        WindowManager wm = context.getSystemService(WindowManager.class);
        wm.getDefaultDisplay().getRealSize(p);
        mDisplayHeight = p.y;

    }

      private int getNextNotifyId() {
        int id = mNextNotifyId;
        mNextNotifyId++;
        return id;
    }

    private Icon getIcon() {
        return Icon.createWithResource(mContext, R.drawable.bg);
    }

    public int addNewBubble(boolean autoExpand, boolean suppressNotif) {
        int id = getNextNotifyId();
        BubbleInfo info = new BubbleInfo(id, DEFAULT_HEIGHT_DP, getIcon());
        mBubbleMap.put(info.id, info);

        Notification.BubbleMetadata data = getBubbleBuilder(info)
                .setSuppressNotification(suppressNotif)
                .setAutoExpandBubble(false)
                .build();
        Notification notification = getNotificationBuilder(info.id)
                .setBubbleMetadata(data).build();

        mNotificationManager.notify(info.id, notification);
        return info.id;
    }

    private Notification.Builder getNotificationBuilder(int id) {
        Person chatBot = new Person.Builder()
                .setBot(true)
                .setName("BubbleChat")
                .setImportant(true)
                .build();
        String shortcutId = "BubbleChat";
        return new Notification.Builder(mContext, CHANNEL_ID)
                .setChannelId(CHANNEL_ID)
                .setShortcutId(shortcutId)
                .setContentTitle("BubbleChat")
                .setContentIntent(PendingIntent.getActivity(mContext, 0,
                        new Intent(mContext, LaunchBubbleActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT))
                .setStyle(new Notification.MessagingStyle(chatBot)
                        .setConversationTitle("BubbleChat")
                        .addMessage("BubbleChat",
                                SystemClock.currentThreadTimeMillis() - 300000, chatBot)
                        .addMessage("Is it me, " + id + ", you're looking for?",
                                SystemClock.currentThreadTimeMillis(), chatBot)
                )
                .setSmallIcon(R.drawable.ic_bubble);
    }

    private Notification.BubbleMetadata.Builder getBubbleBuilder(BubbleInfo info) {
        Intent target = new Intent(mContext, BubbleActivity.class);
        target.putExtra(EXTRA_BUBBLE_NOTIF_ID, info.id);
        PendingIntent bubbleIntent = PendingIntent.getActivity(mContext, info.id, target,
                PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.BubbleMetadata.Builder()
                .setIntent(bubbleIntent)
                .setIcon(info.icon)
                .setDesiredHeight(info.height);
    }

    public void cancel(int id) {
        mNotificationManager.cancel(id);
    }

    public void cancelAll() {
        mNotificationManager.cancelAll();
    }

    public void cancelLast() {
        StatusBarNotification[] activeNotifications = mNotificationManager.getActiveNotifications();
        if (activeNotifications.length > 0) {
            mNotificationManager.cancel(
                    activeNotifications[activeNotifications.length - 1].getId());
        }
    }

    public void cancelFirst() {
        StatusBarNotification[] activeNotifications = mNotificationManager.getActiveNotifications();
        if (activeNotifications.length > 0) {
            mNotificationManager.cancel(activeNotifications[0].getId());
        }
    }
}
