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

import android.content.Context;
import android.media.routeprovider.RouteRequest;
import android.media.session.ISession;
import android.media.session.ISessionCallback;
import android.media.session.ISessionManager;
import android.media.session.RouteInfo;
import android.media.session.RouteOptions;
import android.os.Binder;
import android.os.Handler;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.Log;

import com.android.server.SystemService;

import java.util.ArrayList;

/**
 * System implementation of MediaSessionManager
 */
public class MediaSessionService extends SystemService {
    private static final String TAG = "MediaSessionService";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final SessionManagerImpl mSessionManagerImpl;
    private final MediaRouteProviderWatcher mRouteProviderWatcher;

    private final ArrayList<MediaSessionRecord> mSessions
            = new ArrayList<MediaSessionRecord>();
    private final ArrayList<MediaRouteProviderProxy> mProviders
            = new ArrayList<MediaRouteProviderProxy>();
    private final Object mLock = new Object();
    // TODO do we want a separate thread for handling mediasession messages?
    private final Handler mHandler = new Handler();

    // Used to keep track of the current request to show routes for a specific
    // session so we drop late callbacks properly.
    private int mShowRoutesRequestId = 0;

    // TODO refactor to have per user state. See MediaRouterService for an
    // example

    public MediaSessionService(Context context) {
        super(context);
        mSessionManagerImpl = new SessionManagerImpl();
        mRouteProviderWatcher = new MediaRouteProviderWatcher(context, mProviderWatcherCallback,
                mHandler, context.getUserId());
    }

    @Override
    public void onStart() {
        publishBinderService(Context.MEDIA_SESSION_SERVICE, mSessionManagerImpl);
        mRouteProviderWatcher.start();
    }

    /**
     * Should trigger showing the Media route picker dialog. Right now it just
     * kicks off a query to all the providers to get routes.
     *
     * @param record The session to show the picker for.
     */
    public void showRoutePickerForSession(MediaSessionRecord record) {
        // TODO for now just toggle the route to test (we will only have one
        // match for now)
        if (record.getRoute() != null) {
            // For now send null to mean the local route
            record.selectRoute(null);
            return;
        }
        mShowRoutesRequestId++;
        ArrayList<MediaRouteProviderProxy> providers = mRouteProviderWatcher.getProviders();
        for (int i = providers.size() - 1; i >= 0; i--) {
            MediaRouteProviderProxy provider = providers.get(i);
            provider.getRoutes(record, mShowRoutesRequestId);
        }
    }

    /**
     * Connect a session to the given route.
     *
     * @param session The session to connect.
     * @param route The route to connect to.
     * @param options The options to use for the connection.
     */
    public void connectToRoute(MediaSessionRecord session, RouteInfo route,
            RouteOptions options) {
        synchronized (mLock) {
            MediaRouteProviderProxy proxy = getProviderLocked(route.getProvider());
            if (proxy == null) {
                Log.w(TAG, "Provider for route " + route.getName() + " does not exist.");
                return;
            }
            RouteRequest request = new RouteRequest(session.getSessionInfo(), options, true);
            // TODO make connect an async call to a ThreadPoolExecutor
            proxy.connectToRoute(session, route, request);
        }
    }

    void sessionDied(MediaSessionRecord session) {
        synchronized (mSessions) {
            destroySessionLocked(session);
        }
    }

    void destroySession(MediaSessionRecord session) {
        synchronized (mSessions) {
            destroySessionLocked(session);
        }
    }

    private void destroySessionLocked(MediaSessionRecord session) {
        mSessions.remove(session);
    }

    private void enforcePackageName(String packageName, int uid) {
        if (TextUtils.isEmpty(packageName)) {
            throw new IllegalArgumentException("packageName may not be empty");
        }
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        final int packageCount = packages.length;
        for (int i = 0; i < packageCount; i++) {
            if (packageName.equals(packages[i])) {
                return;
            }
        }
        throw new IllegalArgumentException("packageName is not owned by the calling process");
    }

