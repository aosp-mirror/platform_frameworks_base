/*
 * Copyright (C) 2007 The Android Open Source Project
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

package com.android.server;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.IMountService;
import android.os.Environment;
import android.os.RemoteException;
import android.os.UEventObserver;
import android.util.Log;

import java.io.File;
import java.io.FileReader;

/**
 * MountService implements an to the mount service daemon
 * @hide
 */
class MountService extends IMountService.Stub {
    
    private static final String TAG = "MountService";

    /**
     * Binder context for this service
     */
    private Context mContext;
    
    /**
     * listener object for communicating with the mount service daemon
     */
    private MountListener mListener;

    /**
     * The notification that is shown when USB is connected. It leads the user
     * to a dialog to enable mass storage mode.
     * <p>
     * This is lazily created, so use {@link #getUsbStorageNotification()}.
     */
    private Notification mUsbStorageNotification;

    private class SdDoorListener extends UEventObserver {    
        static final String SD_DOOR_UEVENT_MATCH = "DEVPATH=/devices/virtual/switch/sd-door";
        static final String SD_DOOR_SWITCH_NAME = "sd-door";

        public void onUEvent(UEvent event) {
            if (SD_DOOR_SWITCH_NAME.equals(event.get("SWITCH_NAME"))) {
                sdDoorStateChanged(event.get("SWITCH_STATE"));
            }
        }
    };
    
    /**
     * Constructs a new MountService instance
     * 
     * @param context  Binder context for this service
     */
    public MountService(Context context) {
        mContext = context;
        mListener =  new MountListener(this);       
        Thread thread = new Thread(mListener, MountListener.class.getName());
        thread.start();
        SdDoorListener sdDoorListener = new SdDoorListener();
        sdDoorListener.startObserving(SdDoorListener.SD_DOOR_UEVENT_MATCH);
    }

    /**
     * @return true if USB mass storage support is enabled.
     */
    public boolean getMassStorageEnabled() throws RemoteException {
        return mListener.getMassStorageEnabled();
    }

    /**
     * Enables or disables USB mass storage support.
     * 
     * @param enable  true to enable USB mass storage support
     */
    public void setMassStorageEnabled(boolean enable) throws RemoteException {
        mListener.setMassStorageEnabled(enable);
    }

    /**
     * @return true if USB mass storage is connected.
     */
    public boolean getMassStorageConnected() throws RemoteException {
        return mListener.getMassStorageConnected();
    }
    
