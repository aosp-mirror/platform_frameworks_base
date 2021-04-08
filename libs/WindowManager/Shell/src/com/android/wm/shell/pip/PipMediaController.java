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

package com.android.wm.shell.pip;

import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.app.PendingIntent.FLAG_UPDATE_CURRENT;

import android.annotation.DrawableRes;
import android.annotation.StringRes;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.UserHandle;

import androidx.annotation.Nullable;

import com.android.wm.shell.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Interfaces with the {@link MediaSessionManager} to compose the right set of actions to show (only
 * if there are no actions from the PiP activity itself). The active media controller is only set
 * when there is a media session from the top PiP activity.
 */
public class PipMediaController {
    private static final String SYSTEMUI_PERMISSION = "com.android.systemui.permission.SELF";

    private static final String ACTION_PLAY = "com.android.wm.shell.pip.PLAY";
    private static final String ACTION_PAUSE = "com.android.wm.shell.pip.PAUSE";
    private static final String ACTION_NEXT = "com.android.wm.shell.pip.NEXT";
    private static final String ACTION_PREV = "com.android.wm.shell.pip.PREV";

    /**
     * A listener interface to receive notification on changes to the media actions.
     */
    public interface ActionListener {
        /**
         * Called when the media actions changes.
         */
        void onMediaActionsChanged(List<RemoteAction> actions);
    }

    /**
     * A listener interface to receive notification on changes to the media metadata.
     */
    public interface MetadataListener {
        /**
         * Called when the media metadata changes.
         */
        void onMediaMetadataChanged(MediaMetadata metadata);
    }

    private final Context mContext;
    private final Handler mMainHandler;
    private final HandlerExecutor mHandlerExecutor;

    private final MediaSessionManager mMediaSessionManager;
    private MediaController mMediaController;

    private RemoteAction mPauseAction;
    private RemoteAction mPlayAction;
    private RemoteAction mNextAction;
    private RemoteAction mPrevAction;

