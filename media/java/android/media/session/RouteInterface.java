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
import android.os.ResultReceiver;
import android.util.Log;

import java.util.ArrayList;

/**
 * A route can support multiple interfaces for a {@link MediaSession} to
 * interact with. To use a specific interface with a route a
 * MediaSessionRouteInterface needs to be retrieved from the route. An
 * implementation of the specific interface, like
 * {@link RoutePlaybackControls}, should be used to simplify communication
 * and reduce errors on that interface.
 *
 * @see RoutePlaybackControls for an example
 * @hide
 */
public final class RouteInterface {
    private static final String TAG = "RouteInterface";

    /**
     * Error indicating the route is currently not connected.
     */
    public static final int RESULT_NOT_CONNECTED = -5;
    /**
     * Error indicating the session is no longer using the route this command
     * was sent to.
     */
    public static final int RESULT_ROUTE_IS_STALE = -4;
    /**
     * Error indicating that the interface does not support the command.
     */
    public static final int RESULT_COMMAND_NOT_SUPPORTED = -3;
    /**
     * Error indicating that the route does not support the interface.
     */
    public static final int RESULT_INTERFACE_NOT_SUPPORTED = -2;
    /**
     * Generic error. Extra information about the error may be included in the
     * result bundle.
     */
    public static final int RESULT_ERROR = -1;
    /**
     * The command was successful. Extra information may be included in the
     * result bundle.
     */
    public static final int RESULT_SUCCESS = 1;

    private final Route mRoute;
    private final String mIface;
    private final MediaSession mSession;

    private final Object mLock = new Object();
    private final ArrayList<EventHandler> mListeners = new ArrayList<EventHandler>();

    /**
     * @hide
     */
    RouteInterface(Route route, String iface, MediaSession session) {
        mRoute = route;
        mIface = iface;
        mSession = session;
        mSession.addInterfaceListener(iface, mEventListener);
    }

    /**
     * Send a command using this interface.
     *
     * @param command The command to send.
     * @param extras Any extras to include with the command.
     * @param cb The callback to receive the result on.
     * @return true if the command was sent, false otherwise.
     */
    public boolean sendCommand(String command, Bundle extras, ResultReceiver cb) {
        RouteCommand cmd = new RouteCommand(mRoute.getRouteInfo().getId(), mIface,
                command, extras);
        return mSession.sendRouteCommand(cmd, cb);
    }

    /**
     * Add a listener to this interface. Events will be sent on the caller's
     * thread.
     *
     * @param listener The listener to receive events on.
     */
    public void addListener(EventListener listener) {
        addListener(listener, null);
    }

    /**
     * Add a listener for this interface. If a handler is specified events will
     * be performed on the handler's thread, otherwise the caller's thread will
     * be used.
     *
     * @param listener The listener to receive events on
     * @param handler The handler whose thread to post calls on
     */
    public void addListener(EventListener listener, Handler handler) {
        if (listener == null) {
            throw new IllegalArgumentException("listener may not be null");
        }
        if (handler == null) {
            handler = new Handler();
        }
        synchronized (mLock) {
            if (findIndexOfListenerLocked(listener) != -1) {
                Log.d(TAG, "Listener is already added, ignoring");
                return;
            }
            mListeners.add(new EventHandler(handler.getLooper(), listener));
        }
    }

    /**
     * Remove a listener from this interface.
     *
     * @param listener The listener to stop receiving events on.
     */
    public void removeListener(EventListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener may not be null");
        }
        synchronized (mLock) {
            int index = findIndexOfListenerLocked(listener);
            if (index != -1) {
                mListeners.remove(index);
            }
        }
    }

    private int findIndexOfListenerLocked(EventListener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("Callback cannot be null");
        }
        for (int i = mListeners.size() - 1; i >= 0; i--) {
            EventHandler handler = mListeners.get(i);
            if (listener == handler.mListener) {
                return i;
            }
        }
        return -1;
    }

    private EventListener mEventListener = new EventListener() {
            @Override
        public void onEvent(String event, Bundle args) {
            synchronized (mLock) {
                for (int i = mListeners.size() - 1; i >= 0; i--) {
                    mListeners.get(i).postEvent(event, args);
                }
            }
        }

    };

    /**
     * An EventListener can be registered by an app with TODO to handle events
     * sent by the session on a specific interface.
     */
    public static abstract class EventListener {
        /**
         * This is called when an event is received from the interface. Events
         * are sent by the session owner and will be delivered to all
         * controllers that are listening to the interface.
         *
         * @param event The event that occurred.
         * @param args Any extras that were included with the event. May be
         *            null.
         */
        public abstract void onEvent(String event, Bundle args);
    }

    private static final class EventHandler extends Handler {

        private final EventListener mListener;

        public EventHandler(Looper looper, EventListener cb) {
            super(looper, null, true);
            mListener = cb;
        }

        @Override
        public void handleMessage(Message msg) {
            mListener.onEvent((String) msg.obj, msg.getData());
        }

        public void postEvent(String event, Bundle args) {
            Message msg = obtainMessage(0, event);
            msg.setData(args);
            msg.sendToTarget();
        }
    }
}
