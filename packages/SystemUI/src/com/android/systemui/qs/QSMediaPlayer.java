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

import static com.android.systemui.util.SysuiLifecycle.viewAttachLifecycle;

import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.media.MediaDescription;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.android.settingslib.media.LocalMediaManager;
import com.android.systemui.R;
import com.android.systemui.media.MediaControlPanel;
import com.android.systemui.media.SeekBarObserver;
import com.android.systemui.media.SeekBarViewModel;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.util.concurrent.Executor;

/**
 * Single media player for carousel in QSPanel
 */
public class QSMediaPlayer extends MediaControlPanel {

    private static final String TAG = "QSMediaPlayer";

    // Button IDs for QS controls
    static final int[] QS_ACTION_IDS = {
            R.id.action0,
            R.id.action1,
            R.id.action2,
            R.id.action3,
            R.id.action4
    };

    private final QSPanel mParent;
    private final Executor mForegroundExecutor;
    private final DelayableExecutor mBackgroundExecutor;
    private final SeekBarViewModel mSeekBarViewModel;
    private final SeekBarObserver mSeekBarObserver;
    private String mPackageName;

    /**
     * Initialize quick shade version of player
     * @param context
     * @param parent
     * @param routeManager Provides information about device
     * @param foregroundExecutor
     * @param backgroundExecutor
     */
    public QSMediaPlayer(Context context, ViewGroup parent, LocalMediaManager routeManager,
            Executor foregroundExecutor, DelayableExecutor backgroundExecutor) {
        super(context, parent, routeManager, R.layout.qs_media_panel, QS_ACTION_IDS,
                foregroundExecutor, backgroundExecutor);
        mParent = (QSPanel) parent;
        mForegroundExecutor = foregroundExecutor;
        mBackgroundExecutor = backgroundExecutor;
        mSeekBarViewModel = new SeekBarViewModel(backgroundExecutor);
        mSeekBarObserver = new SeekBarObserver(getView());
        // Can't use the viewAttachLifecycle of media player because remove/add is used to adjust
        // priority of players. As soon as it is removed, the lifecycle will end and the seek bar
        // will stop updating. So, use the lifecycle of the parent instead.
        mSeekBarViewModel.getProgress().observe(viewAttachLifecycle(parent), mSeekBarObserver);
        SeekBar bar = getView().findViewById(R.id.media_progress_bar);
        bar.setOnSeekBarChangeListener(mSeekBarViewModel.getSeekBarListener());
        bar.setOnTouchListener(mSeekBarViewModel.getSeekBarTouchListener());
    }

    /**
     * Add a media panel view based on a media description. Used for resumption
     * @param description
     * @param iconColor
     * @param bgColor
     * @param contentIntent
     * @param pkgName
     */
    public void setMediaSession(MediaSession.Token token, MediaDescription description,
            int iconColor, int bgColor, PendingIntent contentIntent, String pkgName) {
        mPackageName = pkgName;
        PackageManager pm = getContext().getPackageManager();
        Drawable icon = null;
        CharSequence appName = pkgName.substring(pkgName.lastIndexOf("."));
        try {
            icon = pm.getApplicationIcon(pkgName);
            appName = pm.getApplicationLabel(pm.getApplicationInfo(pkgName, 0));
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Error getting package information", e);
        }

        // Set what we can normally
        super.setMediaSession(token, icon, iconColor, bgColor, contentIntent, appName.toString(),
                null);

        // Then add info from MediaDescription
        ImageView albumView = mMediaNotifView.findViewById(R.id.album_art);
        if (albumView != null) {
            // Resize art in a background thread
            mBackgroundExecutor.execute(() -> processAlbumArt(description, albumView));
        }

        // Song name
        TextView titleText = mMediaNotifView.findViewById(R.id.header_title);
        CharSequence songName = description.getTitle();
        titleText.setText(songName);
        titleText.setTextColor(iconColor);

        // Artist name (not in mini player)
        TextView artistText = mMediaNotifView.findViewById(R.id.header_artist);
        if (artistText != null) {
            CharSequence artistName = description.getSubtitle();
            artistText.setText(artistName);
            artistText.setTextColor(iconColor);
        }

        initLongPressMenu(iconColor);

        // Set buttons to resume state
        resetButtons();
    }

