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

import static com.android.systemui.statusbar.StatusBarState.KEYGUARD;
import static com.android.systemui.statusbar.phone.CentralSurfaces.DEBUG_MEDIA_FAKE_ARTWORK;
import static com.android.systemui.statusbar.phone.CentralSurfaces.ENABLE_LOCKSCREEN_WALLPAPER;
import static com.android.systemui.statusbar.phone.CentralSurfaces.SHOW_LOCKSCREEN_MEDIA_ARTWORK;

import android.annotation.MainThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Notification;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.AsyncTask;
import android.os.Trace;
import android.service.notification.NotificationListenerService;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.util.ArraySet;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dependency;
import com.android.systemui.Dumpable;
import com.android.systemui.animation.Interpolators;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.MediaData;
import com.android.systemui.media.MediaDataManager;
import com.android.systemui.media.SmartspaceMediaData;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.dagger.CentralSurfacesModule;
import com.android.systemui.statusbar.notification.NotifPipelineFlags;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;
import com.android.systemui.statusbar.phone.BiometricUnlockController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.KeyguardBypassController;
import com.android.systemui.statusbar.phone.LockscreenWallpaper;
import com.android.systemui.statusbar.phone.ScrimController;
import com.android.systemui.statusbar.phone.ScrimState;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.Utils;
import com.android.systemui.util.concurrency.DelayableExecutor;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import dagger.Lazy;

/**
 * Handles tasks and state related to media notifications. For example, there is a 'current' media
 * notification, which this class keeps track of.
 */
public class NotificationMediaManager implements Dumpable {
    private static final String TAG = "NotificationMediaManager";
    public static final boolean DEBUG_MEDIA = false;

    private final StatusBarStateController mStatusBarStateController
            = Dependency.get(StatusBarStateController.class);
    private final SysuiColorExtractor mColorExtractor = Dependency.get(SysuiColorExtractor.class);
    private final KeyguardStateController mKeyguardStateController = Dependency.get(
            KeyguardStateController.class);
    private final KeyguardBypassController mKeyguardBypassController;
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
    private final NotificationEntryManager mEntryManager;
    private final MediaDataManager mMediaDataManager;
    private final NotifPipeline mNotifPipeline;
    private final NotifCollection mNotifCollection;
    private final boolean mUsingNotifPipeline;

    @Nullable
    private Lazy<NotificationShadeWindowController> mNotificationShadeWindowController;

    @Nullable
    private BiometricUnlockController mBiometricUnlockController;
    @Nullable
    private ScrimController mScrimController;
    @Nullable
    private LockscreenWallpaper mLockscreenWallpaper;

    private final DelayableExecutor mMainExecutor;

    private final Context mContext;
    private final ArrayList<MediaListener> mMediaListeners;
    private final Lazy<Optional<CentralSurfaces>> mCentralSurfacesOptionalLazy;
    private final MediaArtworkProcessor mMediaArtworkProcessor;
    private final Set<AsyncTask<?, ?, ?>> mProcessArtworkTasks = new ArraySet<>();

    protected NotificationPresenter mPresenter;
    private MediaController mMediaController;
    private String mMediaNotificationKey;
    private MediaMetadata mMediaMetadata;

