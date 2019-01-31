/*
 * Copyright 2019 The Android Open Source Project
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
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.VolumeProvider;
import android.media.session.MediaSessionManager.RemoteUserInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ResultReceiver;
import android.service.media.MediaBrowserService;
import android.text.TextUtils;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import java.util.List;
import java.util.Objects;

/**
 * @hide
 */
public final class MediaSessionEngine implements AutoCloseable {
    private static final String TAG = "MediaSession";

    private final Object mLock = new Object();

    private final MediaSession.Token mSessionToken;
    private final MediaController mController;
    private final SessionLink mSessionLink;

    private CallbackMessageHandler mCallbackHandler;
    private VolumeProvider mVolumeProvider;
    private PlaybackState mPlaybackState;

    private boolean mActive = false;

    /**
     * Creates a new session. The session will automatically be registered with
     * the system but will not be published until {@link #setActive(boolean)
     * setActive(true)} is called. You must call {@link #close()} when
     * finished with the session.
     *
     * @param context The context to use to create the session.
     * @param sessionLink A session link for the binder of MediaSessionRecord
     */
    public MediaSessionEngine(@NonNull Context context, @NonNull SessionLink sessionLink,
            @NonNull SessionCallbackLink cbLink) {
        mSessionLink = sessionLink;

        cbLink.setSessionEngine(this);
        mSessionToken = new MediaSession.Token(mSessionLink.getController());
        mController = new MediaController(context, mSessionToken);
    }

    /**
     * Set the callback to receive updates for the MediaSession. This includes
     * media button events and transport controls. The caller's thread will be
     * used to post updates.
     * <p>
     * Set the callback to null to stop receiving updates.
     *
     * @param callback The callback object
     */
    public void setCallback(@Nullable MediaSession.Callback callback) {
        setCallback(callback, new Handler());
    }

    /**
     * Set the callback to receive updates for the MediaSession. This includes
     * media button events and transport controls.
     * <p>
     * Set the callback to null to stop receiving updates.
     *
     * @param callback The callback to receive updates on.
     * @param handler The handler that events should be posted on.
     */
    public void setCallback(@Nullable MediaSession.Callback callback, @NonNull Handler handler) {
        setCallbackInternal(callback == null ? null : new CallbackWrapper(callback), handler);
    }

    private void setCallbackInternal(CallbackWrapper callback, Handler handler) {
        synchronized (mLock) {
            if (mCallbackHandler != null) {
                // We're updating the callback, clear the session from the old one.
                mCallbackHandler.mCallbackWrapper.mSessionImpl = null;
                mCallbackHandler.removeCallbacksAndMessages(null);
            }
            if (callback == null) {
                mCallbackHandler = null;
                return;
            }
            callback.mSessionImpl = this;
            CallbackMessageHandler msgHandler = new CallbackMessageHandler(handler.getLooper(),
                    callback);
            mCallbackHandler = msgHandler;
        }
    }

    /**
     * Set an intent for launching UI for this Session. This can be used as a
     * quick link to an ongoing media screen. The intent should be for an
     * activity that may be started using {@link Activity#startActivity(Intent)}.
     *
     * @param pi The intent to launch to show UI for this Session.
     */
    public void setSessionActivity(@Nullable PendingIntent pi) {
        try {
            mSessionLink.setLaunchPendingIntent(pi);
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Failure in setLaunchPendingIntent.", e);
        }
    }

    /**
     * Set a pending intent for your media button receiver to allow restarting
     * playback after the session has been stopped. If your app is started in
     * this way an {@link Intent#ACTION_MEDIA_BUTTON} intent will be sent via
     * the pending intent.
     *
     * @param mbr The {@link PendingIntent} to send the media button event to.
     */
    public void setMediaButtonReceiver(@Nullable PendingIntent mbr) {
        try {
            mSessionLink.setMediaButtonReceiver(mbr);
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Failure in setMediaButtonReceiver.", e);
        }
    }

    /**
     * Set any flags for the session.
     *
     * @param flags The flags to set for this session.
     */
    public void setFlags(int flags) {
        try {
            mSessionLink.setFlags(flags);
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Failure in setFlags.", e);
        }
    }

    /**
     * Set the attributes for this session's audio. This will affect the
     * system's volume handling for this session. If
     * {@link #setPlaybackToRemote} was previously called it will stop receiving
     * volume commands and the system will begin sending volume changes to the
     * appropriate stream.
     * <p>
     * By default sessions use attributes for media.
     *
     * @param attributes The {@link AudioAttributes} for this session's audio.
     */
    public void setPlaybackToLocal(AudioAttributes attributes) {
        if (attributes == null) {
            throw new IllegalArgumentException("Attributes cannot be null for local playback.");
        }
        try {
            mSessionLink.setPlaybackToLocal(attributes);
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Failure in setPlaybackToLocal.", e);
        }
    }