    private final BroadcastReceiver mMediaActionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (mMediaController == null || mMediaController.getTransportControls() == null) {
                // no active media session, bail early.
                return;
            }
            switch (intent.getAction()) {
                case ACTION_PLAY:
                    mMediaController.getTransportControls().play();
                    break;
                case ACTION_PAUSE:
                    mMediaController.getTransportControls().pause();
                    break;
                case ACTION_NEXT:
                    mMediaController.getTransportControls().skipToNext();
                    break;
                case ACTION_PREV:
                    mMediaController.getTransportControls().skipToPrevious();
                    break;
            }
        }
    };

    private final MediaController.Callback mPlaybackChangedListener =
            new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    notifyActionsChanged();
                }

                @Override
                public void onMetadataChanged(@Nullable MediaMetadata metadata) {
                    notifyMetadataChanged(metadata);
                }
            };

    private final MediaSessionManager.OnActiveSessionsChangedListener mSessionsChangedListener =
            this::resolveActiveMediaController;

    private final ArrayList<ActionListener> mActionListeners = new ArrayList<>();
    private final ArrayList<MetadataListener> mMetadataListeners = new ArrayList<>();

    public PipMediaController(Context context, Handler mainHandler) {
        mContext = context;
        mMainHandler = mainHandler;
        mHandlerExecutor = new HandlerExecutor(mMainHandler);
        IntentFilter mediaControlFilter = new IntentFilter();
        mediaControlFilter.addAction(ACTION_PLAY);
        mediaControlFilter.addAction(ACTION_PAUSE);
        mediaControlFilter.addAction(ACTION_NEXT);
        mediaControlFilter.addAction(ACTION_PREV);
        mContext.registerReceiverForAllUsers(mMediaActionReceiver, mediaControlFilter,
                SYSTEMUI_PERMISSION, mainHandler);

        // Creates the standard media buttons that we may show.
        mPauseAction = getDefaultRemoteAction(R.string.pip_pause,
                R.drawable.pip_ic_pause_white, ACTION_PAUSE);
        mPlayAction = getDefaultRemoteAction(R.string.pip_play,
                R.drawable.pip_ic_play_arrow_white, ACTION_PLAY);
        mNextAction = getDefaultRemoteAction(R.string.pip_skip_to_next,
                R.drawable.pip_ic_skip_next_white, ACTION_NEXT);
        mPrevAction = getDefaultRemoteAction(R.string.pip_skip_to_prev,
                R.drawable.pip_ic_skip_previous_white, ACTION_PREV);

        mMediaSessionManager = context.getSystemService(MediaSessionManager.class);
    }

    /**
     * Handles when an activity is pinned.
     */
    public void onActivityPinned() {
        // Once we enter PiP, try to find the active media controller for the top most activity
        resolveActiveMediaController(mMediaSessionManager.getActiveSessionsForUser(null,
                UserHandle.CURRENT));
    }

    /**
     * Adds a new media action listener.
     */
    public void addActionListener(ActionListener listener) {
        if (!mActionListeners.contains(listener)) {
            mActionListeners.add(listener);
            listener.onMediaActionsChanged(getMediaActions());
        }
    }

    /**
     * Removes a media action listener.
     */
    public void removeActionListener(ActionListener listener) {
        listener.onMediaActionsChanged(Collections.emptyList());
        mActionListeners.remove(listener);
    }

    /**
     * Adds a new media metadata listener.
     */
    public void addMetadataListener(MetadataListener listener) {
        if (!mMetadataListeners.contains(listener)) {
            mMetadataListeners.add(listener);
            listener.onMediaMetadataChanged(getMediaMetadata());
        }
    }

    /**
     * Removes a media metadata listener.
     */
    public void removeMetadataListener(MetadataListener listener) {
        listener.onMediaMetadataChanged(null);
        mMetadataListeners.remove(listener);
    }

    private MediaMetadata getMediaMetadata() {
        return mMediaController != null ? mMediaController.getMetadata() : null;
    }

    /**
     * Gets the set of media actions currently available.
     */
    private List<RemoteAction> getMediaActions() {
        if (mMediaController == null || mMediaController.getPlaybackState() == null) {
            return Collections.emptyList();
        }

        ArrayList<RemoteAction> mediaActions = new ArrayList<>();
        boolean isPlaying = mMediaController.getPlaybackState().isActiveState();
        long actions = mMediaController.getPlaybackState().getActions();

        // Prev action
        mPrevAction.setEnabled((actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0);
        mediaActions.add(mPrevAction);

        // Play/pause action
        if (!isPlaying && ((actions & PlaybackState.ACTION_PLAY) != 0)) {
            mediaActions.add(mPlayAction);
        } else if (isPlaying && ((actions & PlaybackState.ACTION_PAUSE) != 0)) {
            mediaActions.add(mPauseAction);
        }

        // Next action
        mNextAction.setEnabled((actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0);
        mediaActions.add(mNextAction);
        return mediaActions;
    }

    /** @return Default {@link RemoteAction} sends broadcast back to SysUI. */
    private RemoteAction getDefaultRemoteAction(@StringRes int titleAndDescription,
            @DrawableRes int icon, String action) {
        final String titleAndDescriptionStr = mContext.getString(titleAndDescription);
        final Intent intent = new Intent(action);
        intent.setPackage(mContext.getPackageName());
        return new RemoteAction(Icon.createWithResource(mContext, icon),
                titleAndDescriptionStr, titleAndDescriptionStr,
                PendingIntent.getBroadcast(mContext, 0 /* requestCode */, intent,
                        FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE));
    }

    /**
     * Re-registers the session listener for the current user.
     */
    public void registerSessionListenerForCurrentUser() {
        mMediaSessionManager.removeOnActiveSessionsChangedListener(mSessionsChangedListener);
        mMediaSessionManager.addOnActiveSessionsChangedListener(null, UserHandle.CURRENT,
                mHandlerExecutor, mSessionsChangedListener);
    }

    /**
     * Tries to find and set the active media controller for the top PiP activity.
     */
    private void resolveActiveMediaController(List<MediaController> controllers) {
        if (controllers != null) {
            final ComponentName topActivity = PipUtils.getTopPipActivity(mContext).first;
            if (topActivity != null) {
                for (int i = 0; i < controllers.size(); i++) {
                    final MediaController controller = controllers.get(i);
                    if (controller.getPackageName().equals(topActivity.getPackageName())) {
                        setActiveMediaController(controller);
                        return;
                    }
                }
            }
        }
        setActiveMediaController(null);
    }

    /**
     * Sets the active media controller for the top PiP activity.
     */
    private void setActiveMediaController(MediaController controller) {
        if (controller != mMediaController) {
            if (mMediaController != null) {
                mMediaController.unregisterCallback(mPlaybackChangedListener);
            }
            mMediaController = controller;
            if (controller != null) {
                controller.registerCallback(mPlaybackChangedListener, mMainHandler);
            }
            notifyActionsChanged();
            notifyMetadataChanged(getMediaMetadata());

            // TODO(winsonc): Consider if we want to close the PIP after a timeout (like on TV)
        }
    }

    /**
     * Notifies all listeners that the actions have changed.
     */
    private void notifyActionsChanged() {
        if (!mActionListeners.isEmpty()) {
            List<RemoteAction> actions = getMediaActions();
            mActionListeners.forEach(l -> l.onMediaActionsChanged(actions));
        }
    }

    /**
     * Notifies all listeners that the metadata have changed.
     */
    private void notifyMetadataChanged(MediaMetadata metadata) {
        if (!mMetadataListeners.isEmpty()) {
            mMetadataListeners.forEach(l -> l.onMediaMetadataChanged(metadata));
        }
    }
}
