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
import android.media.session.ISessionController;
import android.media.session.ISession;
import android.media.session.ISessionCallback;
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
import java.util.List;

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
 * {@link SessionManager#createSession(String)}. Once a session is created apps
 * that have the MEDIA_CONTENT_CONTROL permission can interact with the session
 * through
 * {@link SessionManager#getActiveSessions(android.content.ComponentName)}. The
 * owner of the session may also use {@link #getSessionToken()} to allow apps
 * without this permission to create a {@link SessionController} to interact
 * with this session.
 * <p>
 * To receive commands, media keys, and other events a Callback must be set with
 * {@link #addCallback(Callback)}.
 * <p>
 * When an app is finished performing playback it must call {@link #release()}
 * to clean up the session and notify any controllers.
 * <p>
 * MediaSession objects are thread safe
 */
public final class Session {
    private static final String TAG = "Session";

    /**
     * Set this flag on the session to indicate that it can handle media button
     * events.
     */
    public static final int FLAG_HANDLES_MEDIA_BUTTONS = 1 << 0;

    /**
     * Set this flag on the session to indicate that it handles commands through
     * the {@link TransportPerformer}. The performer can be retrieved by calling
     * {@link #getTransportPerformer()}.
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
     * Indicates the session was disconnected because the user that the session
     * belonged to is stopping.
     */
    public static final int DISCONNECT_REASON_USER_STOPPING = 1;

    /**
     * Indicates the session was disconnected because the provider disconnected
     * the route.
     */
    public static final int DISCONNECT_REASON_PROVIDER_DISCONNECTED = 2;

    /**
     * Indicates the session was disconnected because the route has changed.
     */
    public static final int DISCONNECT_REASON_ROUTE_CHANGED = 3;

    /**
     * Indicates the session was disconnected because the session owner
     * requested it disconnect.
     */
    public static final int DISCONNECT_REASON_SESSION_DISCONNECTED = 4;

    /**
     * Indicates the session was disconnected because it was destroyed.
     */
    public static final int DISCONNECT_REASON_SESSION_DESTROYED = 5;

    /**
     * Status code indicating the call was handled.
     *
     * @hide
     */
    public static final int RESULT_SUCCESS = 0;

    private static final int MSG_MEDIA_BUTTON = 1;
    private static final int MSG_COMMAND = 2;
    private static final int MSG_ROUTE_CHANGE = 3;
    private static final int MSG_ROUTE_CONNECTED = 4;
    private static final int MSG_ROUTE_DISCONNECTED = 5;

    private static final String KEY_COMMAND = "command";
    private static final String KEY_EXTRAS = "extras";
    private static final String KEY_CALLBACK = "callback";

    private final Object mLock = new Object();

    private final SessionToken mSessionToken;
    private final ISession mBinder;
    private final CallbackStub mCbStub;

    private final ArrayList<MessageHandler> mCallbacks = new ArrayList<MessageHandler>();
    // TODO route interfaces
    private final ArrayMap<String, RouteInterface.EventListener> mInterfaceListeners
            = new ArrayMap<String, RouteInterface.EventListener>();

    private TransportPerformer mPerformer;
    private Route mRoute;

    private boolean mActive = false;;

    /**
     * @hide
     */
    public Session(ISession binder, CallbackStub cbStub) {
        mBinder = binder;
        mCbStub = cbStub;
        ISessionController controllerBinder = null;
        try {
            controllerBinder = mBinder.getController();
        } catch (RemoteException e) {
            throw new RuntimeException("Dead object in MediaSessionController constructor: ", e);
        }
        mSessionToken = new SessionToken(controllerBinder);
        mPerformer = new TransportPerformer(mBinder);
    }

    /**
     * Set the callback to receive updates on.
     *
     * @param callback The callback object
     */
    public void addCallback(Callback callback) {
        addCallback(callback, null);
    }

    /**
     * Add a callback to receive updates for the MediaSession. This includes
     * events like route updates, media buttons, and focus changes.
     *
     * @param callback The callback to receive updates on.
     * @param handler The handler that events should be posted on.
     */
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

    /**
     * Remove a callback. It will no longer receive updates.
     *
     * @param callback The callback to remove.
     */
    public void removeCallback(Callback callback) {
        synchronized (mLock) {
            removeCallbackLocked(callback);
        }
    }

    /**
     * Retrieves the {@link TransportPerformer} for this session. To receive
     * commands through the performer you must also set the
     * {@link #FLAG_HANDLES_TRANSPORT_CONTROLS} flag using
     * {@link #setFlags(int)}.
     *
     * @return The performer associated with this session.
     */
    public TransportPerformer getTransportPerformer() {
        return mPerformer;
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
     * {@link SessionController} for interacting with this session. The owner of
     * the session is responsible for deciding how to distribute these tokens.
     *
     * @return A token that can be used to create a MediaController for this
     *         session
     */
    public SessionToken getSessionToken() {
        return mSessionToken;
    }

    /**
     * Connect to the current route using the specified request.
     * <p>
     * Connection updates will be sent to the callback's
     * {@link Callback#onRouteConnected(Route)} and
     * {@link Callback#onRouteDisconnected(Route, int)} methods. If the
     * connection fails {@link Callback#onRouteDisconnected(Route, int)}
     * will be called.
     * <p>
     * If you already have a connection to this route it will be disconnected
     * before the new connection is established. TODO add an easy way to compare
     * MediaRouteOptions.
     *
     * @param route The route the app is trying to connect to.
     * @param request The connection request to use.
     */
    public void connect(RouteInfo route, RouteOptions request) {
        if (route == null) {
            throw new IllegalArgumentException("Must specify the route");
        }
        if (request == null) {
            throw new IllegalArgumentException("Must specify the connection request");
        }
        try {
            mBinder.connectToRoute(route, request);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error starting connection to route", e);
        }
    }

    /**
     * Disconnect from the current route. After calling you will be switched
     * back to the default route.
     */
    public void disconnect() {
        if (mRoute != null) {
            try {
                mBinder.disconnectFromRoute(mRoute.getRouteInfo());
            } catch (RemoteException e) {
                Log.wtf(TAG, "Error disconnecting from route");
            }
        }
    }

    /**
     * Set the list of route options your app is interested in connecting to. It
     * will be used for picking valid routes.
     *
     * @param options The set of route options your app may use to connect.
     */
    public void setRouteOptions(List<RouteOptions> options) {
        try {
            mBinder.setRouteOptions(options);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error setting route options.", e);
        }
    }

    /**
     * @hide
     * TODO allow multiple listeners for the same interface, allow removal
     */
    public void addInterfaceListener(String iface,
            RouteInterface.EventListener listener) {
        mInterfaceListeners.put(iface, listener);
    }

    /**
     * @hide
     */
    public boolean sendRouteCommand(RouteCommand command, ResultReceiver cb) {
        try {
            mBinder.sendRouteCommand(command, cb);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Error sending command to route.", e);
            return false;
        }
        return true;
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

    private void postRequestRouteChange(RouteInfo route) {
        synchronized (mLock) {
            for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                mCallbacks.get(i).post(MSG_ROUTE_CHANGE, route);
            }
        }
    }

    private void postRouteConnected(RouteInfo route, RouteOptions options) {
        synchronized (mLock) {
            mRoute = new Route(route, options, this);
            for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                mCallbacks.get(i).post(MSG_ROUTE_CONNECTED, mRoute);
            }
        }
    }

    private void postRouteDisconnected(RouteInfo route, int reason) {
        synchronized (mLock) {
            if (mRoute != null && TextUtils.equals(mRoute.getRouteInfo().getId(), route.getId())) {
                for (int i = mCallbacks.size() - 1; i >= 0; i--) {
                    mCallbacks.get(i).post(MSG_ROUTE_DISCONNECTED, mRoute, reason);
                }
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
         * @param route
         */
        public void onRequestRouteChange(RouteInfo route) {
        }

        /**
         * Called when a route has successfully connected. Calls to the route
         * are now valid.
         *
         * @param route The route that was connected
         */
        public void onRouteConnected(Route route) {
        }

        /**
         * Called when a route was disconnected. Further calls to the route will
         * fail. If available a reason for being disconnected will be provided.
         * <p>
         * Valid reasons are:
         * <ul>
         * <li>{@link #DISCONNECT_REASON_USER_STOPPING}</li>
         * <li>{@link #DISCONNECT_REASON_PROVIDER_DISCONNECTED}</li>
         * <li>{@link #DISCONNECT_REASON_ROUTE_CHANGED}</li>
         * <li>{@link #DISCONNECT_REASON_SESSION_DISCONNECTED}</li>
         * <li>{@link #DISCONNECT_REASON_SESSION_DESTROYED}</li>
         * </ul>
         *
         * @param route The route that disconnected
         * @param reason The reason for the disconnect
         */
        public void onRouteDisconnected(Route route, int reason) {
        }
    }

    /**
     * @hide
     */
    public static class CallbackStub extends ISessionCallback.Stub {
        private WeakReference<Session> mMediaSession;

        public void setMediaSession(Session session) {
            mMediaSession = new WeakReference<Session>(session);
        }

        @Override
        public void onCommand(String command, Bundle extras, ResultReceiver cb)
                throws RemoteException {
            Session session = mMediaSession.get();
            if (session != null) {
                session.postCommand(command, extras, cb);
            }
        }

        @Override
        public void onMediaButton(Intent mediaButtonIntent, ResultReceiver cb)
                throws RemoteException {
            Session session = mMediaSession.get();
            if (session != null) {
                session.postMediaButton(mediaButtonIntent);
            }
            if (cb != null) {
                cb.send(RESULT_SUCCESS, null);
            }
        }

        @Override
        public void onRequestRouteChange(RouteInfo route) throws RemoteException {
            Session session = mMediaSession.get();
            if (session != null) {
                session.postRequestRouteChange(route);
            }
        }

        @Override
        public void onRouteConnected(RouteInfo route, RouteOptions options) {
            Session session = mMediaSession.get();
            if (session != null) {
                session.postRouteConnected(route, options);
            }
        }

        @Override
        public void onRouteDisconnected(RouteInfo route, int reason) {
            Session session = mMediaSession.get();
            if (session != null) {
                session.postRouteDisconnected(route, reason);
            }
        }

        @Override
        public void onPlay() throws RemoteException {
            Session session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onPlay();
                }
            }
        }

        @Override
        public void onPause() throws RemoteException {
            Session session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onPause();
                }
            }
        }

        @Override
        public void onStop() throws RemoteException {
            Session session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onStop();
                }
            }
        }

        @Override
        public void onNext() throws RemoteException {
            Session session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onNext();
                }
            }
        }

        @Override
        public void onPrevious() throws RemoteException {
            Session session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onPrevious();
                }
            }
        }

        @Override
        public void onFastForward() throws RemoteException {
            Session session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onFastForward();
                }
            }
        }

        @Override
        public void onRewind() throws RemoteException {
            Session session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onRewind();
                }
            }
        }

        @Override
        public void onSeekTo(long pos) throws RemoteException {
            Session session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onSeekTo(pos);
                }
            }
        }

        @Override
        public void onRate(Rating rating) throws RemoteException {
            Session session = mMediaSession.get();
            if (session != null) {
                TransportPerformer tp = session.getTransportPerformer();
                if (tp != null) {
                    tp.onRate(rating);
                }
            }
        }

        @Override
        public void onRouteEvent(RouteEvent event) throws RemoteException {
            Session session = mMediaSession.get();
            if (session != null) {
                RouteInterface.EventListener iface
                        = session.mInterfaceListeners.get(event.getIface());
                Log.d(TAG, "Received route event on iface " + event.getIface() + ". Listener is "
                        + iface);
                if (iface != null) {
                    iface.onEvent(event.getEvent(), event.getExtras());
                }
            }
        }

        @Override
        public void onRouteStateChange(int state) throws RemoteException {
            // TODO

        }

    }

    private class MessageHandler extends Handler {
        private Session.Callback mCallback;

        public MessageHandler(Looper looper, Session.Callback callback) {
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
                        mCallback.onRequestRouteChange((RouteInfo) msg.obj);
                        break;
                    case MSG_ROUTE_CONNECTED:
                        mCallback.onRouteConnected((Route) msg.obj);
                        break;
                    case MSG_ROUTE_DISCONNECTED:
                        mCallback.onRouteDisconnected((Route) msg.obj, msg.arg1);
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
}
