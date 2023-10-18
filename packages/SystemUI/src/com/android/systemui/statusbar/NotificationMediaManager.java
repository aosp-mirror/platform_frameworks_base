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
 * limitations under the License
 */
package com.android.systemui.statusbar;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Point;
import android.graphics.drawable.Icon;
import android.hardware.display.DisplayManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Trace;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.widget.ImageView;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.Dumpable;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.controls.models.player.MediaData;
import com.android.systemui.media.controls.models.recommendation.SmartspaceMediaData;
import com.android.systemui.media.controls.pipeline.MediaDataManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.dagger.CentralSurfacesModule;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.LockscreenWallpaper;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Handles tasks and state related to media notifications. For example, there is a 'current' media
 * notification, which this class keeps track of.
 */
public class NotificationMediaManager implements Dumpable {
    private static final String TAG = "NotificationMediaManager";
    public static final boolean DEBUG_MEDIA = false;

    private final StatusBarStateController mStatusBarStateController;
    private final SysuiColorExtractor mColorExtractor;
    private final KeyguardStateController mKeyguardStateController;
    private static final HashSet<Integer> PAUSED_MEDIA_STATES = new HashSet<>();
    private static final HashSet<Integer> CONNECTING_MEDIA_STATES = new HashSet<>();
    static {
        PAUSED_MEDIA_STATES.add(PlaybackState.STATE_NONE);
        PAUSED_MEDIA_STATES.add(PlaybackState.STATE_STOPPED);
        PAUSED_MEDIA_STATES.add(PlaybackState.STATE_PAUSED);
        PAUSED_MEDIA_STATES.add(PlaybackState.STATE_ERROR);
        CONNECTING_MEDIA_STATES.add(PlaybackState.STATE_CONNECTING);
        CONNECTING_MEDIA_STATES.add(PlaybackState.STATE_BUFFERING);
    }

    private final NotificationVisibilityProvider mVisibilityProvider;
    private final MediaDataManager mMediaDataManager;
    private final NotifPipeline mNotifPipeline;
    private final NotifCollection mNotifCollection;

    @Nullable
    private BiometricUnlockController mBiometricUnlockController;
    @Nullable
    private ScrimController mScrimController;
    @Nullable
    private LockscreenWallpaper mLockscreenWallpaper;
    @VisibleForTesting
    boolean mIsLockscreenLiveWallpaperEnabled;

    private final Context mContext;
    private final ArrayList<MediaListener> mMediaListeners;

    protected NotificationPresenter mPresenter;
    private MediaController mMediaController;
    private String mMediaNotificationKey;
    private MediaMetadata mMediaMetadata;

    private BackDropView mBackdrop;
    private ImageView mBackdropFront;
    private ImageView mBackdropBack;
    private final Point mTmpDisplaySize = new Point();

    private final DisplayManager mDisplayManager;
    @Nullable
    private List<String> mSmallerInternalDisplayUids;
    private Display mCurrentDisplay;