    private BackDropView mBackdrop;
    private ImageView mBackdropFront;
    private ImageView mBackdropBack;

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
            mMediaArtworkProcessor.clearCache();
            mMediaMetadata = metadata;
            dispatchUpdateMediaMetaData(true /* changed */, true /* allowAnimation */);
        }
    };

    /**
     * Injected constructor. See {@link CentralSurfacesModule}.
     */
    public NotificationMediaManager(
            Context context,
            Lazy<Optional<CentralSurfaces>> centralSurfacesOptionalLazy,
            Lazy<NotificationShadeWindowController> notificationShadeWindowController,
            NotificationVisibilityProvider visibilityProvider,
            NotificationEntryManager notificationEntryManager,
            MediaArtworkProcessor mediaArtworkProcessor,
            KeyguardBypassController keyguardBypassController,
            NotifPipeline notifPipeline,
            NotifCollection notifCollection,
            NotifPipelineFlags notifPipelineFlags,
            @Main DelayableExecutor mainExecutor,
            MediaDataManager mediaDataManager,
            DumpManager dumpManager) {
        mContext = context;
        mMediaArtworkProcessor = mediaArtworkProcessor;
        mKeyguardBypassController = keyguardBypassController;
        mMediaListeners = new ArrayList<>();
        // TODO: use KeyguardStateController#isOccluded to remove this dependency
        mCentralSurfacesOptionalLazy = centralSurfacesOptionalLazy;
        mNotificationShadeWindowController = notificationShadeWindowController;
        mVisibilityProvider = visibilityProvider;
        mEntryManager = notificationEntryManager;
        mMainExecutor = mainExecutor;
        mMediaDataManager = mediaDataManager;
        mNotifPipeline = notifPipeline;
        mNotifCollection = notifCollection;

        if (!notifPipelineFlags.isNewPipelineEnabled()) {
            setupNEM();
            mUsingNotifPipeline = false;
        } else {
            setupNotifPipeline();
            mUsingNotifPipeline = true;
        }

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

    private void setupNEM() {
        mEntryManager.addNotificationEntryListener(new NotificationEntryListener() {

            @Override
            public void onPendingEntryAdded(NotificationEntry entry) {
                mMediaDataManager.onNotificationAdded(entry.getKey(), entry.getSbn());
            }

            @Override
            public void onPreEntryUpdated(NotificationEntry entry) {
                mMediaDataManager.onNotificationAdded(entry.getKey(), entry.getSbn());
            }

            @Override
            public void onEntryInflated(NotificationEntry entry) {
                findAndUpdateMediaNotifications();
            }

            @Override
            public void onEntryReinflated(NotificationEntry entry) {
                findAndUpdateMediaNotifications();
            }

            @Override
            public void onEntryRemoved(
                    @NonNull NotificationEntry entry,
                    @Nullable NotificationVisibility visibility,
                    boolean removedByUser,
                    int reason) {
                removeEntry(entry);
            }
        });

        // Pending entries are never inflated, and will never generate a call to onEntryRemoved().
        // This can happen when notifications are added and canceled before inflation. Add this
        // separate listener for cleanup, since media inflation occurs onPendingEntryAdded().
        mEntryManager.addCollectionListener(new NotifCollectionListener() {
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
                NotificationEntry entry = mEntryManager.getPendingOrActiveNotif(key);
                if (entry != null) {
                    // TODO(b/160713608): "removing" this notification won't happen and
                    //  won't send the 'deleteIntent' if the notification is ongoing.
                    mEntryManager.performRemoveNotification(entry.getSbn(),
                            getDismissedByUserStats(entry),
                            NotificationListenerService.REASON_CANCEL);
                }
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
        if (mUsingNotifPipeline) {
            return Optional.ofNullable(mNotifPipeline.getEntry(mMediaNotificationKey))
                .map(entry -> entry.getIcons().getShelfIcon())
                .map(StatusBarIconView::getSourceIcon)
                .orElse(null);
        } else {
            synchronized (mEntryManager) {
                NotificationEntry entry = mEntryManager
                    .getActiveNotificationUnfiltered(mMediaNotificationKey);
                if (entry == null || entry.getIcons().getShelfIcon() == null) {
                    return null;
                }

                return entry.getIcons().getShelfIcon().getSourceIcon();
            }
        }
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
        if (mUsingNotifPipeline) {
            // TODO(b/169655907): get the semi-filtered notifications for current user
            Collection<NotificationEntry> allNotifications = mNotifPipeline.getAllNotifs();
            metaDataChanged = findPlayingMediaNotification(allNotifications);
        } else {
            synchronized (mEntryManager) {
                Collection<NotificationEntry> allNotifications = mEntryManager.getAllNotifs();
                metaDataChanged = findPlayingMediaNotification(allNotifications);
            }

            if (metaDataChanged) {
                mEntryManager.updateNotifications("NotificationMediaManager - metaDataChanged");
            }

        }
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
        mMediaArtworkProcessor.clearCache();
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
     * Refresh or remove lockscreen artwork from media metadata or the lockscreen wallpaper.
     */
    public void updateMediaMetaData(boolean metaDataChanged, boolean allowEnterAnimation) {
        Trace.beginSection("CentralSurfaces#updateMediaMetaData");
        if (!SHOW_LOCKSCREEN_MEDIA_ARTWORK) {
            Trace.endSection();
            return;
        }

        if (mBackdrop == null) {
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

        Bitmap artworkBitmap = null;
        if (mediaMetadata != null && !mKeyguardBypassController.getBypassEnabled()) {
            artworkBitmap = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (artworkBitmap == null) {
                artworkBitmap = mediaMetadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            }
        }

        // Process artwork on a background thread and send the resulting bitmap to
        // finishUpdateMediaMetaData.
        if (metaDataChanged) {
            for (AsyncTask<?, ?, ?> task : mProcessArtworkTasks) {
                task.cancel(true);
            }
            mProcessArtworkTasks.clear();
        }
        if (artworkBitmap != null && !Utils.useQsMediaPlayer(mContext)) {
            mProcessArtworkTasks.add(new ProcessArtworkTask(this, metaDataChanged,
                    allowEnterAnimation).execute(artworkBitmap));
        } else {
            finishUpdateMediaMetaData(metaDataChanged, allowEnterAnimation, null);
        }

        Trace.endSection();
    }

    private void finishUpdateMediaMetaData(boolean metaDataChanged, boolean allowEnterAnimation,
            @Nullable Bitmap bmp) {
        Drawable artworkDrawable = null;
        if (bmp != null) {
            artworkDrawable = new BitmapDrawable(mBackdropBack.getResources(), bmp);
        }
        boolean hasMediaArtwork = artworkDrawable != null;
        boolean allowWhenShade = false;
        if (ENABLE_LOCKSCREEN_WALLPAPER && artworkDrawable == null) {
            Bitmap lockWallpaper =
                    mLockscreenWallpaper != null ? mLockscreenWallpaper.getBitmap() : null;
            if (lockWallpaper != null) {
                artworkDrawable = new LockscreenWallpaper.WallpaperDrawable(
                        mBackdropBack.getResources(), lockWallpaper);
                // We're in the SHADE mode on the SIM screen - yet we still need to show
                // the lockscreen wallpaper in that mode.
                allowWhenShade = mStatusBarStateController.getState() == KEYGUARD;
            }
        }

        NotificationShadeWindowController windowController =
                mNotificationShadeWindowController.get();
        boolean hideBecauseOccluded =
                mCentralSurfacesOptionalLazy.get()
                        .map(CentralSurfaces::isOccluded).orElse(false);

        final boolean hasArtwork = artworkDrawable != null;
        mColorExtractor.setHasMediaArtwork(hasMediaArtwork);
        if (mScrimController != null) {
            mScrimController.setHasBackdrop(hasArtwork);
        }

        if ((hasArtwork || DEBUG_MEDIA_FAKE_ARTWORK)
                && (mStatusBarStateController.getState() != StatusBarState.SHADE || allowWhenShade)
                &&  mBiometricUnlockController != null && mBiometricUnlockController.getMode()
                        != BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING
                && !hideBecauseOccluded) {
            // time to show some art!
            if (mBackdrop.getVisibility() != View.VISIBLE) {
                mBackdrop.setVisibility(View.VISIBLE);
                if (allowEnterAnimation) {
                    mBackdrop.setAlpha(0);
                    mBackdrop.animate().alpha(1f);
                } else {
                    mBackdrop.animate().cancel();
                    mBackdrop.setAlpha(1f);
                }
                if (windowController != null) {
                    windowController.setBackdropShowing(true);
                }
                metaDataChanged = true;
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: Fading in album artwork");
                }
            }
            if (metaDataChanged) {
                if (mBackdropBack.getDrawable() != null) {
                    Drawable drawable =
                            mBackdropBack.getDrawable().getConstantState()
                                    .newDrawable(mBackdropFront.getResources()).mutate();
                    mBackdropFront.setImageDrawable(drawable);
                    mBackdropFront.setAlpha(1f);
                    mBackdropFront.setVisibility(View.VISIBLE);
                } else {
                    mBackdropFront.setVisibility(View.INVISIBLE);
                }

                if (DEBUG_MEDIA_FAKE_ARTWORK) {
                    final int c = 0xFF000000 | (int)(Math.random() * 0xFFFFFF);
                    Log.v(TAG, String.format("DEBUG_MEDIA: setting new color: 0x%08x", c));
                    mBackdropBack.setBackgroundColor(0xFFFFFFFF);
                    mBackdropBack.setImageDrawable(new ColorDrawable(c));
                } else {
                    mBackdropBack.setImageDrawable(artworkDrawable);
                }

                if (mBackdropFront.getVisibility() == View.VISIBLE) {
                    if (DEBUG_MEDIA) {
                        Log.v(TAG, "DEBUG_MEDIA: Crossfading album artwork from "
                                + mBackdropFront.getDrawable()
                                + " to "
                                + mBackdropBack.getDrawable());
                    }
                    mBackdropFront.animate()
                            .setDuration(250)
                            .alpha(0f).withEndAction(mHideBackdropFront);
                }
            }
        } else {
            // need to hide the album art, either because we are unlocked, on AOD
            // or because the metadata isn't there to support it
            if (mBackdrop.getVisibility() != View.GONE) {
                if (DEBUG_MEDIA) {
                    Log.v(TAG, "DEBUG_MEDIA: Fading out album artwork");
                }
                boolean cannotAnimateDoze = mStatusBarStateController.isDozing()
                        && !ScrimState.AOD.getAnimateChange();
                boolean needsBypassFading = mKeyguardStateController.isBypassFadingAnimation();
                if (((mBiometricUnlockController != null && mBiometricUnlockController.getMode()
                        == BiometricUnlockController.MODE_WAKE_AND_UNLOCK_PULSING
                                || cannotAnimateDoze) && !needsBypassFading)
                        || hideBecauseOccluded) {

                    // We are unlocking directly - no animation!
                    mBackdrop.setVisibility(View.GONE);
                    mBackdropBack.setImageDrawable(null);
                    if (windowController != null) {
                        windowController.setBackdropShowing(false);
                    }
                } else {
                    if (windowController != null) {
                        windowController.setBackdropShowing(false);
                    }
                    mBackdrop.animate()
                            .alpha(0)
                            .setInterpolator(Interpolators.ACCELERATE_DECELERATE)
                            .setDuration(300)
                            .setStartDelay(0)
                            .withEndAction(() -> {
                                mBackdrop.setVisibility(View.GONE);
                                mBackdropFront.animate().cancel();
                                mBackdropBack.setImageDrawable(null);
                                mMainExecutor.execute(mHideBackdropFront);
                            });
                    if (mKeyguardStateController.isKeyguardFadingAway()) {
                        mBackdrop.animate()
                                .setDuration(
                                        mKeyguardStateController.getShortenedFadingAwayDuration())
                                .setStartDelay(
                                        mKeyguardStateController.getKeyguardFadingAwayDelay())
                                .setInterpolator(Interpolators.LINEAR)
                                .start();
                    }
                }
            }
        }
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

    private Bitmap processArtwork(Bitmap artwork) {
        return mMediaArtworkProcessor.processArtwork(mContext, artwork);
    }

    @MainThread
    private void removeTask(AsyncTask<?, ?, ?> task) {
        mProcessArtworkTasks.remove(task);
    }

    /**
     * {@link AsyncTask} to prepare album art for use as backdrop on lock screen.
     */
    private static final class ProcessArtworkTask extends AsyncTask<Bitmap, Void, Bitmap> {

        private final WeakReference<NotificationMediaManager> mManagerRef;
        private final boolean mMetaDataChanged;
        private final boolean mAllowEnterAnimation;

        ProcessArtworkTask(NotificationMediaManager manager, boolean changed,
                boolean allowAnimation) {
            mManagerRef = new WeakReference<>(manager);
            mMetaDataChanged = changed;
            mAllowEnterAnimation = allowAnimation;
        }

        @Override
        protected Bitmap doInBackground(Bitmap... bitmaps) {
            NotificationMediaManager manager = mManagerRef.get();
            if (manager == null || bitmaps.length == 0 || isCancelled()) {
                return null;
            }
            return manager.processArtwork(bitmaps[0]);
        }

        @Override
        protected void onPostExecute(@Nullable Bitmap result) {
            NotificationMediaManager manager = mManagerRef.get();
            if (manager != null && !isCancelled()) {
                manager.removeTask(this);
                manager.finishUpdateMediaMetaData(mMetaDataChanged, mAllowEnterAnimation, result);
            }
        }

        @Override
        protected void onCancelled(Bitmap result) {
            if (result != null) {
                result.recycle();
            }
            NotificationMediaManager manager = mManagerRef.get();
            if (manager != null) {
                manager.removeTask(this);
            }
        }
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
