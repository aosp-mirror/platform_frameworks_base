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

package com.android.keyguard;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.util.Log;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;
import androidx.palette.graphics.Palette;

import com.android.internal.util.ContrastColorUtil;
import com.android.systemui.R;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.statusbar.notification.MediaNotificationProcessor;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.stack.MediaHeaderView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Media controls to display on the lockscreen
 *
 * TODO: Should extend MediaControlPanel to avoid code duplication.
 * Unfortunately, it isn't currently possible because the ActivatableNotificationView background is
 * different.
 */
@Singleton
public class KeyguardMediaPlayer {

    private static final String TAG = "KeyguardMediaPlayer";
    // Buttons that can be displayed on lock screen media controls.
    private static final int[] ACTION_IDS = {R.id.action0, R.id.action1, R.id.action2};

    private final Context mContext;
    private final Executor mBackgroundExecutor;
    private float mAlbumArtRadius;
    private int mAlbumArtSize;
    private View mMediaNotifView;

    @Inject
    public KeyguardMediaPlayer(Context context, @Background Executor backgroundExecutor) {
        mContext = context;
        mBackgroundExecutor = backgroundExecutor;
        loadDimens();
    }

    /** Binds media controls to a view hierarchy. */
    public void bindView(View v) {
        if (mMediaNotifView != null) {
            throw new IllegalStateException("cannot bind views, already bound");
        }
        mMediaNotifView = v;
        loadDimens();
    }

    /** Unbinds media controls. */
    public void unbindView() {
        if (mMediaNotifView == null) {
            throw new IllegalStateException("cannot unbind views, nothing bound");
        }
        mMediaNotifView = null;
    }

    /** Clear the media controls because there isn't an active session. */
    public void clearControls() {
        if (mMediaNotifView != null) {
            mMediaNotifView.setVisibility(View.GONE);
        }
    }

