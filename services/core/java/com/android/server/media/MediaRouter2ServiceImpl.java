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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.IMediaRouter2Manager;
import android.media.IMediaRouterClient;
import android.media.MediaRoute2ProviderInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * TODO: Merge this to MediaRouterService once it's finished.
 */
class MediaRouter2ServiceImpl {
    private static final String TAG = "MediaRouter2ServiceImpl";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final SparseArray<UserRecord> mUserRecords = new SparseArray<>();
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, ClientRecord> mAllClientRecords = new ArrayMap<>();
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, ManagerRecord> mAllManagerRecords = new ArrayMap<>();
    @GuardedBy("mLock")
    private int mCurrentUserId = -1;

    MediaRouter2ServiceImpl(Context context) {
        mContext = context;
    }

    public void registerClientAsUser(@NonNull IMediaRouterClient client,
            @NonNull String packageName, int userId) {
        Objects.requireNonNull(client, "client must not be null");

        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int resolvedUserId = ActivityManager.handleIncomingUser(pid, uid, userId,
                false /*allowAll*/, true /*requireFull*/, "registerClientAsUser", packageName);
        final boolean trusted = mContext.checkCallingOrSelfPermission(
                android.Manifest.permission.CONFIGURE_WIFI_DISPLAY)
                == PackageManager.PERMISSION_GRANTED;
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                registerClientLocked(client, uid, pid, packageName, resolvedUserId, trusted);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void unregisterClient(@NonNull IMediaRouterClient client) {
        Objects.requireNonNull(client, "client must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                unregisterClientLocked(client, false);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void registerManagerAsUser(@NonNull IMediaRouter2Manager manager,
            @NonNull String packageName, int userId) {
        Objects.requireNonNull(manager, "manager must not be null");
        //TODO: should check permission
        final boolean trusted = true;

        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int resolvedUserId = ActivityManager.handleIncomingUser(pid, uid, userId,
                false /*allowAll*/, true /*requireFull*/, "registerManagerAsUser", packageName);
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                registerManagerLocked(manager, uid, pid, packageName, resolvedUserId, trusted);
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

    public void setControlCategories(@NonNull IMediaRouterClient client,
            @Nullable List<String> categories) {
        Objects.requireNonNull(client, "client must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                setControlCategoriesLocked(client, categories);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setRemoteRoute(@NonNull IMediaRouter2Manager manager,
            int uid, @Nullable String routeId, boolean explicit) {
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                setRemoteRouteLocked(manager, uid, routeId, explicit);
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
                    oldUser.mHandler.sendEmptyMessage(MediaRouterService.UserHandler.MSG_STOP);
                    disposeUserIfNeededLocked(oldUser); // since no longer current user
                }

                UserRecord newUser = mUserRecords.get(userId);
                if (newUser != null) {
                    newUser.mHandler.sendEmptyMessage(MediaRouterService.UserHandler.MSG_START);
                }
            }
        }
    }

    void clientDied(ClientRecord clientRecord) {
        synchronized (mLock) {
            unregisterClientLocked(clientRecord.mClient, true);
        }
    }

    void managerDied(ManagerRecord managerRecord) {
        synchronized (mLock) {
            unregisterManagerLocked(managerRecord.mManager, true);
        }
    }

    private void registerClientLocked(IMediaRouterClient client,
            int uid, int pid, String packageName, int userId, boolean trusted) {
        final IBinder binder = client.asBinder();
        ClientRecord clientRecord = mAllClientRecords.get(binder);
        if (clientRecord == null) {
            boolean newUser = false;
            UserRecord userRecord = mUserRecords.get(userId);
            if (userRecord == null) {
                userRecord = new UserRecord(userId);
                newUser = true;
            }
            clientRecord = new ClientRecord(userRecord, client, uid, pid, packageName, trusted);
            try {
                binder.linkToDeath(clientRecord, 0);
            } catch (RemoteException ex) {
                throw new RuntimeException("Media router client died prematurely.", ex);
            }

            if (newUser) {
                mUserRecords.put(userId, userRecord);
                initializeUserLocked(userRecord);
            }

            userRecord.mClientRecords.add(clientRecord);
            mAllClientRecords.put(binder, clientRecord);
        }
    }

    private void unregisterClientLocked(IMediaRouterClient client, boolean died) {
        ClientRecord clientRecord = mAllClientRecords.remove(client.asBinder());
        if (clientRecord != null) {
            UserRecord userRecord = clientRecord.mUserRecord;
            userRecord.mClientRecords.remove(clientRecord);
            //TODO: update discovery request
            clientRecord.dispose();
            disposeUserIfNeededLocked(userRecord); // since client removed from user
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

            //TODO: remove this when it's unnecessary
            // Sends published routes to newly added manager
            userRecord.mHandler.scheduleUpdateManagerState();

            final int count = userRecord.mClientRecords.size();
            for (int i = 0; i < count; ++i) {
                ClientRecord clientRecord = userRecord.mClientRecords.get(i);
                clientRecord.mUserRecord.mHandler.obtainMessage(
                        UserHandler.MSG_UPDATE_CLIENT_USAGE, clientRecord).sendToTarget();
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

    private void setRemoteRouteLocked(IMediaRouter2Manager manager,
            int uid, String routeId, boolean explicit) {
        ManagerRecord managerRecord = mAllManagerRecords.get(manager.asBinder());
        if (managerRecord != null) {
            if (explicit && managerRecord.mTrusted) {
                Pair<Integer, String> obj = new Pair<>(uid, routeId);
                managerRecord.mUserRecord.mHandler.obtainMessage(
                        UserHandler.MSG_SELECT_REMOTE_ROUTE, obj).sendToTarget();
            }
        }
    }

    private void setControlCategoriesLocked(IMediaRouterClient client, List<String> categories) {
        final IBinder binder = client.asBinder();
        ClientRecord clientRecord = mAllClientRecords.get(binder);

        if (clientRecord != null) {
            clientRecord.mControlCategories = categories;
            clientRecord.mUserRecord.mHandler.obtainMessage(
                    UserHandler.MSG_UPDATE_CLIENT_USAGE, clientRecord).sendToTarget();
        }
    }

    private void initializeUserLocked(UserRecord userRecord) {
        if (DEBUG) {
            Slog.d(TAG, userRecord + ": Initialized");
        }
        if (userRecord.mUserId == mCurrentUserId) {
            userRecord.mHandler.sendEmptyMessage(UserHandler.MSG_START);
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

    final class UserRecord {
        public final int mUserId;
        final ArrayList<ClientRecord> mClientRecords = new ArrayList<>();
        final ArrayList<ManagerRecord> mManagerRecords = new ArrayList<>();
        final UserHandler mHandler;

        UserRecord(int userId) {
            mUserId = userId;
            mHandler = new UserHandler(MediaRouter2ServiceImpl.this, this);
        }
    }

    final class ClientRecord implements IBinder.DeathRecipient {
        public final UserRecord mUserRecord;
        public final IMediaRouterClient mClient;
        public final int mUid;
        public final int mPid;
        public final String mPackageName;
        public final boolean mTrusted;
        public List<String> mControlCategories;

        ClientRecord(UserRecord userRecord, IMediaRouterClient client,
                int uid, int pid, String packageName, boolean trusted) {
            mUserRecord = userRecord;
            mClient = client;
            mUid = uid;
            mPid = pid;
            mPackageName = packageName;
            mTrusted = trusted;
            mControlCategories = Collections.emptyList();
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

        ManagerRecord(UserRecord userRecord, IMediaRouter2Manager manager,
                int uid, int pid, String packageName, boolean trusted) {
            mUserRecord = userRecord;
            mManager = manager;
            mUid = uid;
            mPid = pid;
            mPackageName = packageName;
            mTrusted = trusted;
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
            MediaRoute2ProviderProxy.Callback {

        //TODO: Should be rearranged
        public static final int MSG_START = 1;
        public static final int MSG_STOP = 2;

        private static final int MSG_SELECT_REMOTE_ROUTE = 10;
        private static final int MSG_UPDATE_CLIENT_USAGE = 11;
        private static final int MSG_UPDATE_MANAGER_STATE = 12;

        private final WeakReference<MediaRouter2ServiceImpl> mServiceRef;
        private final UserRecord mUserRecord;
        private final MediaRoute2ProviderWatcher mWatcher;
        private final ArrayList<IMediaRouter2Manager> mTempManagers = new ArrayList<>();

        private final ArrayList<MediaRoute2ProviderProxy> mMediaProviders =
                new ArrayList<>();

        private boolean mRunning;
        private boolean mManagerStateUpdateScheduled;

        UserHandler(MediaRouter2ServiceImpl service, UserRecord userRecord) {
            super(Looper.getMainLooper(), null, true);
            mServiceRef = new WeakReference<>(service);
            mUserRecord = userRecord;
            mWatcher = new MediaRoute2ProviderWatcher(service.mContext, this,
                    this, mUserRecord.mUserId);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START: {
                    start();
                    break;
                }
                case MSG_STOP: {
                    stop();
                    break;
                }
                case MSG_SELECT_REMOTE_ROUTE: {
                    Pair<Integer, String> obj = (Pair<Integer, String>) msg.obj;
                    selectRemoteRoute(obj.first, obj.second);
                    break;
                }
                case MSG_UPDATE_CLIENT_USAGE: {
                    updateClientUsage((ClientRecord) msg.obj);
                    break;
                }
                case MSG_UPDATE_MANAGER_STATE: {
                    updateManagerState();
                    break;
                }
            }
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
        public void onProviderStateChanged(MediaRoute2ProviderProxy provider) {
            updateProvider(provider);
        }

        private void updateProvider(MediaRoute2ProviderProxy provider) {
            scheduleUpdateManagerState();
        }


        private void selectRemoteRoute(int uid, String routeId) {
            if (routeId != null) {
                final int providerCount = mMediaProviders.size();

                //TODO: should find proper provider (currently assumes a single provider)
                for (int i = 0; i < providerCount; ++i) {
                    mMediaProviders.get(i).setSelectedRoute(uid, routeId);
                }
            }
        }

        private void scheduleUpdateManagerState() {
            if (!mManagerStateUpdateScheduled) {
                mManagerStateUpdateScheduled = true;
                sendEmptyMessage(MSG_UPDATE_MANAGER_STATE);
            }
        }

        private void updateManagerState() {
            mManagerStateUpdateScheduled = false;

            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return;
            }
            //TODO: Consider using a member variable (like mTempManagers).
            final List<MediaRoute2ProviderInfo> providers = new ArrayList<>();
            final int mediaCount = mMediaProviders.size();
            for (int i = 0; i < mediaCount; i++) {
                final MediaRoute2ProviderInfo providerInfo =
                        mMediaProviders.get(i).getProviderInfo();
                if (providerInfo == null || !providerInfo.isValid()) {
                    Log.w(TAG, "Ignoring invalid provider info : " + providerInfo);
                } else {
                    providers.add(providerInfo);
                }
            }

            try {
                synchronized (service.mLock) {
                    final int count = mUserRecord.mManagerRecords.size();
                    for (int i = 0; i < count; i++) {
                        mTempManagers.add(mUserRecord.mManagerRecords.get(i).mManager);
                    }
                }
                //TODO: Call !proper callbacks when provider descriptor is implemented.
                if (!providers.isEmpty()) {
                    final int count = mTempManagers.size();
                    for (int i = 0; i < count; i++) {
                        try {
                            mTempManagers.get(i).notifyProviderInfosUpdated(providers);
                        } catch (RemoteException ex) {
                            Slog.w(TAG, "Failed to call onStateChanged. Manager probably died.",
                                    ex);
                        }
                    }
                }
            } finally {
                // Clear the list in preparation for the next time.
                mTempManagers.clear();
            }
        }

        private void updateClientUsage(ClientRecord clientRecord) {
            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return;
            }
            List<IMediaRouter2Manager> managers = new ArrayList<>();
            synchronized (service.mLock) {
                final int count = mUserRecord.mManagerRecords.size();
                for (int i = 0; i < count; i++) {
                    managers.add(mUserRecord.mManagerRecords.get(i).mManager);
                }
            }
            final int count = managers.size();
            for (int i = 0; i < count; i++) {
                try {
                    managers.get(i).notifyControlCategoriesChanged(clientRecord.mUid,
                            clientRecord.mControlCategories);
                } catch (RemoteException ex) {
                    Slog.w(TAG, "Failed to call onControlCategoriesChanged. "
                            + "Manager probably died.", ex);
                }
            }
        }
    }
}