    /**
     * Attempt to mount external media
     */
    public void mountMedia(String mountPath) throws RemoteException {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS) 
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires MOUNT_UNMOUNT_FILESYSTEMS permission");
        }
        mListener.mountMedia(mountPath);
    }

    /**
     * Attempt to unmount external media to prepare for eject
     */
    public void unmountMedia(String mountPath) throws RemoteException {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MOUNT_UNMOUNT_FILESYSTEMS) 
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires MOUNT_UNMOUNT_FILESYSTEMS permission");
        }

        // tell mountd to unmount the media
        mListener.ejectMedia(mountPath);
    }

    /**
     * Broadcasts the USB mass storage connected event to all clients.
     */
    void notifyUmsConnected() {
        setUsbStorageNotificationVisibility(true);
        Intent intent = new Intent(Intent.ACTION_UMS_CONNECTED);
        mContext.sendBroadcast(intent);
    }

    /**
     * Broadcasts the USB mass storage disconnected event to all clients.
     */
    void notifyUmsDisconnected() {
        setUsbStorageNotificationVisibility(false);
        Intent intent = new Intent(Intent.ACTION_UMS_DISCONNECTED);
        mContext.sendBroadcast(intent);
    }

    /**
     * Broadcasts the media removed event to all clients.
     */
    void notifyMediaRemoved(String path) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_REMOVED, 
                Uri.parse("file://" + path));
        mContext.sendBroadcast(intent);
    }

    /**
     * Broadcasts the media unmounted event to all clients.
     */
    void notifyMediaUnmounted(String path) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_UNMOUNTED, 
                Uri.parse("file://" + path));
        mContext.sendBroadcast(intent);
    }

    /**
     * Broadcasts the media mounted event to all clients.
     */
    void notifyMediaMounted(String path, boolean readOnly) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_MOUNTED, 
                Uri.parse("file://" + path));
        intent.putExtra("read-only", readOnly);
        mContext.sendBroadcast(intent);
    }

    /**
     * Broadcasts the media shared event to all clients.
     */
    void notifyMediaShared(String path) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_SHARED, 
                Uri.parse("file://" + path));
        mContext.sendBroadcast(intent);
    }

    /**
     * Broadcasts the media bad removal event to all clients.
     */
    void notifyMediaBadRemoval(String path) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_BAD_REMOVAL, 
                Uri.parse("file://" + path));
        mContext.sendBroadcast(intent);
    }

    /**
     * Broadcasts the media unmountable event to all clients.
     */
    void notifyMediaUnmountable(String path) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_UNMOUNTABLE, 
                Uri.parse("file://" + path));
        mContext.sendBroadcast(intent);
    }
    
    /**
     * Broadcasts the media eject event to all clients.
     */
    void notifyMediaEject(String path) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_EJECT, 
                Uri.parse("file://" + path));
        mContext.sendBroadcast(intent);
    }
    
    private void sdDoorStateChanged(String doorState) {
        File directory = Environment.getExternalStorageDirectory();
        String storageState = Environment.getExternalStorageState();
        
        if (directory != null) {
            try {
                if (doorState.equals("open") && (storageState.equals(Environment.MEDIA_MOUNTED) ||
                        storageState.equals(Environment.MEDIA_MOUNTED_READ_ONLY))) {
                    // request SD card unmount if SD card door is opened
                    unmountMedia(directory.getPath());
                } else if (doorState.equals("closed") && storageState.equals(Environment.MEDIA_UNMOUNTED)) {
                    // attempt to remount SD card
                    mountMedia(directory.getPath());
                }
            } catch (RemoteException e) {
                // Nothing to do.
            }
        }
    }

    /**
     * Sets the visibility of the USB storage notification. This should be
     * called when a USB cable is connected and also when it is disconnected.
     * 
     * @param visible Whether to show or hide the notification.
     */
    private void setUsbStorageNotificationVisibility(boolean visible) {
        NotificationManager notificationManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }

        /*
         * The convention for notification IDs is to use the icon's resource ID
         * when the icon is only used by a single notification type, which is
         * the case here.
         */
        Notification notification = getUsbStorageNotification();
        final int notificationId = notification.icon;
        
        if (visible) {
            notificationManager.notify(notificationId, notification);
        } else {
            notificationManager.cancel(notificationId);
        }
    }

    /**
     * Gets the USB storage notification.
     * 
     * @return A {@link Notification} that leads to the dialog to enable USB storage.
     */
    private synchronized Notification getUsbStorageNotification() {
        Resources r = Resources.getSystem();
        CharSequence title =
                r.getText(com.android.internal.R.string.usb_storage_notification_title);
        CharSequence message =
                r.getText(com.android.internal.R.string.usb_storage_notification_message);

        if (mUsbStorageNotification == null) {
            mUsbStorageNotification = new Notification();
            mUsbStorageNotification.icon = com.android.internal.R.drawable.stat_sys_data_usb;
            mUsbStorageNotification.when = 0;
            mUsbStorageNotification.flags = Notification.FLAG_AUTO_CANCEL;
            mUsbStorageNotification.defaults |= Notification.DEFAULT_SOUND;
        }

        mUsbStorageNotification.tickerText = title;
        mUsbStorageNotification.setLatestEventInfo(mContext, title, message,
                getUsbStorageDialogIntent());

        return mUsbStorageNotification;
    }
    
    /**
     * Creates a pending intent to start the USB storage activity.
     * 
     * @return A {@link PendingIntent} that start the USB storage activity.
     */
    private PendingIntent getUsbStorageDialogIntent() {
        Intent intent = new Intent();
        intent.setClass(mContext, com.android.internal.app.UsbStorageActivity.class);
        return PendingIntent.getActivity(mContext, 0, intent, 0);
    }
}