    private final MediaController.Callback mMediaListener = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            super.onPlaybackStateChanged(state);
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: onPlaybackStateChanged: " + state);
            }
            if (state != null) {
                if (!isPlaybackActive(state.getState())) {
                    clearCurrentMediaNotification();
                }
                findAndUpdateMediaNotifications();
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            super.onMetadataChanged(metadata);
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: onMetadataChanged: " + metadata);
            }
            mMediaMetadata = metadata;
            dispatchUpdateMediaMetaData(true /* changed */, true /* allowAnimation */);
        }
    };

    /**
     * Injected constructor. See {@link CentralSurfacesModule}.
     */
    public NotificationMediaManager(
            Context context,
            NotificationVisibilityProvider visibilityProvider,
            NotifPipeline notifPipeline,
            NotifCollection notifCollection,
            MediaDataManager mediaDataManager,
            StatusBarStateController statusBarStateController,
            SysuiColorExtractor colorExtractor,
            KeyguardStateController keyguardStateController,
            DumpManager dumpManager,
            WallpaperManager wallpaperManager,
            DisplayManager displayManager) {
        mContext = context;
        mMediaListeners = new ArrayList<>();
        mVisibilityProvider = visibilityProvider;
        mMediaDataManager = mediaDataManager;
        mNotifPipeline = notifPipeline;
        mNotifCollection = notifCollection;
        mStatusBarStateController = statusBarStateController;
        mColorExtractor = colorExtractor;
        mKeyguardStateController = keyguardStateController;
        mDisplayManager = displayManager;
        mIsLockscreenLiveWallpaperEnabled = wallpaperManager.isLockscreenLiveWallpaperEnabled();

        setupNotifPipeline();

        dumpManager.registerDumpable(this);
    }

    private void setupNotifPipeline() {
        mNotifPipeline.addCollectionListener(new NotifCollectionListener() {
            @Override
            public void onEntryAdded(@NonNull NotificationEntry entry) {
                mMediaDataManager.onNotificationAdded(entry.getKey(), entry.getSbn());
            }

            @Override
            public void onEntryUpdated(NotificationEntry entry) {
                mMediaDataManager.onNotificationAdded(entry.getKey(), entry.getSbn());
            }

            @Override
            public void onEntryBind(NotificationEntry entry, StatusBarNotification sbn) {
                findAndUpdateMediaNotifications();
            }

            @Override
            public void onEntryRemoved(@NonNull NotificationEntry entry, int reason) {
                removeEntry(entry);
            }

            @Override
            public void onEntryCleanUp(@NonNull NotificationEntry entry) {
                removeEntry(entry);
            }
        });

        mMediaDataManager.addListener(new MediaDataManager.Listener() {
            @Override
            public void onMediaDataLoaded(@NonNull String key,
                    @Nullable String oldKey, @NonNull MediaData data, boolean immediately,
                    int receivedSmartspaceCardLatency, boolean isSsReactivated) {
            }

            @Override
            public void onSmartspaceMediaDataLoaded(@NonNull String key,
                    @NonNull SmartspaceMediaData data, boolean shouldPrioritize) {
            }

            @Override
            public void onMediaDataRemoved(@NonNull String key) {
                mNotifPipeline.getAllNotifs()
                        .stream()
                        .filter(entry -> Objects.equals(entry.getKey(), key))
                        .findAny()
                        .ifPresent(entry -> {
                            // TODO(b/160713608): "removing" this notification won't happen and
                            //  won't send the 'deleteIntent' if the notification is ongoing.
                            mNotifCollection.dismissNotification(entry,
                                    getDismissedByUserStats(entry));
                        });
            }

            @Override
            public void onSmartspaceMediaDataRemoved(@NonNull String key, boolean immediately) {}
        });
    }

    private DismissedByUserStats getDismissedByUserStats(NotificationEntry entry) {
        return new DismissedByUserStats(
                NotificationStats.DISMISSAL_SHADE, // Add DISMISSAL_MEDIA?
                NotificationStats.DISMISS_SENTIMENT_NEUTRAL,
                mVisibilityProvider.obtain(entry, /* visible= */ true));
    }

    private void removeEntry(NotificationEntry entry) {
        onNotificationRemoved(entry.getKey());
        mMediaDataManager.onNotificationRemoved(entry.getKey());
    }

    /**
     * Check if a state should be considered actively playing
     * @param state a PlaybackState
     * @return true if playing
     */
    public static boolean isPlayingState(int state) {
        return !PAUSED_MEDIA_STATES.contains(state)
            && !CONNECTING_MEDIA_STATES.contains(state);
    }

    /**
     * Check if a state should be considered as connecting
     * @param state a PlaybackState
     * @return true if connecting or buffering
     */
    public static boolean isConnectingState(int state) {
        return CONNECTING_MEDIA_STATES.contains(state);
    }

    public void setUpWithPresenter(NotificationPresenter presenter) {
        mPresenter = presenter;
    }

    public void onNotificationRemoved(String key) {
        if (key.equals(mMediaNotificationKey)) {
            clearCurrentMediaNotification();
            dispatchUpdateMediaMetaData(true /* changed */, true /* allowEnterAnimation */);
        }
    }

    @Nullable
    public String getMediaNotificationKey() {
        return mMediaNotificationKey;
    }

    public MediaMetadata getMediaMetadata() {
        return mMediaMetadata;
    }

    public Icon getMediaIcon() {
        if (mMediaNotificationKey == null) {
            return null;
        }
        return Optional.ofNullable(mNotifPipeline.getEntry(mMediaNotificationKey))
            .map(entry -> entry.getIcons().getShelfIcon())
            .map(StatusBarIconView::getSourceIcon)
            .orElse(null);
    }

    public void addCallback(MediaListener callback) {
        mMediaListeners.add(callback);
        callback.onPrimaryMetadataOrStateChanged(mMediaMetadata,
                getMediaControllerPlaybackState(mMediaController));
    }

    public void removeCallback(MediaListener callback) {
        mMediaListeners.remove(callback);
    }

    public void findAndUpdateMediaNotifications() {
        boolean metaDataChanged;
        // TODO(b/169655907): get the semi-filtered notifications for current user
        Collection<NotificationEntry> allNotifications = mNotifPipeline.getAllNotifs();
        metaDataChanged = findPlayingMediaNotification(allNotifications);
        dispatchUpdateMediaMetaData(metaDataChanged, true /* allowEnterAnimation */);
    }

    /**
     * Find a notification and media controller associated with the playing media session, and
     * update this manager's internal state.
     * @return whether the current MediaMetadata changed (and needs to be announced to listeners).
     */
    boolean findPlayingMediaNotification(
            @NonNull Collection<NotificationEntry> allNotifications) {
        boolean metaDataChanged = false;
        // Promote the media notification with a controller in 'playing' state, if any.
        NotificationEntry mediaNotification = null;
        MediaController controller = null;
        for (NotificationEntry entry : allNotifications) {
            Notification notif = entry.getSbn().getNotification();
            if (notif.isMediaNotification()) {
                final MediaSession.Token token =
                        entry.getSbn().getNotification().extras.getParcelable(
                                Notification.EXTRA_MEDIA_SESSION, MediaSession.Token.class);
                if (token != null) {
                    MediaController aController = new MediaController(mContext, token);
                    if (PlaybackState.STATE_PLAYING
                            == getMediaControllerPlaybackState(aController)) {
                        if (DEBUG_MEDIA) {
                            Log.v(TAG, "DEBUG_MEDIA: found mediastyle controller matching "
                                    + entry.getSbn().getKey());
                        }
                        mediaNotification = entry;
                        controller = aController;
                        break;
                    }
                }
            }
        }

        if (controller != null && !sameSessions(mMediaController, controller)) {
            // We have a new media session
            clearCurrentMediaNotificationSession();
            mMediaController = controller;
            mMediaController.registerCallback(mMediaListener);
            mMediaMetadata = mMediaController.getMetadata();
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: insert listener, found new controller: "
                        + mMediaController + ", receive metadata: " + mMediaMetadata);
            }

            metaDataChanged = true;
        }

        if (mediaNotification != null
                && !mediaNotification.getSbn().getKey().equals(mMediaNotificationKey)) {
            mMediaNotificationKey = mediaNotification.getSbn().getKey();
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: Found new media notification: key="
                        + mMediaNotificationKey);
            }
        }

        return metaDataChanged;
    }

    public void clearCurrentMediaNotification() {
        mMediaNotificationKey = null;
        clearCurrentMediaNotificationSession();
    }

    private void dispatchUpdateMediaMetaData(boolean changed, boolean allowEnterAnimation) {
        if (mPresenter != null) {
            mPresenter.updateMediaMetaData(changed, allowEnterAnimation);
        }
        @PlaybackState.State int state = getMediaControllerPlaybackState(mMediaController);
        ArrayList<MediaListener> callbacks = new ArrayList<>(mMediaListeners);
        for (int i = 0; i < callbacks.size(); i++) {
            callbacks.get(i).onPrimaryMetadataOrStateChanged(mMediaMetadata, state);
        }
    }

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        pw.print("    mMediaNotificationKey=");
        pw.println(mMediaNotificationKey);
        pw.print("    mMediaController=");
        pw.print(mMediaController);
        if (mMediaController != null) {
            pw.print(" state=" + mMediaController.getPlaybackState());
        }
        pw.println();
        pw.print("    mMediaMetadata=");
        pw.print(mMediaMetadata);
        if (mMediaMetadata != null) {
            pw.print(" title=" + mMediaMetadata.getText(MediaMetadata.METADATA_KEY_TITLE));
        }
        pw.println();
    }

    private boolean isPlaybackActive(int state) {
        return state != PlaybackState.STATE_STOPPED && state != PlaybackState.STATE_ERROR
                && state != PlaybackState.STATE_NONE;
    }

    private boolean sameSessions(MediaController a, MediaController b) {
        if (a == b) {
            return true;
        }
        if (a == null) {
            return false;
        }
        return a.controlsSameSession(b);
    }

    private int getMediaControllerPlaybackState(MediaController controller) {
        if (controller != null) {
            final PlaybackState playbackState = controller.getPlaybackState();
            if (playbackState != null) {
                return playbackState.getState();
            }
        }
        return PlaybackState.STATE_NONE;
    }

    private void clearCurrentMediaNotificationSession() {
        mMediaMetadata = null;
        if (mMediaController != null) {
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: Disconnecting from old controller: "
                        + mMediaController.getPackageName());
            }
            mMediaController.unregisterCallback(mMediaListener);
        }
        mMediaController = null;
    }

    /**
     * Notify lockscreen wallpaper drawable the current internal display.
     */
    public void onDisplayUpdated(Display display) {
        Trace.beginSection("NotificationMediaManager#onDisplayUpdated");
        mCurrentDisplay = display;
        Trace.endSection();
    }

    private boolean isOnSmallerInternalDisplays() {
        if (mSmallerInternalDisplayUids == null) {
            mSmallerInternalDisplayUids = findSmallerInternalDisplayUids();
        }
        return mSmallerInternalDisplayUids.contains(mCurrentDisplay.getUniqueId());
    }

    private List<String> findSmallerInternalDisplayUids() {
        if (mSmallerInternalDisplayUids != null) {
            return mSmallerInternalDisplayUids;
        }
        List<Display> internalDisplays = Arrays.stream(mDisplayManager.getDisplays(
                        DisplayManager.DISPLAY_CATEGORY_ALL_INCLUDING_DISABLED))
                .filter(display -> display.getType() == Display.TYPE_INTERNAL)
                .collect(Collectors.toList());
        if (internalDisplays.isEmpty()) {
            return List.of();
        }
        Display largestDisplay = internalDisplays.stream()
                .max(Comparator.comparingInt(this::getRealDisplayArea))
                .orElse(internalDisplays.get(0));
        internalDisplays.remove(largestDisplay);
        return internalDisplays.stream().map(Display::getUniqueId).collect(Collectors.toList());
    }

    private int getRealDisplayArea(Display display) {
        display.getRealSize(mTmpDisplaySize);
        return mTmpDisplaySize.x * mTmpDisplaySize.y;
    }

    /**
     * Update media state of lockscreen media views and controllers .
     */
    public void updateMediaMetaData(boolean metaDataChanged) {

        if (mIsLockscreenLiveWallpaperEnabled) return;

        Trace.beginSection("CentralSurfaces#updateMediaMetaData");
        if (getBackDropView() == null) {
            Trace.endSection();
            return; // called too early
        }

        boolean wakeAndUnlock = mBiometricUnlockController != null
            && mBiometricUnlockController.isWakeAndUnlock();
        if (mKeyguardStateController.isLaunchTransitionFadingAway() || wakeAndUnlock) {
            mBackdrop.setVisibility(View.INVISIBLE);
            Trace.endSection();
            return;
        }

        MediaMetadata mediaMetadata = getMediaMetadata();

        if (DEBUG_MEDIA) {
            Log.v(TAG, "DEBUG_MEDIA: updating album art for notification "
                    + getMediaNotificationKey()
                    + " metadata=" + mediaMetadata
                    + " metaDataChanged=" + metaDataChanged
                    + " state=" + mStatusBarStateController.getState());
        }

        mColorExtractor.setHasMediaArtwork(false);
        if (mScrimController != null) {
            mScrimController.setHasBackdrop(false);
        }

        Trace.endSection();
    }

    public void setup(BackDropView backdrop, ImageView backdropFront, ImageView backdropBack,
            ScrimController scrimController, LockscreenWallpaper lockscreenWallpaper) {
        mBackdrop = backdrop;
        mBackdropFront = backdropFront;
        mBackdropBack = backdropBack;
        mScrimController = scrimController;
        mLockscreenWallpaper = lockscreenWallpaper;
    }

    public void setBiometricUnlockController(BiometricUnlockController biometricUnlockController) {
        mBiometricUnlockController = biometricUnlockController;
    }

    /**
     * Hide the album artwork that is fading out and release its bitmap.
     */
    protected final Runnable mHideBackdropFront = new Runnable() {
        @Override
        public void run() {
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: removing fade layer");
            }
            mBackdropFront.setVisibility(View.INVISIBLE);
            mBackdropFront.animate().cancel();
            mBackdropFront.setImageDrawable(null);
        }
    };

    // TODO(b/273443374): remove
    public boolean isLockscreenWallpaperOnNotificationShade() {
        return mBackdrop != null && mLockscreenWallpaper != null
                && !mLockscreenWallpaper.isLockscreenLiveWallpaperEnabled()
                && (mBackdropFront.isVisibleToUser() || mBackdropBack.isVisibleToUser());
    }

    // TODO(b/273443374) temporary test helper; remove
    @VisibleForTesting
    BackDropView getBackDropView() {
        return mBackdrop;
    }

    public interface MediaListener {
        /**
         * Called whenever there's new metadata or playback state.
         * @param metadata Current metadata.
         * @param state Current playback state
         * @see PlaybackState.State
         */
        default void onPrimaryMetadataOrStateChanged(MediaMetadata metadata,
                @PlaybackState.State int state) {}
    }
}
