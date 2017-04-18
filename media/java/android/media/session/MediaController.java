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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.VolumeProvider;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

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

    private final TransportControls mTransportControls;

    /**
     * Call for creating a MediaController directly from a binder. Should only
     * be used by framework code.
     *
     * @hide
     */
    public MediaController(Context context, ISessionController sessionBinder) {
        if (sessionBinder == null) {
            throw new IllegalArgumentException("Session token cannot be null");
        }
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        mSessionBinder = sessionBinder;
        mTransportControls = new TransportControls();
        mToken = new MediaSession.Token(sessionBinder);
        mContext = context;
    }

    /**
     * Create a new MediaController from a session's token.
     *
     * @param context The caller's context.
     * @param token The token for the session.
     */
    public MediaController(@NonNull Context context, @NonNull MediaSession.Token token) {
        this(context, token.getBinder());
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
        if (!KeyEvent.isMediaKey(keyEvent.getKeyCode())) {
            return false;
        }
        try {
            return mSessionBinder.sendMediaButton(keyEvent);
        } catch (RemoteException e) {
            // System is dead. =(
        }
        return false;
    }

    /**
     * Get the current playback state for this session.
     *
     * @return The current PlaybackState or null
     */
    public @Nullable PlaybackState getPlaybackState() {
        try {
            return mSessionBinder.getPlaybackState();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling getPlaybackState.", e);
            return null;
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
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling getMetadata.", e);
            return null;
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
            ParceledListSlice queue = mSessionBinder.getQueue();
            if (queue != null) {
                return queue.getList();
            }
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling getQueue.", e);
        }
        return null;
    }

    /**
     * Get the queue title for this session.
     */
    public @Nullable CharSequence getQueueTitle() {
        try {
            return mSessionBinder.getQueueTitle();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling getQueueTitle", e);
        }
        return null;
    }

    /**
     * Get the extras for this session.
     */
    public @Nullable Bundle getExtras() {
        try {
            return mSessionBinder.getExtras();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling getExtras", e);
        }
        return null;
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
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling getRatingType.", e);
            return Rating.RATING_NONE;
        }
    }

    /**
     * Get the flags for this session. Flags are defined in {@link MediaSession}.
     *
     * @return The current set of flags for the session.
     */
    public @MediaSession.SessionFlags long getFlags() {
        try {
            return mSessionBinder.getFlags();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling getFlags.", e);
        }
        return 0;
    }

    /**
     * Get the current playback info for this session.
     *
     * @return The current playback info or null.
     */
    public @Nullable PlaybackInfo getPlaybackInfo() {
        try {
            ParcelableVolumeInfo result = mSessionBinder.getVolumeAttributes();
            return new PlaybackInfo(result.volumeType, result.audioAttrs, result.controlType,
                    result.maxVolume, result.currentVolume);

        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling getAudioInfo.", e);
        }
        return null;
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
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling getPendingIntent.", e);
        }
        return null;
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
            mSessionBinder.setVolumeTo(value, flags, mContext.getPackageName());
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling setVolumeTo.", e);
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
            mSessionBinder.adjustVolume(direction, flags, mContext.getPackageName());
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling adjustVolumeBy.", e);
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
            mSessionBinder.sendCommand(command, args, cb);
        } catch (RemoteException e) {
            Log.d(TAG, "Dead object in sendCommand.", e);
        }
    }

    /**
     * Get the session owner's package name.
     *
     * @return The package name of of the session owner.
     */
    public String getPackageName() {
        if (mPackageName == null) {
            try {
                mPackageName = mSessionBinder.getPackageName();
            } catch (RemoteException e) {
                Log.d(TAG, "Dead object in getPackageName.", e);
            }
        }
        return mPackageName;
    }

    /**
     * Get the session's tag for debugging purposes.
     *
     * @return The session's tag.
     * @hide
     */
    public String getTag() {
        if (mTag == null) {
            try {
                mTag = mSessionBinder.getTag();
            } catch (RemoteException e) {
                Log.d(TAG, "Dead object in getTag.", e);
            }
        }
        return mTag;
    }

    /*
     * @hide
     */
    ISessionController getSessionBinder() {
        return mSessionBinder;
    }

    /**
     * @hide
     */
    public boolean controlsSameSession(MediaController other) {
        if (other == null) return false;
        return mSessionBinder.asBinder() == other.getSessionBinder().asBinder();
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
                mSessionBinder.registerCallbackListener(mCbStub);
                mCbRegistered = true;
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in registerCallback", e);
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
                mSessionBinder.unregisterCallbackListener(mCbStub);
            } catch (RemoteException e) {
                Log.e(TAG, "Dead object in removeCallbackLocked");
            }
            mCbRegistered = false;
        }
        return success;
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

    private final void postMessage(int what, Object obj, Bundle data) {
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
    public static abstract class Callback {
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
        public void onPlaybackStateChanged(@NonNull PlaybackState state) {
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
                mSessionBinder.prepare();
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling prepare.", e);
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
                mSessionBinder.prepareFromMediaId(mediaId, extras);
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling prepare(" + mediaId + ").", e);
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
                mSessionBinder.prepareFromSearch(query, extras);
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling prepare(" + query + ").", e);
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
                mSessionBinder.prepareFromUri(uri, extras);
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling prepare(" + uri + ").", e);
            }
        }

        /**
         * Request that the player start its playback at its current position.
         */
        public void play() {
            try {
                mSessionBinder.play();
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling play.", e);
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
                mSessionBinder.playFromMediaId(mediaId, extras);
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling play(" + mediaId + ").", e);
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
                mSessionBinder.playFromSearch(query, extras);
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling play(" + query + ").", e);
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
                mSessionBinder.playFromUri(uri, extras);
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling play(" + uri + ").", e);
            }
        }

        /**
         * Play an item with a specific id in the play queue. If you specify an
         * id that is not in the play queue, the behavior is undefined.
         */
        public void skipToQueueItem(long id) {
            try {
                mSessionBinder.skipToQueueItem(id);
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling skipToItem(" + id + ").", e);
            }
        }

        /**
         * Request that the player pause its playback and stay at its current
         * position.
         */
        public void pause() {
            try {
                mSessionBinder.pause();
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling pause.", e);
            }
        }

        /**
         * Request that the player stop its playback; it may clear its state in
         * whatever way is appropriate.
         */
        public void stop() {
            try {
                mSessionBinder.stop();
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling stop.", e);
            }
        }

        /**
         * Move to a new location in the media stream.
         *
         * @param pos Position to move to, in milliseconds.
         */
        public void seekTo(long pos) {
            try {
                mSessionBinder.seekTo(pos);
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling seekTo.", e);
            }
        }

        /**
         * Start fast forwarding. If playback is already fast forwarding this
         * may increase the rate.
         */
        public void fastForward() {
            try {
                mSessionBinder.fastForward();
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling fastForward.", e);
            }
        }

        /**
         * Skip to the next item.
         */
        public void skipToNext() {
            try {
                mSessionBinder.next();
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling next.", e);
            }
        }

        /**
         * Start rewinding. If playback is already rewinding this may increase
         * the rate.
         */
        public void rewind() {
            try {
                mSessionBinder.rewind();
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling rewind.", e);
            }
        }

        /**
         * Skip to the previous item.
         */
        public void skipToPrevious() {
            try {
                mSessionBinder.previous();
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling previous.", e);
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
                mSessionBinder.rate(rating);
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error calling rate.", e);
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
                mSessionBinder.sendCustomAction(action, args);
            } catch (RemoteException e) {
                Log.d(TAG, "Dead object in sendCustomAction.", e);
            }
        }
    }

    /**
     * Holds information about the current playback and how audio is handled for
     * this session.
     */
    public static final class PlaybackInfo {
        /**
         * The session uses remote playback.
         */
        public static final int PLAYBACK_TYPE_REMOTE = 2;
        /**
         * The session uses local playback.
         */
        public static final int PLAYBACK_TYPE_LOCAL = 1;

        private final int mVolumeType;
        private final int mVolumeControl;
        private final int mMaxVolume;
        private final int mCurrentVolume;
        private final AudioAttributes mAudioAttrs;

        /**
         * @hide
         */
        public PlaybackInfo(int type, AudioAttributes attrs, int control, int max, int current) {
            mVolumeType = type;
            mAudioAttrs = attrs;
            mVolumeControl = control;
            mMaxVolume = max;
            mCurrentVolume = current;
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
            return mVolumeType;
        }

        /**
         * Get the audio attributes for this session. The attributes will affect
         * volume handling for the session. When the volume type is
         * {@link PlaybackInfo#PLAYBACK_TYPE_REMOTE} these may be ignored by the
         * remote volume handler.
         *
         * @return The attributes for this session.
         */
        public AudioAttributes getAudioAttributes() {
            return mAudioAttrs;
        }

        /**
         * Get the type of volume control that can be used. One of:
         * <ul>
         * <li>{@link VolumeProvider#VOLUME_CONTROL_ABSOLUTE}</li>
         * <li>{@link VolumeProvider#VOLUME_CONTROL_RELATIVE}</li>
         * <li>{@link VolumeProvider#VOLUME_CONTROL_FIXED}</li>
         * </ul>
         *
         * @return The type of volume control that may be used with this
         *         session.
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
    }

    private final static class CallbackStub extends ISessionControllerCallback.Stub {
        private final WeakReference<MediaController> mController;

        public CallbackStub(MediaController controller) {
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
        public void onQueueChanged(ParceledListSlice parceledQueue) {
            List<MediaSession.QueueItem> queue = parceledQueue == null ? null : parceledQueue
                    .getList();
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
        public void onVolumeInfoChanged(ParcelableVolumeInfo pvi) {
            MediaController controller = mController.get();
            if (controller != null) {
                PlaybackInfo info = new PlaybackInfo(pvi.volumeType, pvi.audioAttrs, pvi.controlType,
                        pvi.maxVolume, pvi.currentVolume);
                controller.postMessage(MSG_UPDATE_VOLUME, info, null);
            }
        }

    }

    private final static class MessageHandler extends Handler {
        private final MediaController.Callback mCallback;
        private boolean mRegistered = false;

        public MessageHandler(Looper looper, MediaController.Callback cb) {
            super(looper, null, true);
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
                    mCallback.onQueueChanged((List<MediaSession.QueueItem>) msg.obj);
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
            msg.setData(data);
            msg.sendToTarget();
        }
    }

}
