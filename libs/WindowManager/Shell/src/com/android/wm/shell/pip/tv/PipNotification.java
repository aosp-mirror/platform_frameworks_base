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
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.text.TextUtils;

import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;
import com.android.wm.shell.R;
import com.android.wm.shell.pip.PipMediaController;

import java.util.Objects;

/**
 * A notification that informs users that PIP is running and also provides PIP controls.
 * <p>Once it's created, it will manage the PIP notification UI by itself except for handling
 * configuration changes.
 */
public class PipNotification implements PipController.Listener {
    private static final boolean DEBUG = PipController.DEBUG;
    private static final String TAG = "PipNotification";

    private static final String NOTIFICATION_TAG = PipNotification.class.getSimpleName();
    public static final String NOTIFICATION_CHANNEL_TVPIP = "TPP";

    static final String ACTION_MENU = "PipNotification.menu";
    static final String ACTION_CLOSE = "PipNotification.close";

    private final PackageManager mPackageManager;
    private final NotificationManager mNotificationManager;
    private final Notification.Builder mNotificationBuilder;

    private String mDefaultTitle;
    private int mDefaultIconResId;

    /** Package name for the application that owns PiP window. */
    private String mPackageName;
    private boolean mNotified;
    private String mMediaTitle;
    private Bitmap mArt;

    public PipNotification(Context context, PipMediaController pipMediaController) {
        mPackageManager = context.getPackageManager();
        mNotificationManager = context.getSystemService(NotificationManager.class);

        mNotificationBuilder = new Notification.Builder(context, NOTIFICATION_CHANNEL_TVPIP)
                .setLocalOnly(true)
                .setOngoing(false)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .extend(new Notification.TvExtender()
                        .setContentIntent(createPendingIntent(context, ACTION_MENU))
                        .setDeleteIntent(createPendingIntent(context, ACTION_CLOSE)));

        pipMediaController.addMetadataListener(this::onMediaMetadataChanged);

        onConfigurationChanged(context);
    }

    @Override
    public void onPipEntered(String packageName) {
        mPackageName = packageName;
        notifyPipNotification();
    }

    @Override
    public void onPipActivityClosed() {
        dismissPipNotification();
        mPackageName = null;
    }

    @Override
    public void onShowPipMenu() {
        // no-op.
    }

    @Override
    public void onMoveToFullscreen() {
        dismissPipNotification();
        mPackageName = null;
    }

    @Override
    public void onPipResizeAboutToStart() {
        // no-op.
    }

    private void onMediaMetadataChanged(MediaMetadata metadata) {
        if (updateMediaControllerMetadata(metadata) && mNotified) {
            // update notification
            notifyPipNotification();
        }
    }

    /**
     * Called by {@link PipController} when the configuration is changed.
     */
    void onConfigurationChanged(Context context) {
        Resources res = context.getResources();
        mDefaultTitle = res.getString(R.string.pip_notification_unknown_title);
        mDefaultIconResId = R.drawable.pip_icon;
        if (mNotified) {
            // update notification
            notifyPipNotification();
        }
    }

    private void notifyPipNotification() {
        mNotified = true;
        mNotificationBuilder
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis())
                .setSmallIcon(mDefaultIconResId)
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

    private void dismissPipNotification() {
        mNotified = false;
        mNotificationManager.cancel(NOTIFICATION_TAG, SystemMessage.NOTE_TV_PIP);
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
                new Intent(action), PendingIntent.FLAG_CANCEL_CURRENT);
    }
}
