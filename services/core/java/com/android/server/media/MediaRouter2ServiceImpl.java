/*
 * Copyright 2019 The Android Open Source Project
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

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.IMediaRouter2Client;
import android.media.IMediaRouter2Manager;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.media.RouteSessionInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.function.pooled.PooledLambda;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TODO: Merge this to MediaRouterService once it's finished.
 */
class MediaRouter2ServiceImpl {
    private static final String TAG = "MR2ServiceImpl";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final long ROUTE_SELECTION_REQUEST_TIMEOUT_MS = 5000L;

    private final Context mContext;
    private final Object mLock = new Object();
    final AtomicInteger mNextClientId = new AtomicInteger(1);

    @GuardedBy("mLock")
    private final SparseArray<UserRecord> mUserRecords = new SparseArray<>();
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, Client2Record> mAllClientRecords = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, ManagerRecord> mAllManagerRecords = new ArrayMap<>();
    @GuardedBy("mLock")
    private int mCurrentUserId = -1;


    MediaRouter2ServiceImpl(Context context) {
        mContext = context;
    }

    @NonNull
    public List<MediaRoute2Info> getSystemRoutes() {
        final int uid = Binder.getCallingUid();
        final int userId = UserHandle.getUserId(uid);

        Collection<MediaRoute2Info> systemRoutes;
        synchronized (mLock) {
            UserRecord userRecord = mUserRecords.get(userId);
            if (userRecord == null) {
                userRecord = new UserRecord(userId);
                mUserRecords.put(userId, userRecord);
                initializeUserLocked(userRecord);
            }
            systemRoutes = userRecord.mHandler.mSystemProvider.getProviderInfo().getRoutes();
        }
        return new ArrayList<>(systemRoutes);
    }

