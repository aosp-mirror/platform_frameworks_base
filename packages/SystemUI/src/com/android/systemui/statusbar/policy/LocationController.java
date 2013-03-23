/*
 * Copyright (C) 2008 The Android Open Source Project
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

import java.util.ArrayList;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;
import android.os.UserHandle;
import android.provider.Settings;

// private NM API
import android.app.INotificationManager;

import com.android.systemui.R;

public class LocationController extends BroadcastReceiver {
    private static final String TAG = "StatusBar.LocationController";

    private static final int GPS_NOTIFICATION_ID = 374203-122084;

    private Context mContext;

    private INotificationManager mNotificationService;

    private ArrayList<LocationGpsStateChangeCallback> mChangeCallbacks =
            new ArrayList<LocationGpsStateChangeCallback>();

    public interface LocationGpsStateChangeCallback {
        public void onLocationGpsStateChanged(boolean inUse, String description);
    }

    public LocationController(Context context) {
        mContext = context;

        IntentFilter filter = new IntentFilter();
        filter.addAction(LocationManager.GPS_ENABLED_CHANGE_ACTION);
        filter.addAction(LocationManager.GPS_FIX_CHANGE_ACTION);
        context.registerReceiver(this, filter);

        NotificationManager nm = (NotificationManager)context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        mNotificationService = nm.getService();
    }

    public void addStateChangedCallback(LocationGpsStateChangeCallback cb) {
        mChangeCallbacks.add(cb);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        final boolean enabled = intent.getBooleanExtra(LocationManager.EXTRA_GPS_ENABLED, false);

        boolean visible;
        int iconId, textResId;

        if (action.equals(LocationManager.GPS_FIX_CHANGE_ACTION) && enabled) {
            // GPS is getting fixes
            iconId = com.android.internal.R.drawable.stat_sys_gps_on;
            textResId = R.string.gps_notification_found_text;
            visible = true;
        } else if (action.equals(LocationManager.GPS_ENABLED_CHANGE_ACTION) && !enabled) {
            // GPS is off
            visible = false;
            iconId = textResId = 0;
        } else {
            // GPS is on, but not receiving fixes
            iconId = R.drawable.stat_sys_gps_acquiring_anim;
            textResId = R.string.gps_notification_searching_text;
            visible = true;
        }
        
        try {
            if (visible) {
                Intent gpsIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                gpsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                PendingIntent pendingIntent = PendingIntent.getActivityAsUser(context, 0,
                        gpsIntent, 0, null, UserHandle.CURRENT);
                String text = mContext.getText(textResId).toString();

                Notification n = new Notification.Builder(mContext)
                    .setSmallIcon(iconId)
                    .setContentTitle(text)
                    .setOngoing(true)
                    .setContentIntent(pendingIntent)
                    .getNotification();

                // Notification.Builder will helpfully fill these out for you no matter what you do
                n.tickerView = null;
                n.tickerText = null;
                
                n.priority = Notification.PRIORITY_HIGH;

                int[] idOut = new int[1];
                mNotificationService.enqueueNotificationWithTag(
                        mContext.getPackageName(), mContext.getBasePackageName(),
                        null, 
                        GPS_NOTIFICATION_ID, 
                        n,
                        idOut,
                        UserHandle.USER_ALL);

                for (LocationGpsStateChangeCallback cb : mChangeCallbacks) {
                    cb.onLocationGpsStateChanged(true, text);
                }
            } else {
                mNotificationService.cancelNotificationWithTag(
                        mContext.getPackageName(), null,
                        GPS_NOTIFICATION_ID, UserHandle.USER_ALL);

                for (LocationGpsStateChangeCallback cb : mChangeCallbacks) {
                    cb.onLocationGpsStateChanged(false, null);
                }
            }
        } catch (android.os.RemoteException ex) {
            // well, it was worth a shot
        }
    }
}

