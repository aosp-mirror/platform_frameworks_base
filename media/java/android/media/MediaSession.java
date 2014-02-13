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

package android.media;

import android.content.Intent;
import android.media.IMediaSession;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

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
 * session through {@link MediaSessionManager#listActiveSessions()}. The owner
 * of the session may also use {@link #getSessionToken()} to allow apps without
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

    private static final int MESSAGE_MEDIA_BUTTON = 1;
    private static final int MESSAGE_COMMAND = 2;
    private static final int MESSAGE_ROUTE_CHANGE = 3;

    private static final String KEY_COMMAND = "command";
    private static final String KEY_EXTRAS = "extras";

    private final Object mLock = new Object();

    private final MediaSessionToken mSessionToken;
    private final IMediaSession mBinder;
    private final CallbackStub mCbStub;

    private final ArrayList<Callback> mCallbacks = new ArrayList<Callback>();

    /**
     * @hide
     */
    public MediaSession(IMediaSession binder, CallbackStub cbStub) {
        mBinder = binder;
        mCbStub = cbStub;
        IMediaController controllerBinder = null;
        try {
            controllerBinder = mBinder.getMediaSessionToken();
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
            if (mCallbacks.contains(callback)) {
                Log.w(TAG, "Callback is already added, ignoring");
            }
            if (handler == null) {
                handler = new Handler();
            }
            MessageHandler msgHandler = new MessageHandler(handler.getLooper(), callback);
            callback.setHandler(msgHandler);
            mCallbacks.add(callback);
        }
    }

    public void removeCallback(Callback callback) {
        mCallbacks.remove(callback);
    }

    /**
     * Publish the current playback state to the system and any controllers.
     * Valid values are defined in {@link RemoteControlClient}. TODO move play
     * states somewhere else.
     *
     * @param state
     */
    public void setPlaybackState(int state) {
        try {
            mBinder.setPlaybackState(state);
        } catch (RemoteException e) {
            Log.e(TAG, "Dead object in setPlaybackState: ", e);
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
            Log.e(TAG, "Dead object in onDestroy: ", e);
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

    private void postCommand(String command, Bundle extras) {
        Bundle commandBundle = new Bundle();
        commandBundle.putString(KEY_COMMAND, command);
        commandBundle.putBundle(KEY_EXTRAS, extras);
        synchronized (mLock) {
            for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                Callback cb = mCallbacks.get(i);
                Message msg = cb.mHandler.obtainMessage(MESSAGE_COMMAND, commandBundle);
                cb.mHandler.sendMessage(msg);
            }
        }
    }

    private void postMediaButton(Intent mediaButtonIntent) {
        synchronized (mLock) {
            for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                Callback cb = mCallbacks.get(i);
                Message msg = cb.mHandler.obtainMessage(MESSAGE_MEDIA_BUTTON, mediaButtonIntent);
                cb.mHandler.sendMessage(msg);
            }
        }
    }

    private void postRequestRouteChange(Bundle mediaRouteDescriptor) {
        synchronized (mLock) {
            for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                Callback cb = mCallbacks.get(i);
                Message msg = cb.mHandler.obtainMessage(MESSAGE_ROUTE_CHANGE, mediaRouteDescriptor);
                cb.mHandler.sendMessage(msg);
            }
        }
    }

    /**
     * Receives commands or updates from controllers and routes. An app can
     * specify what commands and buttons it supports by setting them on the
     * MediaSession (TODO).
     */
    public abstract static class Callback {
        private MessageHandler mHandler;

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
        public void onCommand(String command, Bundle extras) {
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

        private void setHandler(MessageHandler handler) {
            mHandler = handler;
        }
    }

    /**
     * @hide
     */
    public static class CallbackStub extends IMediaSessionCallback.Stub {
        private MediaSession mMediaSession;

        public void setMediaSession(MediaSession session) {
            mMediaSession = session;
        }

        @Override
        public void onCommand(String command, Bundle extras) throws RemoteException {
            mMediaSession.postCommand(command, extras);
        }

        @Override
        public void onMediaButton(Intent mediaButtonIntent) throws RemoteException {
            mMediaSession.postMediaButton(mediaButtonIntent);
        }

        @Override
        public void onRequestRouteChange(Bundle mediaRouteDescriptor) throws RemoteException {
            mMediaSession.postRequestRouteChange(mediaRouteDescriptor);
        }

    }

    private class MessageHandler extends Handler {
        private MediaSession.Callback mCallback;

        public MessageHandler(Looper looper, MediaSession.Callback callback) {
            super(looper);
            mCallback = callback;
        }

        @Override
        public void handleMessage(Message msg) {
            synchronized (mLock) {
                if (mCallback == null) {
                    return;
                }
                switch (msg.what) {
                    case MESSAGE_MEDIA_BUTTON:
                        mCallback.onMediaButton((Intent) msg.obj);
                        break;
                    case MESSAGE_COMMAND:
                        Bundle commandBundle = (Bundle) msg.obj;
                        String command = commandBundle.getString(KEY_COMMAND);
                        Bundle extras = commandBundle.getBundle(KEY_EXTRAS);
                        mCallback.onCommand(command, extras);
                        break;
                    case MESSAGE_ROUTE_CHANGE:
                        mCallback.onRequestRouteChange((Bundle) msg.obj);
                        break;
                }
            }
            msg.recycle();
        }
    }
}