    public void registerClient(@NonNull IMediaRouter2Client client,
            @NonNull String packageName) {
        Objects.requireNonNull(client, "client must not be null");

        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int userId = UserHandle.getUserId(uid);
        final boolean trusted = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.CONFIGURE_WIFI_DISPLAY)
                == PackageManager.PERMISSION_GRANTED;
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                registerClient2Locked(client, uid, pid, packageName, userId, trusted);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void unregisterClient(@NonNull IMediaRouter2Client client) {
        Objects.requireNonNull(client, "client must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                unregisterClient2Locked(client, false);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void registerManager(@NonNull IMediaRouter2Manager manager,
            @NonNull String packageName) {
        Objects.requireNonNull(manager, "manager must not be null");
        //TODO: should check permission
        final boolean trusted = true;

        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int userId = UserHandle.getUserId(uid);

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                registerManagerLocked(manager, uid, pid, packageName, userId, trusted);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void unregisterManager(@NonNull IMediaRouter2Manager manager) {
        Objects.requireNonNull(manager, "manager must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                unregisterManagerLocked(manager, false);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void requestCreateSession(IMediaRouter2Client client, MediaRoute2Info route,
            String controlCategory, int requestId) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(route, "route must not be null");
        if (TextUtils.isEmpty(controlCategory)) {
            throw new IllegalArgumentException("controlCategory must not be empty");
        }

        final long token = Binder.clearCallingIdentity();

        try {
            synchronized (mLock) {
                requestCreateSessionLocked(client, route, controlCategory, requestId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void selectRoute(IMediaRouter2Client client, String uniqueSessionId,
            MediaRoute2Info route) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                selectRouteLocked(client, uniqueSessionId, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }


    public void deselectRoute(IMediaRouter2Client client, String uniqueSessionId,
            MediaRoute2Info route) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                deselectRouteLocked(client, uniqueSessionId, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void transferToRoute(IMediaRouter2Client client, String uniqueSessionId,
            MediaRoute2Info route) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                transferToRouteLocked(client, uniqueSessionId, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void sendControlRequest(@NonNull IMediaRouter2Client client,
            @NonNull MediaRoute2Info route, @NonNull Intent request) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(route, "route must not be null");
        Objects.requireNonNull(request, "request must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                sendControlRequestLocked(client, route, request);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setControlCategories(@NonNull IMediaRouter2Client client,
            @NonNull List<String> categories) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(categories, "categories must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                Client2Record clientRecord = mAllClientRecords.get(client.asBinder());
                setControlCategoriesLocked(clientRecord, categories);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void requestSetVolume2(IMediaRouter2Client client, MediaRoute2Info route, int volume) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestSetVolumeLocked(client, route, volume);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void requestUpdateVolume2(IMediaRouter2Client client, MediaRoute2Info route, int delta) {
        Objects.requireNonNull(client, "client must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestUpdateVolumeLocked(client, route, delta);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void requestCreateClientSession(IMediaRouter2Manager manager, String packageName,
            MediaRoute2Info route, int requestId) {
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestClientCreateSessionLocked(manager, packageName, route, requestId);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void requestSetVolume2Manager(IMediaRouter2Manager manager,
            MediaRoute2Info route, int volume) {
        Objects.requireNonNull(manager, "manager must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestSetVolumeLocked(manager, route, volume);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void requestUpdateVolume2Manager(IMediaRouter2Manager manager,
            MediaRoute2Info route, int delta) {
        Objects.requireNonNull(manager, "manager must not be null");
        Objects.requireNonNull(route, "route must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                requestUpdateVolumeLocked(manager, route, delta);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @NonNull
    public List<RouteSessionInfo> getActiveSessions(IMediaRouter2Manager manager) {
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                return getActiveSessionsLocked(manager);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    //TODO: Review this is handling multi-user properly.
    void switchUser() {
        synchronized (mLock) {
            int userId = ActivityManager.getCurrentUser();
            if (mCurrentUserId != userId) {
                final int oldUserId = mCurrentUserId;
                mCurrentUserId = userId; // do this first

                UserRecord oldUser = mUserRecords.get(oldUserId);
                if (oldUser != null) {
                    oldUser.mHandler.sendMessage(
                            obtainMessage(UserHandler::stop, oldUser.mHandler));
                    disposeUserIfNeededLocked(oldUser); // since no longer current user
                }

                UserRecord newUser = mUserRecords.get(userId);
                if (newUser != null) {
                    newUser.mHandler.sendMessage(
                            obtainMessage(UserHandler::start, newUser.mHandler));
                }
            }
        }
    }

    void clientDied(Client2Record clientRecord) {
        synchronized (mLock) {
            unregisterClient2Locked(clientRecord.mClient, true);
        }
    }

    void managerDied(ManagerRecord managerRecord) {
        synchronized (mLock) {
            unregisterManagerLocked(managerRecord.mManager, true);
        }
    }

    private void registerClient2Locked(IMediaRouter2Client client,
            int uid, int pid, String packageName, int userId, boolean trusted) {
        final IBinder binder = client.asBinder();
        if (mAllClientRecords.get(binder) == null) {
            UserRecord userRecord = mUserRecords.get(userId);
            if (userRecord == null) {
                userRecord = new UserRecord(userId);
                mUserRecords.put(userId, userRecord);
                initializeUserLocked(userRecord);
            }
            Client2Record clientRecord = new Client2Record(userRecord, client, uid, pid,
                    packageName, trusted);
            try {
                binder.linkToDeath(clientRecord, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException("Media router client died prematurely.", ex);
            }

            userRecord.mClientRecords.add(clientRecord);
            mAllClientRecords.put(binder, clientRecord);

            userRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::notifyRoutesToClient, userRecord.mHandler, client));
        }
    }

    private void unregisterClient2Locked(IMediaRouter2Client client, boolean died) {
        Client2Record clientRecord = mAllClientRecords.remove(client.asBinder());
        if (clientRecord != null) {
            UserRecord userRecord = clientRecord.mUserRecord;
            userRecord.mClientRecords.remove(clientRecord);
            //TODO: update discovery request
            clientRecord.dispose();
            disposeUserIfNeededLocked(userRecord); // since client removed from user
        }
    }

    private void requestCreateSessionLocked(@NonNull IMediaRouter2Client client,
            @NonNull MediaRoute2Info route, @NonNull String controlCategory, long requestId) {
        final IBinder binder = client.asBinder();
        final Client2Record clientRecord = mAllClientRecords.get(binder);

        // client id is not assigned yet
        if (toClientId(requestId) == 0) {
            requestId = toUniqueRequestId(clientRecord.mClientId, toClientRequestId(requestId));
        }

        if (clientRecord != null) {
            clientRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::requestCreateSessionOnHandler,
                            clientRecord.mUserRecord.mHandler,
                            clientRecord, route, controlCategory, requestId));
        }
    }

    private void selectRouteLocked(@NonNull IMediaRouter2Client client, String uniqueSessionId,
            @NonNull MediaRoute2Info route) {
        final IBinder binder = client.asBinder();
        final Client2Record clientRecord = mAllClientRecords.get(binder);

        if (clientRecord != null) {
            clientRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::selectRouteOnHandler,
                            clientRecord.mUserRecord.mHandler,
                            clientRecord, uniqueSessionId, route));
        }
    }

    private void deselectRouteLocked(@NonNull IMediaRouter2Client client, String uniqueSessionId,
            @NonNull MediaRoute2Info route) {
        final IBinder binder = client.asBinder();
        final Client2Record clientRecord = mAllClientRecords.get(binder);

        if (clientRecord != null) {
            clientRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::deselectRouteOnHandler,
                            clientRecord.mUserRecord.mHandler,
                            clientRecord, uniqueSessionId, route));
        }
    }

    private void transferToRouteLocked(@NonNull IMediaRouter2Client client, String uniqueSessionId,
            @NonNull MediaRoute2Info route) {
        final IBinder binder = client.asBinder();
        final Client2Record clientRecord = mAllClientRecords.get(binder);

        if (clientRecord != null) {
            clientRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::transferToRouteOnHandler,
                            clientRecord.mUserRecord.mHandler,
                            clientRecord, uniqueSessionId, route));
        }
    }

    private void setControlCategoriesLocked(Client2Record clientRecord, List<String> categories) {
        if (clientRecord != null) {
            if (clientRecord.mControlCategories.equals(categories)) {
                return;
            }

            clientRecord.mControlCategories = categories;
            clientRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::updateClientUsage,
                            clientRecord.mUserRecord.mHandler, clientRecord));
        }
    }

    private void sendControlRequestLocked(IMediaRouter2Client client, MediaRoute2Info route,
            Intent request) {
        final IBinder binder = client.asBinder();
        Client2Record clientRecord = mAllClientRecords.get(binder);

        if (clientRecord != null) {
            clientRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::sendControlRequest,
                            clientRecord.mUserRecord.mHandler, route, request));
        }
    }

    private void requestSetVolumeLocked(IMediaRouter2Client client, MediaRoute2Info route,
            int volume) {
        final IBinder binder = client.asBinder();
        Client2Record clientRecord = mAllClientRecords.get(binder);

        if (clientRecord != null) {
            clientRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::requestSetVolume,
                            clientRecord.mUserRecord.mHandler, route, volume));
        }
    }

