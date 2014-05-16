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
package com.android.server.media;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.routeprovider.IRouteConnection;
import android.media.routeprovider.IRouteProvider;
import android.media.routeprovider.IRouteProviderCallback;
import android.media.routeprovider.RouteProviderService;
import android.media.routeprovider.RouteRequest;
import android.media.session.RouteEvent;
import android.media.session.RouteInfo;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * System representation and interface to a MediaRouteProvider. This class is
 * not thread safe so all calls should be made on the main thread.
 */
public class MediaRouteProviderProxy {
    private static final String TAG = "MRPProxy";
    private static final boolean DEBUG = true;

    private static final int MAX_RETRIES = 3;

    private final Object mLock = new Object();
    private final Context mContext;
    private final String mId;
    private final ComponentName mComponentName;
    private final int mUserId;
    // Interfaces declared in the manifest
    private final ArrayList<String> mInterfaces = new ArrayList<String>();
    private final ArrayList<RouteConnectionRecord> mConnections
            = new ArrayList<RouteConnectionRecord>();
    // The sessions that have a route from this provider selected
    private final ArrayList<MediaSessionRecord> mSessions = new ArrayList<MediaSessionRecord>();
    private final Handler mHandler = new Handler();

    private Intent mBindIntent;
    private IRouteProvider mBinder;
    private boolean mRunning;
    private boolean mPaused;
    private boolean mInterested;
    private boolean mBound;
    private int mRetryCount;

    private RoutesListener mRouteListener;

    public MediaRouteProviderProxy(Context context, String id, ComponentName component, int uid,
            ArrayList<String> interfaces) {
        mContext = context;
        mId = id;
        mComponentName = component;
        mUserId = uid;
        if (interfaces != null) {
            mInterfaces.addAll(interfaces);
        }
        mBindIntent = new Intent(RouteProviderService.SERVICE_INTERFACE);
        mBindIntent.setComponent(mComponentName);
    }

    public void destroy() {
        stop();
        mSessions.clear();
        updateBinding();
    }

    /**
     * Send any cleanup messages and unbind from the media route provider
     */
    public void stop() {
        if (mRunning) {
            mRunning = false;
            mRetryCount = 0;
            updateBinding();
        }
    }

    /**
     * Bind to the media route provider and perform any setup needed
     */
    public void start() {
        if (!mRunning) {
            mRunning = true;
            updateBinding();
        }
    }

    /**
     * Set whether or not this provider is currently interesting to the system.
     * In the future this may take a list of interfaces instead.
     *
     * @param interested True if we want to connect to this provider
     */
    public void setInterested(boolean interested) {
        mInterested = interested;
        updateBinding();
    }

    /**
     * Set a listener to get route updates on.
     *
     * @param listener The listener to receive updates on.
     */
    public void setRoutesListener(RoutesListener listener) {
        mRouteListener = listener;
    }