    /**
     * Update media panel view for the given media session
     * @param token token for this media session
     * @param icon app notification icon
     * @param iconColor foreground color (for text, icons)
     * @param bgColor background color
     * @param actionsContainer a LinearLayout containing the media action buttons
     * @param contentIntent Intent to send when user taps on player
     * @param appName Application title
     * @param key original notification's key
     */
    public void setMediaSession(MediaSession.Token token, Drawable icon, int iconColor,
            int bgColor, View actionsContainer, PendingIntent contentIntent, String appName,
            String key) {

        super.setMediaSession(token, icon, iconColor, bgColor, contentIntent, appName, key);

        // Media controls
        if (actionsContainer != null) {
            LinearLayout parentActionsLayout = (LinearLayout) actionsContainer;
            int i = 0;
            for (; i < parentActionsLayout.getChildCount() && i < QS_ACTION_IDS.length; i++) {
                ImageButton thisBtn = mMediaNotifView.findViewById(QS_ACTION_IDS[i]);
                ImageButton thatBtn = parentActionsLayout.findViewById(NOTIF_ACTION_IDS[i]);
                if (thatBtn == null || thatBtn.getDrawable() == null
                        || thatBtn.getVisibility() != View.VISIBLE) {
                    thisBtn.setVisibility(View.GONE);
                    continue;
                }

                Drawable thatIcon = thatBtn.getDrawable();
                thisBtn.setImageDrawable(thatIcon.mutate());
                thisBtn.setVisibility(View.VISIBLE);
                thisBtn.setOnClickListener(v -> {
                    Log.d(TAG, "clicking on other button");
                    thatBtn.performClick();
                });
            }

            // Hide any unused buttons
            for (; i < QS_ACTION_IDS.length; i++) {
                ImageButton thisBtn = mMediaNotifView.findViewById(QS_ACTION_IDS[i]);
                thisBtn.setVisibility(View.GONE);
            }
        }

        // Seek Bar
        final MediaController controller = new MediaController(getContext(), token);
        mBackgroundExecutor.execute(
                () -> mSeekBarViewModel.updateController(controller, iconColor));

        initLongPressMenu(iconColor);
    }

    private void initLongPressMenu(int iconColor) {
        // Set up long press menu
        View guts = mMediaNotifView.findViewById(R.id.media_guts);
        View options = mMediaNotifView.findViewById(R.id.qs_media_controls_options);
        options.setMinimumHeight(guts.getHeight());

        View clearView = options.findViewById(R.id.remove);
        clearView.setOnClickListener(b -> {
            removePlayer();
        });
        ImageView removeIcon = options.findViewById(R.id.remove_icon);
        removeIcon.setImageTintList(ColorStateList.valueOf(iconColor));
        TextView removeText = options.findViewById(R.id.remove_text);
        removeText.setTextColor(iconColor);

        TextView cancelView = options.findViewById(R.id.cancel);
        cancelView.setTextColor(iconColor);
        cancelView.setOnClickListener(b -> {
            options.setVisibility(View.GONE);
            guts.setVisibility(View.VISIBLE);
        });
        // ... but don't enable it yet, and make sure is reset when the session is updated
        mMediaNotifView.setOnLongClickListener(null);
        options.setVisibility(View.GONE);
        guts.setVisibility(View.VISIBLE);
    }

    @Override
    protected void resetButtons() {
        super.resetButtons();
        mSeekBarViewModel.clearController();
        View guts = mMediaNotifView.findViewById(R.id.media_guts);
        View options = mMediaNotifView.findViewById(R.id.qs_media_controls_options);

        mMediaNotifView.setOnLongClickListener(v -> {
            // Replace player view with close/cancel view
            guts.setVisibility(View.GONE);
            options.setVisibility(View.VISIBLE);
            return true; // consumed click
        });
    }

    /**
     * Sets the listening state of the player.
     *
     * Should be set to true when the QS panel is open. Otherwise, false. This is a signal to avoid
     * unnecessary work when the QS panel is closed.
     *
     * @param listening True when player should be active. Otherwise, false.
     */
    public void setListening(boolean listening) {
        mSeekBarViewModel.setListening(listening);
    }

    @Override
    public void removePlayer() {
        Log.d(TAG, "removing player from parent: " + mParent);
        // Ensure this happens on the main thread (could happen in QSMediaBrowser callback)
        mForegroundExecutor.execute(() -> mParent.removeMediaPlayer(QSMediaPlayer.this));
    }

    @Override
    public String getMediaPlayerPackage() {
        if (getController() == null) {
            return mPackageName;
        }
        return super.getMediaPlayerPackage();
    }
}
