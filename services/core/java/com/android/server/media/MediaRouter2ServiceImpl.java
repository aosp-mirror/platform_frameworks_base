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
import android.media.IMediaRouterClient;
import android.media.MediaRoute2Info;
import android.media.MediaRoute2ProviderInfo;
import android.os.Binder;
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

    //TODO: What would happen if a media app used MediaRouter and MediaRouter2 simultaneously?
    public void setControlCategories(@NonNull IMediaRouterClient client,
            @Nullable List<String> categories) {
        Objects.requireNonNull(client, "client must not be null");
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                ClientRecord clientRecord = mAllClientRecords.get(client.asBinder());
                setControlCategoriesLocked(clientRecord, categories);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void setControlCategories2(@NonNull IMediaRouter2Client client,
            @Nullable List<String> categories) {
        Objects.requireNonNull(client, "client must not be null");

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                ClientRecord clientRecord = mAllClientRecords.get(client.asBinder());
                setControlCategoriesLocked(clientRecord, categories);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void selectRoute2(@NonNull IMediaRouter2Client client,
            @Nullable MediaRoute2Info route) {
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                selectRoute2Locked(mAllClientRecords.get(client.asBinder()), route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void selectClientRoute2(@NonNull IMediaRouter2Manager manager,
            String packageName, @Nullable MediaRoute2Info route) {
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                selectClientRoute2Locked(manager, packageName, route);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    public void registerClient(@NonNull IMediaRouterClient client, @NonNull String packageName) {
        Objects.requireNonNull(client, "client must not be null");

        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final int userId = UserHandle.getUserId(uid);

        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                registerClient1Locked(client, packageName, userId);
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
                unregisterClient1Locked(client);
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
            boolean newUser = false;
            UserRecord userRecord = mUserRecords.get(userId);
            if (userRecord == null) {
                userRecord = new UserRecord(userId);
                newUser = true;
            }
            Client2Record clientRecord = new Client2Record(userRecord, client, uid, pid,
                    packageName, trusted);
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

            userRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::notifyProviderInfosUpdatedToClient,
                            userRecord.mHandler, client));
        }
    }

    private void unregisterClient2Locked(IMediaRouter2Client client, boolean died) {
        Client2Record clientRecord = (Client2Record) mAllClientRecords.remove(client.asBinder());
        if (clientRecord != null) {
            UserRecord userRecord = clientRecord.mUserRecord;
            userRecord.mClientRecords.remove(clientRecord);
            //TODO: update discovery request
            clientRecord.dispose();
            disposeUserIfNeededLocked(userRecord); // since client removed from user
        }
    }

    private void selectRoute2Locked(ClientRecord clientRecord, MediaRoute2Info route) {
        if (clientRecord != null) {
            MediaRoute2Info oldRoute = clientRecord.mSelectedRoute;
            clientRecord.mSelectedRoute = route;

            UserHandler handler = clientRecord.mUserRecord.mHandler;
            //TODO: Handle transfer instead of unselect and select
            if (oldRoute != null) {
                handler.sendMessage(
                        obtainMessage(UserHandler::unselectRoute, handler, clientRecord,
                                oldRoute));
            }
            if (route != null) {
                handler.sendMessage(
                        obtainMessage(UserHandler::selectRoute, handler, clientRecord, route));
            }
            handler.sendMessage(
                    obtainMessage(UserHandler::updateClientUsage, handler, clientRecord));
        }
    }

    private void setControlCategoriesLocked(ClientRecord clientRecord, List<String> categories) {
        if (clientRecord != null) {
            clientRecord.mControlCategories = categories;

            clientRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::updateClientUsage,
                            clientRecord.mUserRecord.mHandler, clientRecord));
        }
    }

    private void sendControlRequestLocked(IMediaRouter2Client client, MediaRoute2Info route,
            Intent request) {
        final IBinder binder = client.asBinder();
        ClientRecord clientRecord = mAllClientRecords.get(binder);

        if (clientRecord != null) {
            clientRecord.mUserRecord.mHandler.sendMessage(
                    obtainMessage(UserHandler::sendControlRequest,
                            clientRecord.mUserRecord.mHandler, route, request));
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
                    obtainMessage(UserHandler::notifyProviderInfosUpdatedToManager,
                            userRecord.mHandler, manager));

            for (ClientRecord clientRecord : userRecord.mClientRecords) {
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

    private void selectClientRoute2Locked(IMediaRouter2Manager manager,
            String packageName, MediaRoute2Info route) {
        ManagerRecord managerRecord = mAllManagerRecords.get(manager.asBinder());
        if (managerRecord != null) {
            ClientRecord clientRecord = managerRecord.mUserRecord.findClientRecord(packageName);
            if (clientRecord == null) {
                Slog.w(TAG, "Ignoring route selection for unknown client.");
            }
            if (clientRecord != null && managerRecord.mTrusted) {
                selectRoute2Locked(clientRecord, route);
            }
        }
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

    private void registerClient1Locked(IMediaRouterClient client, String packageName,
            int userId) {
        final IBinder binder = client.asBinder();
        if (mAllClientRecords.get(binder) == null) {
            boolean newUser = false;
            UserRecord userRecord = mUserRecords.get(userId);
            if (userRecord == null) {
                userRecord = new UserRecord(userId);
                newUser = true;
            }
            ClientRecord clientRecord = new Client1Record(userRecord, client, packageName);

            if (newUser) {
                mUserRecords.put(userId, userRecord);
                initializeUserLocked(userRecord);
            }

            userRecord.mClientRecords.add(clientRecord);
            mAllClientRecords.put(binder, clientRecord);
        }
    }

    private void unregisterClient1Locked(IMediaRouterClient client) {
        ClientRecord clientRecord = mAllClientRecords.remove(client.asBinder());
        if (clientRecord != null) {
            UserRecord userRecord = clientRecord.mUserRecord;
            userRecord.mClientRecords.remove(clientRecord);
            disposeUserIfNeededLocked(userRecord);
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

        ClientRecord findClientRecord(String packageName) {
            for (ClientRecord clientRecord : mClientRecords) {
                if (TextUtils.equals(clientRecord.mPackageName, packageName)) {
                    return clientRecord;
                }
            }
            return null;
        }
    }

    class ClientRecord {
        public final UserRecord mUserRecord;
        public final String mPackageName;
        public List<String> mControlCategories;
        public MediaRoute2Info mSelectedRoute;

        ClientRecord(UserRecord userRecord, String packageName) {
            mUserRecord = userRecord;
            mPackageName = packageName;
            mControlCategories = Collections.emptyList();
        }
    }

    final class Client1Record extends ClientRecord {
        public final IMediaRouterClient mClient;

        Client1Record(UserRecord userRecord, IMediaRouterClient client,
                String packageName) {
            super(userRecord, packageName);
            mClient = client;
        }
    }

    final class Client2Record extends ClientRecord
            implements IBinder.DeathRecipient {
        public final IMediaRouter2Client mClient;
        public final int mUid;
        public final int mPid;
        public final boolean mTrusted;

        Client2Record(UserRecord userRecord, IMediaRouter2Client client,
                int uid, int pid, String packageName, boolean trusted) {
            super(userRecord, packageName);
            mClient = client;
            mUid = uid;
            mPid = pid;
            mTrusted = trusted;
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

        private final WeakReference<MediaRouter2ServiceImpl> mServiceRef;
        private final UserRecord mUserRecord;
        private final MediaRoute2ProviderWatcher mWatcher;

        //TODO: Make this thread-safe.
        private final ArrayList<MediaRoute2ProviderProxy> mMediaProviders =
                new ArrayList<>();
        private List<MediaRoute2ProviderInfo> mProviderInfos;

        private boolean mRunning;
        private boolean mProviderInfosUpdateScheduled;

        UserHandler(MediaRouter2ServiceImpl service, UserRecord userRecord) {
            super(Looper.getMainLooper(), null, true);
            mServiceRef = new WeakReference<>(service);
            mUserRecord = userRecord;
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
        public void onProviderStateChanged(MediaRoute2ProviderProxy provider) {
            updateProvider(provider);
        }

        private void updateProvider(MediaRoute2ProviderProxy provider) {
            scheduleUpdateProviderInfos();
        }

        private void selectRoute(ClientRecord clientRecord, MediaRoute2Info route) {
            if (route != null) {
                MediaRoute2ProviderProxy provider = findProvider(route.getProviderId());
                if (provider == null) {
                    Slog.w(TAG, "Ignoring to select route of unknown provider " + route);
                } else {
                    provider.selectRoute(clientRecord.mPackageName, route.getId());
                }
            }
        }

        private void unselectRoute(ClientRecord clientRecord, MediaRoute2Info route) {
            if (route != null) {
                MediaRoute2ProviderProxy provider = findProvider(route.getProviderId());
                if (provider == null) {
                    Slog.w(TAG, "Ignoring to unselect route of unknown provider " + route);
                } else {
                    provider.unselectRoute(clientRecord.mPackageName, route.getId());
                }
            }
        }

        private void sendControlRequest(MediaRoute2Info route, Intent request) {
            final MediaRoute2ProviderProxy provider = findProvider(route.getProviderId());
            if (provider != null) {
                provider.sendControlRequest(route, request);
            }
        }

        private void scheduleUpdateProviderInfos() {
            if (!mProviderInfosUpdateScheduled) {
                mProviderInfosUpdateScheduled = true;
                sendMessage(PooledLambda.obtainMessage(UserHandler::updateProviderInfos, this));
            }
        }

        private void updateProviderInfos() {
            mProviderInfosUpdateScheduled = false;

            MediaRouter2ServiceImpl service = mServiceRef.get();
            if (service == null) {
                return;
            }
            final List<IMediaRouter2Manager> managers = new ArrayList<>();
            final List<IMediaRouter2Client> clients = new ArrayList<>();
            final List<MediaRoute2ProviderInfo> providers = new ArrayList<>();
            for (MediaRoute2ProviderProxy mediaProvider : mMediaProviders) {
                final MediaRoute2ProviderInfo providerInfo =
                        mediaProvider.getProviderInfo();
                if (providerInfo == null || !providerInfo.isValid()) {
                    Slog.w(TAG, "Ignoring invalid provider info : " + providerInfo);
                } else {
                    providers.add(providerInfo);
                }
            }
            mProviderInfos = providers;

            synchronized (service.mLock) {
                for (ManagerRecord managerRecord : mUserRecord.mManagerRecords) {
                    managers.add(managerRecord.mManager);
                }
                for (ClientRecord clientRecord : mUserRecord.mClientRecords) {
                    if (clientRecord instanceof Client2Record) {
                        clients.add(((Client2Record) clientRecord).mClient);
                    }
                }
            }
            for (IMediaRouter2Manager manager : managers) {
                notifyProviderInfosUpdatedToManager(manager);
            }
            for (IMediaRouter2Client client : clients) {
                notifyProviderInfosUpdatedToClient(client);
            }
        }

        private void notifyProviderInfosUpdatedToClient(IMediaRouter2Client client) {
            if (mProviderInfos == null) {
                scheduleUpdateProviderInfos();
                return;
            }
            try {
                client.notifyProviderInfosUpdated(mProviderInfos);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify provider infos updated. Client probably died.");
            }
        }

        private void notifyProviderInfosUpdatedToManager(IMediaRouter2Manager manager) {
            if (mProviderInfos == null) {
                scheduleUpdateProviderInfos();
                return;
            }
            try {
                manager.notifyProviderInfosUpdated(mProviderInfos);
            } catch (RemoteException ex) {
                Slog.w(TAG, "Failed to notify provider infos updated. Manager probably died.");
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

        private MediaRoute2ProviderProxy findProvider(String providerId) {
            for (MediaRoute2ProviderProxy provider : mMediaProviders) {
                final MediaRoute2ProviderInfo providerInfo = provider.getProviderInfo();
                if (providerInfo != null
                        && TextUtils.equals(providerInfo.getUniqueId(), providerId)) {
                    return provider;
                }
            }
            return null;
        }
    }
}
