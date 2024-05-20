/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.session;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.VolumeProvider;
import android.media.VolumeProvider.ControlType;
import android.media.session.MediaSession.QueueItem;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Allows an app to interact with an ongoing media session. Media buttons and
 * other commands can be sent to the session. A callback may be registered to
 * receive updates from the session, such as metadata and play state changes.
 * <p>
 * A MediaController can be created through {@link MediaSessionManager} if you
 * hold the "android.permission.MEDIA_CONTENT_CONTROL" permission or are an
 * enabled notification listener or by getting a {@link MediaSession.Token}
 * directly from the session owner.
 * <p>
 * MediaController objects are thread-safe.
 */
public final class MediaController {
    private static final String TAG = "MediaController";

    private static final int MSG_EVENT = 1;
    private static final int MSG_UPDATE_PLAYBACK_STATE = 2;
    private static final int MSG_UPDATE_METADATA = 3;
    private static final int MSG_UPDATE_VOLUME = 4;
    private static final int MSG_UPDATE_QUEUE = 5;
    private static final int MSG_UPDATE_QUEUE_TITLE = 6;
    private static final int MSG_UPDATE_EXTRAS = 7;
    private static final int MSG_DESTROYED = 8;

    private final ISessionController mSessionBinder;

    private final MediaSession.Token mToken;
    private final Context mContext;
    private final CallbackStub mCbStub = new CallbackStub(this);
    private final ArrayList<MessageHandler> mCallbacks = new ArrayList<MessageHandler>();
    private final Object mLock = new Object();

    private boolean mCbRegistered = false;
    private String mPackageName;
    private String mTag;
    private Bundle mSessionInfo;

    private final TransportControls mTransportControls;

    /**
     * Create a new MediaController from a session's token.
     *
     * @param context The caller's context.
     * @param token The token for the session.
     */
    public MediaController(@NonNull Context context, @NonNull MediaSession.Token token) {
        if (context == null) {
            throw new IllegalArgumentException("context shouldn't be null");
        }
        if (token == null) {
            throw new IllegalArgumentException("token shouldn't be null");
        }
        if (token.getBinder() == null) {
            throw new IllegalArgumentException("token.getBinder() shouldn't be null");
        }
        mSessionBinder = token.getBinder();
        mTransportControls = new TransportControls();
        mToken = token;
        mContext = context;
    }

    /**
     * Get a {@link TransportControls} instance to send transport actions to
     * the associated session.
     *
     * @return A transport controls instance.
     */
    public @NonNull TransportControls getTransportControls() {
        return mTransportControls;
    }

