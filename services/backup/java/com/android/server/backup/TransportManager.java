/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup;

import android.app.backup.BackupManager;
import android.app.backup.SelectBackupTransportCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.backup.IBackupTransport;
import com.android.server.EventLogTags;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Handles in-memory bookkeeping of all BackupTransport objects.
 */
class TransportManager {

    private static final String TAG = "BackupTransportManager";

    private static final String SERVICE_ACTION_TRANSPORT_HOST = "android.backup.TRANSPORT_HOST";

    private final Intent mTransportServiceIntent = new Intent(SERVICE_ACTION_TRANSPORT_HOST);
    private final Context mContext;
    private final PackageManager mPackageManager;
    private final Set<ComponentName> mTransportWhitelist;

    /**
     * This listener is called after we bind to any transport. If it returns true, this is a valid
     * transport.
     */
    private final TransportBoundListener mTransportBoundListener;

    private String mCurrentTransportName;

    /** Lock on this before accessing mValidTransports and mBoundTransports. */
    private final Object mTransportLock = new Object();

    /**
     * We have detected these transports on the device. Unless in exceptional cases, we are also
     * bound to all of these.
     */
    @GuardedBy("mTransportLock")
    private final Map<ComponentName, TransportConnection> mValidTransports = new ArrayMap<>();

    /** We are currently bound to these transports. */
    @GuardedBy("mTransportLock")
    private final Map<String, ComponentName> mBoundTransports = new ArrayMap<>();

