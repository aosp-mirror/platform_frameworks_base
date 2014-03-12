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

import android.content.Intent;
import android.media.Rating;
import android.media.session.IMediaController;
import android.media.session.IMediaSession;
import android.media.session.IMediaSessionCallback;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

/**
 * Allows interaction with media controllers, media routes, volume keys, media
 * buttons, and transport controls.
 * <p>
 * A MediaSession should be created when an app wants to publish media playback
 * information or negotiate with a media route. In general an app only needs one
 * session for all playback, though multiple sessions can be created for sending
 * media to multiple routes or to provide finer grain controls of media.
 * <p>
 * A MediaSession is created by calling
 * {@link MediaSessionManager#createSession(String)}. Once a session is created
 * apps that have the MEDIA_CONTENT_CONTROL permission can interact with the
 * session through {@link MediaSessionManager#getActiveSessions()}. The owner of
 * the session may also use {@link #getSessionToken()} to allow apps without
 * this permission to create a {@link MediaController} to interact with this
 * session.
 * <p>
 * To receive commands, media keys, and other events a Callback must be set with
 * {@link #addCallback(Callback)}.
 * <p>
 * When an app is finished performing playback it must call {@link #release()}
 * to clean up the session and notify any controllers.
 * <p>
 * MediaSession objects are thread safe
 */
public final class MediaSession {
    private static final String TAG = "MediaSession";

    private static final int MSG_MEDIA_BUTTON = 1;
    private static final int MSG_COMMAND = 2;
    private static final int MSG_ROUTE_CHANGE = 3;

    private static final String KEY_COMMAND = "command";
    private static final String KEY_EXTRAS = "extras";
    private static final String KEY_CALLBACK = "callback";

    private final Object mLock = new Object();

    private final MediaSessionToken mSessionToken;
    private final IMediaSession mBinder;
    private final CallbackStub mCbStub;

    private final ArrayList<MessageHandler> mCallbacks = new ArrayList<MessageHandler>();
    // TODO route interfaces
    private final ArrayMap<String, RouteInterface.Stub> mInterfaces
            = new ArrayMap<String, RouteInterface.Stub>();

    private TransportPerformer mPerformer;

    private boolean mPublished = false;;

    /**
     * @hide
     */
    public MediaSession(IMediaSession binder, CallbackStub cbStub) {
        mBinder = binder;
        mCbStub = cbStub;
        IMediaController controllerBinder = null;
        try {
            controllerBinder = mBinder.getMediaController();
        } catch (RemoteException e) {
            throw new RuntimeException("Dead object in MediaSessionController constructor: ", e);
        }
        mSessionToken = new MediaSessionToken(controllerBinder);
    }

    /**
     * Set the callback to receive updates on.
     *
     * @param callback The callback object
     */
    public void addCallback(Callback callback) {
        addCallback(callback, null);
    }

