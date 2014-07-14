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
import android.content.ComponentName;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.Rating;
import android.media.VolumeProvider;
import android.media.routing.MediaRouter;
import android.media.session.ISessionController;
import android.media.session.ISession;
import android.media.session.ISessionCallback;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
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
 * set with {@link #addCallback(Callback)}. To receive transport control
 * commands a {@link TransportControlsCallback} must be set with
 * {@link #addTransportControlsCallback}.
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
     * control commands through a {@link TransportControlsCallback}.
     * The callback can be retrieved by calling {@link #addTransportControlsCallback}.
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

    /**
     * The session uses local playback.
     */
    public static final int PLAYBACK_TYPE_LOCAL = 1;

    /**
     * The session uses remote playback.
     */
    public static final int PLAYBACK_TYPE_REMOTE = 2;

    private final Object mLock = new Object();

    private final MediaSession.Token mSessionToken;
    private final ISession mBinder;
    private final CallbackStub mCbStub;

    private final ArrayList<CallbackMessageHandler> mCallbacks
            = new ArrayList<CallbackMessageHandler>();
    private final ArrayList<TransportMessageHandler> mTransportCallbacks
            = new ArrayList<TransportMessageHandler>();

    private VolumeProvider mVolumeProvider;

    private boolean mActive = false;

    /**
     * @hide
     */
    public MediaSession(ISession binder, CallbackStub cbStub) {
        mBinder = binder;
        mCbStub = cbStub;
        ISessionController controllerBinder = null;
        try {
            controllerBinder = mBinder.getController();
        } catch (RemoteException e) {
            throw new RuntimeException("Dead object in MediaSessionController constructor: ", e);
        }
        mSessionToken = new Token(controllerBinder);
    }

    /**
     * Add a callback to receive updates on for the MediaSession. This includes
     * media button and volume events. The caller's thread will be used to post
     * events.
     *
     * @param callback The callback object
     */
    public void addCallback(@NonNull Callback callback) {
        addCallback(callback, null);
    }

    /**
     * Add a callback to receive updates for the MediaSession. This includes
     * media button and volume events.
     *
     * @param callback The callback to receive updates on.
     * @param handler The handler that events should be posted on.
     */
    public void addCallback(@NonNull Callback callback, @Nullable Handler handler) {
        if (callback == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        synchronized (mLock) {
            if (getHandlerForCallbackLocked(callback) != null) {
                Log.w(TAG, "Callback is already added, ignoring");
                return;
            }
            if (handler == null) {
                handler = new Handler();
            }
            CallbackMessageHandler msgHandler = new CallbackMessageHandler(handler.getLooper(),
                    callback);
            mCallbacks.add(msgHandler);
        }
    }

    /**
     * Remove a callback. It will no longer receive updates.
     *
     * @param callback The callback to remove.
     */
    public void removeCallback(@NonNull Callback callback) {
        synchronized (mLock) {
            removeCallbackLocked(callback);
        }
    }

    /**
     * Set an intent for launching UI for this Session. This can be used as a
     * quick link to an ongoing media screen.
     *
     * @param pi The intent to launch to show UI for this Session.
     */
    public void setLaunchPendingIntent(@Nullable PendingIntent pi) {
        // TODO
    }

    /**
     * Associates a {@link MediaRouter} with this session to control the destination
     * of media content.
     * <p>
     * A media router may only be associated with at most one session at a time.
     * </p>
     *
     * @param router The media router, or null to remove the current association.
     */
    public void setMediaRouter(@Nullable MediaRouter router) {
        try {
            mBinder.setMediaRouter(router != null ? router.getBinder() : null);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Failure in setMediaButtonReceiver.", e);
        }
    }

    /**
     * Set a media button event receiver component to use to restart playback
     * after an app has been stopped.
     *
     * @param mbr The receiver component to send the media button event to.
     * @hide
     */
    public void setMediaButtonReceiver(@Nullable ComponentName mbr) {
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
    public void setFlags(int flags) {
        try {
            mBinder.setFlags(flags);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Failure in setFlags.", e);
        }
    }

    /**
     * Set the stream this session is playing on. This will affect the system's
     * volume handling for this session. If {@link #setPlaybackToRemote} was
     * previously called it will stop receiving volume commands and the system
     * will begin sending volume changes to the appropriate stream.
     * <p>
     * By default sessions are on {@link AudioManager#STREAM_MUSIC}.
     *
     * @param stream The {@link AudioManager} stream this session is playing on.
     */
    public void setPlaybackToLocal(int stream) {
        try {
            mBinder.configureVolumeHandling(PLAYBACK_TYPE_LOCAL, stream, 0);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Failure in setPlaybackToLocal.", e);
        }
    }

    /**
     * Configure this session to use remote volume handling. This must be called
     * to receive volume button events, otherwise the system will adjust the
     * current stream volume for this session. If {@link #setPlaybackToLocal}
     * was previously called that stream will stop receiving volume changes for
     * this session.
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
            mBinder.configureVolumeHandling(PLAYBACK_TYPE_REMOTE, volumeProvider.getVolumeControl(),
                    volumeProvider.getMaxVolume());
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
     * Add a callback to receive transport controls on, such as play, rewind, or
     * fast forward.
     *
     * @param callback The callback object
     */
    public void addTransportControlsCallback(@NonNull TransportControlsCallback callback) {
        addTransportControlsCallback(callback, null);
    }

    /**
     * Add a callback to receive transport controls on, such as play, rewind, or
     * fast forward. The updates will be posted to the specified handler. If no
     * handler is provided they will be posted to the caller's thread.
     *
     * @param callback The callback to receive updates on
     * @param handler The handler to post the updates on
     */
    public void addTransportControlsCallback(@NonNull TransportControlsCallback callback,
            @Nullable Handler handler) {
        if (callback == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        synchronized (mLock) {
            if (getTransportControlsHandlerForCallbackLocked(callback) != null) {
                Log.w(TAG, "Callback is already added, ignoring");
                return;
            }
            if (handler == null) {
                handler = new Handler();
            }
            TransportMessageHandler msgHandler = new TransportMessageHandler(handler.getLooper(),
                    callback);
            mTransportCallbacks.add(msgHandler);
        }
    }

    /**
     * Stop receiving transport controls on the specified callback. If an update
     * has already been posted you may still receive it after this call returns.
     *
     * @param callback The callback to stop receiving updates on
     */
    public void removeTransportControlsCallback(@NonNull TransportControlsCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        synchronized (mLock) {
            removeTransportControlsCallbackLocked(callback);
        }
    }

    /**
     * Update the current playback state.
     *
     * @param state The current state of playback
     */
    public void setPlaybackState(@Nullable PlaybackState state) {
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
        postToTransportCallbacks(TransportMessageHandler.MSG_PLAY);
    }

    private void dispatchPause() {
        postToTransportCallbacks(TransportMessageHandler.MSG_PAUSE);
    }

    private void dispatchStop() {
        postToTransportCallbacks(TransportMessageHandler.MSG_STOP);
    }

    private void dispatchNext() {
        postToTransportCallbacks(TransportMessageHandler.MSG_NEXT);
    }

    private void dispatchPrevious() {
        postToTransportCallbacks(TransportMessageHandler.MSG_PREVIOUS);
    }

    private void dispatchFastForward() {
        postToTransportCallbacks(TransportMessageHandler.MSG_FAST_FORWARD);
    }

    private void dispatchRewind() {
        postToTransportCallbacks(TransportMessageHandler.MSG_REWIND);
    }

    private void dispatchSeekTo(long pos) {
        postToTransportCallbacks(TransportMessageHandler.MSG_SEEK_TO, pos);
    }

    private void dispatchRate(Rating rating) {
        postToTransportCallbacks(TransportMessageHandler.MSG_RATE, rating);
    }

    private TransportMessageHandler getTransportControlsHandlerForCallbackLocked(
            TransportControlsCallback callback) {
        for (int i = mTransportCallbacks.size() - 1; i >= 0; i--) {
            TransportMessageHandler handler = mTransportCallbacks.get(i);
            if (callback == handler.mCallback) {
                return handler;
            }
        }
        return null;
    }

    private boolean removeTransportControlsCallbackLocked(TransportControlsCallback callback) {
        for (int i = mTransportCallbacks.size() - 1; i >= 0; i--) {
            if (callback == mTransportCallbacks.get(i).mCallback) {
                mTransportCallbacks.remove(i);
                return true;
            }
        }
        return false;
    }

    private void postToTransportCallbacks(int what, Object obj) {
        synchronized (mLock) {
            for (int i = mTransportCallbacks.size() - 1; i >= 0; i--) {
                mTransportCallbacks.get(i).post(what, obj);
            }
        }
    }

    private void postToTransportCallbacks(int what) {
        postToTransportCallbacks(what, null);
    }

    private CallbackMessageHandler getHandlerForCallbackLocked(Callback cb) {
        if (cb == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        for (int i = mCallbacks.size() - 1; i >= 0; i--) {
            CallbackMessageHandler handler = mCallbacks.get(i);
            if (cb == handler.mCallback) {
                return handler;
            }
        }
        return null;
    }

    private boolean removeCallbackLocked(Callback cb) {
        if (cb == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        for (int i = mCallbacks.size() - 1; i >= 0; i--) {
            CallbackMessageHandler handler = mCallbacks.get(i);
            if (cb == handler.mCallback) {
                mCallbacks.remove(i);
                return true;
            }
        }
        return false;
    }

    private void postCommand(String command, Bundle extras, ResultReceiver resultCb) {
        Command cmd = new Command(command, extras, resultCb);
        synchronized (mLock) {
            for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                mCallbacks.get(i).post(CallbackMessageHandler.MSG_COMMAND, cmd);
            }
        }
    }

    private void postMediaButton(Intent mediaButtonIntent) {
        synchronized (mLock) {
            for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                mCallbacks.get(i).post(CallbackMessageHandler.MSG_MEDIA_BUTTON, mediaButtonIntent);
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
     * Receives generic commands or updates from controllers and the system.
     * Callbacks may be registered using {@link #addCallback}.
     */
    public abstract static class Callback {

        public Callback() {
        }

        /**
         * Called when a media button is pressed and this session has the
         * highest priority or a controller sends a media button event to the
         * session. TODO determine if using Intents identical to the ones
         * RemoteControlClient receives is useful
         * <p>
         * The intent will be of type {@link Intent#ACTION_MEDIA_BUTTON} with a
         * KeyEvent in {@link Intent#EXTRA_KEY_EVENT}
         *
         * @param mediaButtonIntent an intent containing the KeyEvent as an
         *            extra
         */
        public void onMediaButtonEvent(@NonNull Intent mediaButtonIntent) {
        }

        /**
         * Called when a controller has sent a custom command to this session.
         * The owner of the session may handle custom commands but is not
         * required to.
         *
         * @param command The command name.
         * @param extras Optional parameters for the command, may be null.
         * @param cb A result receiver to which a result may be sent by the command, may be null.
         */
        public void onControlCommand(@NonNull String command, @Nullable Bundle extras,
                @Nullable ResultReceiver cb) {
        }
    }

    /**
     * Receives transport control commands. Callbacks may be registered using
     * {@link #addTransportControlsCallback}.
     */
    public static abstract class TransportControlsCallback {

        /**
         * Override to handle requests to begin playback.
         */
        public void onPlay() {
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
    }

    /**
     * @hide
     */
    public static class CallbackStub extends ISessionCallback.Stub {
        private WeakReference<MediaSession> mMediaSession;

        public void setMediaSession(MediaSession session) {
            mMediaSession = new WeakReference<MediaSession>(session);
        }

        @Override
        public void onCommand(String command, Bundle extras, ResultReceiver cb) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.postCommand(command, extras, cb);
            }
        }

        @Override
        public void onMediaButton(Intent mediaButtonIntent, int sequenceNumber,
                ResultReceiver cb) {
            MediaSession session = mMediaSession.get();
            try {
                if (session != null) {
                    session.postMediaButton(mediaButtonIntent);
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
        public void onAdjustVolumeBy(int delta) {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                if (session.mVolumeProvider != null) {
                    session.mVolumeProvider.onAdjustVolumeBy(delta);
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

    private class CallbackMessageHandler extends Handler {
        private static final int MSG_MEDIA_BUTTON = 1;
        private static final int MSG_COMMAND = 2;

        private MediaSession.Callback mCallback;

        public CallbackMessageHandler(Looper looper, MediaSession.Callback callback) {
            super(looper, null, true);
            mCallback = callback;
        }

        @Override
        public void handleMessage(Message msg) {
            synchronized (mLock) {
                if (mCallback == null) {
                    return;
                }
                switch (msg.what) {
                    case MSG_MEDIA_BUTTON:
                        mCallback.onMediaButtonEvent((Intent) msg.obj);
                        break;
                    case MSG_COMMAND:
                        Command cmd = (Command) msg.obj;
                        mCallback.onControlCommand(cmd.command, cmd.extras, cmd.stub);
                        break;
                }
            }
        }

        public void post(int what, Object obj) {
            obtainMessage(what, obj).sendToTarget();
        }

        public void post(int what, Object obj, int arg1) {
            obtainMessage(what, arg1, 0, obj).sendToTarget();
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

    private class TransportMessageHandler extends Handler {
        private static final int MSG_PLAY = 1;
        private static final int MSG_PAUSE = 2;
        private static final int MSG_STOP = 3;
        private static final int MSG_NEXT = 4;
        private static final int MSG_PREVIOUS = 5;
        private static final int MSG_FAST_FORWARD = 6;
        private static final int MSG_REWIND = 7;
        private static final int MSG_SEEK_TO = 8;
        private static final int MSG_RATE = 9;

        private TransportControlsCallback mCallback;

        public TransportMessageHandler(Looper looper, TransportControlsCallback cb) {
            super(looper);
            mCallback = cb;
        }

        public void post(int what, Object obj) {
            obtainMessage(what, obj).sendToTarget();
        }

        public void post(int what) {
            post(what, null);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PLAY:
                    mCallback.onPlay();
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
            }
        }
    }
}
