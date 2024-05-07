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
import android.content.Context;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.systemui.Dumpable;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.media.controls.domain.pipeline.MediaDataManager;
import com.android.systemui.media.controls.shared.model.MediaData;
import com.android.systemui.media.controls.shared.model.SmartspaceMediaData;
import com.android.systemui.statusbar.dagger.CentralSurfacesModule;
import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.notifcollection.DismissedByUserStats;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.collection.render.NotificationVisibilityProvider;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Handles tasks and state related to media notifications. For example, there is a 'current' media
 * notification, which this class keeps track of.
 */
public class NotificationMediaManager implements Dumpable {
    private static final String TAG = "NotificationMediaManager";
    public static final boolean DEBUG_MEDIA = false;

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

    private final Context mContext;
    private final ArrayList<MediaListener> mMediaListeners;

    private final Executor mBackgroundExecutor;

    protected NotificationPresenter mPresenter;
    private MediaController mMediaController;
    private String mMediaNotificationKey;
    private MediaMetadata mMediaMetadata;

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
            dispatchUpdateMediaMetaData();
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
            DumpManager dumpManager,
            @Background Executor backgroundExecutor) {
        mContext = context;
        mMediaListeners = new ArrayList<>();
        mVisibilityProvider = visibilityProvider;
        mMediaDataManager = mediaDataManager;
        mNotifPipeline = notifPipeline;
        mNotifCollection = notifCollection;
        mBackgroundExecutor = backgroundExecutor;

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
            dispatchUpdateMediaMetaData();
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
        // TODO(b/169655907): get the semi-filtered notifications for current user
        Collection<NotificationEntry> allNotifications = mNotifPipeline.getAllNotifs();
        findPlayingMediaNotification(allNotifications);
        dispatchUpdateMediaMetaData();
    }

    /**
     * Find a notification and media controller associated with the playing media session, and
     * update this manager's internal state.
     * TODO(b/273443374) check this method
     */
    void findPlayingMediaNotification(@NonNull Collection<NotificationEntry> allNotifications) {
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
        }

        if (mediaNotification != null
                && !mediaNotification.getSbn().getKey().equals(mMediaNotificationKey)) {
            mMediaNotificationKey = mediaNotification.getSbn().getKey();
            if (DEBUG_MEDIA) {
                Log.v(TAG, "DEBUG_MEDIA: Found new media notification: key="
                        + mMediaNotificationKey);
            }
        }
    }

    public void clearCurrentMediaNotification() {
        mMediaNotificationKey = null;
        clearCurrentMediaNotificationSession();
    }

    private void dispatchUpdateMediaMetaData() {
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
            // TODO(b/336612071): move to background thread
            mMediaController.unregisterCallback(mMediaListener);
        }
        mMediaController = null;
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