    /**
     * Configure this session to use remote volume handling. This must be called
     * to receive volume button events, otherwise the system will adjust the
     * appropriate stream volume for this session. If
     * {@link #setPlaybackToLocal} was previously called the system will stop
     * handling volume changes for this session and pass them to the volume
     * provider instead.
     *
     * @param volumeProvider The provider that will handle volume changes. May
     *            not be null.
     */
    public void setPlaybackToRemote(@NonNull VolumeProvider volumeProvider) {
        if (volumeProvider == null) {
            throw new IllegalArgumentException("volumeProvider may not be null!");
        }
        synchronized (mLock) {
            mVolumeProvider = volumeProvider;
        }
        volumeProvider.setCallback(new VolumeProvider.Callback() {
            @Override
            public void onVolumeChanged(VolumeProvider volumeProvider) {
                notifyRemoteVolumeChanged(volumeProvider);
            }
        });

        try {
            mSessionLink.setPlaybackToRemote(volumeProvider.getVolumeControl(),
                    volumeProvider.getMaxVolume());
            mSessionLink.setCurrentVolume(volumeProvider.getCurrentVolume());
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Failure in setPlaybackToRemote.", e);
        }
    }

    /**
     * Set if this session is currently active and ready to receive commands. If
     * set to false your session's controller may not be discoverable. You must
     * set the session to active before it can start receiving media button
     * events or transport commands.
     *
     * @param active Whether this session is active or not.
     */
    public void setActive(boolean active) {
        if (mActive == active) {
            return;
        }
        try {
            mSessionLink.setActive(active);
            mActive = active;
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Failure in setActive.", e);
        }
    }

    /**
     * Get the current active state of this session.
     *
     * @return True if the session is active, false otherwise.
     */
    public boolean isActive() {
        return mActive;
    }

    /**
     * Send a proprietary event to all MediaControllers listening to this
     * Session. It's up to the Controller/Session owner to determine the meaning
     * of any events.
     *
     * @param event The name of the event to send
     * @param extras Any extras included with the event
     */
    public void sendSessionEvent(@NonNull String event, @Nullable Bundle extras) {
        if (TextUtils.isEmpty(event)) {
            throw new IllegalArgumentException("event cannot be null or empty");
        }
        try {
            mSessionLink.sendEvent(event, extras);
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Error sending event", e);
        }
    }

    /**
     * This must be called when an app has finished performing playback. If
     * playback is expected to start again shortly the session can be left open,
     * but it must be released if your activity or service is being destroyed.
     */
    public void close() {
        try {
            mSessionLink.destroySession();
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Error releasing session: ", e);
        }
    }

    /**
     * Retrieve a token object that can be used by apps to create a
     * {@link MediaController} for interacting with this session. The owner of
     * the session is responsible for deciding how to distribute these tokens.
     *
     * @return A token that can be used to create a MediaController for this
     *         session
     */
    public @NonNull MediaSession.Token getSessionToken() {
        return mSessionToken;
    }

    /**
     * Get a controller for this session. This is a convenience method to avoid
     * having to cache your own controller in process.
     *
     * @return A controller for this session.
     */
    public @NonNull MediaController getController() {
        return mController;
    }

    /**
     * Update the current playback state.
     *
     * @param state The current state of playback
     */
    public void setPlaybackState(@Nullable PlaybackState state) {
        mPlaybackState = state;
        try {
            mSessionLink.setPlaybackState(state);
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Dead object in setPlaybackState.", e);
        }
    }

    /**
     * Update the current metadata. New metadata can be created using
     * {@link android.media.MediaMetadata.Builder}. This operation may take time proportional to
     * the size of the bitmap to replace large bitmaps with a scaled down copy.
     *
     * @param metadata The new metadata
     * @see android.media.MediaMetadata.Builder#putBitmap
     */
    public void setMetadata(@Nullable MediaMetadata metadata) {
        long duration = -1;
        int fields = 0;
        MediaDescription description = null;
        if (metadata != null) {
            if (metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)) {
                duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            }
            fields = metadata.size();
            description = metadata.getDescription();
        }
        String metadataDescription = "size=" + fields + ", description=" + description;

