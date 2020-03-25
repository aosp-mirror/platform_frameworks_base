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
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
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
    private final KeyguardMediaViewModel mViewModel;
    private KeyguardMediaObserver mObserver;

    @Inject
    public KeyguardMediaPlayer(Context context, @Background Executor backgroundExecutor) {
        mContext = context;
        mBackgroundExecutor = backgroundExecutor;
        mViewModel = new KeyguardMediaViewModel(context);
    }

    /** Binds media controls to a view hierarchy. */
    public void bindView(View v) {
        if (mObserver != null) {
            throw new IllegalStateException("cannot bind views, already bound");
        }
        mViewModel.loadDimens();
        mObserver = new KeyguardMediaObserver(v);
        // Control buttons
        for (int i = 0; i < ACTION_IDS.length; i++) {
            ImageButton button = v.findViewById(ACTION_IDS[i]);
            if (button == null) {
                continue;
            }
            final int index = i;
            button.setOnClickListener(unused -> mViewModel.onActionClick(index));
        }
        mViewModel.getKeyguardMedia().observeForever(mObserver);
    }

    /** Unbinds media controls. */
    public void unbindView() {
        if (mObserver == null) {
            throw new IllegalStateException("cannot unbind views, nothing bound");
        }
        mViewModel.getKeyguardMedia().removeObserver(mObserver);
        mObserver = null;
    }

    /** Clear the media controls because there isn't an active session. */
    public void clearControls() {
        mBackgroundExecutor.execute(mViewModel::clearControls);
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
        if (mObserver == null) {
            throw new IllegalStateException("cannot update controls, views not bound");
        }
        if (mediaMetadata == null) {
            Log.d(TAG, "media metadata was null, closing media controls");
            // Note that clearControls() executes on the same background executor, so there
            // shouldn't be an issue with an outdated update running after clear. However, if stale
            // controls are observed then consider removing any enqueued updates.
            clearControls();
            return;
        }
        mBackgroundExecutor.execute(() -> mViewModel.updateControls(entry, appIcon, mediaMetadata));
    }

    /** ViewModel for KeyguardMediaControls. */
    private static final class KeyguardMediaViewModel {

        private final Context mContext;
        private final MutableLiveData<KeyguardMedia> mMedia = new MutableLiveData<>();
        private final Object mActionsLock = new Object();
        private List<PendingIntent> mActions;
        private float mAlbumArtRadius;
        private int mAlbumArtSize;

        KeyguardMediaViewModel(Context context) {
            mContext = context;
            loadDimens();
        }

        /** Close the media player because there isn't an active session. */
        public void clearControls() {
            synchronized (mActionsLock) {
                mActions = null;
            }
            mMedia.postValue(null);
        }

        /** Update the media player with information about the active session. */
        public void updateControls(NotificationEntry entry, Icon appIcon,
                MediaMetadata mediaMetadata) {

            // Foreground and Background colors computed from album art
            Notification notif = entry.getSbn().getNotification();
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
            RoundedBitmapDrawable artwork = null;
            if (artworkBitmap != null) {
                Bitmap original = artworkBitmap.copy(Bitmap.Config.ARGB_8888, true);
                Bitmap scaled = Bitmap.createScaledBitmap(original, mAlbumArtSize, mAlbumArtSize,
                        false);
                artwork = RoundedBitmapDrawableFactory.create(mContext.getResources(), scaled);
                artwork.setCornerRadius(mAlbumArtRadius);
            }

            // App name
            Notification.Builder builder = Notification.Builder.recoverBuilder(mContext, notif);
            String app = builder.loadHeaderAppName();

            // App Icon
            Drawable appIconDrawable = appIcon.loadDrawable(mContext);

            // Song name
            String song = mediaMetadata.getString(MediaMetadata.METADATA_KEY_TITLE);

            // Artist name
            String artist = mediaMetadata.getString(MediaMetadata.METADATA_KEY_ARTIST);

            // Control buttons
            List<Drawable> actionIcons = new ArrayList<>();
            final List<PendingIntent> intents = new ArrayList<>();
            Notification.Action[] actions = notif.actions;
            final int[] actionsToShow = notif.extras.getIntArray(
                    Notification.EXTRA_COMPACT_ACTIONS);

            Context packageContext = entry.getSbn().getPackageContext(mContext);
            for (int i = 0; i < ACTION_IDS.length; i++) {
                if (actionsToShow != null && actions != null && i < actionsToShow.length
                        && actionsToShow[i] < actions.length) {
                    final int idx = actionsToShow[i];
                    actionIcons.add(actions[idx].getIcon().loadDrawable(packageContext));
                    intents.add(actions[idx].actionIntent);
                } else {
                    actionIcons.add(null);
                    intents.add(null);
                }
            }
            synchronized (mActionsLock) {
                mActions = intents;
            }

            KeyguardMedia data = new KeyguardMedia(fgColor, bgColor, app, appIconDrawable, artist,
                    song, artwork, actionIcons);
            mMedia.postValue(data);
        }

        /** Gets state for the lock screen media controls. */
        public LiveData<KeyguardMedia> getKeyguardMedia() {
            return mMedia;
        }

        /**
         * Handle user clicks on media control buttons (actions).
         *
         * @param index position of the button that was clicked.
         */
        public void onActionClick(int index) {
            PendingIntent intent = null;
            // This might block the ui thread to wait for the lock. Currently, however, the
            // lock is held by the bg thread to assign a member, which should be fast. An
            // alternative could be to add the intents to the state and let the observer set
            // the onClick listeners.
            synchronized (mActionsLock) {
                if (mActions != null && index < mActions.size()) {
                    intent = mActions.get(index);
                }
            }
            if (intent != null) {
                try {
                    intent.send();
                } catch (PendingIntent.CanceledException e) {
                    Log.d(TAG, "failed to send action intent", e);
                }
            }
        }

        void loadDimens() {
            mAlbumArtRadius = mContext.getResources().getDimension(R.dimen.qs_media_corner_radius);
            mAlbumArtSize = (int) mContext.getResources().getDimension(
                    R.dimen.qs_media_album_size);
        }
    }

    /** Observer for state changes of lock screen media controls. */
    private static final class KeyguardMediaObserver implements Observer<KeyguardMedia> {

        private final View mRootView;
        private final MediaHeaderView mMediaHeaderView;
        private final ImageView mAlbumView;
        private final ImageView mAppIconView;
        private final TextView mAppNameView;
        private final TextView mTitleView;
        private final TextView mArtistView;
        private final List<ImageButton> mButtonViews = new ArrayList<>();

        KeyguardMediaObserver(View v) {
            mRootView = v;
            mMediaHeaderView = v instanceof MediaHeaderView ? (MediaHeaderView) v : null;
            mAlbumView = v.findViewById(R.id.album_art);
            mAppIconView = v.findViewById(R.id.icon);
            mAppNameView = v.findViewById(R.id.app_name);
            mTitleView = v.findViewById(R.id.header_title);
            mArtistView = v.findViewById(R.id.header_artist);
            for (int i = 0; i < ACTION_IDS.length; i++) {
                mButtonViews.add(v.findViewById(ACTION_IDS[i]));
            }
        }

        /** Updates lock screen media player views when state changes. */
        @Override
        public void onChanged(KeyguardMedia data) {
            if (data == null) {
                mRootView.setVisibility(View.GONE);
                return;
            }
            mRootView.setVisibility(View.VISIBLE);

            // Background color
            if (mMediaHeaderView != null) {
                mMediaHeaderView.setBackgroundColor(data.getBackgroundColor());
            }

            // Album art
            if (mAlbumView != null) {
                mAlbumView.setImageDrawable(data.getArtwork());
                mAlbumView.setVisibility(data.getArtwork() == null ? View.GONE : View.VISIBLE);
            }

            // App icon
            if (mAppIconView != null) {
                Drawable iconDrawable = data.getAppIcon();
                iconDrawable.setTint(data.getForegroundColor());
                mAppIconView.setImageDrawable(iconDrawable);
            }

            // App name
            if (mAppNameView != null) {
                String appNameString = data.getApp();
                mAppNameView.setText(appNameString);
                mAppNameView.setTextColor(data.getForegroundColor());
            }

            // Song name
            if (mTitleView != null) {
                mTitleView.setText(data.getSong());
                mTitleView.setTextColor(data.getForegroundColor());
            }

            // Artist name
            if (mArtistView != null) {
                mArtistView.setText(data.getArtist());
                mArtistView.setTextColor(data.getForegroundColor());
            }

            // Control buttons
            for (int i = 0; i < ACTION_IDS.length; i++) {
                ImageButton button = mButtonViews.get(i);
                if (button == null) {
                    continue;
                }
                Drawable icon = data.getActionIcons().get(i);
                if (icon == null) {
                    button.setVisibility(View.GONE);
                    button.setImageDrawable(null);
                } else {
                    button.setVisibility(View.VISIBLE);
                    button.setImageDrawable(icon);
                    button.setImageTintList(ColorStateList.valueOf(data.getForegroundColor()));
                }
            }
        }
    }
}