    TransportManager(Context context, Set<ComponentName> whitelist, String defaultTransport,
            TransportBoundListener listener) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mTransportWhitelist = whitelist;
        mCurrentTransportName = defaultTransport;
        mTransportBoundListener = listener;
    }

    void onPackageAdded(String packageName) {
        // New package added. Bind to all transports it contains.
        synchronized (mTransportLock) {
            log_verbose("Package added. Binding to all transports. " + packageName);
            bindToAllInternal(packageName, null /* all components */);
        }
    }

    void onPackageRemoved(String packageName) {
        // Package removed. Remove all its transports from our list. These transports have already
        // been removed from mBoundTransports because onServiceDisconnected would already been
        // called on TransportConnection objects.
        synchronized (mTransportLock) {
            for (ComponentName transport : mValidTransports.keySet()) {
                if (transport.getPackageName().equals(packageName)) {
                    TransportConnection removed = mValidTransports.remove(transport);
                    if (removed != null) {
                        mContext.unbindService(removed);
                        log_verbose("Package removed, Removing transport: " +
                                transport.flattenToShortString());
                    }
                }
            }
        }
    }

    void onPackageChanged(String packageName, String[] components) {
        synchronized (mTransportLock) {
            // Remove all changed components from mValidTransports. We'll bind to them again
            // and re-add them if still valid.
            for (String component : components) {
                ComponentName componentName = new ComponentName(packageName, component);
                TransportConnection removed = mValidTransports.remove(componentName);
                if (removed != null) {
                    mContext.unbindService(removed);
                    log_verbose("Package changed. Removing transport: " +
                            componentName.flattenToShortString());
                }
            }
            bindToAllInternal(packageName, components);
        }
    }

    IBackupTransport getTransportBinder(String transportName) {
        synchronized (mTransportLock) {
            ComponentName component = mBoundTransports.get(transportName);
            if (component == null) {
                Slog.w(TAG, "Transport " + transportName + " not bound.");
                return null;
            }
            TransportConnection conn = mValidTransports.get(component);
            if (conn == null) {
                Slog.w(TAG, "Transport " + transportName + " not valid.");
                return null;
            }
            return conn.getBinder();
        }
    }

    IBackupTransport getCurrentTransportBinder() {
        return getTransportBinder(mCurrentTransportName);
    }

    String getTransportName(IBackupTransport binder) {
        synchronized (mTransportLock) {
            for (TransportConnection conn : mValidTransports.values()) {
                if (conn.getBinder() == binder) {
                    return conn.getName();
                }
            }
        }
        return null;
    }

    String[] getBoundTransportNames() {
        synchronized (mTransportLock) {
            return mBoundTransports.keySet().toArray(new String[0]);
        }
    }

    ComponentName[] getAllTransportCompenents() {
        synchronized (mTransportLock) {
            return mValidTransports.keySet().toArray(new ComponentName[0]);
        }
    }

    String getCurrentTransportName() {
        return mCurrentTransportName;
    }

    Set<ComponentName> getTransportWhitelist() {
        return mTransportWhitelist;
    }

    String selectTransport(String transport) {
        synchronized (mTransportLock) {
            String prevTransport = mCurrentTransportName;
            mCurrentTransportName = transport;
            return prevTransport;
        }
    }

    void ensureTransportReady(ComponentName transportComponent, SelectBackupTransportCallback listener) {
        synchronized (mTransportLock) {
            TransportConnection conn = mValidTransports.get(transportComponent);
            if (conn == null) {
                listener.onFailure(BackupManager.ERROR_TRANSPORT_UNAVAILABLE);
                return;
            }
            // Transport can be unbound if the process hosting it crashed.
            conn.bindIfUnbound();
            conn.addListener(listener);
        }
    }

    void registerAllTransports() {
        bindToAllInternal(null /* all packages */, null /* all components */);
    }

    /**
     * Bind to all transports belonging to the given package and the given component list.
     * null acts a wildcard.
     *
     * If packageName is null, bind to all transports in all packages.
     * If components is null, bind to all transports in the given package.
     */
    private void bindToAllInternal(String packageName, String[] components) {
        PackageInfo pkgInfo = null;
        if (packageName != null) {
            try {
                pkgInfo = mPackageManager.getPackageInfo(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.w(TAG, "Package not found: " + packageName);
                return;
            }
        }

        Intent intent = new Intent(mTransportServiceIntent);
        if (packageName != null) {
            intent.setPackage(packageName);
        }

        List<ResolveInfo> hosts = mPackageManager.queryIntentServicesAsUser(
                intent, 0, UserHandle.USER_SYSTEM);
        if (hosts != null) {
            for (ResolveInfo host : hosts) {
                final ServiceInfo info = host.serviceInfo;
                boolean shouldBind = false;
                if (components != null && packageName != null) {
                    for (String component : components) {
                        ComponentName cn = new ComponentName(pkgInfo.packageName, component);
                        if (info.getComponentName().equals(cn)) {
                            shouldBind = true;
                            break;
                        }
                    }
                } else {
                    shouldBind = true;
                }
                if (shouldBind && isTransportTrusted(info.getComponentName())) {
                    tryBindTransport(info);
                }
            }
        }
    }

    /** Transport has to be whitelisted and privileged. */
    private boolean isTransportTrusted(ComponentName transport) {
        if (!mTransportWhitelist.contains(transport)) {
            Slog.w(TAG, "BackupTransport " + transport.flattenToShortString() +
                    " not whitelisted.");
            return false;
        }
        try {
            PackageInfo packInfo = mPackageManager.getPackageInfo(transport.getPackageName(), 0);
            if ((packInfo.applicationInfo.privateFlags & ApplicationInfo.PRIVATE_FLAG_PRIVILEGED)
                    == 0) {
                Slog.w(TAG, "Transport package " + transport.getPackageName() + " not privileged");
                return false;
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Package not found.", e);
            return false;
        }
        return true;
    }

    private void tryBindTransport(ServiceInfo transport) {
        Slog.d(TAG, "Binding to transport: " + transport.getComponentName().flattenToShortString());
        // TODO: b/22388012 (Multi user backup and restore)
        TransportConnection connection = new TransportConnection(transport.getComponentName());
        if (bindToTransport(transport.getComponentName(), connection)) {
            synchronized (mTransportLock) {
                mValidTransports.put(transport.getComponentName(), connection);
            }
        } else {
            Slog.w(TAG, "Couldn't bind to transport " + transport.getComponentName());
        }
    }

    private boolean bindToTransport(ComponentName componentName, ServiceConnection connection) {
        Intent intent = new Intent(mTransportServiceIntent)
                .setComponent(componentName);
        return mContext.bindServiceAsUser(intent, connection, Context.BIND_AUTO_CREATE,
                UserHandle.SYSTEM);
    }

    private class TransportConnection implements ServiceConnection {

        // Hold mTransportsLock to access these fields so as to provide a consistent view of them.
        private IBackupTransport mBinder;
        private final List<SelectBackupTransportCallback> mListeners = new ArrayList<>();
        private String mTransportName;

        private final ComponentName mTransportComponent;

        private TransportConnection(ComponentName transportComponent) {
            mTransportComponent = transportComponent;
        }

        @Override
        public void onServiceConnected(ComponentName component, IBinder binder) {
            synchronized (mTransportLock) {
                mBinder = IBackupTransport.Stub.asInterface(binder);
                boolean success = false;

                EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE,
                    component.flattenToShortString(), 1);

                try {
                    mTransportName = mBinder.name();
                    // BackupManager requests some fields from the transport. If they are
                    // invalid, throw away this transport.
                    success = mTransportBoundListener.onTransportBound(mBinder);
                } catch (RemoteException e) {
                    success = false;
                    Slog.e(TAG, "Couldn't get transport name.", e);
                } finally {
                    if (success) {
                        Slog.d(TAG, "Bound to transport: " + component.flattenToShortString());
                        mBoundTransports.put(mTransportName, component);
                        for (SelectBackupTransportCallback listener : mListeners) {
                            listener.onSuccess(mTransportName);
                        }
                    } else {
                        Slog.w(TAG, "Bound to transport " + component.flattenToShortString() +
                                " but it is invalid");
                        EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE,
                                component.flattenToShortString(), 0);
                        mContext.unbindService(this);
                        mValidTransports.remove(component);
                        mBinder = null;
                        for (SelectBackupTransportCallback listener : mListeners) {
                            listener.onFailure(BackupManager.ERROR_TRANSPORT_INVALID);
                        }
                    }
                    mListeners.clear();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName component) {
            synchronized (mTransportLock) {
                mBinder = null;
                mBoundTransports.remove(mTransportName);
            }
            EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE,
                    component.flattenToShortString(), 0);
            Slog.w(TAG, "Disconnected from transport " + component.flattenToShortString());
        }

        private IBackupTransport getBinder() {
            synchronized (mTransportLock) {
                return mBinder;
            }
        }

        private String getName() {
            synchronized (mTransportLock) {
                return mTransportName;
            }
        }

        private void bindIfUnbound() {
            synchronized (mTransportLock) {
                if (mBinder == null) {
                    Slog.d(TAG,
                            "Rebinding to transport " + mTransportComponent.flattenToShortString());
                    bindToTransport(mTransportComponent, this);
                }
            }
        }

        private void addListener(SelectBackupTransportCallback listener) {
            synchronized (mTransportLock) {
                if (mBinder == null) {
                    // We are waiting for bind to complete. If mBinder is set to null after the bind
                    // is complete due to transport being invalid, we won't find 'this' connection
                    // object in mValidTransports list and this function can't be called.
                    mListeners.add(listener);
                } else {
                    listener.onSuccess(mTransportName);
                }
            }
        }
    }

    interface TransportBoundListener {
        /** Should return true if this is a valid transport. */
        boolean onTransportBound(IBackupTransport binder);
    }

    private static void log_verbose(String message) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, message);
        }
    }
}
