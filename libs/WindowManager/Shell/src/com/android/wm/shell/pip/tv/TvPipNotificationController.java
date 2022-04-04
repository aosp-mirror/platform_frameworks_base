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

package com.android.wm.shell.pip.tv;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.os.Handler;
import android.text.TextUtils;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.R;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.Objects;

/**
 * A notification that informs users that PIP is running and also provides PIP controls.
 * <p>Once it's created, it will manage the PIP notification UI by itself except for handling
 * configuration changes.
 */
public class TvPipNotificationController {
    private static final String TAG = "TvPipNotification";
    private static final boolean DEBUG = TvPipController.DEBUG;

    // Referenced in com.android.systemui.util.NotificationChannels.
    public static final String NOTIFICATION_CHANNEL = "TVPIP";
    private static final String NOTIFICATION_TAG = "TvPip";
    private static final String SYSTEMUI_PERMISSION = "com.android.systemui.permission.SELF";

    private static final String ACTION_SHOW_PIP_MENU =
            "com.android.wm.shell.pip.tv.notification.action.SHOW_PIP_MENU";
    private static final String ACTION_CLOSE_PIP =
            "com.android.wm.shell.pip.tv.notification.action.CLOSE_PIP";
    private static final String ACTION_MOVE_PIP =
            "com.android.wm.shell.pip.tv.notification.action.MOVE_PIP";
    private static final String ACTION_TOGGLE_EXPANDED_PIP =
            "com.android.wm.shell.pip.tv.notification.action.TOGGLE_EXPANDED_PIP";

    private final Context mContext;
    private final PackageManager mPackageManager;
    private final NotificationManager mNotificationManager;
    private final Notification.Builder mNotificationBuilder;
    private final ActionBroadcastReceiver mActionBroadcastReceiver;
    private final Handler mMainHandler;
    private Delegate mDelegate;

    private String mDefaultTitle;

    /** Package name for the application that owns PiP window. */
    private String mPackageName;
    private boolean mNotified;
    private String mMediaTitle;
    private Bitmap mArt;

    public TvPipNotificationController(Context context, PipMediaController pipMediaController,
            Handler mainHandler) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mNotificationManager = context.getSystemService(NotificationManager.class);
        mMainHandler = mainHandler;

        mNotificationBuilder = new Notification.Builder(context, NOTIFICATION_CHANNEL)
                .setLocalOnly(true)
                .setOngoing(false)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setShowWhen(true)
                .setSmallIcon(R.drawable.pip_icon)
                .extend(new Notification.TvExtender()
                        .setContentIntent(createPendingIntent(context, ACTION_SHOW_PIP_MENU))
                        .setDeleteIntent(createPendingIntent(context, ACTION_CLOSE_PIP)));

        mActionBroadcastReceiver = new ActionBroadcastReceiver();

        pipMediaController.addMetadataListener(this::onMediaMetadataChanged);

        onConfigurationChanged(context);
    }

    void setDelegate(Delegate delegate) {
        if (DEBUG) {
            ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: setDelegate(), delegate=%s", TAG, delegate);
        }
        if (mDelegate != null) {
            throw new IllegalStateException(
                    "The delegate has already been set and should not change.");
        }
        if (delegate == null) {
            throw new IllegalArgumentException("The delegate must not be null.");
        }

        mDelegate = delegate;
    }

    void show(String packageName) {
        if (mDelegate == null) {
            throw new IllegalStateException("Delegate is not set.");
        }

        mPackageName = packageName;
        update();
        mActionBroadcastReceiver.register();
    }

    void dismiss() {
        mNotificationManager.cancel(NOTIFICATION_TAG, SystemMessage.NOTE_TV_PIP);
        mNotified = false;
        mPackageName = null;
        mActionBroadcastReceiver.unregister();
    }

    private void onMediaMetadataChanged(MediaMetadata metadata) {
        if (updateMediaControllerMetadata(metadata) && mNotified) {
            // update notification
            update();
        }
    }

    /**
     * Called by {@link PipController} when the configuration is changed.
     */
    void onConfigurationChanged(Context context) {
        mDefaultTitle = context.getResources().getString(R.string.pip_notification_unknown_title);
        if (mNotified) {
            // Update the notification.
            update();
        }
    }

    private void update() {
        mNotified = true;
        mNotificationBuilder
                .setWhen(System.currentTimeMillis())
                .setContentTitle(getNotificationTitle());
        if (mArt != null) {
            mNotificationBuilder.setStyle(new Notification.BigPictureStyle()
                    .bigPicture(mArt));
        } else {
            mNotificationBuilder.setStyle(null);
        }
        mNotificationManager.notify(NOTIFICATION_TAG, SystemMessage.NOTE_TV_PIP,
                mNotificationBuilder.build());
    }

    private boolean updateMediaControllerMetadata(MediaMetadata metadata) {
        String title = null;
        Bitmap art = null;
        if (metadata != null) {
            title = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (TextUtils.isEmpty(title)) {
                title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            }
            art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) {
                art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
        }

        if (TextUtils.equals(title, mMediaTitle) && Objects.equals(art, mArt)) {
            return false;
        }

        mMediaTitle = title;
        mArt = art;

        return true;
    }


    private String getNotificationTitle() {
        if (!TextUtils.isEmpty(mMediaTitle)) {
            return mMediaTitle;
        }

        final String applicationTitle = getApplicationLabel(mPackageName);
        if (!TextUtils.isEmpty(applicationTitle)) {
            return applicationTitle;
        }

        return mDefaultTitle;
    }

    private String getApplicationLabel(String packageName) {
        try {
            final ApplicationInfo appInfo = mPackageManager.getApplicationInfo(packageName, 0);
            return mPackageManager.getApplicationLabel(appInfo).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private static PendingIntent createPendingIntent(Context context, String action) {
        return PendingIntent.getBroadcast(context, 0,
                new Intent(action).setPackage(context.getPackageName()),
                PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private class ActionBroadcastReceiver extends BroadcastReceiver {
        final IntentFilter mIntentFilter;
        {
            mIntentFilter = new IntentFilter();
            mIntentFilter.addAction(ACTION_CLOSE_PIP);
            mIntentFilter.addAction(ACTION_SHOW_PIP_MENU);
            mIntentFilter.addAction(ACTION_MOVE_PIP);
            mIntentFilter.addAction(ACTION_TOGGLE_EXPANDED_PIP);
        }
        boolean mRegistered = false;

        void register() {
            if (mRegistered) return;

            mContext.registerReceiverForAllUsers(this, mIntentFilter, SYSTEMUI_PERMISSION,
                    mMainHandler);
            mRegistered = true;
        }

        void unregister() {
            if (!mRegistered) return;

            mContext.unregisterReceiver(this);
            mRegistered = false;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (DEBUG) {
                ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                        "%s: on(Broadcast)Receive(), action=%s", TAG, action);
            }

            if (ACTION_SHOW_PIP_MENU.equals(action)) {
                mDelegate.showPictureInPictureMenu();
            } else if (ACTION_CLOSE_PIP.equals(action)) {
                mDelegate.closePip();
            } else if (ACTION_MOVE_PIP.equals(action)) {
                mDelegate.enterPipMovementMenu();
            } else if (ACTION_TOGGLE_EXPANDED_PIP.equals(action)) {
                mDelegate.togglePipExpansion();
            }
        }
    }

    interface Delegate {
        void showPictureInPictureMenu();
        void closePip();
        void enterPipMovementMenu();
        void togglePipExpansion();
    }
}