    /**
     * Send a request to the Provider to get all the routes that the session can
     * use.
     *
     * @param record The session to get routes for.
     * @param requestId An id to identify this request.
     */
    public void getRoutes(MediaSessionRecord record, final int requestId) {
        // TODO change routes to have a system global id and maintain a mapping
        // to the original route
        if (mBinder == null) {
            Log.wtf(TAG, "Attempted to call getRoutes without a binder connection");
            return;
        }
        List<RouteRequest> requests = record.getRouteRequests();
        final String sessionId = record.getSessionInfo().getId();
        try {
            mBinder.getAvailableRoutes(requests, new ResultReceiver(mHandler) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    if (resultCode != RouteProviderService.RESULT_SUCCESS) {
                        // ignore failures, just means no routes were generated
                        return;
                    }
                    ArrayList<RouteInfo> routes
                            = resultData.getParcelableArrayList(RouteProviderService.KEY_ROUTES);
                    ArrayList<RouteInfo> sysRoutes = new ArrayList<RouteInfo>();
                    for (int i = 0; i < routes.size(); i++) {
                        RouteInfo route = routes.get(i);
                        RouteInfo.Builder bob = new RouteInfo.Builder(route);
                        bob.setProviderId(mId);
                        sysRoutes.add(bob.build());
                    }
                    if (mRouteListener != null) {
                        mRouteListener.onRoutesUpdated(sessionId, sysRoutes, requestId);
                    }
                }
            });
        } catch (RemoteException e) {
            Log.d(TAG, "Error in getRoutes", e);
        }
    }

    /**
     * Try connecting again if we've been disconnected.
     */
    public void rebindIfDisconnected() {
        if (mBinder == null && shouldBind()) {
            unbind();
            bind();
        }
    }

    /**
     * Send a request to connect to a route.
     *
     * @param session The session that is trying to connect.
     * @param route The route it is connecting to.
     * @param request The request with the connection parameters.
     * @return true if the request was sent, false otherwise.
     */
    public boolean connectToRoute(MediaSessionRecord session, final RouteInfo route,
            final RouteRequest request) {
        final String sessionId = session.getSessionInfo().getId();
        try {
            mBinder.connect(route, request, new ResultReceiver(mHandler) {
                @Override
                protected void onReceiveResult(int resultCode, Bundle resultData) {
                    if (resultCode != RouteProviderService.RESULT_SUCCESS) {
                        // TODO handle connection failure
                        return;
                    }
                    IBinder binder = resultData.getBinder(RouteProviderService.KEY_CONNECTION);
                    IRouteConnection connection = null;
                    if (binder != null) {
                        connection = IRouteConnection.Stub.asInterface(binder);
                    }

                    if (connection != null) {
                        RouteConnectionRecord record = new RouteConnectionRecord(
                                connection, mComponentName.getPackageName(), mUserId);
                        mConnections.add(record);
                        if (mRouteListener != null) {
                            mRouteListener.onRouteConnected(sessionId, route, request, record);
                        }
                    }
                }
            });
        } catch (RemoteException e) {
            Log.e(TAG, "Error connecting to route.", e);
            return false;
        }
        return true;
    }

    /**
     * Check if this is the provider you're looking for.
     */
    public boolean hasComponentName(String packageName, String className) {
        return mComponentName.getPackageName().equals(packageName)
                && mComponentName.getClassName().equals(className);
    }

    /**
     * Get the unique id for this provider.
     *
     * @return The provider's id.
     */
    public String getId() {
        return mId;
    }

    public void addSession(MediaSessionRecord session) {
        mSessions.add(session);
    }

    public void removeSession(MediaSessionRecord session) {
        mSessions.remove(session);
        updateBinding();
    }

    public int getSessionCount() {
        return mSessions.size();
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + mId + " " + this);
        String indent = prefix + "  ";

        pw.println(indent + "component=" + mComponentName.toString());
        pw.println(indent + "user id=" + mUserId);
        pw.println(indent + "interfaces=" + mInterfaces.toString());
        pw.println(indent + "connections=" + mConnections.toString());
        pw.println(indent + "running=" + mRunning);
        pw.println(indent + "interested=" + mInterested);
        pw.println(indent + "bound=" + mBound);
    }

    private void updateBinding() {
        if (shouldBind()) {
            bind();
        } else {
            unbind();
        }
    }

    // We want to bind as long as we're interested in this provider or there are
    // sessions connected to it.
    private boolean shouldBind() {
        return (mRunning && mInterested) || (!mSessions.isEmpty());
    }

    private void bind() {
        if (!mBound) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Binding");
            }

            try {
                mBound = mContext.bindServiceAsUser(mBindIntent, mServiceConn,
                        Context.BIND_AUTO_CREATE, new UserHandle(mUserId));
                if (!mBound && DEBUG) {
                    Slog.d(TAG, this + ": Bind failed");
                }
            } catch (SecurityException ex) {
                if (DEBUG) {
                    Slog.d(TAG, this + ": Bind failed", ex);
                }
            }
        }
    }

    private void unbind() {
        if (mBound) {
            if (DEBUG) {
                Slog.d(TAG, this + ": Unbinding");
            }

            mBound = false;
            mContext.unbindService(mServiceConn);
        }
    }

    private RouteConnectionRecord getConnectionLocked(IBinder binder) {
        for (int i = mConnections.size() - 1; i >= 0; i--) {
            RouteConnectionRecord record = mConnections.get(i);
            if (record.isConnection(binder)) {
                return record;
            }
        }
        return null;
    }

    private ServiceConnection mServiceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mBinder = IRouteProvider.Stub.asInterface(service);
            if (DEBUG) {
                Slog.d(TAG, "Connected to route provider");
            }
            try {
                mBinder.registerCallback(mCbStub);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error registering callback on route provider. Retry count: "
                        + mRetryCount, e);
                if (mRetryCount < MAX_RETRIES) {
                    mRetryCount++;
                    rebindIfDisconnected();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mBinder = null;
            if (DEBUG) {
                Slog.d(TAG, "Disconnected from route provider");
            }
        }

    };

    private IRouteProviderCallback.Stub mCbStub = new IRouteProviderCallback.Stub() {
        @Override
        public void onConnectionStateChanged(IRouteConnection connection, int state)
                throws RemoteException {
            // TODO
        }

        @Override
        public void onRouteEvent(RouteEvent event) throws RemoteException {
            synchronized (mLock) {
                RouteConnectionRecord record = getConnectionLocked(event.getConnection());
                Log.d(TAG, "Received route event for record " + record);
                if (record != null) {
                    record.sendEvent(event);
                }
            }
        }

        @Override
        public void onConnectionTerminated(IRouteConnection connection) throws RemoteException {
            synchronized (mLock) {
                RouteConnectionRecord record = getConnectionLocked(connection.asBinder());
                if (record != null) {
                    record.disconnect();
                    mConnections.remove(record);
                }
            }
        }

        @Override
        public void onRoutesChanged() throws RemoteException {
            // TODO
        }
    };

    /**
     * Listener for receiving responses to route requests on the provider.
     */
    public interface RoutesListener {
        /**
         * Called when routes have been returned from a request to getRoutes.
         *
         * @param record The session that the routes were requested for.
         * @param routes The matching routes returned by the provider.
         * @param reqId The request id this is responding to.
         */
        public void onRoutesUpdated(String sessionId, ArrayList<RouteInfo> routes,
                int reqId);

        /**
         * Called when a route has successfully connected.
         *
         * @param session The session that was connected.
         * @param route The route it connected to.
         * @param options The options that were used for the connection.
         * @param connection The connection instance that was created.
         */
        public void onRouteConnected(String sessionId, RouteInfo route,
                RouteRequest options, RouteConnectionRecord connection);
    }
}