    /**
     * Update the media player
     *
     * TODO: consider registering a MediaLister instead of exposing this update method.
     *
     * @param entry Media notification that will be used to update the player
     * @param appIcon Icon for the app playing the media
     * @param mediaMetadata Media metadata that will be used to update the player
     */
    public void updateControls(NotificationEntry entry, Icon appIcon,
            MediaMetadata mediaMetadata) {
        if (mMediaNotifView == null) {
            throw new IllegalStateException("cannot update controls, views not bound");
        }
        if (mediaMetadata == null) {
            throw new IllegalArgumentException("media metadata was null");
        }
        mMediaNotifView.setVisibility(View.VISIBLE);

        Notification notif = entry.getSbn().getNotification();

        // Computed foreground and background color based on album art.
        int fgColor = notif.color;
        int bgColor = entry.getRow() == null ? -1 : entry.getRow().getCurrentBackgroundTint();
        Bitmap artworkBitmap = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (artworkBitmap == null) {
            artworkBitmap = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        }
        if (artworkBitmap != null) {
            // If we have art, get colors from that
            Palette p = MediaNotificationProcessor.generateArtworkPaletteBuilder(artworkBitmap)
                    .generate();
            Palette.Swatch swatch = MediaNotificationProcessor.findBackgroundSwatch(p);
            bgColor = swatch.getRgb();
            fgColor = MediaNotificationProcessor.selectForegroundColor(bgColor, p);
        }
        // Make sure colors will be legible
        boolean isDark = !ContrastColorUtil.isColorLight(bgColor);
        fgColor = ContrastColorUtil.resolveContrastColor(mContext, fgColor, bgColor,
                isDark);
        fgColor = ContrastColorUtil.ensureTextContrast(fgColor, bgColor, isDark);

        // Album art
        ImageView albumView = mMediaNotifView.findViewById(R.id.album_art);
        if (albumView != null) {
            // Resize art in a background thread
            final Bitmap bm = artworkBitmap;
            mBackgroundExecutor.execute(() -> processAlbumArt(bm, albumView));
        }

        // App icon
        ImageView appIconView = mMediaNotifView.findViewById(R.id.icon);
        if (appIconView != null) {
            Drawable iconDrawable = appIcon.loadDrawable(mContext);
            iconDrawable.setTint(fgColor);
            appIconView.setImageDrawable(iconDrawable);
        }

        // App name
        TextView appName = mMediaNotifView.findViewById(R.id.app_name);
        if (appName != null) {
            Notification.Builder builder = Notification.Builder.recoverBuilder(mContext, notif);
            String appNameString = builder.loadHeaderAppName();
            appName.setText(appNameString);
            appName.setTextColor(fgColor);
        }

        // Song name
        TextView titleText = mMediaNotifView.findViewById(R.id.header_title);
        if (titleText != null) {
            String songName = mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            titleText.setText(songName);
            titleText.setTextColor(fgColor);
        }

        // Artist name
        TextView artistText = mMediaNotifView.findViewById(R.id.header_artist);
        if (artistText != null) {
            String artistName = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            artistText.setText(artistName);
            artistText.setTextColor(fgColor);
        }

        // Background color
        if (mMediaNotifView instanceof MediaHeaderView) {
            MediaHeaderView head = (MediaHeaderView) mMediaNotifView;
            head.setBackgroundColor(bgColor);
        }

        // Control buttons
        final List<Icon> icons = new ArrayList<>();
        final List<PendingIntent> intents = new ArrayList<>();
        Notification.Action[] actions = notif.actions;
        final int[] actionsToShow = notif.extras.getIntArray(Notification.EXTRA_COMPACT_ACTIONS);

        for (int i = 0; i < ACTION_IDS.length; i++) {
            if (actionsToShow != null && actions != null && i < actionsToShow.length
                    && actionsToShow[i] < actions.length) {
                final int idx = actionsToShow[i];
                icons.add(actions[idx].getIcon());
                intents.add(actions[idx].actionIntent);
            } else {
                icons.add(null);
                intents.add(null);
            }
        }

        Context packageContext = entry.getSbn().getPackageContext(mContext);
        for (int i = 0; i < ACTION_IDS.length; i++) {
            ImageButton button = mMediaNotifView.findViewById(ACTION_IDS[i]);
            if (button == null) {
                continue;
            }
            Icon icon = icons.get(i);
            if (icon == null) {
                button.setVisibility(View.GONE);
            } else {
                button.setVisibility(View.VISIBLE);
                button.setImageDrawable(icon.loadDrawable(packageContext));
                button.setImageTintList(ColorStateList.valueOf(fgColor));
                final PendingIntent intent = intents.get(i);
                if (intent != null) {
                    button.setOnClickListener(v -> {
                        try {
                            intent.send();
                        } catch (PendingIntent.CanceledException e) {
                            Log.d(TAG, "failed to send action intent", e);
                        }
                    });
                }
            }
        }
    }

    /**
     * Process album art for layout
     * @param albumArt bitmap to use for album art
     * @param albumView view to hold the album art
     */
    private void processAlbumArt(Bitmap albumArt, ImageView albumView) {
        RoundedBitmapDrawable roundedDrawable = null;
        if (albumArt != null) {
            Bitmap original = albumArt.copy(Bitmap.Config.ARGB_8888, true);
            Bitmap scaled = Bitmap.createScaledBitmap(original, mAlbumArtSize, mAlbumArtSize,
                    false);
            roundedDrawable = RoundedBitmapDrawableFactory.create(mContext.getResources(), scaled);
            roundedDrawable.setCornerRadius(mAlbumArtRadius);
        } else {
            Log.e(TAG, "No album art available");
        }

        // Now that it's resized, update the UI
        final RoundedBitmapDrawable result = roundedDrawable;
        albumView.post(() -> {
            albumView.setImageDrawable(result);
            albumView.setVisibility(result == null ? View.GONE : View.VISIBLE);
        });
    }

    private void loadDimens() {
        mAlbumArtRadius = mContext.getResources().getDimension(R.dimen.qs_media_corner_radius);
        mAlbumArtSize = (int) mContext.getResources().getDimension(
                    R.dimen.qs_media_album_size);
    }
}
