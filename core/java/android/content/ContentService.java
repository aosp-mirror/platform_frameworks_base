/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.content;

import android.accounts.Account;
import android.database.IContentObserver;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Config;
import android.util.Log;
import android.Manifest;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * {@hide}
 */
public final class ContentService extends IContentService.Stub {
    private static final String TAG = "ContentService";
    private Context mContext;
    private boolean mFactoryTest;
    private final ObserverNode mRootNode = new ObserverNode("");
    private SyncManager mSyncManager = null;
    private final Object mSyncManagerLock = new Object();

    private SyncManager getSyncManager() {
        synchronized(mSyncManagerLock) {
            try {
                // Try to create the SyncManager, return null if it fails (e.g. the disk is full).
                if (mSyncManager == null) mSyncManager = new SyncManager(mContext, mFactoryTest);
            } catch (SQLiteException e) {
                Log.e(TAG, "Can't create SyncManager", e);
            }
            return mSyncManager;
        }
    }

    @Override
    protected synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.DUMP,
                "caller doesn't have the DUMP permission");

        // This makes it so that future permission checks will be in the context of this
        // process rather than the caller's process. We will restore this before returning.
        long identityToken = clearCallingIdentity();
        try {
            if (mSyncManager == null) {
                pw.println("No SyncManager created!  (Disk full?)");
            } else {
                mSyncManager.dump(fd, pw);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            // The content service only throws security exceptions, so let's
            // log all others.
            if (!(e instanceof SecurityException)) {
                Log.e(TAG, "Content Service Crash", e);
            }
            throw e;
        }
    }

    /*package*/ ContentService(Context context, boolean factoryTest) {
        mContext = context;
        mFactoryTest = factoryTest;
        getSyncManager();
    }

    public void registerContentObserver(Uri uri, boolean notifyForDescendents,
            IContentObserver observer) {
        if (observer == null || uri == null) {
            throw new IllegalArgumentException("You must pass a valid uri and observer");
        }
        synchronized (mRootNode) {
            mRootNode.addObserver(uri, observer, notifyForDescendents);
            if (Config.LOGV) Log.v(TAG, "Registered observer " + observer + " at " + uri +
                    " with notifyForDescendents " + notifyForDescendents);
        }
    }

    public void unregisterContentObserver(IContentObserver observer) {
        if (observer == null) {
            throw new IllegalArgumentException("You must pass a valid observer");
        }
        synchronized (mRootNode) {
            mRootNode.removeObserver(observer);
            if (Config.LOGV) Log.v(TAG, "Unregistered observer " + observer);
        }
    }

    public void notifyChange(Uri uri, IContentObserver observer,
            boolean observerWantsSelfNotifications, boolean syncToNetwork) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Log.v(TAG, "Notifying update of " + uri + " from observer " + observer
                    + ", syncToNetwork " + syncToNetwork);
        }
        // This makes it so that future permission checks will be in the context of this
        // process rather than the caller's process. We will restore this before returning.
        long identityToken = clearCallingIdentity();
        try {
            ArrayList<ObserverCall> calls = new ArrayList<ObserverCall>();
            synchronized (mRootNode) {
                mRootNode.collectObservers(uri, 0, observer, observerWantsSelfNotifications,
                        calls);
            }
            final int numCalls = calls.size();
            for (int i=0; i<numCalls; i++) {
                ObserverCall oc = calls.get(i);
                try {
                    oc.mObserver.onChange(oc.mSelfNotify);
                    if (Log.isLoggable(TAG, Log.VERBOSE)) {
                        Log.v(TAG, "Notified " + oc.mObserver + " of " + "update at " + uri);
                    }
                } catch (RemoteException ex) {
                    synchronized (mRootNode) {
                        Log.w(TAG, "Found dead observer, removing");
                        IBinder binder = oc.mObserver.asBinder();
                        final ArrayList<ObserverNode.ObserverEntry> list
                                = oc.mNode.mObservers;
                        int numList = list.size();
                        for (int j=0; j<numList; j++) {
                            ObserverNode.ObserverEntry oe = list.get(j);
                            if (oe.observer.asBinder() == binder) {
                                list.remove(j);
                                j--;
                                numList--;
                            }
                        }
                    }
                }
            }
            if (syncToNetwork) {
                SyncManager syncManager = getSyncManager();
                if (syncManager != null) {
                    syncManager.scheduleLocalSync(null /* all accounts */, uri.getAuthority());
                }
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /**
     * Hide this class since it is not part of api,
     * but current unittest framework requires it to be public
     * @hide
     *
     */
    public static final class ObserverCall {
        final ObserverNode mNode;
        final IContentObserver mObserver;
        final boolean mSelfNotify;

        ObserverCall(ObserverNode node, IContentObserver observer,
                boolean selfNotify) {
            mNode = node;
            mObserver = observer;
            mSelfNotify = selfNotify;
        }
    }

    public void requestSync(Account account, String authority, Bundle extras) {
        ContentResolver.validateSyncExtrasBundle(extras);
        // This makes it so that future permission checks will be in the context of this
        // process rather than the caller's process. We will restore this before returning.
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                syncManager.scheduleSync(account, authority, extras, 0 /* no delay */,
                        false /* onlyThoseWithUnkownSyncableState */);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /**
     * Clear all scheduled sync operations that match the uri and cancel the active sync
     * if they match the authority and account, if they are present.
     * @param account filter the pending and active syncs to cancel using this account
     * @param authority filter the pending and active syncs to cancel using this authority
     */
    public void cancelSync(Account account, String authority) {
        // This makes it so that future permission checks will be in the context of this
        // process rather than the caller's process. We will restore this before returning.
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                syncManager.clearScheduledSyncOperations(account, authority);
                syncManager.cancelActiveSync(account, authority);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    /**
     * Get information about the SyncAdapters that are known to the system.
     * @return an array of SyncAdapters that have registered with the system
     */
    public SyncAdapterType[] getSyncAdapterTypes() {
        // This makes it so that future permission checks will be in the context of this
        // process rather than the caller's process. We will restore this before returning.
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            return syncManager.getSyncAdapterTypes();
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public boolean getSyncAutomatically(Account account, String providerName) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_SETTINGS,
                "no permission to read the sync settings");
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                return syncManager.getSyncStorageEngine().getSyncAutomatically(
                        account, providerName);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
        return false;
    }

    public void setSyncAutomatically(Account account, String providerName, boolean sync) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.WRITE_SYNC_SETTINGS,
                "no permission to write the sync settings");
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                syncManager.getSyncStorageEngine().setSyncAutomatically(
                        account, providerName, sync);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public int getIsSyncable(Account account, String providerName) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_SETTINGS,
                "no permission to read the sync settings");
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                return syncManager.getSyncStorageEngine().getIsSyncable(
                        account, providerName);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
        return -1;
    }

    public void setIsSyncable(Account account, String providerName, int syncable) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.WRITE_SYNC_SETTINGS,
                "no permission to write the sync settings");
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                syncManager.getSyncStorageEngine().setIsSyncable(
                        account, providerName, syncable);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public boolean getMasterSyncAutomatically() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_SETTINGS,
                "no permission to read the sync settings");
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                return syncManager.getSyncStorageEngine().getMasterSyncAutomatically();
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
        return false;
    }

    public void setMasterSyncAutomatically(boolean flag) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.WRITE_SYNC_SETTINGS,
                "no permission to write the sync settings");
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                syncManager.getSyncStorageEngine().setMasterSyncAutomatically(flag);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public boolean isSyncActive(Account account, String authority) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_STATS,
                "no permission to read the sync stats");
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                return syncManager.getSyncStorageEngine().isSyncActive(
                        account, authority);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
        return false;
    }

    public ActiveSyncInfo getActiveSync() {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_STATS,
                "no permission to read the sync stats");
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                return syncManager.getSyncStorageEngine().getActiveSync();
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
        return null;
    }

    public SyncStatusInfo getSyncStatus(Account account, String authority) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_STATS,
                "no permission to read the sync stats");
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                return syncManager.getSyncStorageEngine().getStatusByAccountAndAuthority(
                    account, authority);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
        return null;
    }

    public boolean isSyncPending(Account account, String authority) {
        mContext.enforceCallingOrSelfPermission(Manifest.permission.READ_SYNC_STATS,
                "no permission to read the sync stats");
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                return syncManager.getSyncStorageEngine().isSyncPending(account, authority);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
        return false;
    }

    public void addStatusChangeListener(int mask, ISyncStatusObserver callback) {
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                syncManager.getSyncStorageEngine().addStatusChangeListener(mask, callback);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public void removeStatusChangeListener(ISyncStatusObserver callback) {
        long identityToken = clearCallingIdentity();
        try {
            SyncManager syncManager = getSyncManager();
            if (syncManager != null) {
                syncManager.getSyncStorageEngine().removeStatusChangeListener(callback);
            }
        } finally {
            restoreCallingIdentity(identityToken);
        }
    }

    public static IContentService main(Context context, boolean factoryTest) {
        ContentService service = new ContentService(context, factoryTest);
        ServiceManager.addService(ContentResolver.CONTENT_SERVICE_NAME, service);
        return service;
    }

    /**
     * Hide this class since it is not part of api,
     * but current unittest framework requires it to be public
     * @hide
     */
    public static final class ObserverNode {
        private class ObserverEntry implements IBinder.DeathRecipient {
            public IContentObserver observer;
            public boolean notifyForDescendents;

            public ObserverEntry(IContentObserver o, boolean n) {
                observer = o;
                notifyForDescendents = n;
                try {
                    observer.asBinder().linkToDeath(this, 0);
                } catch (RemoteException e) {
                    binderDied();
                }
            }

            public void binderDied() {
                removeObserver(observer);
            }
        }

        public static final int INSERT_TYPE = 0;
        public static final int UPDATE_TYPE = 1;
        public static final int DELETE_TYPE = 2;

        private String mName;
        private ArrayList<ObserverNode> mChildren = new ArrayList<ObserverNode>();
        private ArrayList<ObserverEntry> mObservers = new ArrayList<ObserverEntry>();

        public ObserverNode(String name) {
            mName = name;
        }

        private String getUriSegment(Uri uri, int index) {
            if (uri != null) {
                if (index == 0) {
                    return uri.getAuthority();
                } else {
                    return uri.getPathSegments().get(index - 1);
                }
            } else {
                return null;
            }
        }

        private int countUriSegments(Uri uri) {
            if (uri == null) {
                return 0;
            }
            return uri.getPathSegments().size() + 1;
        }

        public void addObserver(Uri uri, IContentObserver observer, boolean notifyForDescendents) {
            addObserver(uri, 0, observer, notifyForDescendents);
        }

        private void addObserver(Uri uri, int index, IContentObserver observer,
                boolean notifyForDescendents) {

            // If this is the leaf node add the observer
            if (index == countUriSegments(uri)) {
                mObservers.add(new ObserverEntry(observer, notifyForDescendents));
                return;
            }

            // Look to see if the proper child already exists
            String segment = getUriSegment(uri, index);
            if (segment == null) {
                throw new IllegalArgumentException("Invalid Uri (" + uri + ") used for observer");
            }
            int N = mChildren.size();
            for (int i = 0; i < N; i++) {
                ObserverNode node = mChildren.get(i);
                if (node.mName.equals(segment)) {
                    node.addObserver(uri, index + 1, observer, notifyForDescendents);
                    return;
                }
            }

            // No child found, create one
            ObserverNode node = new ObserverNode(segment);
            mChildren.add(node);
            node.addObserver(uri, index + 1, observer, notifyForDescendents);
        }

        public boolean removeObserver(IContentObserver observer) {
            int size = mChildren.size();
            for (int i = 0; i < size; i++) {
                boolean empty = mChildren.get(i).removeObserver(observer);
                if (empty) {
                    mChildren.remove(i);
                    i--;
                    size--;
                }
            }

            IBinder observerBinder = observer.asBinder();
            size = mObservers.size();
            for (int i = 0; i < size; i++) {
                ObserverEntry entry = mObservers.get(i);
                if (entry.observer.asBinder() == observerBinder) {
                    mObservers.remove(i);
                    // We no longer need to listen for death notifications. Remove it.
                    observerBinder.unlinkToDeath(entry, 0);
                    break;
                }
            }

            if (mChildren.size() == 0 && mObservers.size() == 0) {
                return true;
            }
            return false;
        }

        private void collectMyObservers(Uri uri,
                boolean leaf, IContentObserver observer, boolean selfNotify,
                ArrayList<ObserverCall> calls)
        {
            int N = mObservers.size();
            IBinder observerBinder = observer == null ? null : observer.asBinder();
            for (int i = 0; i < N; i++) {
                ObserverEntry entry = mObservers.get(i);

                // Don't notify the observer if it sent the notification and isn't interesed
                // in self notifications
                if (entry.observer.asBinder() == observerBinder && !selfNotify) {
                    continue;
                }

                // Make sure the observer is interested in the notification
                if (leaf || (!leaf && entry.notifyForDescendents)) {
                    calls.add(new ObserverCall(this, entry.observer, selfNotify));
                }
            }
        }

        public void collectObservers(Uri uri, int index, IContentObserver observer,
                boolean selfNotify, ArrayList<ObserverCall> calls) {
            String segment = null;
            int segmentCount = countUriSegments(uri);
            if (index >= segmentCount) {
                // This is the leaf node, notify all observers
                collectMyObservers(uri, true, observer, selfNotify, calls);
            } else if (index < segmentCount){
                segment = getUriSegment(uri, index);
                // Notify any observers at this level who are interested in descendents
                collectMyObservers(uri, false, observer, selfNotify, calls);
            }

            int N = mChildren.size();
            for (int i = 0; i < N; i++) {
                ObserverNode node = mChildren.get(i);
                if (segment == null || node.mName.equals(segment)) {
                    // We found the child,
                    node.collectObservers(uri, index + 1, observer, selfNotify, calls);
                    if (segment != null) {
                        break;
                    }
                }
            }
        }
    }
}
