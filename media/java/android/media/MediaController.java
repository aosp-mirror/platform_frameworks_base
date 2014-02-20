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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;

/**
 * Allows an app to interact with an ongoing media session. Media buttons and
 * other commands can be sent to the session. A callback may be registered to
 * receive updates from the session, such as metadata and play state changes.
 * <p>
 * A MediaController can be created through {@link MediaSessionManager} if you
 * hold the "android.permission.MEDIA_CONTENT_CONTROL" permission or directly if
 * you have a {@link MediaSessionToken} from the session owner.
 * <p>
 * MediaController objects are thread-safe.
 */
public final class MediaController {
    private static final String TAG = "MediaController";

    private static final int MESSAGE_EVENT = 1;
    private static final int MESSAGE_PLAYBACK_STATE = 2;
    private static final int MESSAGE_METADATA = 3;
    private static final int MESSAGE_ROUTE = 4;

    private static final String KEY_EVENT = "event";
    private static final String KEY_EXTRAS = "extras";

    private final IMediaController mSessionBinder;

    private final CallbackStub mCbStub = new CallbackStub();
    private final ArrayList<Callback> mCbs = new ArrayList<Callback>();
    private final Object mLock = new Object();

    private boolean mCbRegistered = false;

    /**
     * If you have a {@link MediaSessionToken} from the owner of the session a
     * controller can be created directly. It is up to the session creator to
     * handle token distribution if desired.
     *
     * @see MediaSession#getSessionToken()
     * @param token A token from the creator of the session
     */
    public MediaController(MediaSessionToken token) {
        mSessionBinder = token.getBinder();
    }

    /**
     * @hide
     */
    public MediaController(IMediaController sessionBinder) {
        mSessionBinder = sessionBinder;
    }

    /**
     * Sends a generic command to the session. It is up to the session creator
     * to decide what commands and parameters they will support. As such,
     * commands should only be sent to sessions that the controller owns.
     *
     * @param command The command to send
     * @param params Any parameters to include with the command
     */
    public void sendCommand(String command, Bundle params) {
        if (TextUtils.isEmpty(command)) {
            throw new IllegalArgumentException("command cannot be null or empty");
        }
        try {
            mSessionBinder.sendCommand(command, params);
        } catch (RemoteException e) {
            Log.d(TAG, "Dead object in sendCommand.", e);
        }
    }

    /**
     * Send the specified media button to the session. Only media keys can be
     * sent using this method.
     *
     * @param keycode The media button keycode, such as
     *            {@link KeyEvent#KEYCODE_MEDIA_BUTTON_PLAY}.
     */
    public void sendMediaButton(int keycode) {
        if (!KeyEvent.isMediaKey(keycode)) {
            throw new IllegalArgumentException("May only send media buttons through "
                    + "sendMediaButton");
        }
        // TODO do something better than key down/up events
        KeyEvent event = new KeyEvent(KeyEvent.ACTION_UP, keycode);
        try {
            mSessionBinder.sendMediaButton(event);
        } catch (RemoteException e) {
            Log.d(TAG, "Dead object in sendMediaButton", e);
        }
    }

    /**
     * Adds a callback to receive updates from the Session. Updates will be
     * posted on the caller's thread.
     *
     * @param cb The callback object, must not be null
     */
    public void addCallback(Callback cb) {
        addCallback(cb, null);
    }

    /**
     * Adds a callback to receive updates from the session. Updates will be
     * posted on the specified handler.
     *
     * @param cb Cannot be null.
     * @param handler The handler to post updates on, if null the callers thread
     *            will be used
     */
    public void addCallback(Callback cb, Handler handler) {
        if (handler == null) {
            handler = new Handler();
        }
        synchronized (mLock) {
            addCallbackLocked(cb, handler);
        }
    }

    /**
     * Stop receiving updates on the specified callback. If an update has
     * already been posted you may still receive it after calling this method.
     *
     * @param cb The callback to remove
     */
    public void removeCallback(Callback cb) {
        synchronized (mLock) {
            removeCallbackLocked(cb);
        }
    }

    /*
     * @hide
     */
    IMediaController getSessionBinder() {
        return mSessionBinder;
    }

