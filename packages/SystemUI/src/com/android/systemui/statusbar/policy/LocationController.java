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

import android.app.INotificationManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.location.LocationManager;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;

import com.android.systemui.R;

import java.util.ArrayList;

public class LocationController extends BroadcastReceiver {
    private static final String TAG = "StatusBar.LocationController";

    private static final int GPS_NOTIFICATION_ID = 374203-122084;

    private Context mContext;

    private INotificationManager mNotificationService;

    private ArrayList<LocationGpsStateChangeCallback> mChangeCallbacks =
            new ArrayList<LocationGpsStateChangeCallback>();
    private ArrayList<LocationSettingsChangeCallback> mSettingsChangeCallbacks =
            new ArrayList<LocationSettingsChangeCallback>();

    /**
     * A callback for change in gps status (enabled/disabled, have lock, etc).
     */
    public interface LocationGpsStateChangeCallback {
        public void onLocationGpsStateChanged(boolean inUse, String description);
    }

    /**
     * A callback for change in location settings (the user has enabled/disabled location).
     */
    public interface LocationSettingsChangeCallback {
        /**
         * Called whenever location settings change.
         *
         * @param locationEnabled A value of true indicates that at least one type of location
         *                        is enabled in settings.
         */
        public void onLocationSettingsChanged(boolean locationEnabled);
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

        // Register to listen for changes to the location settings
        context.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.LOCATION_PROVIDERS_ALLOWED), true,
                new ContentObserver(new Handler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        boolean isEnabled = isLocationEnabled();
                        for (LocationSettingsChangeCallback cb : mSettingsChangeCallbacks) {
                            cb.onLocationSettingsChanged(isEnabled);
                        }
                    }
                });
    }

    /**
     * Add a callback to listen for changes in gps status.
     */
    public void addStateChangedCallback(LocationGpsStateChangeCallback cb) {
        mChangeCallbacks.add(cb);
    }

    /**
     * Add a callback to listen for changes in location settings.
     */
    public void addSettingsChangedCallback(LocationSettingsChangeCallback cb) {
        mSettingsChangeCallbacks.add(cb);
    }

    /**
     * Enable or disable location in settings.
     *
     * <p>This will attempt to enable/disable every type of location setting
     * (e.g. high and balanced power).
     *
     * <p>If enabling, a user consent dialog will pop up prompting the user to accept.
     * If the user doesn't accept, network location won't be enabled.
     */
    public void setLocationEnabled(boolean enabled) {
        final UserManager um = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        if (um.hasUserRestriction(UserManager.DISALLOW_SHARE_LOCATION)) {
            return;
        }
        final ContentResolver cr = mContext.getContentResolver();
        // When enabling location, a user consent dialog will pop up, and the
        // setting won't be fully enabled until the user accepts the agreement.
        Settings.Secure.setLocationMasterSwitchEnabled(cr, enabled);
    }

    /**
     * Returns true if either gps or network location are enabled in settings.
     */
    public boolean isLocationEnabled() {
        ContentResolver contentResolver = mContext.getContentResolver();
        return Settings.Secure.isLocationMasterSwitchEnabled(contentResolver);
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

