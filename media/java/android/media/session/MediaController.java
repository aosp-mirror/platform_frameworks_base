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

import android.media.MediaMetadata;
import android.media.Rating;
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
    private static final String TAG = "SessionController";

    private static final int MSG_EVENT = 1;
    private static final int MSG_UPDATE_PLAYBACK_STATE = 2;
    private static final int MSG_UPDATE_METADATA = 3;
    private static final int MSG_ROUTE = 4;

    private final ISessionController mSessionBinder;

    private final CallbackStub mCbStub = new CallbackStub(this);
    private final ArrayList<MessageHandler> mCallbacks = new ArrayList<MessageHandler>();
    private final Object mLock = new Object();

    private boolean mCbRegistered = false;
    private MediaSessionInfo mInfo;

    private TransportControls mTransportController;

    private MediaController(ISessionController sessionBinder) {
        mSessionBinder = sessionBinder;
        mTransportController = new TransportControls();
    }

    /**
     * @hide
     */
    public static MediaController fromBinder(ISessionController sessionBinder) {
        MediaController controller = new MediaController(sessionBinder);
        try {
            controller.mSessionBinder.registerCallbackListener(controller.mCbStub);
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
     * Get a {@link TransportControls} instance for this session.
     *
     * @return A controls instance
     */
    public TransportControls getTransportControls() {
        return mTransportController;
    }

    /**
     * Send the specified media button event to the session. Only media keys can
     * be sent by this method, other keys will be ignored.
     *
     * @param keyEvent The media button event to dispatch.
     * @return true if the event was sent to the session, false otherwise.
     */
    public boolean dispatchMediaButtonEvent(KeyEvent keyEvent) {
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
    public PlaybackState getPlaybackState() {
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
    public MediaMetadata getMetadata() {
        try {
            return mSessionBinder.getMetadata();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling getMetadata.", e);
            return null;
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
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling getRatingType.", e);
            return Rating.RATING_NONE;
        }
    }

    /**
     * Get the flags for this session.
     *
     * @return The current set of flags for the session.
     * @hide
     */
    public long getFlags() {
        try {
            return mSessionBinder.getFlags();
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error calling getFlags.", e);
        }
        return 0;
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
    public void sendControlCommand(String command, Bundle params, ResultReceiver cb) {
        if (TextUtils.isEmpty(command)) {
            throw new IllegalArgumentException("command cannot be null or empty");
        }
        try {
            mSessionBinder.sendCommand(command, params, cb);
        } catch (RemoteException e) {
            Log.d(TAG, "Dead object in sendCommand.", e);
        }
    }

    /**
     * Request that the route picker be shown for this session. This should
     * generally be called in response to a user action.
     *
     * @hide
     */
    public void showRoutePicker() {
        try {
            mSessionBinder.showRoutePicker();
        } catch (RemoteException e) {
            Log.d(TAG, "Dead object in showRoutePicker", e);
        }
    }

    /**
     * Get the info for the session this controller is connected to.
     *
     * @return The session info for the connected session.
     * @hide
     */
    public MediaSessionInfo getSessionInfo() {
        if (mInfo == null) {
            try {
                mInfo = mSessionBinder.getSessionInfo();
            } catch (RemoteException e) {
                Log.e(TAG, "Error in getSessionInfo.", e);
            }
        }
        return mInfo;
    }

    /*
     * @hide
     */
    ISessionController getSessionBinder() {
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

    private final void postMessage(int what, Object obj, Bundle data) {
        synchronized (mLock) {
            for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                mCallbacks.get(i).post(what, obj, data);
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
        public void onSessionEvent(String event, Bundle extras) {
        }

        /**
         * Override to handle route changes for this session.
         *
         * @param route The new route
         * @hide
         */
        public void onRouteChanged(RouteInfo route) {
        }

        /**
         * Override to handle changes in playback state.
         *
         * @param state The new playback state of the session
         */
        public void onPlaybackStateChanged(PlaybackState state) {
        }

        /**
         * Override to handle changes to the current metadata.
         *
         * @see MediaMetadata
         * @param metadata The current metadata for the session or null
         */
        public void onMetadataChanged(MediaMetadata metadata) {
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
    }

    private final static class CallbackStub extends ISessionControllerCallback.Stub {
        private final WeakReference<MediaController> mController;

        public CallbackStub(MediaController controller) {
            mController = new WeakReference<MediaController>(controller);
        }

        @Override
        public void onEvent(String event, Bundle extras) {
            MediaController controller = mController.get();
            if (controller != null) {
                controller.postMessage(MSG_EVENT, event, extras);
            }
        }

        @Override
        public void onRouteChanged(RouteInfo route) {
            MediaController controller = mController.get();
            if (controller != null) {
                controller.postMessage(MSG_ROUTE, route, null);
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
                    mCallback.onSessionEvent((String) msg.obj, msg.getData());
                    break;
                case MSG_ROUTE:
                    mCallback.onRouteChanged((RouteInfo) msg.obj);
                    break;
                case MSG_UPDATE_PLAYBACK_STATE:
                    mCallback.onPlaybackStateChanged((PlaybackState) msg.obj);
                    break;
                case MSG_UPDATE_METADATA:
                    mCallback.onMetadataChanged((MediaMetadata) msg.obj);
                    break;
            }
        }

        public void post(int what, Object obj, Bundle data) {
            obtainMessage(what, obj).sendToTarget();
        }
    }

}