    private void addCallbackLocked(Callback cb, Handler handler) {
        if (cb == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        if (handler == null) {
            throw new IllegalArgumentException("Handler cannot be null");
        }
        if (mCbs.contains(cb)) {
            Log.w(TAG, "Callback is already added, ignoring");
            return;
        }
        cb.setHandler(handler);
        mCbs.add(cb);

        // Only register one cb binder, track callbacks internally and notify
        if (!mCbRegistered) {
            try {
                mSessionBinder.registerCallbackListener(mCbStub);
                mCbRegistered = true;
            } catch (RemoteException e) {
                Log.d(TAG, "Dead object in registerCallback", e);
            }
        }
    }

    private void removeCallbackLocked(Callback cb) {
        if (cb == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        mCbs.remove(cb);

        if (mCbs.size() == 0 && mCbRegistered) {
            try {
                mSessionBinder.unregisterCallbackListener(mCbStub);
            } catch (RemoteException e) {
                Log.d(TAG, "Dead object in unregisterCallback", e);
            }
            mCbRegistered = false;
        }
    }

    private void pushOnEventLocked(String event, Bundle extras) {
        for (int i = mCbs.size() - 1; i >= 0; i--) {
            mCbs.get(i).postEvent(event, extras);
        }
    }

    private void pushOnMetadataUpdateLocked(Bundle metadata) {
        for (int i = mCbs.size() - 1; i >= 0; i--) {
            mCbs.get(i).postMetadataUpdate(metadata);
        }
    }

    private void pushOnPlaybackUpdateLocked(int newState) {
        for (int i = mCbs.size() - 1; i >= 0; i--) {
            mCbs.get(i).postPlaybackStateChange(newState);
        }
    }

    private void pushOnRouteChangedLocked(Bundle routeDescriptor) {
        for (int i = mCbs.size() - 1; i >= 0; i--) {
            mCbs.get(i).postRouteChanged(routeDescriptor);
        }
    }

    /**
     * MediaSession callbacks will be posted on the thread that created the
     * Callback object.
     */
    public static abstract class Callback {
        private Handler mHandler;

        /**
         * Override to handle custom events sent by the session owner.
         * Controllers should only handle these for sessions they own.
         *
         * @param event
         */
        public void onEvent(String event, Bundle extras) {
        }

        /**
         * Override to handle updates to the playback state. Valid values are in
         * {@link RemoteControlClient}. TODO put playstate values somewhere more
         * generic.
         *
         * @param state
         */
        public void onPlaybackStateChange(int state) {
        }

        /**
         * Override to handle metadata changes for this session's media. The
         * default supported fields are those in {@link MediaMetadataRetriever}.
         *
         * @param metadata
         */
        public void onMetadataUpdate(Bundle metadata) {
        }

        /**
         * Override to handle route changes for this session.
         *
         * @param route
         */
        public void onRouteChanged(Bundle route) {
        }

        private void setHandler(Handler handler) {
            mHandler = new MessageHandler(handler.getLooper(), this);
        }

        private void postEvent(String event, Bundle extras) {
            Bundle eventBundle = new Bundle();
            eventBundle.putString(KEY_EVENT, event);
            eventBundle.putBundle(KEY_EXTRAS, extras);
            Message msg = mHandler.obtainMessage(MESSAGE_EVENT, eventBundle);
            mHandler.sendMessage(msg);
        }

        private void postPlaybackStateChange(final int state) {
            Message msg = mHandler.obtainMessage(MESSAGE_PLAYBACK_STATE, state, 0);
            mHandler.sendMessage(msg);
        }

        private void postMetadataUpdate(final Bundle metadata) {
            Message msg = mHandler.obtainMessage(MESSAGE_METADATA, metadata);
            mHandler.sendMessage(msg);
        }

        private void postRouteChanged(final Bundle descriptor) {
            Message msg = mHandler.obtainMessage(MESSAGE_ROUTE, descriptor);
            mHandler.sendMessage(msg);
        }
    }

    private final class CallbackStub extends IMediaControllerCallback.Stub {

        @Override
        public void onEvent(String event, Bundle extras) throws RemoteException {
            synchronized (mLock) {
                pushOnEventLocked(event, extras);
            }
        }

        @Override
        public void onMetadataUpdate(Bundle metadata) throws RemoteException {
            synchronized (mLock) {
                pushOnMetadataUpdateLocked(metadata);
            }
        }

        @Override
        public void onPlaybackUpdate(final int newState) throws RemoteException {
            synchronized (mLock) {
                pushOnPlaybackUpdateLocked(newState);
            }
        }

        @Override
        public void onRouteChanged(Bundle mediaRouteDescriptor) throws RemoteException {
            synchronized (mLock) {
                pushOnRouteChangedLocked(mediaRouteDescriptor);
            }
        }

    }

    private final static class MessageHandler extends Handler {
        private final MediaController.Callback mCb;

        public MessageHandler(Looper looper, MediaController.Callback cb) {
            super(looper);
            mCb = cb;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_EVENT:
                    Bundle eventBundle = (Bundle) msg.obj;
                    String event = eventBundle.getString(KEY_EVENT);
                    Bundle extras = eventBundle.getBundle(KEY_EXTRAS);
                    mCb.onEvent(event, extras);
                    break;
                case MESSAGE_PLAYBACK_STATE:
                    mCb.onPlaybackStateChange(msg.arg1);
                    break;
                case MESSAGE_METADATA:
                    mCb.onMetadataUpdate((Bundle) msg.obj);
                    break;
                case MESSAGE_ROUTE:
                    mCb.onRouteChanged((Bundle) msg.obj);
            }
        }
    }

}
