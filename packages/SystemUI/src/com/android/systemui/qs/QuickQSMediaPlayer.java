/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.qs;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.NotificationMediaManager;

import java.util.List;

/**
 * QQS mini media player
 */
public class QuickQSMediaPlayer implements NotificationMediaManager.MediaListener {

    private static final String TAG = "QQSMediaPlayer";
    private final NotificationMediaManager mMediaManager;

    private Context mContext;
    private LinearLayout mMediaNotifView;
    private MediaSession.Token mToken;
    private MediaController mController;
    private int mForegroundColor;
    private ComponentName mRecvComponent;

    // Button IDs for QS controls
    private static final int[] QQS_ACTION_IDS = {R.id.action0, R.id.action1, R.id.action2};

    // Button IDs used in notifications
    private static final int[] NOTIF_ACTION_IDS = {
            com.android.internal.R.id.action0,
            com.android.internal.R.id.action1,
            com.android.internal.R.id.action2,
            com.android.internal.R.id.action3,
            com.android.internal.R.id.action4
    };

    private MediaController.Callback mSessionCallback = new MediaController.Callback() {
        @Override
        public void onSessionDestroyed() {
            Log.d(TAG, "session destroyed");
            mController.unregisterCallback(mSessionCallback);
            clearControls();
        }
    };

    /**
     *
     * @param context
     * @param parent
     */
    public QuickQSMediaPlayer(Context context, ViewGroup parent, NotificationMediaManager manager) {
        mContext = context;
        LayoutInflater inflater = LayoutInflater.from(mContext);
        mMediaNotifView = (LinearLayout) inflater.inflate(R.layout.qqs_media_panel, parent, false);
        mMediaManager = manager;
    }

    public View getView() {
        return mMediaNotifView;
    }

    /**
     *
     * @param token token for this media session
     * @param icon app notification icon
     * @param iconColor foreground color (for text, icons)
     * @param bgColor background color
     * @param actionsContainer a LinearLayout containing the media action buttons
     * @param actionsToShow indices of which actions to display in the mini player
     *                      (max 3: Notification.MediaStyle.MAX_MEDIA_BUTTONS_IN_COMPACT)
     * @param contentIntent Intent to send when user taps on the view
     */
    public void setMediaSession(MediaSession.Token token, Icon icon, int iconColor, int bgColor,
            View actionsContainer, int[] actionsToShow, PendingIntent contentIntent) {
        mToken = token;
        mForegroundColor = iconColor;

        String oldPackage = "";
        if (mController != null) {
            oldPackage = mController.getPackageName();
        }
        MediaController controller = new MediaController(mContext, token);
        boolean samePlayer = mToken.equals(token) && oldPackage.equals(controller.getPackageName());
        if (mController != null && !samePlayer && !isPlaying(controller)) {
            // Only update if this is a different session and currently playing
            return;
        }
        mController = controller;
        MediaMetadata mMediaMetadata = mController.getMetadata();

        // Try to find a receiver for the media button that matches this app
        PackageManager pm = mContext.getPackageManager();
        Intent it = new Intent(Intent.ACTION_MEDIA_BUTTON);
        List<ResolveInfo> info = pm.queryBroadcastReceiversAsUser(it, 0, mContext.getUser());
        if (info != null) {
            for (ResolveInfo inf : info) {
                if (inf.activityInfo.packageName.equals(mController.getPackageName())) {
                    mRecvComponent = inf.getComponentInfo().getComponentName();
                }
            }
        }
        mController.registerCallback(mSessionCallback);

        if (mMediaMetadata == null) {
            Log.e(TAG, "Media metadata was null");
            return;
        }

        // Action
        mMediaNotifView.setOnClickListener(v -> {
            try {
                contentIntent.send();
                mContext.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
            } catch (PendingIntent.CanceledException e) {
                Log.e(TAG, "Pending intent was canceled: " + e.getMessage());
            }
        });

        mMediaNotifView.setBackgroundTintList(ColorStateList.valueOf(bgColor));

        // App icon
        ImageView appIcon = mMediaNotifView.findViewById(R.id.icon);
        Drawable iconDrawable = icon.loadDrawable(mContext);
        iconDrawable.setTint(mForegroundColor);
        appIcon.setImageDrawable(iconDrawable);

        // Song name
        TextView titleText = mMediaNotifView.findViewById(R.id.header_title);
        String songName = mMediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        titleText.setText(songName);
        titleText.setTextColor(mForegroundColor);

        LinearLayout parentActionsLayout = (LinearLayout) actionsContainer;
        int i = 0;
        if (actionsToShow != null) {
            int maxButtons = Math.min(actionsToShow.length, parentActionsLayout.getChildCount());
            maxButtons = Math.min(maxButtons, QQS_ACTION_IDS.length);
            for (; i < maxButtons; i++) {
                ImageButton thisBtn = mMediaNotifView.findViewById(QQS_ACTION_IDS[i]);
                int thatId = NOTIF_ACTION_IDS[actionsToShow[i]];
                ImageButton thatBtn = parentActionsLayout.findViewById(thatId);
                if (thatBtn == null || thatBtn.getDrawable() == null
                        || thatBtn.getVisibility() != View.VISIBLE) {
                    thisBtn.setVisibility(View.GONE);
                    continue;
                }

                Drawable thatIcon = thatBtn.getDrawable();
                thisBtn.setImageDrawable(thatIcon.mutate());
                thisBtn.setVisibility(View.VISIBLE);
                thisBtn.setOnClickListener(v -> {
                    thatBtn.performClick();
                });
            }
        }

        // Hide any unused buttons
        for (; i < QQS_ACTION_IDS.length; i++) {
            ImageButton thisBtn = mMediaNotifView.findViewById(QQS_ACTION_IDS[i]);
            thisBtn.setVisibility(View.GONE);
        }

        // Ensure is only added once
        mMediaManager.removeCallback(this);
        mMediaManager.addCallback(this);
    }

