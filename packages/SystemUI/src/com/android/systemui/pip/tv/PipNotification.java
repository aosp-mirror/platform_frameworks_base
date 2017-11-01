/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.pip.tv;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ParceledListSlice;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import com.android.systemui.util.NotificationChannels;
import com.android.systemui.R;
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage;

/**
 * A notification that informs users that PIP is running and also provides PIP controls.
 * <p>Once it's created, it will manage the PIP notification UI by itself except for handling
 * configuration changes.
 */
public class PipNotification {
    private static final String TAG = "PipNotification";
    private static final String NOTIFICATION_TAG = PipNotification.class.getName();
    private static final boolean DEBUG = PipManager.DEBUG;

    private static final String ACTION_MENU = "PipNotification.menu";
    private static final String ACTION_CLOSE = "PipNotification.close";

    private final PipManager mPipManager = PipManager.getInstance();

    private final NotificationManager mNotificationManager;
    private final Notification.Builder mNotificationBuilder;

    private MediaController mMediaController;
    private String mDefaultTitle;
    private int mDefaultIconResId;

    private boolean mNotified;
    private String mTitle;
    private Bitmap mArt;

    private PipManager.Listener mPipListener = new PipManager.Listener() {
        @Override
        public void onPipEntered() {
            updateMediaControllerMetadata();
            notifyPipNotification();
        }

        @Override
        public void onPipActivityClosed() {
            dismissPipNotification();
        }

        @Override
        public void onShowPipMenu() {
            // no-op.
        }

        @Override
        public void onPipMenuActionsChanged(ParceledListSlice actions) {
            // no-op.
        }

        @Override
        public void onMoveToFullscreen() {
            dismissPipNotification();
        }

        @Override
        public void onPipResizeAboutToStart() {
            // no-op.
        }
    };

    private MediaController.Callback mMediaControllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (updateMediaControllerMetadata() && mNotified) {
                // update notification
                notifyPipNotification();
            }
        }
    };

    private final PipManager.MediaListener mPipMediaListener = new PipManager.MediaListener() {
        @Override
        public void onMediaControllerChanged() {
            MediaController newController = mPipManager.getMediaController();
            if (mMediaController == newController) {
                return;
            }
            if (mMediaController != null) {
                mMediaController.unregisterCallback(mMediaControllerCallback);
            }
            mMediaController = newController;
            if (mMediaController != null) {
                mMediaController.registerCallback(mMediaControllerCallback);
            }
            if (updateMediaControllerMetadata() && mNotified) {
                // update notification
                notifyPipNotification();
            }
        }
    };

    private final BroadcastReceiver mEventReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (DEBUG) {
                Log.d(TAG, "Received " + intent.getAction() + " from the notification UI");
            }
            switch (intent.getAction()) {
                case ACTION_MENU:
                    mPipManager.showPictureInPictureMenu();
                    break;
                case ACTION_CLOSE:
                    mPipManager.closePip();
                    break;
            }
        }
    };

    public PipNotification(Context context) {
        mNotificationManager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);

        mNotificationBuilder = new Notification.Builder(context, NotificationChannels.TVPIP)
                .setLocalOnly(true)
                .setOngoing(false)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .extend(new Notification.TvExtender()
                        .setContentIntent(createPendingIntent(context, ACTION_MENU))
                        .setDeleteIntent(createPendingIntent(context, ACTION_CLOSE)));

        mPipManager.addListener(mPipListener);
        mPipManager.addMediaListener(mPipMediaListener);

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_MENU);
        intentFilter.addAction(ACTION_CLOSE);
        context.registerReceiver(mEventReceiver, intentFilter);

        onConfigurationChanged(context);
    }

    /**
     * Called by {@link PipManager} when the configuration is changed.
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
                .setContentTitle(!TextUtils.isEmpty(mTitle) ? mTitle : mDefaultTitle);
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

    private boolean updateMediaControllerMetadata() {
        String title = null;
        Bitmap art = null;
        if (mPipManager.getMediaController() != null) {
            MediaMetadata metadata = mPipManager.getMediaController().getMetadata();
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
        }
        if (!TextUtils.equals(title, mTitle) || art != mArt) {
            mTitle = title;
            mArt = art;
            return true;
        }
        return false;
    }

    private static PendingIntent createPendingIntent(Context context, String action) {
        return PendingIntent.getBroadcast(context, 0,
                new Intent(action), PendingIntent.FLAG_CANCEL_CURRENT);
    }
}
