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
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.net.Uri;
import android.os.IMountService;
import android.os.Environment;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UEventObserver;
import android.text.TextUtils;
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

    private boolean mPlaySounds;

    private boolean mMounted;

    private boolean mAutoStartUms;

    /**
     * Constructs a new MountService instance
     * 
     * @param context  Binder context for this service
     */
    public MountService(Context context) {
        mContext = context;

        // Register a BOOT_COMPLETED handler so that we can start
        // MountListener. We defer the startup so that we don't
        // start processing events before we ought-to
        mContext.registerReceiver(mBroadcastReceiver,
                new IntentFilter(Intent.ACTION_BOOT_COMPLETED), null, null);

        mListener =  new MountListener(this);       
        mShowSafeUnmountNotificationWhenUnmounted = false;

        mPlaySounds = SystemProperties.get("persist.service.mount.playsnd", "1").equals("1");

        mAutoStartUms = SystemProperties.get("persist.service.mount.umsauto", "0").equals("1");
    }

    BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
                Thread thread = new Thread(mListener, MountListener.class.getName());
                thread.start();
            }
        }
    };

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

        // Set a flag so that when we get the unmounted event, we know
        // to display the notification
        mShowSafeUnmountNotificationWhenUnmounted = true;

        // tell mountd to unmount the media
        mListener.ejectMedia(mountPath);
    }

    /**
     * Attempt to format external media
     */
    public void formatMedia(String formatPath) throws RemoteException {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.MOUNT_FORMAT_FILESYSTEMS) 
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires MOUNT_FORMAT_FILESYSTEMS permission");
        }

        mListener.formatMedia(formatPath);
    }

    /**
     * Returns true if we're playing media notification sounds.
     */
    public boolean getPlayNotificationSounds() {
        return mPlaySounds;
    }

    /**
     * Set whether or not we're playing media notification sounds.
     */
    public void setPlayNotificationSounds(boolean enabled) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_SETTINGS) 
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires WRITE_SETTINGS permission");
        }
        mPlaySounds = enabled;
        SystemProperties.set("persist.service.mount.playsnd", (enabled ? "1" : "0"));
    }

    /**
     * Returns true if we auto-start UMS on cable insertion.
     */
    public boolean getAutoStartUms() {
        return mAutoStartUms;
    }

    /**
     * Set whether or not we're playing media notification sounds.
     */
    public void setAutoStartUms(boolean enabled) {
        if (mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.WRITE_SETTINGS) 
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires WRITE_SETTINGS permission");
        }
        mAutoStartUms = enabled;
        SystemProperties.set("persist.service.mount.umsauto", (enabled ? "1" : "0"));
    }

    /**
     * Update the state of the USB mass storage notification
     */
    void updateUsbMassStorageNotification(boolean suppressIfConnected, boolean sound) {

        try {

            if (getMassStorageConnected() && !suppressIfConnected) {
                Intent intent = new Intent();
                intent.setClass(mContext, com.android.internal.app.UsbStorageActivity.class);
                PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);
                setUsbStorageNotification(
                        com.android.internal.R.string.usb_storage_notification_title,
                        com.android.internal.R.string.usb_storage_notification_message,
                        com.android.internal.R.drawable.stat_sys_data_usb,
                        sound, true, pi);
            } else {
                setUsbStorageNotification(0, 0, 0, false, false, null);
            }
        } catch (RemoteException e) {
            // Nothing to do
        }
    }

    void handlePossibleExplicitUnmountBroadcast(String path) {
        if (mMounted) {
            mMounted = false;
            Intent intent = new Intent(Intent.ACTION_MEDIA_UNMOUNTED, 
                    Uri.parse("file://" + path));
            mContext.sendBroadcast(intent);
        }
    }

    /**
     * Broadcasts the USB mass storage connected event to all clients.
     */
    void notifyUmsConnected() {
        String storageState = Environment.getExternalStorageState();
        if (!storageState.equals(Environment.MEDIA_REMOVED) &&
            !storageState.equals(Environment.MEDIA_BAD_REMOVAL) &&
            !storageState.equals(Environment.MEDIA_CHECKING)) {

            if (mAutoStartUms) {
                try {
                    setMassStorageEnabled(true);
                } catch (RemoteException e) {
                }
            } else {
                updateUsbMassStorageNotification(false, true);
            }
        }

        Intent intent = new Intent(Intent.ACTION_UMS_CONNECTED);
        mContext.sendBroadcast(intent);
    }

    /**
     * Broadcasts the USB mass storage disconnected event to all clients.
     */
    void notifyUmsDisconnected() {
        updateUsbMassStorageNotification(false, false);
        Intent intent = new Intent(Intent.ACTION_UMS_DISCONNECTED);
        mContext.sendBroadcast(intent);
    }

    /**
     * Broadcasts the media removed event to all clients.
     */
    void notifyMediaRemoved(String path) {
        updateUsbMassStorageNotification(true, false);

        setMediaStorageNotification(
                com.android.internal.R.string.ext_media_nomedia_notification_title,
                com.android.internal.R.string.ext_media_nomedia_notification_message,
                com.android.internal.R.drawable.stat_sys_no_sim,
                true, false, null);
        handlePossibleExplicitUnmountBroadcast(path);

        Intent intent = new Intent(Intent.ACTION_MEDIA_REMOVED, 
                Uri.parse("file://" + path));
        mContext.sendBroadcast(intent);
    }

    /**
     * Broadcasts the media unmounted event to all clients.
     */
    void notifyMediaUnmounted(String path) {
        if (mShowSafeUnmountNotificationWhenUnmounted) {
            setMediaStorageNotification(
                    com.android.internal.R.string.ext_media_safe_unmount_notification_title,
                    com.android.internal.R.string.ext_media_safe_unmount_notification_message,
                    com.android.internal.R.drawable.stat_notify_sim_toolkit,
                    true, true, null);
            mShowSafeUnmountNotificationWhenUnmounted = false;
        } else {
            setMediaStorageNotification(0, 0, 0, false, false, null);
        }
        updateUsbMassStorageNotification(false, false);

        Intent intent = new Intent(Intent.ACTION_MEDIA_UNMOUNTED, 
                Uri.parse("file://" + path));
        mContext.sendBroadcast(intent);
    }

    /**
     * Broadcasts the media checking event to all clients.
     */
    void notifyMediaChecking(String path) {
        setMediaStorageNotification(
                com.android.internal.R.string.ext_media_checking_notification_title,
                com.android.internal.R.string.ext_media_checking_notification_message,
                com.android.internal.R.drawable.stat_notify_sim_toolkit,
                true, false, null);

        updateUsbMassStorageNotification(true, false);
        Intent intent = new Intent(Intent.ACTION_MEDIA_CHECKING, 
                Uri.parse("file://" + path));
        mContext.sendBroadcast(intent);
    }

    /**
     * Broadcasts the media nofs event to all clients.
     */
    void notifyMediaNoFs(String path) {
        
        Intent intent = new Intent();
        intent.setClass(mContext, com.android.internal.app.ExternalMediaFormatActivity.class);
        PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);

        setMediaStorageNotification(com.android.internal.R.string.ext_media_nofs_notification_title,
                                    com.android.internal.R.string.ext_media_nofs_notification_message,
                                    com.android.internal.R.drawable.stat_sys_no_sim,
                                    true, false, pi);
        updateUsbMassStorageNotification(false, false);
        intent = new Intent(Intent.ACTION_MEDIA_NOFS, 
                Uri.parse("file://" + path));
        mContext.sendBroadcast(intent);
    }

    /**
     * Broadcasts the media mounted event to all clients.
     */
    void notifyMediaMounted(String path, boolean readOnly) {
        setMediaStorageNotification(0, 0, 0, false, false, null);
        updateUsbMassStorageNotification(false, false);
        Intent intent = new Intent(Intent.ACTION_MEDIA_MOUNTED, 
                Uri.parse("file://" + path));
        intent.putExtra("read-only", readOnly);
        mMounted = true;
        mContext.sendBroadcast(intent);
    }

    /**
     * Broadcasts the media shared event to all clients.
     */
    void notifyMediaShared(String path) {
        Intent intent = new Intent();
        intent.setClass(mContext, com.android.internal.app.UsbStorageStopActivity.class);
        PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);
        setUsbStorageNotification(com.android.internal.R.string.usb_storage_stop_notification_title,
                                  com.android.internal.R.string.usb_storage_stop_notification_message,
                                  com.android.internal.R.drawable.stat_sys_warning,
                                  false, true, pi);
        handlePossibleExplicitUnmountBroadcast(path);
        intent = new Intent(Intent.ACTION_MEDIA_SHARED, 
                Uri.parse("file://" + path));
        mContext.sendBroadcast(intent);
    }

    /**
     * Broadcasts the media bad removal event to all clients.
     */
    void notifyMediaBadRemoval(String path) {
        updateUsbMassStorageNotification(true, false);
        setMediaStorageNotification(com.android.internal.R.string.ext_media_badremoval_notification_title,
                                    com.android.internal.R.string.ext_media_badremoval_notification_message,
                                    com.android.internal.R.drawable.stat_sys_warning,
                                    true, true, null);

        handlePossibleExplicitUnmountBroadcast(path);
        Intent intent = new Intent(Intent.ACTION_MEDIA_BAD_REMOVAL, 
                Uri.parse("file://" + path));
        mContext.sendBroadcast(intent);

        intent = new Intent(Intent.ACTION_MEDIA_REMOVED, 
                Uri.parse("file://" + path));
        mContext.sendBroadcast(intent);
    }

    /**
     * Broadcasts the media unmountable event to all clients.
     */
    void notifyMediaUnmountable(String path) {
        Intent intent = new Intent();
        intent.setClass(mContext, com.android.internal.app.ExternalMediaFormatActivity.class);
        PendingIntent pi = PendingIntent.getActivity(mContext, 0, intent, 0);

        setMediaStorageNotification(com.android.internal.R.string.ext_media_unmountable_notification_title,
                                    com.android.internal.R.string.ext_media_unmountable_notification_message,
                                    com.android.internal.R.drawable.stat_sys_no_sim,
                                    true, false, pi); 
        updateUsbMassStorageNotification(false, false);

        handlePossibleExplicitUnmountBroadcast(path);

        intent = new Intent(Intent.ACTION_MEDIA_UNMOUNTABLE, 
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

            if (sound && mPlaySounds) {
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

            if (mPlaySounds) {
                mMediaStorageNotification.defaults |= Notification.DEFAULT_SOUND;
            } else {
                mMediaStorageNotification.defaults &= ~Notification.DEFAULT_SOUND;
            }

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

