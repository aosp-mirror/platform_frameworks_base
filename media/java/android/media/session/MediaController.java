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

    private static final int MSG_EVENT = 1;
    private static final int MESSAGE_PLAYBACK_STATE = 2;
    private static final int MESSAGE_METADATA = 3;
    private static final int MSG_ROUTE = 4;

    private final IMediaController mSessionBinder;

    private final CallbackStub mCbStub = new CallbackStub(this);
    private final ArrayList<MessageHandler> mCallbacks = new ArrayList<MessageHandler>();
    private final Object mLock = new Object();

    private boolean mCbRegistered = false;

    private TransportController mTransportController;

    private MediaController(IMediaController sessionBinder) {
        mSessionBinder = sessionBinder;
    }

    /**
     * @hide
     */
    public static MediaController fromBinder(IMediaController sessionBinder) {
        MediaController controller = new MediaController(sessionBinder);
        try {
            controller.mSessionBinder.registerCallbackListener(controller.mCbStub);
            if (controller.mSessionBinder.isTransportControlEnabled()) {
                controller.mTransportController = new TransportController(sessionBinder);
            }
        } catch (RemoteException e) {
            Log.wtf(TAG, "MediaController created with expired token", e);
            controller = null;
        }
        return controller;
    }

    /**
     * Get a new MediaController for a MediaSessionToken. If successful the
     * controller returned will be connected to the session that generated the
     * token.
     *
     * @param token The session token to use
     * @return A controller for the session or null
     */
    public static MediaController fromToken(MediaSessionToken token) {
        return fromBinder(token.getBinder());
    }

    /**
     * Get a TransportController if the session supports it. If it is not
     * supported null will be returned.
     *
     * @return A TransportController or null
     */
    public TransportController getTransportController() {
        return mTransportController;
    }

    /**
     * Send the specified media button to the session. Only media keys can be
     * sent using this method.
     *
     * @param keycode The media button keycode, such as
     *            {@link KeyEvent#KEYCODE_MEDIA_PLAY}.
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
     * posted on the specified handler's thread.
     *
     * @param cb Cannot be null.
     * @param handler The handler to post updates on. If null the callers thread
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

    /**
     * Sends a generic command to the session. It is up to the session creator
     * to decide what commands and parameters they will support. As such,
     * commands should only be sent to sessions that the controller owns.
     *
     * @param command The command to send
     * @param params Any parameters to include with the command
     * @param cb The callback to receive the result on
     */
    public void sendCommand(String command, Bundle params, ResultReceiver cb) {
        if (TextUtils.isEmpty(command)) {
            throw new IllegalArgumentException("command cannot be null or empty");
        }
        try {
            mSessionBinder.sendCommand(command, params, cb);
        } catch (RemoteException e) {
            Log.d(TAG, "Dead object in sendCommand.", e);
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
        if (getHandlerForCallbackLocked(cb) != null) {
            Log.w(TAG, "Callback is already added, ignoring");
            return;
        }
        MessageHandler holder = new MessageHandler(handler.getLooper(), cb);
        mCallbacks.add(holder);

        if (!mCbRegistered) {
            try {
                mSessionBinder.registerCallbackListener(mCbStub);
                mCbRegistered = true;
            } catch (RemoteException e) {
                Log.d(TAG, "Dead object in registerCallback", e);
            }
        }
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

    private void postEvent(String event, Bundle extras) {
        synchronized (mLock) {
            for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                mCallbacks.get(i).post(MSG_EVENT, event, extras);
            }
        }
    }

    private void postRouteChanged(Bundle routeDescriptor) {
        synchronized (mLock) {
            for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                mCallbacks.get(i).post(MSG_ROUTE, null, routeDescriptor);
            }
        }
    }

    /**
     * Callback for receiving updates on from the session. A Callback can be
     * registered using {@link #addCallback}
     */
    public static abstract class Callback {
        /**
         * Override to handle custom events sent by the session owner without a
         * specified interface. Controllers should only handle these for
         * sessions they own.
         *
         * @param event
         */
        public void onEvent(String event, Bundle extras) {
        }

        /**
         * Override to handle route changes for this session.
         *
         * @param route
         */
        public void onRouteChanged(Bundle route) {
        }
    }

    private final static class CallbackStub extends IMediaControllerCallback.Stub {
        private final WeakReference<MediaController> mController;

        public CallbackStub(MediaController controller) {
            mController = new WeakReference<MediaController>(controller);
        }

        @Override
        public void onEvent(String event, Bundle extras) {
            MediaController controller = mController.get();
            if (controller != null) {
                controller.postEvent(event, extras);
            }
        }

        @Override
        public void onRouteChanged(Bundle mediaRouteDescriptor) {
            MediaController controller = mController.get();
            if (controller != null) {
                controller.postRouteChanged(mediaRouteDescriptor);
            }
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            MediaController controller = mController.get();
            if (controller != null) {
                TransportController tc = controller.getTransportController();
                if (tc != null) {
                    tc.postPlaybackStateChanged(state);
                }
            }
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            MediaController controller = mController.get();
            if (controller != null) {
                TransportController tc = controller.getTransportController();
                if (tc != null) {
                    tc.postMetadataChanged(metadata);
                }
            }
        }

    }

    private final static class MessageHandler extends Handler {
        private final MediaController.Callback mCallback;

        public MessageHandler(Looper looper, MediaController.Callback cb) {
            super(looper, null, true);
            mCallback = cb;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_EVENT:
                    mCallback.onEvent((String) msg.obj, msg.getData());
                    break;
                case MSG_ROUTE:
                    mCallback.onRouteChanged(msg.getData());
            }
        }

        public void post(int what, Object obj, Bundle data) {
            obtainMessage(what, obj).sendToTarget();
        }
    }

}