    public MediaSession.Token getMediaSessionToken() {
        return mToken;
    }

    /**
     * Check whether the media controlled by this player is currently playing
     * @return whether it is playing, or false if no controller information
     */
    public boolean isPlaying(MediaController controller) {
        if (controller == null) {
            return false;
        }

        PlaybackState state = controller.getPlaybackState();
        if (state == null) {
            return false;
        }

        return (state.getState() == PlaybackState.STATE_PLAYING);
    }

    /**
     * Check whether this player has an attached media session.
     * @return whether there is a controller with a current media session.
     */
    public boolean hasMediaSession() {
        return mController != null && mController.getPlaybackState() != null;
    }

    /**
     * Put controls into a resumption state
     */
    public void clearControls() {
        // Hide all the old buttons
        final int[] actionIds = {R.id.action0, R.id.action1, R.id.action2};
        for (int i = 0; i < actionIds.length; i++) {
            ImageButton thisBtn = mMediaNotifView.findViewById(actionIds[i]);
            if (thisBtn != null) {
                thisBtn.setVisibility(View.GONE);
            }
        }

        // Add a restart button
        ImageButton btn = mMediaNotifView.findViewById(actionIds[0]);
        btn.setOnClickListener(v -> {
            Log.d(TAG, "Attempting to restart session");
            // Send a media button event to previously found receiver
            if (mRecvComponent != null) {
                Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
                intent.setComponent(mRecvComponent);
                int keyCode = KeyEvent.KEYCODE_MEDIA_PLAY;
                intent.putExtra(
                        Intent.EXTRA_KEY_EVENT,
                        new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                mContext.sendBroadcast(intent);
            } else {
                Log.d(TAG, "No receiver to restart");
                // If we don't have a receiver, try relaunching the activity instead
                try {
                    mController.getSessionActivity().send();
                } catch (PendingIntent.CanceledException e) {
                    Log.e(TAG, "Pending intent was canceled", e);
                }
            }
        });
        btn.setImageDrawable(mContext.getResources().getDrawable(R.drawable.lb_ic_play));
        btn.setImageTintList(ColorStateList.valueOf(mForegroundColor));
        btn.setVisibility(View.VISIBLE);
    }

    @Override
    public void onMetadataOrStateChanged(MediaMetadata metadata, int state) {
        if (state == PlaybackState.STATE_NONE) {
            Log.d(TAG, "clearing controls because state none");
            clearControls();
            mMediaManager.removeCallback(this);
        }
    }
}