        try {
            mSessionLink.setMetadata(metadata, duration, metadataDescription);
        } catch (RuntimeException e) {
            Log.wtf(TAG, "Dead object in setPlaybackState.", e);
        }
    }

    /**
     * Update the list of items in the play queue. It is an ordered list and
     * should contain the current item, and previous or upcoming items if they
     * exist. Specify null if there is no current play queue.
     * <p>
     * The queue should be of reasonable size. If the play queue is unbounded
     * within your app, it is better to send a reasonable amount in a sliding
     * window instead.
     *
     * @param queue A list of items in the play queue.
     */
    public void setQueue(@Nullable List<MediaSession.QueueItem> queue) {
        try {
            mSessionLink.setQueue(queue);
        } catch (RuntimeException e) {
            Log.wtf("Dead object in setQueue.", e);
        }
    }

    /**
     * Set the title of the play queue. The UI should display this title along
     * with the play queue itself.
     * e.g. "Play Queue", "Now Playing", or an album name.
     *
     * @param title The title of the play queue.
     */
    public void setQueueTitle(@Nullable CharSequence title) {
        try {
            mSessionLink.setQueueTitle(title);
        } catch (RuntimeException e) {
            Log.wtf("Dead object in setQueueTitle.", e);
        }
    }

    /**
     * Set the style of rating used by this session. Apps trying to set the
     * rating should use this style. Must be one of the following:
     * <ul>
     * <li>{@link Rating#RATING_NONE}</li>
     * <li>{@link Rating#RATING_3_STARS}</li>
     * <li>{@link Rating#RATING_4_STARS}</li>
     * <li>{@link Rating#RATING_5_STARS}</li>
     * <li>{@link Rating#RATING_HEART}</li>
     * <li>{@link Rating#RATING_PERCENTAGE}</li>
     * <li>{@link Rating#RATING_THUMB_UP_DOWN}</li>
     * </ul>
     */
    public void setRatingType(int type) {
        try {
            mSessionLink.setRatingType(type);
        } catch (RuntimeException e) {
            Log.e(TAG, "Error in setRatingType.", e);
        }
    }

    /**
     * Set some extras that can be associated with the {@link MediaSession}. No assumptions should
     * be made as to how a {@link MediaController} will handle these extras.
     * Keys should be fully qualified (e.g. com.example.MY_EXTRA) to avoid conflicts.
     *
     * @param extras The extras associated with the {@link MediaSession}.
     */
    public void setExtras(@Nullable Bundle extras) {
        try {
            mSessionLink.setExtras(extras);
        } catch (RuntimeException e) {
            Log.wtf("Dead object in setExtras.", e);
        }
    }

    /**
     * Gets the controller information who sent the current request.
     * <p>
     * Note: This is only valid while in a request callback, such as
     * {@link MediaSession.Callback#onPlay}.
     *
     * @throws IllegalStateException If this method is called outside of
     * {@link MediaSession.Callback} methods.
     * @see MediaSessionManager#isTrustedForMediaControl(RemoteUserInfo)
     */
    public @NonNull RemoteUserInfo getCurrentControllerInfo() {
        if (mCallbackHandler == null || mCallbackHandler.mCurrentControllerInfo == null) {
            throw new IllegalStateException(
                    "This should be called inside of MediaSession.Callback methods");
        }
        return mCallbackHandler.mCurrentControllerInfo;
    }

    /**
     * Returns the name of the package that sent the last media button, transport control, or
     * command from controllers and the system. This is only valid while in a request callback, such
     * as {@link MediaSession.Callback#onPlay}.
     */
    public String getCallingPackage() {
        if (mCallbackHandler != null && mCallbackHandler.mCurrentControllerInfo != null) {
            return mCallbackHandler.mCurrentControllerInfo.getPackageName();
        }
        return null;
    }


    /**
     * Notify the system that the remote volume changed.
     *
     * @param provider The provider that is handling volume changes.
     */
    private void notifyRemoteVolumeChanged(VolumeProvider provider) {
        synchronized (mLock) {
            if (provider == null || provider != mVolumeProvider) {
                Log.w(TAG, "Received update from stale volume provider");
                return;
            }
        }
        try {
            mSessionLink.setCurrentVolume(provider.getCurrentVolume());
        } catch (RuntimeException e) {
            Log.e(TAG, "Error in notifyVolumeChanged", e);
        }
    }

    void dispatchPrepare(RemoteUserInfo caller) {
        postToCallback(caller, CallbackMessageHandler.MSG_PREPARE, null, null);
    }

    void dispatchPrepareFromMediaId(RemoteUserInfo caller, String mediaId, Bundle extras) {
        postToCallback(caller, CallbackMessageHandler.MSG_PREPARE_MEDIA_ID, mediaId, extras);
    }

    void dispatchPrepareFromSearch(RemoteUserInfo caller, String query, Bundle extras) {
        postToCallback(caller, CallbackMessageHandler.MSG_PREPARE_SEARCH, query, extras);
    }

    void dispatchPrepareFromUri(RemoteUserInfo caller, Uri uri, Bundle extras) {
        postToCallback(caller, CallbackMessageHandler.MSG_PREPARE_URI, uri, extras);
    }

    void dispatchPlay(RemoteUserInfo caller) {
        postToCallback(caller, CallbackMessageHandler.MSG_PLAY, null, null);
    }

    void dispatchPlayFromMediaId(RemoteUserInfo caller, String mediaId, Bundle extras) {
        postToCallback(caller, CallbackMessageHandler.MSG_PLAY_MEDIA_ID, mediaId, extras);
    }

    void dispatchPlayFromSearch(RemoteUserInfo caller, String query, Bundle extras) {
        postToCallback(caller, CallbackMessageHandler.MSG_PLAY_SEARCH, query, extras);
    }

    void dispatchPlayFromUri(RemoteUserInfo caller, Uri uri, Bundle extras) {
        postToCallback(caller, CallbackMessageHandler.MSG_PLAY_URI, uri, extras);
    }

    void dispatchSkipToItem(RemoteUserInfo caller, long id) {
        postToCallback(caller, CallbackMessageHandler.MSG_SKIP_TO_ITEM, id, null);
    }

    void dispatchPause(RemoteUserInfo caller) {
        postToCallback(caller, CallbackMessageHandler.MSG_PAUSE, null, null);
    }

    void dispatchStop(RemoteUserInfo caller) {
        postToCallback(caller, CallbackMessageHandler.MSG_STOP, null, null);
    }

    void dispatchNext(RemoteUserInfo caller) {
        postToCallback(caller, CallbackMessageHandler.MSG_NEXT, null, null);
    }

    void dispatchPrevious(RemoteUserInfo caller) {
        postToCallback(caller, CallbackMessageHandler.MSG_PREVIOUS, null, null);
    }

    void dispatchFastForward(RemoteUserInfo caller) {
        postToCallback(caller, CallbackMessageHandler.MSG_FAST_FORWARD, null, null);
    }

    void dispatchRewind(RemoteUserInfo caller) {
        postToCallback(caller, CallbackMessageHandler.MSG_REWIND, null, null);
    }

    void dispatchSeekTo(RemoteUserInfo caller, long pos) {
        postToCallback(caller, CallbackMessageHandler.MSG_SEEK_TO, pos, null);
    }

    void dispatchRate(RemoteUserInfo caller, Rating rating) {
        postToCallback(caller, CallbackMessageHandler.MSG_RATE, rating, null);
    }

    void dispatchCustomAction(RemoteUserInfo caller, String action, Bundle args) {
        postToCallback(caller, CallbackMessageHandler.MSG_CUSTOM_ACTION, action, args);
    }

    void dispatchMediaButton(RemoteUserInfo caller, Intent mediaButtonIntent) {
        postToCallback(caller, CallbackMessageHandler.MSG_MEDIA_BUTTON, mediaButtonIntent, null);
    }

    void dispatchMediaButtonDelayed(RemoteUserInfo info, Intent mediaButtonIntent,
            long delay) {
        postToCallbackDelayed(info, CallbackMessageHandler.MSG_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT,
                mediaButtonIntent, null, delay);
    }

    void dispatchAdjustVolume(RemoteUserInfo caller, int direction) {
        postToCallback(caller, CallbackMessageHandler.MSG_ADJUST_VOLUME, direction, null);
    }

    void dispatchSetVolumeTo(RemoteUserInfo caller, int volume) {
        postToCallback(caller, CallbackMessageHandler.MSG_SET_VOLUME, volume, null);
    }

    void dispatchCommand(RemoteUserInfo caller, String command, Bundle args,
            ResultReceiver resultCb) {
        Command cmd = new Command(command, args, resultCb);
        postToCallback(caller, CallbackMessageHandler.MSG_COMMAND, cmd, null);
    }

    private void postToCallback(RemoteUserInfo caller, int what, Object obj, Bundle data) {
        postToCallbackDelayed(caller, what, obj, data, 0);
    }

    private void postToCallbackDelayed(RemoteUserInfo caller, int what, Object obj, Bundle data,
            long delay) {
        synchronized (mLock) {
            if (mCallbackHandler != null) {
                mCallbackHandler.post(caller, what, obj, data, delay);
            }
        }
    }

    /**
     * Return true if this is considered an active playback state.
     */
    public static boolean isActiveState(int state) {
        switch (state) {
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_PLAYING:
                return true;
        }
        return false;
    }

    /**
     * Interface for handling MediaButtoneEvent
     */
    public interface MediaButtonEventDelegate {
        /**
         * Called when a media button is pressed and this session has the
         * highest priority or a controller sends a media button event to the
         * session.
         *
         * @param mediaButtonIntent an intent containing the KeyEvent as an extra
         * @return True if the event was handled, false otherwise.
         */
        boolean onMediaButtonIntent(Intent mediaButtonIntent);
    }

    /**
     * Receives media buttons, transport controls, and commands from controllers
     * and the system. A callback may be set using {@link #setCallback}.
     * @hide
     */
    public static class CallbackWrapper implements MediaButtonEventDelegate {

        private final MediaSession.Callback mCallback;

        @SuppressWarnings("WeakerAccess") /* synthetic access */
                MediaSessionEngine mSessionImpl;
        @SuppressWarnings("WeakerAccess") /* synthetic access */
        CallbackMessageHandler mHandler;
        private boolean mMediaPlayPauseKeyPending;

        public CallbackWrapper(MediaSession.Callback callback) {
            mCallback = callback;
            if (mCallback != null) {
                mCallback.onSetMediaButtonEventDelegate(this);
            }
        }

        /**
         * Called when a controller has sent a command to this session.
         * The owner of the session may handle custom commands but is not
         * required to.
         *
         * @param command The command name.
         * @param args Optional parameters for the command, may be null.
         * @param cb A result receiver to which a result may be sent by the command, may be null.
         */
        public void onCommand(@NonNull String command, @Nullable Bundle args,
                @Nullable ResultReceiver cb) {
            if (mCallback != null) {
                mCallback.onCommand(command, args, cb);
            }
        }

        /**
         * Called when a media button is pressed and this session has the
         * highest priority or a controller sends a media button event to the
         * session. The default behavior will call the relevant method if the
         * action for it was set.
         * <p>
         * The intent will be of type {@link Intent#ACTION_MEDIA_BUTTON} with a
         * KeyEvent in {@link Intent#EXTRA_KEY_EVENT}
         *
         * @param mediaButtonIntent an intent containing the KeyEvent as an
         *            extra
         * @return True if the event was handled, false otherwise.
         */
        public boolean onMediaButtonEvent(@NonNull Intent mediaButtonIntent) {
            return mCallback == null ? false : mCallback.onMediaButtonEvent(mediaButtonIntent);
        }

        private void handleMediaPlayPauseKeySingleTapIfPending() {
            if (!mMediaPlayPauseKeyPending) {
                return;
            }
            mMediaPlayPauseKeyPending = false;
            mHandler.removeMessages(CallbackMessageHandler.MSG_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT);
            PlaybackState state = mSessionImpl.mPlaybackState;
            long validActions = state == null ? 0 : state.getActions();
            boolean isPlaying = state != null
                    && state.getState() == PlaybackState.STATE_PLAYING;
            boolean canPlay = (validActions & (PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_PLAY)) != 0;
            boolean canPause = (validActions & (PlaybackState.ACTION_PLAY_PAUSE
                    | PlaybackState.ACTION_PAUSE)) != 0;
            if (isPlaying && canPause) {
                onPause();
            } else if (!isPlaying && canPlay) {
                onPlay();
            }
        }

        /**
         * Override to handle requests to prepare playback. During the preparation, a session should
         * not hold audio focus in order to allow other sessions play seamlessly. The state of
         * playback should be updated to {@link PlaybackState#STATE_PAUSED} after the preparation is
         * done.
         */
        public void onPrepare() {
            if (mCallback != null) {
                mCallback.onPrepare();
            }
        }

        /**
         * Override to handle requests to prepare for playing a specific mediaId that was provided
         * by your app's {@link MediaBrowserService}. During the preparation, a session should not
         * hold audio focus in order to allow other sessions play seamlessly. The state of playback
         * should be updated to {@link PlaybackState#STATE_PAUSED} after the preparation is done.
         * The playback of the prepared content should start in the implementation of
         * {@link #onPlay}. Override {@link #onPlayFromMediaId} to handle requests for starting
         * playback without preparation.
         */
        public void onPrepareFromMediaId(String mediaId, Bundle extras) {
            if (mCallback != null) {
                mCallback.onPrepareFromMediaId(mediaId, extras);
            }
        }

        /**
         * Override to handle requests to prepare playback from a search query. An empty query
         * indicates that the app may prepare any music. The implementation should attempt to make a
         * smart choice about what to play. During the preparation, a session should not hold audio
         * focus in order to allow other sessions play seamlessly. The state of playback should be
         * updated to {@link PlaybackState#STATE_PAUSED} after the preparation is done. The playback
         * of the prepared content should start in the implementation of {@link #onPlay}. Override
         * {@link #onPlayFromSearch} to handle requests for starting playback without preparation.
         */
        public void onPrepareFromSearch(String query, Bundle extras) {
            if (mCallback != null) {
                mCallback.onPrepareFromSearch(query, extras);
            }
        }

        /**
         * Override to handle requests to prepare a specific media item represented by a URI.
         * During the preparation, a session should not hold audio focus in order to allow
         * other sessions play seamlessly. The state of playback should be updated to
         * {@link PlaybackState#STATE_PAUSED} after the preparation is done.
         * The playback of the prepared content should start in the implementation of
         * {@link #onPlay}. Override {@link #onPlayFromUri} to handle requests
         * for starting playback without preparation.
         */
        public void onPrepareFromUri(Uri uri, Bundle extras) {
            if (mCallback != null) {
                mCallback.onPrepareFromUri(uri, extras);
            }
        }

        /**
         * Override to handle requests to begin playback.
         */
        public void onPlay() {
            if (mCallback != null) {
                mCallback.onPlay();
            }
        }

        /**
         * Override to handle requests to begin playback from a search query. An
         * empty query indicates that the app may play any music. The
         * implementation should attempt to make a smart choice about what to
         * play.
         */
        public void onPlayFromSearch(String query, Bundle extras) {
            if (mCallback != null) {
                mCallback.onPlayFromSearch(query, extras);
            }
        }

        /**
         * Override to handle requests to play a specific mediaId that was
         * provided by your app's {@link MediaBrowserService}.
         */
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            if (mCallback != null) {
                mCallback.onPlayFromMediaId(mediaId, extras);
            }
        }

        /**
         * Override to handle requests to play a specific media item represented by a URI.
         */
        public void onPlayFromUri(Uri uri, Bundle extras) {
            if (mCallback != null) {
                mCallback.onPlayFromUri(uri, extras);
            }
        }

        /**
         * Override to handle requests to play an item with a given id from the
         * play queue.
         */
        public void onSkipToQueueItem(long id) {
            if (mCallback != null) {
                mCallback.onSkipToQueueItem(id);
            }
        }

        /**
         * Override to handle requests to pause playback.
         */
        public void onPause() {
            if (mCallback != null) {
                mCallback.onPause();
            }
        }

        /**
         * Override to handle requests to skip to the next media item.
         */
        public void onSkipToNext() {
            if (mCallback != null) {
                mCallback.onSkipToNext();
            }
        }

        /**
         * Override to handle requests to skip to the previous media item.
         */
        public void onSkipToPrevious() {
            if (mCallback != null) {
                mCallback.onSkipToPrevious();
            }
        }

        /**
         * Override to handle requests to fast forward.
         */
        public void onFastForward() {
            if (mCallback != null) {
                mCallback.onFastForward();
            }
        }

        /**
         * Override to handle requests to rewind.
         */
        public void onRewind() {
            if (mCallback != null) {
                mCallback.onRewind();
            }
        }

        /**
         * Override to handle requests to stop playback.
         */
        public void onStop() {
            if (mCallback != null) {
                mCallback.onStop();
            }
        }

        /**
         * Override to handle requests to seek to a specific position in ms.
         *
         * @param pos New position to move to, in milliseconds.
         */
        public void onSeekTo(long pos) {
            if (mCallback != null) {
                mCallback.onSeekTo(pos);
            }
        }

        /**
         * Override to handle the item being rated.
         *
         * @param rating
         */
        public void onSetRating(@NonNull Rating rating) {
            if (mCallback != null) {
                mCallback.onSetRating(rating);
            }
        }

        /**
         * Called when a {@link MediaController} wants a {@link PlaybackState.CustomAction} to be
         * performed.
         *
         * @param action The action that was originally sent in the
         *               {@link PlaybackState.CustomAction}.
         * @param extras Optional extras specified by the {@link MediaController}.
         */
        public void onCustomAction(@NonNull String action, @Nullable Bundle extras) {
            if (mCallback != null) {
                mCallback.onCustomAction(action, extras);
            }
        }

        @Override
        public boolean onMediaButtonIntent(Intent mediaButtonIntent) {
            if (mSessionImpl != null && mHandler != null
                    && Intent.ACTION_MEDIA_BUTTON.equals(mediaButtonIntent.getAction())) {
                KeyEvent ke = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (ke != null && ke.getAction() == KeyEvent.ACTION_DOWN) {
                    PlaybackState state = mSessionImpl.mPlaybackState;
                    long validActions = state == null ? 0 : state.getActions();
                    switch (ke.getKeyCode()) {
                        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        case KeyEvent.KEYCODE_HEADSETHOOK:
                            if (ke.getRepeatCount() > 0) {
                                // Consider long-press as a single tap.
                                handleMediaPlayPauseKeySingleTapIfPending();
                            } else if (mMediaPlayPauseKeyPending) {
                                // Consider double tap as the next.
                                mHandler.removeMessages(CallbackMessageHandler
                                        .MSG_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT);
                                mMediaPlayPauseKeyPending = false;
                                if ((validActions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) {
                                    onSkipToNext();
                                }
                            } else {
                                mMediaPlayPauseKeyPending = true;
                                mSessionImpl.dispatchMediaButtonDelayed(
                                        mSessionImpl.getCurrentControllerInfo(),
                                        mediaButtonIntent, ViewConfiguration.getDoubleTapTimeout());
                            }
                            return true;
                        default:
                            // If another key is pressed within double tap timeout, consider the
                            // pending play/pause as a single tap to handle media keys in order.
                            handleMediaPlayPauseKeySingleTapIfPending();
                            break;
                    }

                    switch (ke.getKeyCode()) {
                        case KeyEvent.KEYCODE_MEDIA_PLAY:
                            if ((validActions & PlaybackState.ACTION_PLAY) != 0) {
                                onPlay();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_PAUSE:
                            if ((validActions & PlaybackState.ACTION_PAUSE) != 0) {
                                onPause();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_NEXT:
                            if ((validActions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) {
                                onSkipToNext();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                            if ((validActions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0) {
                                onSkipToPrevious();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_STOP:
                            if ((validActions & PlaybackState.ACTION_STOP) != 0) {
                                onStop();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                            if ((validActions & PlaybackState.ACTION_FAST_FORWARD) != 0) {
                                onFastForward();
                                return true;
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_REWIND:
                            if ((validActions & PlaybackState.ACTION_REWIND) != 0) {
                                onRewind();
                                return true;
                            }
                            break;
                    }
                }
            }
            return false;
        }
    }

     /**
     * A single item that is part of the play queue. It contains a description
     * of the item and its id in the queue.
     */
    public static final class QueueItem {
        /**
         * This id is reserved. No items can be explicitly assigned this id.
         */
        public static final int UNKNOWN_ID = -1;

        private final MediaDescription mDescription;
        private final long mId;

        /**
         * Create a new {@link MediaSession.QueueItem}.
         *
         * @param description The {@link MediaDescription} for this item.
         * @param id An identifier for this item. It must be unique within the
         *            play queue and cannot be {@link #UNKNOWN_ID}.
         */
        public QueueItem(MediaDescription description, long id) {
            if (description == null) {
                throw new IllegalArgumentException("Description cannot be null.");
            }
            if (id == UNKNOWN_ID) {
                throw new IllegalArgumentException("Id cannot be QueueItem.UNKNOWN_ID");
            }
            mDescription = description;
            mId = id;
        }

        public QueueItem(Parcel in) {
            mDescription = MediaDescription.CREATOR.createFromParcel(in);
            mId = in.readLong();
        }

        /**
         * Get the description for this item.
         */
        public MediaDescription getDescription() {
            return mDescription;
        }

        /**
         * Get the queue id for this item.
         */
        public long getQueueId() {
            return mId;
        }

        /**
         * Flatten this object in to a Parcel.
         *
         * @param dest The Parcel in which the object should be written.
         * @param flags Additional flags about how the object should be written.
         */
        public void writeToParcel(Parcel dest, int flags) {
            mDescription.writeToParcel(dest, flags);
            dest.writeLong(mId);
        }

        @Override
        public String toString() {
            return "MediaSession.QueueItem {" + "Description=" + mDescription + ", Id=" + mId
                    + " }";
        }

        @Override
        public boolean equals(Object o) {
            if (o == null) {
                return false;
            }

            if (!(o instanceof QueueItem)) {
                return false;
            }

            final QueueItem item = (QueueItem) o;
            if (mId != item.mId) {
                return false;
            }

            if (!Objects.equals(mDescription, item.mDescription)) {
                return false;
            }

            return true;
        }
    }

    private static final class Command {
        public final String command;
        public final Bundle extras;
        public final ResultReceiver stub;

        Command(String command, Bundle extras, ResultReceiver stub) {
            this.command = command;
            this.extras = extras;
            this.stub = stub;
        }
    }

    private class CallbackMessageHandler extends Handler {
        private static final int MSG_COMMAND = 1;
        private static final int MSG_MEDIA_BUTTON = 2;
        private static final int MSG_PREPARE = 3;
        private static final int MSG_PREPARE_MEDIA_ID = 4;
        private static final int MSG_PREPARE_SEARCH = 5;
        private static final int MSG_PREPARE_URI = 6;
        private static final int MSG_PLAY = 7;
        private static final int MSG_PLAY_MEDIA_ID = 8;
        private static final int MSG_PLAY_SEARCH = 9;
        private static final int MSG_PLAY_URI = 10;
        private static final int MSG_SKIP_TO_ITEM = 11;
        private static final int MSG_PAUSE = 12;
        private static final int MSG_STOP = 13;
        private static final int MSG_NEXT = 14;
        private static final int MSG_PREVIOUS = 15;
        private static final int MSG_FAST_FORWARD = 16;
        private static final int MSG_REWIND = 17;
        private static final int MSG_SEEK_TO = 18;
        private static final int MSG_RATE = 19;
        private static final int MSG_CUSTOM_ACTION = 20;
        private static final int MSG_ADJUST_VOLUME = 21;
        private static final int MSG_SET_VOLUME = 22;
        private static final int MSG_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT = 23;

        @SuppressWarnings("WeakerAccess") /* synthetic access */
        CallbackWrapper mCallbackWrapper;
        @SuppressWarnings("WeakerAccess") /* synthetic access */
        RemoteUserInfo mCurrentControllerInfo;

        CallbackMessageHandler(Looper looper, CallbackWrapper callbackWrapper) {
            super(looper);
            mCallbackWrapper = callbackWrapper;
            mCallbackWrapper.mHandler = this;
        }

        void post(RemoteUserInfo caller, int what, Object obj, Bundle data, long delayMs) {
            Pair<RemoteUserInfo, Object> objWithCaller = Pair.create(caller, obj);
            Message msg = obtainMessage(what, objWithCaller);
            msg.setAsynchronous(true);
            msg.setData(data);
            if (delayMs > 0) {
                sendMessageDelayed(msg, delayMs);
            } else {
                sendMessage(msg);
            }
        }

        @Override
        public void handleMessage(Message msg) {
            mCurrentControllerInfo = ((Pair<RemoteUserInfo, Object>) msg.obj).first;

            VolumeProvider vp;
            Object obj = ((Pair<RemoteUserInfo, Object>) msg.obj).second;

            switch (msg.what) {
                case MSG_COMMAND:
                    Command cmd = (Command) obj;
                    mCallbackWrapper.onCommand(cmd.command, cmd.extras, cmd.stub);
                    break;
                case MSG_MEDIA_BUTTON:
                    mCallbackWrapper.onMediaButtonEvent((Intent) obj);
                    break;
                case MSG_PREPARE:
                    mCallbackWrapper.onPrepare();
                    break;
                case MSG_PREPARE_MEDIA_ID:
                    mCallbackWrapper.onPrepareFromMediaId((String) obj, msg.getData());
                    break;
                case MSG_PREPARE_SEARCH:
                    mCallbackWrapper.onPrepareFromSearch((String) obj, msg.getData());
                    break;
                case MSG_PREPARE_URI:
                    mCallbackWrapper.onPrepareFromUri((Uri) obj, msg.getData());
                    break;
                case MSG_PLAY:
                    mCallbackWrapper.onPlay();
                    break;
                case MSG_PLAY_MEDIA_ID:
                    mCallbackWrapper.onPlayFromMediaId((String) obj, msg.getData());
                    break;
                case MSG_PLAY_SEARCH:
                    mCallbackWrapper.onPlayFromSearch((String) obj, msg.getData());
                    break;
                case MSG_PLAY_URI:
                    mCallbackWrapper.onPlayFromUri((Uri) obj, msg.getData());
                    break;
                case MSG_SKIP_TO_ITEM:
                    mCallbackWrapper.onSkipToQueueItem((Long) obj);
                    break;
                case MSG_PAUSE:
                    mCallbackWrapper.onPause();
                    break;
                case MSG_STOP:
                    mCallbackWrapper.onStop();
                    break;
                case MSG_NEXT:
                    mCallbackWrapper.onSkipToNext();
                    break;
                case MSG_PREVIOUS:
                    mCallbackWrapper.onSkipToPrevious();
                    break;
                case MSG_FAST_FORWARD:
                    mCallbackWrapper.onFastForward();
                    break;
                case MSG_REWIND:
                    mCallbackWrapper.onRewind();
                    break;
                case MSG_SEEK_TO:
                    mCallbackWrapper.onSeekTo((Long) obj);
                    break;
                case MSG_RATE:
                    mCallbackWrapper.onSetRating((Rating) obj);
                    break;
                case MSG_CUSTOM_ACTION:
                    mCallbackWrapper.onCustomAction((String) obj, msg.getData());
                    break;
                case MSG_ADJUST_VOLUME:
                    synchronized (mLock) {
                        vp = mVolumeProvider;
                    }
                    if (vp != null) {
                        vp.onAdjustVolume((int) obj);
                    }
                    break;
                case MSG_SET_VOLUME:
                    synchronized (mLock) {
                        vp = mVolumeProvider;
                    }
                    if (vp != null) {
                        vp.onSetVolumeTo((int) obj);
                    }
                    break;
                case MSG_PLAY_PAUSE_KEY_DOUBLE_TAP_TIMEOUT:
                    mCallbackWrapper.handleMediaPlayPauseKeySingleTapIfPending();
                    break;
            }
            mCurrentControllerInfo = null;
        }
    }
}