    private void requestUpdateVolumeLocked(IMediaRouter2Client client, MediaRoute2Info route,
            int delta) {
        final IBinder binder = client.asBinder();
        Client2Record clientRecord = mAllClientRecords.get(binder);

        if (clientRecord != null) {
            clientRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::requestUpdateVolume,
                            clientRecord.mUserRecord.mHandler, route, delta));
        }
    }

    private void registerManagerLocked(IMediaRouter2Manager manager,
            int uid, int pid, String packageName, int userId, boolean trusted) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);
        if (managerRecord == null) {
            boolean newUser = false;
            UserRecord userRecord = mUserRecords.get(userId);
            if (userRecord == null) {
                userRecord = new UserRecord(userId);
                newUser = true;
            }
            managerRecord = new ManagerRecord(userRecord, manager, uid, pid, packageName, trusted);
            try {
                binder.linkToDeath(managerRecord, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException("Media router manager died prematurely.", ex);
            }

            if (newUser) {
                mUserRecords.put(userId, userRecord);
                initializeUserLocked(userRecord);
            }

            userRecord.mManagerRecords.add(managerRecord);
            mAllManagerRecords.put(binder, managerRecord);

            userRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::notifyRoutesToManager,
                            userRecord.mHandler, manager));

            for (Client2Record clientRecord : userRecord.mClientRecords) {
                // TODO: Do not use updateClientUsage since it updates all managers.
                // Instead, Notify only to the manager that is currently being registered.

                // TODO: UserRecord <-> ClientRecord, why do they reference each other?
                // How about removing mUserRecord from clientRecord?
                clientRecord.mUserRecord.mHandler.sendMessage(
                        obtainMessage(UserHandler::updateClientUsage,
                            clientRecord.mUserRecord.mHandler, clientRecord));
            }
        }
    }

    private void unregisterManagerLocked(IMediaRouter2Manager manager, boolean died) {
        ManagerRecord managerRecord = mAllManagerRecords.remove(manager.asBinder());
        if (managerRecord != null) {
            UserRecord userRecord = managerRecord.mUserRecord;
            userRecord.mManagerRecords.remove(managerRecord);
            managerRecord.dispose();
            disposeUserIfNeededLocked(userRecord); // since manager removed from user
        }
    }

    private void requestClientCreateSessionLocked(IMediaRouter2Manager manager,
            String packageName, MediaRoute2Info route, int requestId) {
        ManagerRecord managerRecord = mAllManagerRecords.get(manager.asBinder());
        if (managerRecord != null) {
            Client2Record clientRecord =
                    managerRecord.mUserRecord.findClientRecordLocked(packageName);
            if (clientRecord == null) {
                Slog.w(TAG, "Ignoring session creation for unknown client.");
            }
            long uniqueRequestId = toUniqueRequestId(managerRecord.mClientId, requestId);
            if (clientRecord != null && managerRecord.mTrusted) {
                //TODO: select category properly
                requestCreateSessionLocked(clientRecord.mClient, route,
                        route.getSupportedCategories().get(0), uniqueRequestId);
            }
        }
    }

    private void requestSetVolumeLocked(IMediaRouter2Manager manager, MediaRoute2Info route,
            int volume) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord != null) {
            managerRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::requestSetVolume,
                            managerRecord.mUserRecord.mHandler, route, volume));
        }
    }

    private void requestUpdateVolumeLocked(IMediaRouter2Manager manager, MediaRoute2Info route,
            int delta) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord != null) {
            managerRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::requestUpdateVolume,
                            managerRecord.mUserRecord.mHandler, route, delta));
        }
    }

    private List<RouteSessionInfo> getActiveSessionsLocked(IMediaRouter2Manager manager) {
        final IBinder binder = manager.asBinder();
        ManagerRecord managerRecord = mAllManagerRecords.get(binder);

        if (managerRecord == null) {
            return Collections.emptyList();
        }

        List<RouteSessionInfo> sessionInfos = new ArrayList<>();
        for (MediaRoute2Provider provider : managerRecord.mUserRecord.mHandler.mMediaProviders) {
            sessionInfos.addAll(provider.getSessionInfos());
        }
        return sessionInfos;
    }

    private void initializeUserLocked(UserRecord userRecord) {
        if (DEBUG) {
            Slog.d(TAG, userRecord + ": Initialized");
        }
        if (userRecord.mUserId == mCurrentUserId) {
            userRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::start, userRecord.mHandler));
        }
    }

    private void disposeUserIfNeededLocked(UserRecord userRecord) {
        // If there are no records left and the user is no longer current then go ahead
        // and purge the user record and all of its associated state.  If the user is current
        // then leave it alone since we might be connected to a route or want to query
        // the same route information again soon.
        if (userRecord.mUserId != mCurrentUserId
                && userRecord.mClientRecords.isEmpty()
                && userRecord.mManagerRecords.isEmpty()) {
            if (DEBUG) {
                Slog.d(TAG, userRecord + ": Disposed");
            }
            mUserRecords.remove(userRecord.mUserId);
            // Note: User already stopped (by switchUser) so no need to send stop message here.
        }
    }

    static long toUniqueRequestId(int clientId, int requestId) {
        return ((long) clientId << 32) | requestId;
    }

    static int toClientId(long uniqueRequestId) {
        return (int) (uniqueRequestId >> 32);
    }

    static int toClientRequestId(long uniqueRequestId) {
        return (int) uniqueRequestId;
    }

    final class UserRecord {
        public final int mUserId;
        //TODO: make records private for thread-safety
        final ArrayList<Client2Record> mClientRecords = new ArrayList<>();
        final ArrayList<ManagerRecord> mManagerRecords = new ArrayList<>();
        final UserHandler mHandler;

        UserRecord(int userId) {
            mUserId = userId;
            mHandler = new UserHandler(MediaRouter2ServiceImpl.this, this);
        }

        // TODO: This assumes that only one client exists in a package. Is it true?
        Client2Record findClientRecordLocked(String packageName) {
            for (Client2Record clientRecord : mClientRecords) {
                if (TextUtils.equals(clientRecord.mPackageName, packageName)) {
                    return clientRecord;
                }
            }
            return null;
        }
    }

    final class Client2Record implements IBinder.DeathRecipient {
        public final UserRecord mUserRecord;
        public final String mPackageName;
        public final List<Integer> mSelectRouteSequenceNumbers;
        public final IMediaRouter2Client mClient;
        public final int mUid;
        public final int mPid;
        public final boolean mTrusted;
        public final int mClientId;

        public List<String> mControlCategories;
        public boolean mIsManagerSelecting;
        public MediaRoute2Info mSelectingRoute;
        public MediaRoute2Info mSelectedRoute;

        Client2Record(UserRecord userRecord, IMediaRouter2Client client,
                int uid, int pid, String packageName, boolean trusted) {
            mUserRecord = userRecord;
            mPackageName = packageName;
            mSelectRouteSequenceNumbers = new ArrayList<>();
            mControlCategories = Collections.emptyList();
            mClient = client;
            mUid = uid;
            mPid = pid;
            mTrusted = trusted;
            mClientId = mNextClientId.getAndIncrement();
        }

        public void dispose() {
            mClient.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            clientDied(this);
        }
    }

    final class ManagerRecord implements IBinder.DeathRecipient {
        public final UserRecord mUserRecord;
        public final IMediaRouter2Manager mManager;
        public final int mUid;
        public final int mPid;
        public final String mPackageName;
        public final boolean mTrusted;
        public final int mClientId;

        ManagerRecord(UserRecord userRecord, IMediaRouter2Manager manager,
                int uid, int pid, String packageName, boolean trusted) {
            mUserRecord = userRecord;
            mManager = manager;
            mUid = uid;
            mPid = pid;
            mPackageName = packageName;
            mTrusted = trusted;
            mClientId = mNextClientId.getAndIncrement();
        }

        public void dispose() {
            mManager.asBinder().unlinkToDeath(this, 0);
        }

        @Override
        public void binderDied() {
            managerDied(this);
        }

        public void dump(PrintWriter pw, String prefix) {
            pw.println(prefix + this);

            final String indent = prefix + "  ";
            pw.println(indent + "mTrusted=" + mTrusted);
        }

        @Override
        public String toString() {
            return "Manager " + mPackageName + " (pid " + mPid + ")";
        }
    }

    static final class UserHandler extends Handler implements
            MediaRoute2ProviderWatcher.Callback,
            MediaRoute2Provider.Callback {

        private final WeakReference<MediaRouter2ServiceImpl> mServiceRef;
        private final UserRecord mUserRecord;
        private final MediaRoute2ProviderWatcher mWatcher;

        //TODO: Make this thread-safe.
        private final SystemMediaRoute2Provider mSystemProvider;
        private final ArrayList<MediaRoute2Provider> mMediaProviders =
                new ArrayList<>();

        private final List<MediaRoute2ProviderInfo> mLastProviderInfos = new ArrayList<>();
        private final CopyOnWriteArrayList<SessionCreationRequest> mSessionCreationRequests =
                new CopyOnWriteArrayList<>();
        private final Map<String, Client2Record> mSessionToClientMap = new ArrayMap<>();

        private boolean mRunning;

        UserHandler(MediaRouter2ServiceImpl service, UserRecord userRecord) {
            super(Looper.getMainLooper(), null, true);
            mServiceRef = new WeakReference<>(service);
            mUserRecord = userRecord;
            mSystemProvider = new SystemMediaRoute2Provider(service.mContext, this);
            mMediaProviders.add(mSystemProvider);
            mWatcher = new MediaRoute2ProviderWatcher(service.mContext, this,
                    this, mUserRecord.mUserId);
        }

        private void start() {
            if (!mRunning) {
                mRunning = true;
                mWatcher.start();
            }
        }

        private void stop() {
            if (mRunning) {
                mRunning = false;
                //TODO: may unselect routes
                mWatcher.stop(); // also stops all providers
            }
        }

        @Override
        public void onAddProvider(MediaRoute2ProviderProxy provider) {
            provider.setCallback(this);
            mMediaProviders.add(provider);
        }

        @Override
        public void onRemoveProvider(MediaRoute2ProviderProxy provider) {
            mMediaProviders.remove(provider);
        }

        @Override
        public void onProviderStateChanged(@NonNull MediaRoute2Provider provider) {
            sendMessage(PooledLambda.obtainMessage(UserHandler::updateProvider, this, provider));
        }

        @Override
        public void onSessionCreated(@NonNull MediaRoute2Provider provider,
                @Nullable RouteSessionInfo sessionInfo, long requestId) {
            sendMessage(PooledLambda.obtainMessage(UserHandler::handleCreateSessionResultOnHandler,
                    this, provider, sessionInfo, requestId));
        }

        @Override
        public void onSessionInfoChanged(@NonNull MediaRoute2Provider provider,
                @NonNull RouteSessionInfo sessionInfo) {
            sendMessage(PooledLambda.obtainMessage(UserHandler::updateSession,
                    this, provider, sessionInfo));
        }

        //TODO: notify session info updates
        private void updateProvider(MediaRoute2Provider provider) {
            int providerIndex = getProviderInfoIndex(provider.getUniqueId());
            MediaRoute2ProviderInfo providerInfo = provider.getProviderInfo();
            MediaRoute2ProviderInfo prevInfo =
                    (providerIndex < 0) ? null : mLastProviderInfos.get(providerIndex);

            if (Objects.equals(prevInfo, providerInfo)) return;

            if (prevInfo == null) {
                mLastProviderInfos.add(providerInfo);
                Collection<MediaRoute2Info> addedRoutes = providerInfo.getRoutes();
                if (addedRoutes.size() > 0) {
                    sendMessage(PooledLambda.obtainMessage(UserHandler::notifyRoutesAddedToClients,
                            this, getClients(), new ArrayList<>(addedRoutes)));
                }
            } else if (providerInfo == null) {
                mLastProviderInfos.remove(prevInfo);
                Collection<MediaRoute2Info> removedRoutes = prevInfo.getRoutes();
                if (removedRoutes.size() > 0) {
                    sendMessage(PooledLambda.obtainMessage(
                            UserHandler::notifyRoutesRemovedToClients,
                            this, getClients(), new ArrayList<>(removedRoutes)));
                }
            } else {
                mLastProviderInfos.set(providerIndex, providerInfo);
                List<MediaRoute2Info> addedRoutes = new ArrayList<>();
                List<MediaRoute2Info> removedRoutes = new ArrayList<>();
                List<MediaRoute2Info> changedRoutes = new ArrayList<>();

                final Collection<MediaRoute2Info> currentRoutes = providerInfo.getRoutes();
                final Set<String> updatedRouteIds = new HashSet<>();

                for (MediaRoute2Info route : currentRoutes) {
                    if (!route.isValid()) {
                        Slog.w(TAG, "Ignoring invalid route : " + route);
                        continue;
                    }
                    MediaRoute2Info prevRoute = prevInfo.getRoute(route.getId());

                    if (prevRoute != null) {
                        if (!Objects.equals(prevRoute, route)) {
                            changedRoutes.add(route);
                        }
                        updatedRouteIds.add(route.getId());
                    } else {
                        addedRoutes.add(route);
                    }
                }

                for (MediaRoute2Info prevRoute : prevInfo.getRoutes()) {
                    if (!updatedRouteIds.contains(prevRoute.getId())) {
                        removedRoutes.add(prevRoute);
                    }
                }

                List<IMediaRouter2Client> clients = getClients();
                List<IMediaRouter2Manager> managers = getManagers();
                if (addedRoutes.size() > 0) {
                    notifyRoutesAddedToClients(clients, addedRoutes);
                    notifyRoutesAddedToManagers(managers, addedRoutes);
                }
                if (removedRoutes.size() > 0) {
                    notifyRoutesRemovedToClients(clients, removedRoutes);
                    notifyRoutesRemovedToManagers(managers, removedRoutes);
                }
                if (changedRoutes.size() > 0) {
                    notifyRoutesChangedToClients(clients, changedRoutes);
                    notifyRoutesChangedToManagers(managers, changedRoutes);
                }
            }
        }

        private int getProviderInfoIndex(String providerId) {
            for (int i = 0; i < mLastProviderInfos.size(); i++) {
                MediaRoute2ProviderInfo providerInfo = mLastProviderInfos.get(i);
                if (TextUtils.equals(providerInfo.getUniqueId(), providerId)) {
                    return i;
                }
            }
            return -1;
        }

        private void requestCreateSessionOnHandler(Client2Record clientRecord,
                MediaRoute2Info route, String controlCategory, long requestId) {

            final MediaRoute2Provider provider = findProvider(route.getProviderId());
            if (provider == null) {
                Slog.w(TAG, "Ignoring session creation request since no provider found for"
                        + " given route=" + route);
                notifySessionCreationFailed(clientRecord, toClientRequestId(requestId));
                return;
            }

            if (!route.getSupportedCategories().contains(controlCategory)) {
                Slog.w(TAG, "Ignoring session creation request since the given route=" + route
                        + " doesn't support the given category=" + controlCategory);
                notifySessionCreationFailed(clientRecord, toClientRequestId(requestId));
                return;
            }

            // TODO: Apply timeout for each request (How many seconds should we wait?)
            SessionCreationRequest request = new SessionCreationRequest(
                    clientRecord, route, controlCategory, requestId);
            mSessionCreationRequests.add(request);

            provider.requestCreateSession(clientRecord.mPackageName, route.getId(),
                    controlCategory, requestId);
        }

        private void selectRouteOnHandler(@NonNull Client2Record clientRecord,
                String uniqueSessionId, MediaRoute2Info route) {
            if (!checkArgumentsForSessionControl(clientRecord, uniqueSessionId, route,
                    "selecting")) {
                return;
            }

            final String providerId = route.getProviderId();
            final MediaRoute2Provider provider = findProvider(providerId);
            // TODO: Remove this null check when the mMediaProviders are referenced only in handler.
            if (provider == null) {
                return;
            }
            provider.selectRoute(RouteSessionInfo.getSessionId(uniqueSessionId, providerId), route);
        }

        private void deselectRouteOnHandler(@NonNull Client2Record clientRecord,
                String uniqueSessionId, MediaRoute2Info route) {
            if (!checkArgumentsForSessionControl(clientRecord, uniqueSessionId, route,
                    "deselecting")) {
                return;
            }

            final String providerId = route.getProviderId();
            final MediaRoute2Provider provider = findProvider(providerId);
            // TODO: Remove this null check when the mMediaProviders are referenced only in handler.
            if (provider == null) {
                return;
            }
            provider.deselectRoute(
                    RouteSessionInfo.getSessionId(uniqueSessionId, providerId), route);
        }

        private void transferToRouteOnHandler(@NonNull Client2Record clientRecord,
                String uniqueSessionId, MediaRoute2Info route) {
            if (!checkArgumentsForSessionControl(clientRecord, uniqueSessionId, route,
                    "transferring to")) {
                return;
            }

            final String providerId = route.getProviderId();
            final MediaRoute2Provider provider = findProvider(providerId);
            // TODO: Remove this null check when the mMediaProviders are referenced only in handler.
            if (provider == null) {
                return;
            }
            provider.transferToRoute(
                    RouteSessionInfo.getSessionId(uniqueSessionId, providerId), route);
        }

        private boolean checkArgumentsForSessionControl(@NonNull Client2Record clientRecord,
                String uniqueSessionId, MediaRoute2Info route, @NonNull String description) {
            if (route == null) {
                Slog.w(TAG, "Ignoring " + description + " null route");
                return false;
            }

            final String providerId = route.getProviderId();
            final MediaRoute2Provider provider = findProvider(providerId);
            if (provider == null) {
                Slog.w(TAG, "Ignoring " + description + " route since no provider found for "
                        + "given route=" + route);
                return false;
            }

            if (TextUtils.isEmpty(uniqueSessionId)) {
                Slog.w(TAG, "Ignoring " + description + " route with empty unique session ID. "
                        + "route=" + route);
                return false;
            }

            Client2Record matchingRecord = mSessionToClientMap.get(uniqueSessionId);
            if (matchingRecord != clientRecord) {
                Slog.w(TAG, "Ignoring " + description + " route from non-matching client. "
                        + "packageName=" + clientRecord.mPackageName + " route=" + route);
                return false;
            }

            try {
                RouteSessionInfo.getSessionId(uniqueSessionId, providerId);
            } catch (Exception ex) {
                Slog.w(TAG, "Failed to get int session id from unique session id. "
                        + "uniqueSessionId=" + uniqueSessionId + " providerId=" + providerId);
                return false;
            }

            return true;
        }

        private void handleCreateSessionResultOnHandler(
                @NonNull MediaRoute2Provider provider, @Nullable RouteSessionInfo sessionInfo,
                long requestId) {
            SessionCreationRequest matchingRequest = null;

            for (SessionCreationRequest request : mSessionCreationRequests) {
                if (request.mRequestId == requestId
                        && TextUtils.equals(
                                request.mRoute.getProviderId(), provider.getUniqueId())) {
                    matchingRequest = request;
                    break;
                }
            }

            if (matchingRequest == null) {
                Slog.w(TAG, "Ignoring session creation result for unknown request. "
                        + "requestId=" + requestId + ", sessionInfo=" + sessionInfo);
                return;
            }

            mSessionCreationRequests.remove(matchingRequest);

            if (sessionInfo == null) {
                // Failed
                notifySessionCreationFailed(matchingRequest.mClientRecord,
                        toClientRequestId(requestId));
                return;
            }

            String originalRouteId = matchingRequest.mRoute.getId();
            String originalCategory = matchingRequest.mControlCategory;
            Client2Record client2Record = matchingRequest.mClientRecord;

            if (!sessionInfo.getSelectedRoutes().contains(originalRouteId)
                    || !TextUtils.equals(originalCategory,
                        sessionInfo.getControlCategory())) {
                Slog.w(TAG, "Created session doesn't match the original request."
                        + " originalRouteId=" + originalRouteId
                        + ", originalCategory=" + originalCategory + ", requestId=" + requestId
                        + ", sessionInfo=" + sessionInfo);
                notifySessionCreationFailed(matchingRequest.mClientRecord,
                        toClientRequestId(requestId));
                return;
            }

            // Succeeded
            notifySessionCreated(matchingRequest.mClientRecord,
                    sessionInfo, toClientRequestId(requestId));
            mSessionToClientMap.put(sessionInfo.getUniqueSessionId(), client2Record);
            // TODO: Tell managers for the session creation
        }

        private void updateSession(@NonNull MediaRoute2Provider provider,
                @NonNull RouteSessionInfo sessionInfo) {
            RouteSessionInfo sessionInfoWithProviderId = new RouteSessionInfo.Builder(sessionInfo)
                    .setProviderId(provider.getUniqueId())
                    .build();

            Client2Record client2Record = mSessionToClientMap.get(
                    sessionInfoWithProviderId.getUniqueSessionId());
            if (client2Record == null) {
                Slog.w(TAG, "No matching client found for session=" + sessionInfoWithProviderId);
                // TODO: Tell managers for the session update
                return;
            }
            notifySessionInfoChanged(client2Record, sessionInfoWithProviderId);
            // TODO: Tell managers for the session update
        }

        private void notifySessionCreated(Client2Record clientRecord, RouteSessionInfo sessionInfo,
                int requestId) {
            try {
                clientRecord.mClient.notifySessionCreated(sessionInfo, requestId);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify client of the session creation."
                        + " Client probably died.", ex);
            }
        }

        private void notifySessionCreationFailed(Client2Record clientRecord, int requestId) {
            try {
                clientRecord.mClient.notifySessionCreated(/* sessionInfo= */ null, requestId);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify client of the session creation failure."
                        + " Client probably died.", ex);
            }
        }

        private void notifySessionInfoChanged(Client2Record clientRecord,
                RouteSessionInfo sessionInfo) {
            try {
                clientRecord.mClient.notifySessionInfoChanged(sessionInfo);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify client of the session info change."
                        + " Client probably died.", ex);
            }
        }

        private void sendControlRequest(MediaRoute2Info route, Intent request) {
            final MediaRoute2Provider provider = findProvider(route.getProviderId());
            if (provider != null) {
                provider.sendControlRequest(route, request);
            }
        }

        private void requestSetVolume(MediaRoute2Info route, int volume) {
            final MediaRoute2Provider provider = findProvider(route.getProviderId());
            if (provider != null) {
                provider.requestSetVolume(route, volume);
            }
        }

        private void requestUpdateVolume(MediaRoute2Info route, int delta) {
            final MediaRoute2Provider provider = findProvider(route.getProviderId());
            if (provider != null) {
                provider.requestUpdateVolume(route, delta);
            }
        }

        private List<IMediaRouter2Client> getClients() {
            final List<IMediaRouter2Client> clients = new ArrayList<>();
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return clients;
            }
            synchronized (service.mLock) {
                for (Client2Record clientRecord : mUserRecord.mClientRecords) {
                    clients.add(clientRecord.mClient);
                }
            }
            return clients;
        }

        private List<IMediaRouter2Manager> getManagers() {
            final List<IMediaRouter2Manager> managers = new ArrayList<>();
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return managers;
            }
            synchronized (service.mLock) {
                for (ManagerRecord managerRecord : mUserRecord.mManagerRecords) {
                    managers.add(managerRecord.mManager);
                }
            }
            return managers;
        }

        private void notifyRoutesToClient(IMediaRouter2Client client) {
            List<MediaRoute2Info> routes = new ArrayList<>();
            for (MediaRoute2ProviderInfo providerInfo : mLastProviderInfos) {
                routes.addAll(providerInfo.getRoutes());
            }
            if (routes.size() == 0) {
                return;
            }
            try {
                client.notifyRoutesAdded(routes);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify all routes. Client probably died.", ex);
            }
        }

        // TODO: Remove notifyRouteSelected* methods
        private void notifyRouteSelectedToClient(IMediaRouter2Client client,
                MediaRoute2Info route, int reason, Bundle controlHints) {
        }

        private void notifyRoutesAddedToClients(List<IMediaRouter2Client> clients,
                List<MediaRoute2Info> routes) {
            for (IMediaRouter2Client client : clients) {
                try {
                    client.notifyRoutesAdded(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes added. Client probably died.", ex);
                }
            }
        }

        private void notifyRoutesRemovedToClients(List<IMediaRouter2Client> clients,
                List<MediaRoute2Info> routes) {
            for (IMediaRouter2Client client : clients) {
                try {
                    client.notifyRoutesRemoved(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes removed. Client probably died.", ex);
                }
            }
        }

        private void notifyRoutesChangedToClients(List<IMediaRouter2Client> clients,
                List<MediaRoute2Info> routes) {
            for (IMediaRouter2Client client : clients) {
                try {
                    client.notifyRoutesChanged(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes changed. Client probably died.", ex);
                }
            }
        }

        private void notifyRoutesToManager(IMediaRouter2Manager manager) {
            List<MediaRoute2Info> routes = new ArrayList<>();
            for (MediaRoute2ProviderInfo providerInfo : mLastProviderInfos) {
                routes.addAll(providerInfo.getRoutes());
            }
            if (routes.size() == 0) {
                return;
            }
            try {
                manager.notifyRoutesAdded(routes);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify all routes. Manager probably died.", ex);
            }
        }

        private void notifyRoutesAddedToManagers(List<IMediaRouter2Manager> managers,
                List<MediaRoute2Info> routes) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyRoutesAdded(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes added. Manager probably died.", ex);
                }
            }
        }

        private void notifyRoutesRemovedToManagers(List<IMediaRouter2Manager> managers,
                List<MediaRoute2Info> routes) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyRoutesRemoved(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes removed. Manager probably died.", ex);
                }
            }
        }

        private void notifyRoutesChangedToManagers(List<IMediaRouter2Manager> managers,
                List<MediaRoute2Info> routes) {
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyRoutesChanged(routes);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to notify routes changed. Manager probably died.", ex);
                }
            }
        }

        private void updateClientUsage(Client2Record clientRecord) {
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return;
            }
            List<IMediaRouter2Manager> managers = new ArrayList<>();
            synchronized (service.mLock) {
                for (ManagerRecord managerRecord : mUserRecord.mManagerRecords) {
                    managers.add(managerRecord.mManager);
                }
            }
            for (IMediaRouter2Manager manager : managers) {
                try {
                    manager.notifyRouteSelected(clientRecord.mPackageName,
                            clientRecord.mSelectedRoute);
                    manager.notifyControlCategoriesChanged(clientRecord.mPackageName,
                            clientRecord.mControlCategories);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to update client usage. Manager probably died.", ex);
                }
            }
        }

        private MediaRoute2Provider findProvider(String providerId) {
            for (MediaRoute2Provider provider : mMediaProviders) {
                if (TextUtils.equals(provider.getUniqueId(), providerId)) {
                    return provider;
                }
            }
            return null;
        }

        final class SessionCreationRequest {
            public final Client2Record mClientRecord;
            public final MediaRoute2Info mRoute;
            public final String mControlCategory;
            public final long mRequestId;

            SessionCreationRequest(@NonNull Client2Record clientRecord,
                    @NonNull MediaRoute2Info route,
                    @NonNull String controlCategory, long requestId) {
                mClientRecord = clientRecord;
                mRoute = route;
                mControlCategory = controlCategory;
                mRequestId = requestId;
            }
        }
    }
}