    /**
     * Send the specified media button event to the session. Only media keys can
     * be sent by this method, other keys will be ignored.
     *
     * @param keyEvent The media button event to dispatch.
     * @return true if the event was sent to the session, false otherwise.
     */
    public boolean dispatchMediaButtonEvent(@NonNull KeyEvent keyEvent) {
        if (keyEvent == null) {
            throw new IllegalArgumentException("KeyEvent may not be null");
        }
        if (!KeyEvent.isMediaSessionKey(keyEvent.getKeyCode())) {
            return false;
        }
        try {
            return mSessionBinder.sendMediaButton(mContext.getPackageName(), keyEvent);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get the current playback state for this session.
     *
     * @return The current PlaybackState or null
     */
    public @Nullable PlaybackState getPlaybackState() {
        try {
            return mSessionBinder.getPlaybackState();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get the current metadata for this session.
     *
     * @return The current MediaMetadata or null.
     */
    public @Nullable MediaMetadata getMetadata() {
        try {
            return mSessionBinder.getMetadata();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get the current play queue for this session if one is set. If you only
     * care about the current item {@link #getMetadata()} should be used.
     *
     * @return The current play queue or null.
     */
    public @Nullable List<MediaSession.QueueItem> getQueue() {
        try {
            ParceledListSlice list = mSessionBinder.getQueue();
            return list == null ? null : list.getList();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get the queue title for this session.
     */
    public @Nullable CharSequence getQueueTitle() {
        try {
            return mSessionBinder.getQueueTitle();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get the extras for this session.
     */
    public @Nullable Bundle getExtras() {
        try {
            return mSessionBinder.getExtras();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get the rating type supported by the session. One of:
     * <ul>
     * <li>{@link Rating#RATING_NONE}</li>
     * <li>{@link Rating#RATING_HEART}</li>
     * <li>{@link Rating#RATING_THUMB_UP_DOWN}</li>
     * <li>{@link Rating#RATING_3_STARS}</li>
     * <li>{@link Rating#RATING_4_STARS}</li>
     * <li>{@link Rating#RATING_5_STARS}</li>
     * <li>{@link Rating#RATING_PERCENTAGE}</li>
     * </ul>
     *
     * @return The supported rating type
     */
    public int getRatingType() {
        try {
            return mSessionBinder.getRatingType();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get the flags for this session. Flags are defined in {@link MediaSession}.
     *
     * @return The current set of flags for the session.
     */
    public long getFlags() {
        try {
            return mSessionBinder.getFlags();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /** Returns the current playback info for this session. */
    @NonNull
    public PlaybackInfo getPlaybackInfo() {
        try {
            return mSessionBinder.getVolumeAttributes();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get an intent for launching UI associated with this session if one
     * exists.
     *
     * @return A {@link PendingIntent} to launch UI or null.
     */
    public @Nullable PendingIntent getSessionActivity() {
        try {
            return mSessionBinder.getLaunchPendingIntent();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get the token for the session this is connected to.
     *
     * @return The token for the connected session.
     */
    public @NonNull MediaSession.Token getSessionToken() {
        return mToken;
    }

    /**
     * Set the volume of the output this session is playing on. The command will
     * be ignored if it does not support
     * {@link VolumeProvider#VOLUME_CONTROL_ABSOLUTE}. The flags in
     * {@link AudioManager} may be used to affect the handling.
     *
     * @see #getPlaybackInfo()
     * @param value The value to set it to, between 0 and the reported max.
     * @param flags Flags from {@link AudioManager} to include with the volume
     *            request.
     */
    public void setVolumeTo(int value, int flags) {
        try {
            // Note: Need both package name and OP package name. Package name is used for
            //       RemoteUserInfo, and OP package name is used for AudioService's internal
            //       AppOpsManager usages.
            mSessionBinder.setVolumeTo(mContext.getPackageName(), mContext.getOpPackageName(),
                    value, flags);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Adjust the volume of the output this session is playing on. The direction
     * must be one of {@link AudioManager#ADJUST_LOWER},
     * {@link AudioManager#ADJUST_RAISE}, or {@link AudioManager#ADJUST_SAME}.
     * The command will be ignored if the session does not support
     * {@link VolumeProvider#VOLUME_CONTROL_RELATIVE} or
     * {@link VolumeProvider#VOLUME_CONTROL_ABSOLUTE}. The flags in
     * {@link AudioManager} may be used to affect the handling.
     *
     * @see #getPlaybackInfo()
     * @param direction The direction to adjust the volume in.
     * @param flags Any flags to pass with the command.
     */
    public void adjustVolume(int direction, int flags) {
        try {
            // Note: Need both package name and OP package name. Package name is used for
            //       RemoteUserInfo, and OP package name is used for AudioService's internal
            //       AppOpsManager usages.
            mSessionBinder.adjustVolume(mContext.getPackageName(), mContext.getOpPackageName(),
                    direction, flags);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Registers a callback to receive updates from the Session. Updates will be
     * posted on the caller's thread.
     *
     * @param callback The callback object, must not be null.
     */
    public void registerCallback(@NonNull Callback callback) {
        registerCallback(callback, null);
    }

    /**
     * Registers a callback to receive updates from the session. Updates will be
     * posted on the specified handler's thread.
     *
     * @param callback The callback object, must not be null.
     * @param handler The handler to post updates on. If null the callers thread
     *            will be used.
     */
    public void registerCallback(@NonNull Callback callback, @Nullable Handler handler) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        if (handler == null) {
            handler = new Handler();
        }
        synchronized (mLock) {
            addCallbackLocked(callback, handler);
        }
    }

    /**
     * Unregisters the specified callback. If an update has already been posted
     * you may still receive it after calling this method.
     *
     * @param callback The callback to remove.
     */
    public void unregisterCallback(@NonNull Callback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null");
        }
        synchronized (mLock) {
            removeCallbackLocked(callback);
        }
    }

    /**
     * Sends a generic command to the session. It is up to the session creator
     * to decide what commands and parameters they will support. As such,
     * commands should only be sent to sessions that the controller owns.
     *
     * @param command The command to send
     * @param args Any parameters to include with the command
     * @param cb The callback to receive the result on
     */
    public void sendCommand(@NonNull String command, @Nullable Bundle args,
            @Nullable ResultReceiver cb) {
        if (TextUtils.isEmpty(command)) {
            throw new IllegalArgumentException("command cannot be null or empty");
        }
        try {
            mSessionBinder.sendCommand(mContext.getPackageName(), command, args, cb);
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }
    }

    /**
     * Get the session owner's package name.
     *
     * @return The package name of the session owner.
     */
    public String getPackageName() {
        if (mPackageName == null) {
            try {
                mPackageName = mSessionBinder.getPackageName();
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }
        return mPackageName;
    }

    /**
     * Gets the additional session information which was set when the session was created.
     *
     * @return The additional session information, or an empty {@link Bundle} if not set.
     */
    @NonNull
    public Bundle getSessionInfo() {
        if (mSessionInfo != null) {
            return new Bundle(mSessionInfo);
        }

        // Get info from the connected session.
        try {
            mSessionInfo = mSessionBinder.getSessionInfo();
        } catch (RemoteException ex) {
            throw ex.rethrowFromSystemServer();
        }

        if (mSessionInfo == null) {
            Log.d(TAG, "sessionInfo is not set.");
            mSessionInfo = Bundle.EMPTY;
        } else if (MediaSession.hasCustomParcelable(mSessionInfo)) {
            Log.w(TAG, "sessionInfo contains custom parcelable. Ignoring.");
            mSessionInfo = Bundle.EMPTY;
        }
        return new Bundle(mSessionInfo);
    }

    /**
     * Get the session's tag for debugging purposes.
     *
     * @return The session's tag.
     */
    @NonNull
    public String getTag() {
        if (mTag == null) {
            try {
                mTag = mSessionBinder.getTag();
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }
        return mTag;
    }

    /**
     * @hide
     * Returns whether this and {@code other} media controller controls the same session.
     */
    @UnsupportedAppUsage(publicAlternatives = "Check equality of {@link #getSessionToken() tokens} "
            + "instead.", maxTargetSdk = Build.VERSION_CODES.R)
    public boolean controlsSameSession(@Nullable MediaController other) {
        if (other == null) return false;
        return mToken.equals(other.mToken);
    }

    private void addCallbackLocked(Callback cb, Handler handler) {
        if (getHandlerForCallbackLocked(cb) != null) {
            Log.w(TAG, "Callback is already added, ignoring");
            return;
        }
        MessageHandler holder = new MessageHandler(handler.getLooper(), cb);
        mCallbacks.add(holder);
        holder.mRegistered = true;

        if (!mCbRegistered) {
            try {
                mSessionBinder.registerCallback(mContext.getPackageName(), mCbStub);
                mCbRegistered = true;
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }
    }

    private boolean removeCallbackLocked(Callback cb) {
        boolean success = false;
        for (int i = mCallbacks.size() - 1; i >= 0; i--) {
            MessageHandler handler = mCallbacks.get(i);
            if (cb == handler.mCallback) {
                mCallbacks.remove(i);
                success = true;
                handler.mRegistered = false;
            }
        }
        if (mCbRegistered && mCallbacks.size() == 0) {
            try {
                mSessionBinder.unregisterCallback(mCbStub);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
            mCbRegistered = false;
        }
        return success;
    }

    /**
     * Gets associated handler for the given callback.
     * @hide
     */
    @VisibleForTesting
    public Handler getHandlerForCallback(Callback cb) {
        synchronized (mLock) {
            return getHandlerForCallbackLocked(cb);
        }
    }

    private MessageHandler getHandlerForCallbackLocked(Callback cb) {
        if (cb == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        for (int i = mCallbacks.size() - 1; i >= 0; i--) {
            MessageHandler handler = mCallbacks.get(i);
            if (cb == handler.mCallback) {
                return handler;
            }
        }
        return null;
    }

    private void postMessage(int what, Object obj, Bundle data) {
        synchronized (mLock) {
            for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                mCallbacks.get(i).post(what, obj, data);
            }
        }
    }

    /**
     * Callback for receiving updates from the session. A Callback can be
     * registered using {@link #registerCallback}.
     */
    public abstract static class Callback {
        /**
         * Override to handle the session being destroyed. The session is no
         * longer valid after this call and calls to it will be ignored.
         */
        public void onSessionDestroyed() {
        }

        /**
         * Override to handle custom events sent by the session owner without a
         * specified interface. Controllers should only handle these for
         * sessions they own.
         *
         * @param event The event from the session.
         * @param extras Optional parameters for the event, may be null.
         */
        public void onSessionEvent(@NonNull String event, @Nullable Bundle extras) {
        }

        /**
         * Override to handle changes in playback state.
         *
         * @param state The new playback state of the session
         */
        public void onPlaybackStateChanged(@Nullable PlaybackState state) {
        }

        /**
         * Override to handle changes to the current metadata.
         *
         * @param metadata The current metadata for the session or null if none.
         * @see MediaMetadata
         */
        public void onMetadataChanged(@Nullable MediaMetadata metadata) {
        }

        /**
         * Override to handle changes to items in the queue.
         *
         * @param queue A list of items in the current play queue. It should
         *            include the currently playing item as well as previous and
         *            upcoming items if applicable.
         * @see MediaSession.QueueItem
         */
        public void onQueueChanged(@Nullable List<MediaSession.QueueItem> queue) {
        }

        /**
         * Override to handle changes to the queue title.
         *
         * @param title The title that should be displayed along with the play queue such as
         *              "Now Playing". May be null if there is no such title.
         */
        public void onQueueTitleChanged(@Nullable CharSequence title) {
        }

        /**
         * Override to handle changes to the {@link MediaSession} extras.
         *
         * @param extras The extras that can include other information associated with the
         *               {@link MediaSession}.
         */
        public void onExtrasChanged(@Nullable Bundle extras) {
        }

        /**
         * Override to handle changes to the audio info.
         *
         * @param info The current audio info for this session.
         */
        public void onAudioInfoChanged(PlaybackInfo info) {
        }
    }

    /**
     * Interface for controlling media playback on a session. This allows an app
     * to send media transport commands to the session.
     */
    public final class TransportControls {
        private static final String TAG = "TransportController";

        private TransportControls() {
        }

        /**
         * Request that the player prepare its playback. In other words, other sessions can continue
         * to play during the preparation of this session. This method can be used to speed up the
         * start of the playback. Once the preparation is done, the session will change its playback
         * state to {@link PlaybackState#STATE_PAUSED}. Afterwards, {@link #play} can be called to
         * start playback.
         */
        public void prepare() {
            try {
                mSessionBinder.prepare(mContext.getPackageName());
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Request that the player prepare playback for a specific media id. In other words, other
         * sessions can continue to play during the preparation of this session. This method can be
         * used to speed up the start of the playback. Once the preparation is done, the session
         * will change its playback state to {@link PlaybackState#STATE_PAUSED}. Afterwards,
         * {@link #play} can be called to start playback. If the preparation is not needed,
         * {@link #playFromMediaId} can be directly called without this method.
         *
         * @param mediaId The id of the requested media.
         * @param extras Optional extras that can include extra information about the media item
         *               to be prepared.
         */
        public void prepareFromMediaId(String mediaId, Bundle extras) {
            if (TextUtils.isEmpty(mediaId)) {
                throw new IllegalArgumentException(
                        "You must specify a non-empty String for prepareFromMediaId.");
            }
            try {
                mSessionBinder.prepareFromMediaId(mContext.getPackageName(), mediaId, extras);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Request that the player prepare playback for a specific search query. An empty or null
         * query should be treated as a request to prepare any music. In other words, other sessions
         * can continue to play during the preparation of this session. This method can be used to
         * speed up the start of the playback. Once the preparation is done, the session will
         * change its playback state to {@link PlaybackState#STATE_PAUSED}. Afterwards,
         * {@link #play} can be called to start playback. If the preparation is not needed,
         * {@link #playFromSearch} can be directly called without this method.
         *
         * @param query The search query.
         * @param extras Optional extras that can include extra information
         *               about the query.
         */
        public void prepareFromSearch(String query, Bundle extras) {
            if (query == null) {
                // This is to remain compatible with
                // INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH
                query = "";
            }
            try {
                mSessionBinder.prepareFromSearch(mContext.getPackageName(), query, extras);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Request that the player prepare playback for a specific {@link Uri}. In other words,
         * other sessions can continue to play during the preparation of this session. This method
         * can be used to speed up the start of the playback. Once the preparation is done, the
         * session will change its playback state to {@link PlaybackState#STATE_PAUSED}. Afterwards,
         * {@link #play} can be called to start playback. If the preparation is not needed,
         * {@link #playFromUri} can be directly called without this method.
         *
         * @param uri The URI of the requested media.
         * @param extras Optional extras that can include extra information about the media item
         *               to be prepared.
         */
        public void prepareFromUri(Uri uri, Bundle extras) {
            if (uri == null || Uri.EMPTY.equals(uri)) {
                throw new IllegalArgumentException(
                        "You must specify a non-empty Uri for prepareFromUri.");
            }
            try {
                mSessionBinder.prepareFromUri(mContext.getPackageName(), uri, extras);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Request that the player start its playback at its current position.
         */
        public void play() {
            try {
                mSessionBinder.play(mContext.getPackageName());
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Request that the player start playback for a specific media id.
         *
         * @param mediaId The id of the requested media.
         * @param extras Optional extras that can include extra information about the media item
         *               to be played.
         */
        public void playFromMediaId(String mediaId, Bundle extras) {
            if (TextUtils.isEmpty(mediaId)) {
                throw new IllegalArgumentException(
                        "You must specify a non-empty String for playFromMediaId.");
            }
            try {
                mSessionBinder.playFromMediaId(mContext.getPackageName(), mediaId, extras);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Request that the player start playback for a specific search query.
         * An empty or null query should be treated as a request to play any
         * music.
         *
         * @param query The search query.
         * @param extras Optional extras that can include extra information
         *               about the query.
         */
        public void playFromSearch(String query, Bundle extras) {
            if (query == null) {
                // This is to remain compatible with
                // INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH
                query = "";
            }
            try {
                mSessionBinder.playFromSearch(mContext.getPackageName(), query, extras);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Request that the player start playback for a specific {@link Uri}.
         *
         * @param uri The URI of the requested media.
         * @param extras Optional extras that can include extra information about the media item
         *               to be played.
         */
        public void playFromUri(Uri uri, Bundle extras) {
            if (uri == null || Uri.EMPTY.equals(uri)) {
                throw new IllegalArgumentException(
                        "You must specify a non-empty Uri for playFromUri.");
            }
            try {
                mSessionBinder.playFromUri(mContext.getPackageName(), uri, extras);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Play an item with a specific id in the play queue. If you specify an
         * id that is not in the play queue, the behavior is undefined.
         */
        public void skipToQueueItem(long id) {
            try {
                mSessionBinder.skipToQueueItem(mContext.getPackageName(), id);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Request that the player pause its playback and stay at its current
         * position.
         */
        public void pause() {
            try {
                mSessionBinder.pause(mContext.getPackageName());
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Request that the player stop its playback; it may clear its state in
         * whatever way is appropriate.
         */
        public void stop() {
            try {
                mSessionBinder.stop(mContext.getPackageName());
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Move to a new location in the media stream.
         *
         * @param pos Position to move to, in milliseconds.
         */
        public void seekTo(long pos) {
            try {
                mSessionBinder.seekTo(mContext.getPackageName(), pos);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Start fast forwarding. If playback is already fast forwarding this
         * may increase the rate.
         */
        public void fastForward() {
            try {
                mSessionBinder.fastForward(mContext.getPackageName());
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Skip to the next item.
         */
        public void skipToNext() {
            try {
                mSessionBinder.next(mContext.getPackageName());
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Start rewinding. If playback is already rewinding this may increase
         * the rate.
         */
        public void rewind() {
            try {
                mSessionBinder.rewind(mContext.getPackageName());
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Skip to the previous item.
         */
        public void skipToPrevious() {
            try {
                mSessionBinder.previous(mContext.getPackageName());
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Rate the current content. This will cause the rating to be set for
         * the current user. The Rating type must match the type returned by
         * {@link #getRatingType()}.
         *
         * @param rating The rating to set for the current content
         */
        public void setRating(Rating rating) {
            try {
                mSessionBinder.rate(mContext.getPackageName(), rating);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Sets the playback speed. A value of {@code 1.0f} is the default playback value,
         * and a negative value indicates reverse playback. {@code 0.0f} is not allowed.
         *
         * @param speed The playback speed
         * @throws IllegalArgumentException if the {@code speed} is equal to zero.
         */
        public void setPlaybackSpeed(float speed) {
            if (speed == 0.0f) {
                throw new IllegalArgumentException("speed must not be zero");
            }
            try {
                mSessionBinder.setPlaybackSpeed(mContext.getPackageName(), speed);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }

        /**
         * Send a custom action back for the {@link MediaSession} to perform.
         *
         * @param customAction The action to perform.
         * @param args Optional arguments to supply to the {@link MediaSession} for this
         *             custom action.
         */
        public void sendCustomAction(@NonNull PlaybackState.CustomAction customAction,
                @Nullable Bundle args) {
            if (customAction == null) {
                throw new IllegalArgumentException("CustomAction cannot be null.");
            }
            sendCustomAction(customAction.getAction(), args);
        }

        /**
         * Send the id and args from a custom action back for the {@link MediaSession} to perform.
         *
         * @see #sendCustomAction(PlaybackState.CustomAction action, Bundle args)
         * @param action The action identifier of the {@link PlaybackState.CustomAction} as
         *               specified by the {@link MediaSession}.
         * @param args Optional arguments to supply to the {@link MediaSession} for this
         *             custom action.
         */
        public void sendCustomAction(@NonNull String action, @Nullable Bundle args) {
            if (TextUtils.isEmpty(action)) {
                throw new IllegalArgumentException("CustomAction cannot be null.");
            }
            try {
                mSessionBinder.sendCustomAction(mContext.getPackageName(), action, args);
            } catch (RemoteException ex) {
                throw ex.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Holds information about the current playback and how audio is handled for
     * this session.
     */
    public static final class PlaybackInfo implements Parcelable {

        /**
         * @hide
         */
        @IntDef({PLAYBACK_TYPE_LOCAL, PLAYBACK_TYPE_REMOTE})
        @Retention(RetentionPolicy.SOURCE)
        public @interface PlaybackType {}

        /**
         * The session uses local playback.
         */
        public static final int PLAYBACK_TYPE_LOCAL = 1;
        /**
         * The session uses remote playback.
         */
        public static final int PLAYBACK_TYPE_REMOTE = 2;

        private final int mPlaybackType;
        private final int mVolumeControl;
        private final int mMaxVolume;
        private final int mCurrentVolume;
        private final AudioAttributes mAudioAttrs;
        private final String mVolumeControlId;

        /**
         * Creates a new playback info.
         *
         * @param playbackType The playback type. Should be {@link #PLAYBACK_TYPE_LOCAL} or {@link
         *     #PLAYBACK_TYPE_REMOTE}
         * @param volumeControl See {@link #getVolumeControl()}.
         * @param maxVolume The max volume. Should be equal or greater than zero.
         * @param currentVolume The current volume. Should be in the interval [0, maxVolume].
         * @param audioAttrs The audio attributes for this playback. Should not be null.
         * @param volumeControlId See {@link #getVolumeControlId()}.
         * @hide
         */
        @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
        public PlaybackInfo(
                @PlaybackType int playbackType,
                @ControlType int volumeControl,
                @IntRange(from = 0) int maxVolume,
                @IntRange(from = 0) int currentVolume,
                @NonNull AudioAttributes audioAttrs,
                @Nullable String volumeControlId) {
            mPlaybackType = playbackType;
            mVolumeControl = volumeControl;
            mMaxVolume = maxVolume;
            mCurrentVolume = currentVolume;
            mAudioAttrs = audioAttrs;
            mVolumeControlId = volumeControlId;
        }

        PlaybackInfo(Parcel in) {
            mPlaybackType = in.readInt();
            mVolumeControl = in.readInt();
            mMaxVolume = in.readInt();
            mCurrentVolume = in.readInt();
            mAudioAttrs = in.readParcelable(null, android.media.AudioAttributes.class);
            mVolumeControlId = in.readString();
        }

        /**
         * Get the type of playback which affects volume handling. One of:
         * <ul>
         * <li>{@link #PLAYBACK_TYPE_LOCAL}</li>
         * <li>{@link #PLAYBACK_TYPE_REMOTE}</li>
         * </ul>
         *
         * @return The type of playback this session is using.
         */
        public int getPlaybackType() {
            return mPlaybackType;
        }

        /**
         * Get the volume control type associated to the session, as indicated by {@link
         * VolumeProvider#getVolumeControl()}.
         */
        public int getVolumeControl() {
            return mVolumeControl;
        }

        /**
         * Get the maximum volume that may be set for this session.
         *
         * @return The maximum allowed volume where this session is playing.
         */
        public int getMaxVolume() {
            return mMaxVolume;
        }

        /**
         * Get the current volume for this session.
         *
         * @return The current volume where this session is playing.
         */
        public int getCurrentVolume() {
            return mCurrentVolume;
        }

        /**
         * Get the audio attributes for this session. The attributes will affect volume handling for
         * the session. When the playback type is {@link PlaybackInfo#PLAYBACK_TYPE_REMOTE} these
         * may be ignored by the remote volume handler.
         *
         * @return The attributes for this session.
         */
        public AudioAttributes getAudioAttributes() {
            return mAudioAttrs;
        }

        /**
         * Get the routing controller ID for this session, as indicated by {@link
         * VolumeProvider#getVolumeControlId()}. Returns null if unset, or if {@link
         * #getPlaybackType()} is {@link #PLAYBACK_TYPE_LOCAL}.
         */
        @Nullable
        public String getVolumeControlId() {
            return mVolumeControlId;
        }

        @Override
        public String toString() {
            return "playbackType=" + mPlaybackType + ", volumeControlType=" + mVolumeControl
                    + ", maxVolume=" + mMaxVolume + ", currentVolume=" + mCurrentVolume
                    + ", audioAttrs=" + mAudioAttrs + ", volumeControlId=" + mVolumeControlId;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mPlaybackType);
            dest.writeInt(mVolumeControl);
            dest.writeInt(mMaxVolume);
            dest.writeInt(mCurrentVolume);
            dest.writeParcelable(mAudioAttrs, flags);
            dest.writeString(mVolumeControlId);
        }

        public static final @android.annotation.NonNull Parcelable.Creator<PlaybackInfo> CREATOR =
                new Parcelable.Creator<PlaybackInfo>() {
            @Override
            public PlaybackInfo createFromParcel(Parcel in) {
                return new PlaybackInfo(in);
            }

            @Override
            public PlaybackInfo[] newArray(int size) {
                return new PlaybackInfo[size];
            }
        };
    }

    private static final class CallbackStub extends ISessionControllerCallback.Stub {
        private final WeakReference<MediaController> mController;

        CallbackStub(MediaController controller) {
            mController = new WeakReference<MediaController>(controller);
        }

        @Override
        public void onSessionDestroyed() {
            MediaController controller = mController.get();
            if (controller != null) {
                controller.postMessage(MSG_DESTROYED, null, null);
            }
        }

        @Override
        public void onEvent(String event, Bundle extras) {
            MediaController controller = mController.get();
            if (controller != null) {
                controller.postMessage(MSG_EVENT, event, extras);
            }
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            MediaController controller = mController.get();
            if (controller != null) {
                controller.postMessage(MSG_UPDATE_PLAYBACK_STATE, state, null);
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            MediaController controller = mController.get();
            if (controller != null) {
                controller.postMessage(MSG_UPDATE_METADATA, metadata, null);
            }
        }

        @Override
        public void onQueueChanged(ParceledListSlice queue) {
            MediaController controller = mController.get();
            if (controller != null) {
                controller.postMessage(MSG_UPDATE_QUEUE, queue, null);
            }
        }

        @Override
        public void onQueueTitleChanged(CharSequence title) {
            MediaController controller = mController.get();
            if (controller != null) {
                controller.postMessage(MSG_UPDATE_QUEUE_TITLE, title, null);
            }
        }

        @Override
        public void onExtrasChanged(Bundle extras) {
            MediaController controller = mController.get();
            if (controller != null) {
                controller.postMessage(MSG_UPDATE_EXTRAS, extras, null);
            }
        }

        @Override
        public void onVolumeInfoChanged(PlaybackInfo info) {
            MediaController controller = mController.get();
            if (controller != null) {
                controller.postMessage(MSG_UPDATE_VOLUME, info, null);
            }
        }
    }

    private static final class MessageHandler extends Handler {
        private final MediaController.Callback mCallback;
        private boolean mRegistered = false;

        MessageHandler(Looper looper, MediaController.Callback cb) {
            super(looper);
            mCallback = cb;
        }

        @Override
        public void handleMessage(Message msg) {
            if (!mRegistered) {
                return;
            }
            switch (msg.what) {
                case MSG_EVENT:
                    mCallback.onSessionEvent((String) msg.obj, msg.getData());
                    break;
                case MSG_UPDATE_PLAYBACK_STATE:
                    mCallback.onPlaybackStateChanged((PlaybackState) msg.obj);
                    break;
                case MSG_UPDATE_METADATA:
                    mCallback.onMetadataChanged((MediaMetadata) msg.obj);
                    break;
                case MSG_UPDATE_QUEUE:
                    mCallback.onQueueChanged(msg.obj == null ? null :
                            (List<QueueItem>) ((ParceledListSlice) msg.obj).getList());
                    break;
                case MSG_UPDATE_QUEUE_TITLE:
                    mCallback.onQueueTitleChanged((CharSequence) msg.obj);
                    break;
                case MSG_UPDATE_EXTRAS:
                    mCallback.onExtrasChanged((Bundle) msg.obj);
                    break;
                case MSG_UPDATE_VOLUME:
                    mCallback.onAudioInfoChanged((PlaybackInfo) msg.obj);
                    break;
                case MSG_DESTROYED:
                    mCallback.onSessionDestroyed();
                    break;
            }
        }

        public void post(int what, Object obj, Bundle data) {
            Message msg = obtainMessage(what, obj);
            msg.setAsynchronous(true);
            msg.setData(data);
            msg.sendToTarget();
        }
    }

}
