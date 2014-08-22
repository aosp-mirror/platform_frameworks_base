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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.VolumeProvider;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * Allows interaction with media controllers, volume keys, media buttons, and
 * transport controls.
 * <p>
 * A MediaSession should be created when an app wants to publish media playback
 * information or handle media keys. In general an app only needs one session
 * for all playback, though multiple sessions can be created to provide finer
 * grain controls of media.
 * <p>
 * Once a session is created the owner of the session may pass its
 * {@link #getSessionToken() session token} to other processes to allow them to
 * create a {@link MediaController} to interact with the session.
 * <p>
 * To receive commands, media keys, and other events a {@link Callback} must be
 * set with {@link #setCallback(Callback)} and {@link #setActive(boolean)
 * setActive(true)} must be called.
 * <p>
 * When an app is finished performing playback it must call {@link #release()}
 * to clean up the session and notify any controllers.
 * <p>
 * MediaSession objects are thread safe.
 */
public final class MediaSession {
    private static final String TAG = "MediaSession";

    /**
     * Set this flag on the session to indicate that it can handle media button
     * events.
     */
    public static final int FLAG_HANDLES_MEDIA_BUTTONS = 1 << 0;

    /**
     * Set this flag on the session to indicate that it handles transport
     * control commands through its {@link Callback}.
     */
    public static final int FLAG_HANDLES_TRANSPORT_CONTROLS = 1 << 1;

    /**
     * System only flag for a session that needs to have priority over all other
     * sessions. This flag ensures this session will receive media button events
     * regardless of the current ordering in the system.
     *
     * @hide
     */
    public static final int FLAG_EXCLUSIVE_GLOBAL_PRIORITY = 1 << 16;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
            FLAG_HANDLES_MEDIA_BUTTONS,
            FLAG_HANDLES_TRANSPORT_CONTROLS,
            FLAG_EXCLUSIVE_GLOBAL_PRIORITY })
    public @interface SessionFlags { }

    private final Object mLock = new Object();

    private final MediaSession.Token mSessionToken;
    private final MediaController mController;
    private final ISession mBinder;
    private final CallbackStub mCbStub;

    private CallbackMessageHandler mCallback;
    private VolumeProvider mVolumeProvider;
    private PlaybackState mPlaybackState;

    private boolean mActive = false;

    /**
     * Creates a new session. The session will automatically be registered with
     * the system but will not be published until {@link #setActive(boolean)
     * setActive(true)} is called. You must call {@link #release()} when
     * finished with the session.
     *
     * @param context The context to use to create the session.
     * @param tag A short name for debugging purposes.
     */
    public MediaSession(@NonNull Context context, @NonNull String tag) {
        this(context, tag, UserHandle.myUserId());
    }

    /**
     * Creates a new session as the specified user. To create a session as a
     * user other than your own you must hold the
     * {@link android.Manifest.permission#INTERACT_ACROSS_USERS_FULL}
     * permission.
     *
     * @param context The context to use to create the session.
     * @param tag A short name for debugging purposes.
     * @param userId The user id to create the session as.
     * @hide
     */
    public MediaSession(@NonNull Context context, @NonNull String tag, int userId) {
        if (context == null) {
            throw new IllegalArgumentException("context cannot be null.");
        }
        if (TextUtils.isEmpty(tag)) {
            throw new IllegalArgumentException("tag cannot be null or empty");
        }
        mCbStub = new CallbackStub(this);
        MediaSessionManager manager = (MediaSessionManager) context
                .getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            mBinder = manager.createSession(mCbStub, tag, userId);
            mSessionToken = new Token(mBinder.getController());
            mController = new MediaController(context, mSessionToken);
        } catch (RemoteException e) {
            throw new RuntimeException("Remote error creating session.", e);
        }
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
    public void setCallback(@Nullable Callback callback) {
        setCallback(callback, null);
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
    public void setCallback(@Nullable Callback callback, @Nullable Handler handler) {
        if (callback == null) {
            mCallback = null;
            return;
        }
        synchronized (mLock) {
            if (mCallback != null && mCallback.mCallback == callback) {
                Log.w(TAG, "Tried to set same callback, ignoring");
                return;
            }
            if (handler == null) {
                handler = new Handler();
            }
            CallbackMessageHandler msgHandler = new CallbackMessageHandler(handler.getLooper(),
                    callback);
            mCallback = msgHandler;
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
            mBinder.setLaunchPendingIntent(pi);
        } catch (RemoteException e) {
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
            mBinder.setMediaButtonReceiver(mbr);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Failure in setMediaButtonReceiver.", e);
        }
    }

    /**
     * Set any flags for the session.
     *
     * @param flags The flags to set for this session.
     */
    public void setFlags(@SessionFlags int flags) {
        try {
            mBinder.setFlags(flags);
        } catch (RemoteException e) {
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
            mBinder.setPlaybackToLocal(attributes);
        } catch (RemoteException e) {
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
        mVolumeProvider = volumeProvider;
        volumeProvider.setCallback(new VolumeProvider.Callback() {
            @Override
            public void onVolumeChanged(VolumeProvider volumeProvider) {
                notifyRemoteVolumeChanged(volumeProvider);
            }
        });

        try {
            mBinder.setPlaybackToRemote(volumeProvider.getVolumeControl(),
                    volumeProvider.getMaxVolume());
            mBinder.setCurrentVolume(volumeProvider.onGetCurrentVolume());
        } catch (RemoteException e) {
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
            mBinder.setActive(active);
            mActive = active;
        } catch (RemoteException e) {
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
            mBinder.sendEvent(event, extras);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error sending event", e);
        }
    }

    /**
     * This must be called when an app has finished performing playback. If
     * playback is expected to start again shortly the session can be left open,
     * but it must be released if your activity or service is being destroyed.
     */
    public void release() {
        try {
            mBinder.destroy();
        } catch (RemoteException e) {
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
    public @NonNull Token getSessionToken() {
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
            mBinder.setPlaybackState(state);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Dead object in setPlaybackState.", e);
        }
    }

    /**
     * Update the current metadata. New metadata can be created using
     * {@link android.media.MediaMetadata.Builder}.
     *
     * @param metadata The new metadata
     */
    public void setMetadata(@Nullable MediaMetadata metadata) {
        try {
            mBinder.setMetadata(metadata);
        } catch (RemoteException e) {
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
    public void setQueue(@Nullable List<Item> queue) {
        try {
            mBinder.setQueue(new ParceledListSlice<Item>(queue));
        } catch (RemoteException e) {
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
            mBinder.setQueueTitle(title);
        } catch (RemoteException e) {
            Log.wtf("Dead object in setQueueTitle.", e);
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
            mBinder.setExtras(extras);
        } catch (RemoteException e) {
            Log.wtf("Dead object in setExtras.", e);
        }
    }

    /**
     * Notify the system that the remote volume changed.
     *
     * @param provider The provider that is handling volume changes.
     * @hide
     */
    public void notifyRemoteVolumeChanged(VolumeProvider provider) {
        if (provider == null || provider != mVolumeProvider) {
            Log.w(TAG, "Received update from stale volume provider");
            return;
        }
        try {
            mBinder.setCurrentVolume(provider.onGetCurrentVolume());
        } catch (RemoteException e) {
            Log.e(TAG, "Error in notifyVolumeChanged", e);
        }
    }

    private void dispatchPlay() {
        postToCallback(CallbackMessageHandler.MSG_PLAY);
    }

    private void dispatchPlayUri(Uri uri, Bundle extras) {
        postToCallback(CallbackMessageHandler.MSG_PLAY_URI, uri, extras);
    }

    private void dispatchPlayFromSearch(String query, Bundle extras) {
        postToCallback(CallbackMessageHandler.MSG_PLAY_SEARCH, query, extras);
    }

    private void dispatchSkipToItem(long id) {
        postToCallback(CallbackMessageHandler.MSG_SKIP_TO_ITEM, id);
    }

    private void dispatchPause() {
        postToCallback(CallbackMessageHandler.MSG_PAUSE);
    }

    private void dispatchStop() {
        postToCallback(CallbackMessageHandler.MSG_STOP);
    }

    private void dispatchNext() {
        postToCallback(CallbackMessageHandler.MSG_NEXT);
    }

    private void dispatchPrevious() {
        postToCallback(CallbackMessageHandler.MSG_PREVIOUS);
    }

    private void dispatchFastForward() {
        postToCallback(CallbackMessageHandler.MSG_FAST_FORWARD);
    }

    private void dispatchRewind() {
        postToCallback(CallbackMessageHandler.MSG_REWIND);
    }

    private void dispatchSeekTo(long pos) {
        postToCallback(CallbackMessageHandler.MSG_SEEK_TO, pos);
    }

    private void dispatchRate(Rating rating) {
        postToCallback(CallbackMessageHandler.MSG_RATE, rating);
    }

    private void dispatchCustomAction(String action, Bundle args) {
        postToCallback(CallbackMessageHandler.MSG_CUSTOM_ACTION, action, args);
    }

    private void dispatchMediaButton(Intent mediaButtonIntent) {
        postToCallback(CallbackMessageHandler.MSG_MEDIA_BUTTON, mediaButtonIntent);
    }

    private void postToCallback(int what) {
        postToCallback(what, null);
    }

    private void postCommand(String command, Bundle args, ResultReceiver resultCb) {
        Command cmd = new Command(command, args, resultCb);
        postToCallback(CallbackMessageHandler.MSG_COMMAND, cmd);
    }

    private void postToCallback(int what, Object obj) {
        postToCallback(what, obj, null);
    }

    private void postToCallback(int what, Object obj, Bundle extras) {
        synchronized (mLock) {
            if (mCallback != null) {
                mCallback.post(what, obj, extras);
            }
        }
    }

    /**
     * Return true if this is considered an active playback state.
     *
     * @hide
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
     * Represents an ongoing session. This may be passed to apps by the session
     * owner to allow them to create a {@link MediaController} to communicate with
     * the session.
     */
    public static final class Token implements Parcelable {

        private ISessionController mBinder;

        /**
         * @hide
         */
        public Token(ISessionController binder) {
            mBinder = binder;
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeStrongBinder(mBinder.asBinder());
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((mBinder == null) ? 0 : mBinder.asBinder().hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Token other = (Token) obj;
            if (mBinder == null) {
                if (other.mBinder != null)
                    return false;
            } else if (!mBinder.asBinder().equals(other.mBinder.asBinder()))
                return false;
            return true;
        }

        ISessionController getBinder() {
            return mBinder;
        }

        public static final Parcelable.Creator<Token> CREATOR
                = new Parcelable.Creator<Token>() {
            @Override
            public Token createFromParcel(Parcel in) {
                return new Token(ISessionController.Stub.asInterface(in.readStrongBinder()));
            }

            @Override
            public Token[] newArray(int size) {
                return new Token[size];
            }
        };
    }

    /**
     * Receives media buttons, transport controls, and commands from controllers
     * and the system. A callback may be set using {@link #setCallback}.
     */
    public abstract static class Callback {
        private MediaSession mSession;

        public Callback() {
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
         */
        public boolean onMediaButtonEvent(@NonNull Intent mediaButtonIntent) {
            if (mSession != null
                    && Intent.ACTION_MEDIA_BUTTON.equals(mediaButtonIntent.getAction())) {
                KeyEvent ke = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (ke != null && ke.getAction() == KeyEvent.ACTION_DOWN) {
                    PlaybackState state = mSession.mPlaybackState;
                    long validActions = state == null ? 0 : state.getActions();
                    switch (ke.getKeyCode()) {
                        case KeyEvent.KEYCODE_MEDIA_PLAY:
                            if ((validActions & PlaybackState.ACTION_PLAY) != 0) {
                                onPlay();
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_PAUSE:
                            if ((validActions & PlaybackState.ACTION_PAUSE) != 0) {
                                onPause();
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_NEXT:
                            if ((validActions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0) {
                                onSkipToNext();
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                            if ((validActions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0) {
                                onSkipToPrevious();
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_STOP:
                            if ((validActions & PlaybackState.ACTION_STOP) != 0) {
                                onStop();
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                            if ((validActions & PlaybackState.ACTION_FAST_FORWARD) != 0) {
                                onFastForward();
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_REWIND:
                            if ((validActions & PlaybackState.ACTION_REWIND) != 0) {
                                onRewind();
                            }
                            break;
                        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                        case KeyEvent.KEYCODE_HEADSETHOOK:
                            boolean isPlaying = state == null ? false
                                    : state.getState() == PlaybackState.STATE_PLAYING;
                            boolean canPlay = (validActions & (PlaybackState.ACTION_PLAY_PAUSE
                                    | PlaybackState.ACTION_PLAY)) != 0;
                            boolean canPause = (validActions & (PlaybackState.ACTION_PLAY_PAUSE
                                    | PlaybackState.ACTION_PAUSE)) != 0;
                            if (isPlaying && canPause) {
                                onPause();
                            } else if (!isPlaying && canPlay) {
                                onPlay();
                            }
                            break;
                    }
                }
            }
            return false;
        }

        /**
         * Override to handle requests to begin playback.
         */
        public void onPlay() {
        }

        /**
         * Override to handle requests to play a specific {@link Uri}.
         */
        public void onPlayUri(Uri uri, Bundle extras) {
        }

        /**
         * Override to handle requests to begin playback from a search query.
         */
        public void onPlayFromSearch(String query, Bundle extras) {
        }

        /**
         * Override to handle requests to play an item with a given id from the
         * play queue.
         */
        public void onSkipToItem(long id) {
        }

        /**
         * Override to handle requests to pause playback.
         */
        public void onPause() {
        }

        /**
         * Override to handle requests to skip to the next media item.
         */
        public void onSkipToNext() {
        }

        /**
         * Override to handle requests to skip to the previous media item.
         */
        public void onSkipToPrevious() {
        }

        /**
         * Override to handle requests to fast forward.
         */
        public void onFastForward() {
        }

        /**
         * Override to handle requests to rewind.
         */
        public void onRewind() {
        }

        /**
         * Override to handle requests to stop playback.
         */
        public void onStop() {
        }

        /**
         * Override to handle requests to seek to a specific position in ms.
         *
         * @param pos New position to move to, in milliseconds.
         */
        public void onSeekTo(long pos) {
        }

        /**
         * Override to handle the item being rated.
         *
         * @param rating
         */
        public void onSetRating(@NonNull Rating rating) {
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
        }

        private void setSession(MediaSession session) {
            mSession = session;
        }
    }

    /**
     * @hide
     */
    public static class CallbackStub extends ISessionCallback.Stub {
        private WeakReference<MediaSession> mMediaSession;

        public CallbackStub(MediaSession session) {
            mMediaSession = new WeakReference<MediaSession>(session);
        }

        @Override
        public void onCommand(String command, Bundle args, ResultReceiver cb) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.postCommand(command, args, cb);
            }
        }

        @Override
        public void onMediaButton(Intent mediaButtonIntent, int sequenceNumber,
                ResultReceiver cb) {
            MediaSession session = mMediaSession.get();
            try {
                if (session != null) {
                    session.dispatchMediaButton(mediaButtonIntent);
                }
            } finally {
                if (cb != null) {
                    cb.send(sequenceNumber, null);
                }
            }
        }

        @Override
        public void onPlay() {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPlay();
            }
        }

        @Override
        public void onPlayUri(Uri uri, Bundle extras) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPlayUri(uri, extras);
            }
        }

        @Override
        public void onPlayFromSearch(String query, Bundle extras) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPlayFromSearch(query, extras);
            }
        }

        @Override
        public void onSkipToTrack(long id) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchSkipToItem(id);
            }
        }

        @Override
        public void onPause() {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPause();
            }
        }

        @Override
        public void onStop() {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchStop();
            }
        }

        @Override
        public void onNext() {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchNext();
            }
        }

        @Override
        public void onPrevious() {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPrevious();
            }
        }

        @Override
        public void onFastForward() {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchFastForward();
            }
        }

        @Override
        public void onRewind() {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchRewind();
            }
        }

        @Override
        public void onSeekTo(long pos) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchSeekTo(pos);
            }
        }

        @Override
        public void onRate(Rating rating) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchRate(rating);
            }
        }

        @Override
        public void onCustomAction(String action, Bundle args) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchCustomAction(action, args);
            }
        }

        @Override
        public void onAdjustVolume(int direction) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                if (session.mVolumeProvider != null) {
                    session.mVolumeProvider.onAdjustVolume(direction);
                }
            }
        }

        @Override
        public void onSetVolumeTo(int value) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                if (session.mVolumeProvider != null) {
                    session.mVolumeProvider.onSetVolumeTo(value);
                }
            }
        }

    }

    /**
     * A single item that is part of the play queue. It contains information
     * necessary to display a single item in the queue.
     */
    public static final class Item implements Parcelable {
        /**
         * This id is reserved. No items can be explicitly asigned this id.
         */
        public static final int UNKNOWN_ID = -1;

        private final MediaMetadata mMetadata;
        private final long mId;
        private final Uri mUri;
        private final Bundle mExtras;

        /**
         * Create a new {@link MediaSession.Item}.
         *
         * @param metadata The metadata for this item.
         * @param id An identifier for this item. It must be unique within the
         *            play queue.
         * @param uri The uri for this item.
         * @param extras A bundle of extras that can be used to add extra
         *            information about this item.
         */
        private Item(MediaMetadata metadata, long id, Uri uri, Bundle extras) {
            mMetadata = metadata;
            mId = id;
            mUri = uri;
            mExtras = extras;
        }

        private Item(Parcel in) {
            mMetadata = MediaMetadata.CREATOR.createFromParcel(in);
            mId = in.readLong();
            mUri = Uri.CREATOR.createFromParcel(in);
            mExtras = in.readBundle();
        }

        /**
         * Get the metadata for this item.
         */
        public MediaMetadata getMetadata() {
            return mMetadata;
        }

        /**
         * Get the id for this item.
         */
        public long getId() {
            return mId;
        }

        /**
         * Get the Uri for this item.
         */
        public Uri getUri() {
            return mUri;
        }

        /**
         * Get the extras for this item.
         */
        public Bundle getExtras() {
            return mExtras;
        }

        /**
         * Builder for {@link MediaSession.Item} objects.
         */
        public static final class Builder {
            private final MediaMetadata mMetadata;
            private final long mId;
            private final Uri mUri;

            private Bundle mExtras;

            /**
             * Create a builder with the metadata, id, and uri already set.
             */
            public Builder(MediaMetadata metadata, long id, Uri uri) {
                if (metadata == null) {
                    throw new IllegalArgumentException(
                            "You must specify a non-null MediaMetadata to build an Item.");
                }
                if (uri == null) {
                    throw new IllegalArgumentException(
                            "You must specify a non-null Uri to build an Item.");
                }
                if (id == UNKNOWN_ID) {
                    throw new IllegalArgumentException(
                            "You must specify an id other than UNKNOWN_ID to build an Item.");
                }
                mMetadata = metadata;
                mId = id;
                mUri = uri;
            }

            /**
             * Set optional extras for the item.
             */
            public MediaSession.Item.Builder setExtras(Bundle extras) {
                mExtras = extras;
                return this;
            }

            /**
             * Create the {@link Item}.
             */
            public MediaSession.Item build() {
                return new MediaSession.Item(mMetadata, mId, mUri, mExtras);
            }
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            mMetadata.writeToParcel(dest, flags);
            dest.writeLong(mId);
            mUri.writeToParcel(dest, flags);
            dest.writeBundle(mExtras);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<MediaSession.Item> CREATOR
                = new Creator<MediaSession.Item>() {

            @Override
            public MediaSession.Item createFromParcel(Parcel p) {
                return new MediaSession.Item(p);
            }

            @Override
            public MediaSession.Item[] newArray(int size) {
                return new MediaSession.Item[size];
            }
        };

        @Override
        public String toString() {
            return "MediaSession.Item {" +
                    "Metadata=" + mMetadata +
                    ", Id=" + mId +
                    ", Uri=" + mUri +
                    ", Extras=" + mExtras +
                    " }";
        }
    }

    private static final class Command {
        public final String command;
        public final Bundle extras;
        public final ResultReceiver stub;

        public Command(String command, Bundle extras, ResultReceiver stub) {
            this.command = command;
            this.extras = extras;
            this.stub = stub;
        }
    }

    private class CallbackMessageHandler extends Handler {

        private static final int MSG_PLAY = 1;
        private static final int MSG_PLAY_URI = 2;
        private static final int MSG_PLAY_SEARCH = 3;
        private static final int MSG_SKIP_TO_ITEM = 4;
        private static final int MSG_PAUSE = 5;
        private static final int MSG_STOP = 6;
        private static final int MSG_NEXT = 7;
        private static final int MSG_PREVIOUS = 8;
        private static final int MSG_FAST_FORWARD = 9;
        private static final int MSG_REWIND = 10;
        private static final int MSG_SEEK_TO = 11;
        private static final int MSG_RATE = 12;
        private static final int MSG_CUSTOM_ACTION = 13;
        private static final int MSG_MEDIA_BUTTON = 14;
        private static final int MSG_COMMAND = 15;

        private MediaSession.Callback mCallback;

        public CallbackMessageHandler(Looper looper, MediaSession.Callback callback) {
            super(looper, null, true);
            mCallback = callback;
        }

        public void post(int what, Object obj, Bundle bundle) {
            Message msg = obtainMessage(what, obj);
            msg.setData(bundle);
            msg.sendToTarget();
        }

        public void post(int what, Object obj) {
            obtainMessage(what, obj).sendToTarget();
        }

        public void post(int what) {
            post(what, null);
        }

        public void post(int what, Object obj, int arg1) {
            obtainMessage(what, arg1, 0, obj).sendToTarget();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PLAY:
                    mCallback.onPlay();
                    break;
                case MSG_PLAY_URI:
                    mCallback.onPlayUri((Uri) msg.obj, msg.getData());
                    break;
                case MSG_PLAY_SEARCH:
                    mCallback.onPlayFromSearch((String) msg.obj, msg.getData());
                    break;
                case MSG_SKIP_TO_ITEM:
                    mCallback.onSkipToItem((Long) msg.obj);
                case MSG_PAUSE:
                    mCallback.onPause();
                    break;
                case MSG_STOP:
                    mCallback.onStop();
                    break;
                case MSG_NEXT:
                    mCallback.onSkipToNext();
                    break;
                case MSG_PREVIOUS:
                    mCallback.onSkipToPrevious();
                    break;
                case MSG_FAST_FORWARD:
                    mCallback.onFastForward();
                    break;
                case MSG_REWIND:
                    mCallback.onRewind();
                    break;
                case MSG_SEEK_TO:
                    mCallback.onSeekTo((Long) msg.obj);
                    break;
                case MSG_RATE:
                    mCallback.onSetRating((Rating) msg.obj);
                    break;
                case MSG_CUSTOM_ACTION:
                    mCallback.onCustomAction((String) msg.obj, msg.getData());
                    break;
                case MSG_MEDIA_BUTTON:
                    mCallback.onMediaButtonEvent((Intent) msg.obj);
                    break;
                case MSG_COMMAND:
                    Command cmd = (Command) msg.obj;
                    mCallback.onCommand(cmd.command, cmd.extras, cmd.stub);
                    break;
            }
        }
    }
}