    public void addCallback(Callback callback, Handler handler) {
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
            MessageHandler msgHandler = new MessageHandler(handler.getLooper(), callback);
            mCallbacks.add(msgHandler);
        }
    }

    public void removeCallback(Callback callback) {
        synchronized (mLock) {
            removeCallbackLocked(callback);
        }
    }

    /**
     * Start using a TransportPerformer with this media session. This must be
     * called before calling publish and cannot be called more than once.
     * Calling this will allow MediaControllers to retrieve a
     * TransportController.
     *
     * @see TransportController
     * @return The TransportPerformer created for this session
     */
    public TransportPerformer setTransportPerformerEnabled() {
        if (mPerformer != null) {
            throw new IllegalStateException("setTransportPerformer can only be called once.");
        }
        if (mPublished) {
            throw new IllegalStateException("setTransportPerformer cannot be called after publish");
        }

        mPerformer = new TransportPerformer(mBinder);
        try {
            mBinder.setTransportPerformerEnabled();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Failure in setTransportPerformerEnabled.", e);
        }
        return mPerformer;
    }

    /**
     * Retrieves the TransportPerformer used by this session. If called before
     * {@link #setTransportPerformerEnabled} null will be returned.
     *
     * @return The TransportPerformer associated with this session or null
     */
    public TransportPerformer getTransportPerformer() {
        return mPerformer;
    }

    /**
     * Call after you have finished setting up the session. This will make it
     * available to listeners and begin pushing updates to MediaControllers.
     * This can only be called once.
     */
    public void publish() {
        if (mPublished) {
            throw new RuntimeException("publish() may only be called once.");
        }
        try {
            mBinder.publish();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Failure in publish.", e);
        }
        mPublished = true;
    }

    /**
     * Add an interface that can be used by MediaSessions. TODO make this a
     * route provider api
     *
     * @see RouteInterface
     * @param iface The interface to add
     * @hide
     */
    public void addInterface(RouteInterface.Stub iface) {
        if (iface == null) {
            throw new IllegalArgumentException("Stub cannot be null");
        }
        String name = iface.getName();
        if (TextUtils.isEmpty(name)) {
            throw new IllegalArgumentException("Stub must return a valid name");
        }
        if (mInterfaces.containsKey(iface)) {
            throw new IllegalArgumentException("Interface is already added");
        }
        synchronized (mLock) {
            mInterfaces.put(iface.getName(), iface);
        }
    }

    /**
     * Send a proprietary event to all MediaControllers listening to this
     * Session. It's up to the Controller/Session owner to determine the meaning
     * of any events.
     *
     * @param event The name of the event to send
     * @param extras Any extras included with the event
     */
    public void sendEvent(String event, Bundle extras) {
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
    public MediaSessionToken getSessionToken() {
        return mSessionToken;
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

    private boolean removeCallbackLocked(Callback cb) {
        if (cb == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        for (int i = mCallbacks.size() - 1; i >= 0; i--) {
            MessageHandler handler = mCallbacks.get(i);
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
                mCallbacks.get(i).post(MSG_COMMAND, cmd);
            }
        }
    }

    private void postMediaButton(Intent mediaButtonIntent) {
        synchronized (mLock) {
            for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                mCallbacks.get(i).post(MSG_MEDIA_BUTTON, mediaButtonIntent);
            }
        }
    }

    private void postRequestRouteChange(Bundle mediaRouteDescriptor) {
        synchronized (mLock) {
            for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                mCallbacks.get(i).post(MSG_ROUTE_CHANGE, mediaRouteDescriptor);
            }
        }
    }

    /**
     * Receives commands or updates from controllers and routes. An app can
     * specify what commands and buttons it supports by setting them on the
     * MediaSession (TODO).
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
        public void onMediaButton(Intent mediaButtonIntent) {
        }

        /**
         * Called when a controller has sent a custom command to this session.
         * The owner of the session may handle custom commands but is not
         * required to.
         *
         * @param command
         * @param extras optional
         */
        public void onCommand(String command, Bundle extras, ResultReceiver cb) {
        }

        /**
         * Called when the user has selected a different route to connect to.
         * The app is responsible for connecting to the new route and migrating
         * ongoing playback if necessary.
         *
         * @param descriptor
         */
        public void onRequestRouteChange(Bundle descriptor) {
        }
    }

    /**
     * @hide
     */
    public static class CallbackStub extends IMediaSessionCallback.Stub {
        private WeakReference<MediaSession> mMediaSession;

        public void setMediaSession(MediaSession session) {
            mMediaSession = new WeakReference<MediaSession>(session);
        }

        @Override
        public void onCommand(String command, Bundle extras, ResultReceiver cb)
                throws RemoteException {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.postCommand(command, extras, cb);
            }
        }

        @Override
        public void onMediaButton(Intent mediaButtonIntent) throws RemoteException {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.postMediaButton(mediaButtonIntent);
            }
        }

        @Override
        public void onRequestRouteChange(Bundle mediaRouteDescriptor) throws RemoteException {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                session.postRequestRouteChange(mediaRouteDescriptor);
            }
        }

        @Override
        public void onPlay() throws RemoteException {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onPlay();
                }
            }
        }

        @Override
        public void onPause() throws RemoteException {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onPause();
                }
            }
        }

        @Override
        public void onStop() throws RemoteException {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onStop();
                }
            }
        }

        @Override
        public void onNext() throws RemoteException {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onNext();
                }
            }
        }

        @Override
        public void onPrevious() throws RemoteException {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onPrevious();
                }
            }
        }

        @Override
        public void onFastForward() throws RemoteException {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onFastForward();
                }
            }
        }

        @Override
        public void onRewind() throws RemoteException {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onRewind();
                }
            }
        }

        @Override
        public void onSeekTo(long pos) throws RemoteException {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onSeekTo(pos);
                }
            }
        }

        @Override
        public void onRate(Rating rating) throws RemoteException {
            MediaSession session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onRate(rating);
                }
            }
        }

    }

    private class MessageHandler extends Handler {
        private MediaSession.Callback mCallback;

        public MessageHandler(Looper looper, MediaSession.Callback callback) {
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
                        mCallback.onMediaButton((Intent) msg.obj);
                        break;
                    case MSG_COMMAND:
                        Command cmd = (Command) msg.obj;
                        mCallback.onCommand(cmd.command, cmd.extras, cmd.stub);
                        break;
                    case MSG_ROUTE_CHANGE:
                        mCallback.onRequestRouteChange((Bundle) msg.obj);
                        break;
                }
            }
            msg.recycle();
        }

        public void post(int what, Object obj) {
            obtainMessage(what, obj).sendToTarget();
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
}