    private MediaSessionRecord createSessionInternal(int pid, String packageName,
            ISessionCallback cb, String tag) {
        synchronized (mLock) {
            return createSessionLocked(pid, packageName, cb, tag);
        }
    }

    private MediaSessionRecord createSessionLocked(int pid, String packageName,
            ISessionCallback cb, String tag) {
        final MediaSessionRecord session = new MediaSessionRecord(pid, packageName, cb, tag, this,
                mHandler);
        try {
            cb.asBinder().linkToDeath(session, 0);
        } catch (RemoteException e) {
            throw new RuntimeException("Media Session owner died prematurely.", e);
        }
        synchronized (mSessions) {
            mSessions.add(session);
        }
        if (DEBUG) {
            Log.d(TAG, "Created session for package " + packageName + " with tag " + tag);
        }
        return session;
    }

    private MediaRouteProviderProxy getProviderLocked(String providerId) {
        for (int i = mProviders.size() - 1; i >= 0; i--) {
            MediaRouteProviderProxy provider = mProviders.get(i);
            if (TextUtils.equals(providerId, provider.getId())) {
                return provider;
            }
        }
        return null;
    }

    private int findIndexOfSessionForIdLocked(String sessionId) {
        for (int i = mSessions.size() - 1; i >= 0; i--) {
            MediaSessionRecord session = mSessions.get(i);
            if (TextUtils.equals(session.getSessionInfo().getId(), sessionId)) {
                return i;
            }
        }
        return -1;
    }

    private MediaRouteProviderWatcher.Callback mProviderWatcherCallback
            = new MediaRouteProviderWatcher.Callback() {
        @Override
        public void removeProvider(MediaRouteProviderProxy provider) {
            synchronized (mLock) {
                mProviders.remove(provider);
                provider.setRoutesListener(null);
                provider.setInterested(false);
            }
        }

        @Override
        public void addProvider(MediaRouteProviderProxy provider) {
            synchronized (mLock) {
                mProviders.add(provider);
                provider.setRoutesListener(mRoutesCallback);
                provider.setInterested(true);
            }
        }
    };

    private MediaRouteProviderProxy.RoutesListener mRoutesCallback
            = new MediaRouteProviderProxy.RoutesListener() {
        @Override
        public void onRoutesUpdated(String sessionId, ArrayList<RouteInfo> routes,
                int reqId) {
            // TODO for now select the first route to test, eventually add the
            // new routes to the dialog if it is still open
            synchronized (mLock) {
                int index = findIndexOfSessionForIdLocked(sessionId);
                if (index != -1 && routes != null && routes.size() > 0) {
                    MediaSessionRecord record = mSessions.get(index);
                    record.selectRoute(routes.get(0));
                }
            }
        }

        @Override
        public void onRouteConnected(String sessionId, RouteInfo route,
                RouteRequest options, RouteConnectionRecord connection) {
            synchronized (mLock) {
                int index = findIndexOfSessionForIdLocked(sessionId);
                if (index != -1) {
                    MediaSessionRecord session = mSessions.get(index);
                    session.setRouteConnected(route, options.getConnectionOptions(), connection);
                }
            }
        }
    };

    class SessionManagerImpl extends ISessionManager.Stub {
        // TODO add createSessionAsUser, pass user-id to
        // ActivityManagerNative.handleIncomingUser and stash result for use
        // when starting services on that session's behalf.
        @Override
        public ISession createSession(String packageName, ISessionCallback cb, String tag)
                throws RemoteException {
            final int pid = Binder.getCallingPid();
            final int uid = Binder.getCallingUid();
            final long token = Binder.clearCallingIdentity();
            try {
                enforcePackageName(packageName, uid);
                if (cb == null) {
                    throw new IllegalArgumentException("Controller callback cannot be null");
                }
                return createSessionInternal(pid, packageName, cb, tag).getSessionBinder();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }

}
