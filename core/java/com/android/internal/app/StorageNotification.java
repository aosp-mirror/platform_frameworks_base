/*
 * Copyright (C) 2010 Google Inc.
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

package com.android.internal.app;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IMountService;
import android.os.Message;
import android.os.MountServiceResultCode;
import android.os.ServiceManager;
import android.storage.StorageEventListener;
import android.storage.StorageManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

public class StorageNotification implements StorageEventListener {
    private static final String TAG = "StorageNotification";

    /**
     * Binder context for this service
     */
    private Context mContext;
    
    /**
     * The notification that is shown when a USB mass storage host
     * is connected. 
     * <p>
     * This is lazily created, so use {@link #setUsbStorageNotification()}.
     */
    private Notification mUsbStorageNotification;

    /**
     * The notification that is shown when the following media events occur:
     *     - Media is being checked
     *     - Media is blank (or unknown filesystem)
     *     - Media is corrupt
     *     - Media is safe to unmount
     *     - Media is missing
     * <p>
     * This is lazily created, so use {@link #setMediaStorageNotification()}.
     */
    private Notification mMediaStorageNotification;
    
    private boolean mShowSafeUnmountNotificationWhenUnmounted;
    private boolean mUmsAvailable;
    private IMountService mMountService; // XXX: This should go away soon

    public StorageNotification(Context context) {
        mContext = context;

        /*
         * XXX: This needs to be exposed via StorageManager
         */
        mMountService = IMountService.Stub.asInterface(ServiceManager.getService("mount"));
        try {
            mUmsAvailable = mMountService.getShareMethodAvailable("ums");
        } catch (Exception e) {
            Log.e(TAG, "Failed to get ums availability", e);
        }
    }

    public void onShareAvailabilityChanged(String method, boolean available) {
        if (method.equals("ums")) {
            mUmsAvailable = available;
            /*
             * Even though we may have a UMS host connected, we the SD card
             * may not be in a state for export.
             */
            String st = Environment.getExternalStorageState();
            if (available && (st.equals(
                    Environment.MEDIA_REMOVED) || st.equals(Environment.MEDIA_CHECKING))) {
                /*
                 * No card or card being checked = don't display
                 */
                available = false;
            }
 
            updateUsbMassStorageNotification(available);
        }
    }

    public void onMediaInserted(String label, String path, int major, int minor) {
    }

    public void onMediaRemoved(String label, String path, int major, int minor, boolean clean) {
        /*
         * Media removed - first clear the USB storage notification (if any)
         */
        updateUsbMassStorageNotification(false);

        if (clean) {
            setMediaStorageNotification(
                    com.android.internal.R.string.ext_media_nomedia_notification_title,
                    com.android.internal.R.string.ext_media_nomedia_notification_message,
                    com.android.internal.R.drawable.stat_notify_sdcard_usb,
                    true, false, null);
        } else {
            setMediaStorageNotification(
                    com.android.internal.R.string.ext_media_badremoval_notification_title,
                    com.android.internal.R.string.ext_media_badremoval_notification_message,
                    com.android.internal.R.drawable.stat_sys_warning,
                    true, true, null);
        }
    }

    public void onVolumeStateChanged(String label, String path, String oldState, String newState) {
        if (newState.equals(Environment.MEDIA_SHARED)) {
            Intent intent = new Intent();
            intent.setClass(mContext, com.android.internal.app.UsbStorageActivity.class);
            PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);
            setUsbStorageNotification(
                    com.android.internal.R.string.usb_storage_stop_notification_title,
                    com.android.internal.R.string.usb_storage_stop_notification_message,
                    com.android.internal.R.drawable.stat_sys_warning, false, true, pi);
        } else if (newState.equals(Environment.MEDIA_CHECKING)) {
            setMediaStorageNotification(
                    com.android.internal.R.string.ext_media_checking_notification_title,
                    com.android.internal.R.string.ext_media_checking_notification_message,
                    com.android.internal.R.drawable.stat_notify_sdcard_prepare, true, false, null);
            updateUsbMassStorageNotification(false);
        } else if (newState.equals(Environment.MEDIA_MOUNTED)) {
            setMediaStorageNotification(0, 0, 0, false, false, null);
            updateUsbMassStorageNotification(mUmsAvailable);
        } else if (newState.equals(Environment.MEDIA_UNMOUNTED)) {
            if (mShowSafeUnmountNotificationWhenUnmounted) {
                setMediaStorageNotification(
                        com.android.internal.R.string.ext_media_safe_unmount_notification_title,
                        com.android.internal.R.string.ext_media_safe_unmount_notification_message,
                        com.android.internal.R.drawable.stat_notify_sdcard, true, true, null);
                mShowSafeUnmountNotificationWhenUnmounted = false;
            } else {
                setMediaStorageNotification(0, 0, 0, false, false, null);
            }
            updateUsbMassStorageNotification(mUmsAvailable);
        } else if (newState.equals(Environment.MEDIA_NOFS)) {
            Intent intent = new Intent();
            intent.setClass(mContext, com.android.internal.app.ExternalMediaFormatActivity.class);
            PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);

            setMediaStorageNotification(
                    com.android.internal.R.string.ext_media_nofs_notification_title,
                    com.android.internal.R.string.ext_media_nofs_notification_message,
                    com.android.internal.R.drawable.stat_notify_sdcard_usb, true, false, pi);
            updateUsbMassStorageNotification(mUmsAvailable);
        } else if (newState.equals(Environment.MEDIA_UNMOUNTABLE)) {
            Intent intent = new Intent();
            intent.setClass(mContext, com.android.internal.app.ExternalMediaFormatActivity.class);
            PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);

            setMediaStorageNotification(
                    com.android.internal.R.string.ext_media_unmountable_notification_title,
                    com.android.internal.R.string.ext_media_unmountable_notification_message,
                    com.android.internal.R.drawable.stat_notify_sdcard_usb, true, false, pi); 
            updateUsbMassStorageNotification(mUmsAvailable);
        }
    }

    /**
     * Update the state of the USB mass storage notification
     */
    void updateUsbMassStorageNotification(boolean available) {

        if (available) {
            Intent intent = new Intent();
            intent.setClass(mContext, com.android.internal.app.UsbStorageActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);
            setUsbStorageNotification(
                    com.android.internal.R.string.usb_storage_notification_title,
                    com.android.internal.R.string.usb_storage_notification_message,
                    com.android.internal.R.drawable.stat_sys_data_usb,
                    false, true, pi);
        } else {
            setUsbStorageNotification(0, 0, 0, false, false, null);
        }
    }

    /**
     * Sets the USB storage notification.
     */
    private synchronized void setUsbStorageNotification(int titleId, int messageId, int icon, boolean sound, boolean visible,
                                                        PendingIntent pi) {

        if (!visible && mUsbStorageNotification == null) {
            return;
        }

        NotificationManager notificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);

        if (notificationManager == null) {
            return;
        }
        
        if (visible) {
            Resources r = Resources.getSystem();
            CharSequence title = r.getText(titleId);
            CharSequence message = r.getText(messageId);

            if (mUsbStorageNotification == null) {
                mUsbStorageNotification = new Notification();
                mUsbStorageNotification.icon = icon;
                mUsbStorageNotification.when = 0;
            }

            if (sound) {
                mUsbStorageNotification.defaults |= Notification.DEFAULT_SOUND;
            } else {
                mUsbStorageNotification.defaults &= ~Notification.DEFAULT_SOUND;
            }
                
            mUsbStorageNotification.flags = Notification.FLAG_ONGOING_EVENT;

            mUsbStorageNotification.tickerText = title;
            if (pi == null) {
                Intent intent = new Intent();
                pi = PendingIntent.getBroadcast(mContext, 0, intent, 0);
            }

            mUsbStorageNotification.setLatestEventInfo(mContext, title, message, pi);
        }
    
        final int notificationId = mUsbStorageNotification.icon;
        if (visible) {
            notificationManager.notify(notificationId, mUsbStorageNotification);
        } else {
            notificationManager.cancel(notificationId);
        }
    }

    private synchronized boolean getMediaStorageNotificationDismissable() {
        if ((mMediaStorageNotification != null) &&
            ((mMediaStorageNotification.flags & Notification.FLAG_AUTO_CANCEL) ==
                    Notification.FLAG_AUTO_CANCEL))
            return true;

        return false;
    }

    /**
     * Sets the media storage notification.
     */
    private synchronized void setMediaStorageNotification(int titleId, int messageId, int icon, boolean visible,
                                                          boolean dismissable, PendingIntent pi) {

        if (!visible && mMediaStorageNotification == null) {
            return;
        }

        NotificationManager notificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);

        if (notificationManager == null) {
            return;
        }

        if (mMediaStorageNotification != null && visible) {
            /*
             * Dismiss the previous notification - we're about to
             * re-use it.
             */
            final int notificationId = mMediaStorageNotification.icon;
            notificationManager.cancel(notificationId);
        }
        
        if (visible) {
            Resources r = Resources.getSystem();
            CharSequence title = r.getText(titleId);
            CharSequence message = r.getText(messageId);

            if (mMediaStorageNotification == null) {
                mMediaStorageNotification = new Notification();
                mMediaStorageNotification.when = 0;
            }

            mMediaStorageNotification.defaults &= ~Notification.DEFAULT_SOUND;

            if (dismissable) {
                mMediaStorageNotification.flags = Notification.FLAG_AUTO_CANCEL;
            } else {
                mMediaStorageNotification.flags = Notification.FLAG_ONGOING_EVENT;
            }

            mMediaStorageNotification.tickerText = title;
            if (pi == null) {
                Intent intent = new Intent();
                pi = PendingIntent.getBroadcast(mContext, 0, intent, 0);
            }

            mMediaStorageNotification.icon = icon;
            mMediaStorageNotification.setLatestEventInfo(mContext, title, message, pi);
        }
    
        final int notificationId = mMediaStorageNotification.icon;
        if (visible) {
            notificationManager.notify(notificationId, mMediaStorageNotification);
        } else {
            notificationManager.cancel(notificationId);
        }
    }
}
