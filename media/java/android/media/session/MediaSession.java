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
import android.media.MediaDescription;
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
import android.service.media.MediaBrowserService;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;

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
     * @deprecated This flag is no longer used. All media sessions are expected to handle media
     * button events now.
     */
    @Deprecated
    public static final int FLAG_HANDLES_MEDIA_BUTTONS = 1 << 0;

    /**
     * Set this flag on the session to indicate that it handles transport
     * control commands through its {@link Callback}.
     * @deprecated This flag is no longer used. All media sessions are expected to handle transport
     * controls now.
     */
    @Deprecated
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
    private final int mMaxBitmapSize;

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
        mMaxBitmapSize = context.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.config_mediaMetadataBitmapMaxSize);
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
        synchronized (mLock) {
            if (callback == null) {
                if (mCallback != null) {
                    mCallback.mCallback.mSession = null;
                }
                mCallback = null;
                return;
            }
            if (mCallback != null) {
                // We're updating the callback, clear the session from the old
                // one.
                mCallback.mCallback.mSession = null;
            }
            if (handler == null) {
                handler = new Handler();
            }
            callback.mSession = this;
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
            mBinder.setPlaybackToRemote(volumeProvider.getVolumeControl(),
                    volumeProvider.getMaxVolume());
            mBinder.setCurrentVolume(volumeProvider.getCurrentVolume());
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
     * {@link android.media.MediaMetadata.Builder}. This operation may take time proportional to
     * the size of the bitmap to replace large bitmaps with a scaled down copy.
     *
     * @param metadata The new metadata
     * @see android.media.MediaMetadata.Builder#putBitmap
     */
    public void setMetadata(@Nullable MediaMetadata metadata) {
        if (metadata != null) {
            metadata = (new MediaMetadata.Builder(metadata, mMaxBitmapSize)).build();
        }
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
    public void setQueue(@Nullable List<QueueItem> queue) {
        try {
            mBinder.setQueue(queue == null ? null : new ParceledListSlice<QueueItem>(queue));
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
    public void setRatingType(@Rating.Style int type) {
        try {
            mBinder.setRatingType(type);
        } catch (RemoteException e) {
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
        synchronized (mLock) {
            if (provider == null || provider != mVolumeProvider) {
                Log.w(TAG, "Received update from stale volume provider");
                return;
            }
        }
        try {
            mBinder.setCurrentVolume(provider.getCurrentVolume());
        } catch (RemoteException e) {
            Log.e(TAG, "Error in notifyVolumeChanged", e);
        }
    }

    /**
     * Returns the name of the package that sent the last media button, transport control, or
     * command from controllers and the system. This is only valid while in a request callback, such
     * as {@link Callback#onPlay}.
     *
     * @hide
     */
    public String getCallingPackage() {
        try {
            return mBinder.getCallingPackage();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Dead object in getCallingPackage.", e);
        }
        return null;
    }

    private void dispatchPrepare() {
        postToCallback(CallbackMessageHandler.MSG_PREPARE);
    }

    private void dispatchPrepareFromMediaId(String mediaId, Bundle extras) {
        postToCallback(CallbackMessageHandler.MSG_PREPARE_MEDIA_ID, mediaId, extras);
    }

    private void dispatchPrepareFromSearch(String query, Bundle extras) {
        postToCallback(CallbackMessageHandler.MSG_PREPARE_SEARCH, query, extras);
    }

    private void dispatchPrepareFromUri(Uri uri, Bundle extras) {
        postToCallback(CallbackMessageHandler.MSG_PREPARE_URI, uri, extras);
    }

    private void dispatchPlay() {
        postToCallback(CallbackMessageHandler.MSG_PLAY);
    }

    private void dispatchPlayFromMediaId(String mediaId, Bundle extras) {
        postToCallback(CallbackMessageHandler.MSG_PLAY_MEDIA_ID, mediaId, extras);
    }

    private void dispatchPlayFromSearch(String query, Bundle extras) {
        postToCallback(CallbackMessageHandler.MSG_PLAY_SEARCH, query, extras);
    }

    private void dispatchPlayFromUri(Uri uri, Bundle extras) {
        postToCallback(CallbackMessageHandler.MSG_PLAY_URI, uri, extras);
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

    private void dispatchAdjustVolume(int direction) {
        postToCallback(CallbackMessageHandler.MSG_ADJUST_VOLUME, direction);
    }

    private void dispatchSetVolumeTo(int volume) {
        postToCallback(CallbackMessageHandler.MSG_SET_VOLUME, volume);
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
         * @return True if the event was handled, false otherwise.
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
                                return true;
                            } else if (!isPlaying && canPlay) {
                                onPlay();
                                return true;
                            }
                            break;
                    }
                }
            }
            return false;
        }

        /**
         * Override to handle requests to prepare playback. During the preparation, a session should
         * not hold audio focus in order to allow other sessions play seamlessly. The state of
         * playback should be updated to {@link PlaybackState#STATE_PAUSED} after the preparation is
         * done.
         */
        public void onPrepare() {
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
        }

        /**
         * Override to handle requests to begin playback.
         */
        public void onPlay() {
        }

        /**
         * Override to handle requests to begin playback from a search query. An
         * empty query indicates that the app may play any music. The
         * implementation should attempt to make a smart choice about what to
         * play.
         */
        public void onPlayFromSearch(String query, Bundle extras) {
        }

        /**
         * Override to handle requests to play a specific mediaId that was
         * provided by your app's {@link MediaBrowserService}.
         */
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
        }

        /**
         * Override to handle requests to play a specific media item represented by a URI.
         */
        public void onPlayFromUri(Uri uri, Bundle extras) {
        }

        /**
         * Override to handle requests to play an item with a given id from the
         * play queue.
         */
        public void onSkipToQueueItem(long id) {
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
        public void onPrepare() {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPrepare();
            }
        }

        @Override
        public void onPrepareFromMediaId(String mediaId, Bundle extras) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPrepareFromMediaId(mediaId, extras);
            }
        }

        @Override
        public void onPrepareFromSearch(String query, Bundle extras) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPrepareFromSearch(query, extras);
            }
        }

        @Override
        public void onPrepareFromUri(Uri uri, Bundle extras) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPrepareFromUri(uri, extras);
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
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPlayFromMediaId(mediaId, extras);
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
        public void onPlayFromUri(Uri uri, Bundle extras) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchPlayFromUri(uri, extras);
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
                session.dispatchAdjustVolume(direction);
            }
        }

        @Override
        public void onSetVolumeTo(int value) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.dispatchSetVolumeTo(value);
            }
        }

    }

    /**
     * A single item that is part of the play queue. It contains a description
     * of the item and its id in the queue.
     */
    public static final class QueueItem implements Parcelable {
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

        private QueueItem(Parcel in) {
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

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            mDescription.writeToParcel(dest, flags);
            dest.writeLong(mId);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<MediaSession.QueueItem> CREATOR = new Creator<MediaSession.QueueItem>() {

            @Override
            public MediaSession.QueueItem createFromParcel(Parcel p) {
                return new MediaSession.QueueItem(p);
            }

            @Override
            public MediaSession.QueueItem[] newArray(int size) {
                return new MediaSession.QueueItem[size];
            }
        };

        @Override
        public String toString() {
            return "MediaSession.QueueItem {" +
                    "Description=" + mDescription +
                    ", Id=" + mId + " }";
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

        public Command(String command, Bundle extras, ResultReceiver stub) {
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
            VolumeProvider vp;
            switch (msg.what) {
                case MSG_COMMAND:
                    Command cmd = (Command) msg.obj;
                    mCallback.onCommand(cmd.command, cmd.extras, cmd.stub);
                    break;
                case MSG_MEDIA_BUTTON:
                    mCallback.onMediaButtonEvent((Intent) msg.obj);
                    break;
                case MSG_PREPARE:
                    mCallback.onPrepare();
                    break;
                case MSG_PREPARE_MEDIA_ID:
                    mCallback.onPrepareFromMediaId((String) msg.obj, msg.getData());
                    break;
                case MSG_PREPARE_SEARCH:
                    mCallback.onPrepareFromSearch((String) msg.obj, msg.getData());
                    break;
                case MSG_PREPARE_URI:
                    mCallback.onPrepareFromUri((Uri) msg.obj, msg.getData());
                    break;
                case MSG_PLAY:
                    mCallback.onPlay();
                    break;
                case MSG_PLAY_MEDIA_ID:
                    mCallback.onPlayFromMediaId((String) msg.obj, msg.getData());
                    break;
                case MSG_PLAY_SEARCH:
                    mCallback.onPlayFromSearch((String) msg.obj, msg.getData());
                    break;
                case MSG_PLAY_URI:
                    mCallback.onPlayFromUri((Uri) msg.obj, msg.getData());
                    break;
                case MSG_SKIP_TO_ITEM:
                    mCallback.onSkipToQueueItem((Long) msg.obj);
                    break;
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
                case MSG_ADJUST_VOLUME:
                    synchronized (mLock) {
                        vp = mVolumeProvider;
                    }
                    if (vp != null) {
                        vp.onAdjustVolume((int) msg.obj);
                    }
                    break;
                case MSG_SET_VOLUME:
                    synchronized (mLock) {
                        vp = mVolumeProvider;
                    }
                    if (vp != null) {
                        vp.onSetVolumeTo((int) msg.obj);
                    }
                    break;
            }
        }
    }
}
