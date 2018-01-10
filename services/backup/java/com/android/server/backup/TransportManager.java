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

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;

import android.annotation.Nullable;
import android.app.backup.BackupManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.EventLog;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.backup.IBackupTransport;
import com.android.server.EventLogTags;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.transport.TransportClientManager;
import com.android.server.backup.transport.TransportConnectionListener;
import com.android.server.backup.transport.TransportNotAvailableException;
import com.android.server.backup.transport.TransportNotRegisteredException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Handles in-memory bookkeeping of all BackupTransport objects.
 */
public class TransportManager {

    private static final String TAG = "BackupTransportManager";

    @VisibleForTesting
    public static final String SERVICE_ACTION_TRANSPORT_HOST = "android.backup.TRANSPORT_HOST";

    private static final long REBINDING_TIMEOUT_UNPROVISIONED_MS = 30 * 1000; // 30 sec
    private static final long REBINDING_TIMEOUT_PROVISIONED_MS = 5 * 60 * 1000; // 5 mins
    private static final int REBINDING_TIMEOUT_MSG = 1;

    private final Intent mTransportServiceIntent = new Intent(SERVICE_ACTION_TRANSPORT_HOST);
    private final Context mContext;
    private final PackageManager mPackageManager;
    private final Set<ComponentName> mTransportWhitelist;
    private final Handler mHandler;
    private final TransportClientManager mTransportClientManager;

    /**
     * This listener is called after we bind to any transport. If it returns true, this is a valid
     * transport.
     */
    private TransportBoundListener mTransportBoundListener;

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

    /** @see #getEligibleTransportComponents() */
    @GuardedBy("mTransportLock")
    private final Set<ComponentName> mEligibleTransports = new ArraySet<>();

    /** @see #getRegisteredTransportNames() */
    @GuardedBy("mTransportLock")
    private final Map<ComponentName, TransportDescription> mRegisteredTransportsDescriptionMap =
            new ArrayMap<>();

    @GuardedBy("mTransportLock")
    private volatile String mCurrentTransportName;

    TransportManager(
            Context context,
            Set<ComponentName> whitelist,
            String defaultTransport,
            TransportBoundListener listener,
            Looper looper) {
        this(context, whitelist, defaultTransport, looper);
        mTransportBoundListener = listener;
    }

    TransportManager(
            Context context,
            Set<ComponentName> whitelist,
            String defaultTransport,
            Looper looper) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        if (whitelist != null) {
            mTransportWhitelist = whitelist;
        } else {
            mTransportWhitelist = new ArraySet<>();
        }
        mCurrentTransportName = defaultTransport;
        mHandler = new RebindOnTimeoutHandler(looper);
        mTransportClientManager = new TransportClientManager(context);
    }

    public void setTransportBoundListener(TransportBoundListener transportBoundListener) {
        mTransportBoundListener = transportBoundListener;
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
            Iterator<Map.Entry<ComponentName, TransportConnection>> iter =
                    mValidTransports.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<ComponentName, TransportConnection> validTransport = iter.next();
                ComponentName componentName = validTransport.getKey();
                if (componentName.getPackageName().equals(packageName)) {
                    TransportConnection transportConnection = validTransport.getValue();
                    iter.remove();
                    if (transportConnection != null) {
                        mContext.unbindService(transportConnection);
                        log_verbose("Package removed, removing transport: "
                                + componentName.flattenToShortString());
                    }
                }
            }
            removeTransportsIfLocked(
                    componentName -> packageName.equals(componentName.getPackageName()));
        }
    }

    void onPackageChanged(String packageName, String[] components) {
        synchronized (mTransportLock) {
            // Remove all changed components from mValidTransports. We'll bind to them again
            // and re-add them if still valid.
            Set<ComponentName> transportsToBeRemoved = new ArraySet<>();
            for (String component : components) {
                ComponentName componentName = new ComponentName(packageName, component);
                transportsToBeRemoved.add(componentName);
                TransportConnection removed = mValidTransports.remove(componentName);
                if (removed != null) {
                    mContext.unbindService(removed);
                    log_verbose("Package changed. Removing transport: " +
                            componentName.flattenToShortString());
                }
            }
            removeTransportsIfLocked(transportsToBeRemoved::contains);
            bindToAllInternal(packageName, components);
        }
    }

    @GuardedBy("mTransportLock")
    private void removeTransportsIfLocked(Predicate<ComponentName> filter) {
        mEligibleTransports.removeIf(filter);
        mRegisteredTransportsDescriptionMap.keySet().removeIf(filter);
    }

    public IBackupTransport getTransportBinder(String transportName) {
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

    public IBackupTransport getCurrentTransportBinder() {
        return getTransportBinder(mCurrentTransportName);
    }

    /**
     * Returns the transport name associated with {@code transportComponent}.
     * @throws TransportNotRegisteredException if the transport is not registered.
     */
    public String getTransportName(ComponentName transportComponent)
            throws TransportNotRegisteredException {
        synchronized (mTransportLock) {
            return getRegisteredTransportDescriptionOrThrowLocked(transportComponent).name;
        }
    }

    /**
     * Retrieve the configuration intent of {@code transportName}.
     * @throws TransportNotRegisteredException if the transport is not registered.
     */
    @Nullable
    public Intent getTransportConfigurationIntent(String transportName)
            throws TransportNotRegisteredException {
        synchronized (mTransportLock) {
            return getRegisteredTransportDescriptionOrThrowLocked(transportName)
                    .configurationIntent;
        }
    }

    /**
     * Retrieve the data management intent of {@code transportName}.
     * @throws TransportNotRegisteredException if the transport is not registered.
     */
    @Nullable
    public Intent getTransportDataManagementIntent(String transportName)
            throws TransportNotRegisteredException {
        synchronized (mTransportLock) {
            return getRegisteredTransportDescriptionOrThrowLocked(transportName)
                    .dataManagementIntent;
        }
    }

    /**
     * Retrieve the current destination string of {@code transportName}.
     * @throws TransportNotRegisteredException if the transport is not registered.
     */
    public String getTransportCurrentDestinationString(String transportName)
            throws TransportNotRegisteredException {
        synchronized (mTransportLock) {
            return getRegisteredTransportDescriptionOrThrowLocked(transportName)
                    .currentDestinationString;
        }
    }

    /**
     * Retrieve the data management label of {@code transportName}.
     * @throws TransportNotRegisteredException if the transport is not registered.
     */
    @Nullable
    public String getTransportDataManagementLabel(String transportName)
            throws TransportNotRegisteredException {
        synchronized (mTransportLock) {
            return getRegisteredTransportDescriptionOrThrowLocked(transportName)
                    .dataManagementLabel;
        }
    }

    /**
     * Retrieve the transport dir name of {@code transportName}.
     * @throws TransportNotRegisteredException if the transport is not registered.
     */
    public String getTransportDirName(String transportName)
            throws TransportNotRegisteredException {
        synchronized (mTransportLock) {
            return getRegisteredTransportDescriptionOrThrowLocked(transportName)
                    .transportDirName;
        }
    }

    /**
     * Retrieve the transport dir name of {@code transportComponent}.
     * @throws TransportNotRegisteredException if the transport is not registered.
     */
    public String getTransportDirName(ComponentName transportComponent)
            throws TransportNotRegisteredException {
        synchronized (mTransportLock) {
            return getRegisteredTransportDescriptionOrThrowLocked(transportComponent)
                    .transportDirName;
        }
    }

    /**
     * Execute {@code transportConsumer} for each registered transport passing the transport name.
     * This is called with an internal lock held, ensuring that the transport will remain registered
     * while {@code transportConsumer} is being executed. Don't do heavy operations in
     * {@code transportConsumer}.
     */
    public void forEachRegisteredTransport(Consumer<String> transportConsumer) {
        synchronized (mTransportLock) {
            for (TransportDescription transportDescription
                    : mRegisteredTransportsDescriptionMap.values()) {
                transportConsumer.accept(transportDescription.name);
            }
        }
    }

    public String getTransportName(IBackupTransport binder) {
        synchronized (mTransportLock) {
            for (TransportConnection conn : mValidTransports.values()) {
                if (conn.getBinder() == binder) {
                    return conn.getName();
                }
            }
        }
        return null;
    }

    @GuardedBy("mTransportLock")
    @Nullable
    private ComponentName getRegisteredTransportComponentLocked(String transportName) {
        Map.Entry<ComponentName, TransportDescription> entry =
                getRegisteredTransportEntryLocked(transportName);
        return (entry == null) ? null : entry.getKey();
    }

    @GuardedBy("mTransportLock")
    @Nullable
    private TransportDescription getRegisteredTransportDescriptionLocked(String transportName) {
        Map.Entry<ComponentName, TransportDescription> entry =
                getRegisteredTransportEntryLocked(transportName);
        return (entry == null) ? null : entry.getValue();
    }

    @GuardedBy("mTransportLock")
    private TransportDescription getRegisteredTransportDescriptionOrThrowLocked(
            String transportName) throws TransportNotRegisteredException {
        TransportDescription description = getRegisteredTransportDescriptionLocked(transportName);
        if (description == null) {
            throw new TransportNotRegisteredException(transportName);
        }
        return description;
    }

    @GuardedBy("mTransportLock")
    @Nullable
    private Map.Entry<ComponentName, TransportDescription> getRegisteredTransportEntryLocked(
            String transportName) {
        for (Map.Entry<ComponentName, TransportDescription> entry
                : mRegisteredTransportsDescriptionMap.entrySet()) {
            TransportDescription description = entry.getValue();
            if (transportName.equals(description.name)) {
                return entry;
            }
        }
        return null;
    }

    @GuardedBy("mTransportLock")
    private TransportDescription getRegisteredTransportDescriptionOrThrowLocked(
            ComponentName transportComponent) throws TransportNotRegisteredException {
        TransportDescription description =
                mRegisteredTransportsDescriptionMap.get(transportComponent);
        if (description == null) {
            throw new TransportNotRegisteredException(transportComponent);
        }
        return description;
    }

    @Nullable
    public TransportClient getTransportClient(String transportName, String caller) {
        try {
            return getTransportClientOrThrow(transportName, caller);
        } catch (TransportNotRegisteredException e) {
            Slog.w(TAG, "Transport " + transportName + " not registered");
            return null;
        }
    }

    public TransportClient getTransportClientOrThrow(String transportName, String caller)
            throws TransportNotRegisteredException {
        synchronized (mTransportLock) {
            ComponentName component = getRegisteredTransportComponentLocked(transportName);
            if (component == null) {
                throw new TransportNotRegisteredException(transportName);
            }
            return mTransportClientManager.getTransportClient(component, caller);
        }
    }

    public boolean isTransportRegistered(String transportName) {
        synchronized (mTransportLock) {
            return getRegisteredTransportEntryLocked(transportName) != null;
        }
    }

    /**
     * Returns a {@link TransportClient} for the current transport or null if not found.
     *
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link TransportClient#connectAsync(TransportConnectionListener, String)} for more
     *     details.
     * @return A {@link TransportClient} or null if not found.
     */
    @Nullable
    public TransportClient getCurrentTransportClient(String caller) {
        synchronized (mTransportLock) {
            return getTransportClient(mCurrentTransportName, caller);
        }
    }

    /**
     * Returns a {@link TransportClient} for the current transport or throws if not registered.
     *
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link TransportClient#connectAsync(TransportConnectionListener, String)} for more
     *     details.
     * @return A {@link TransportClient}.
     * @throws TransportNotRegisteredException if the transport is not registered.
     */
    public TransportClient getCurrentTransportClientOrThrow(String caller)
            throws TransportNotRegisteredException {
        synchronized (mTransportLock) {
            return getTransportClientOrThrow(mCurrentTransportName, caller);
        }
    }

    /**
     * Disposes of the {@link TransportClient}.
     *
     * @param transportClient The {@link TransportClient} to be disposed of.
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link TransportClient#connectAsync(TransportConnectionListener, String)} for more
     *     details.
     */
    public void disposeOfTransportClient(TransportClient transportClient, String caller) {
        mTransportClientManager.disposeOfTransportClient(transportClient, caller);
    }

    String[] getBoundTransportNames() {
        synchronized (mTransportLock) {
            return mBoundTransports.keySet().toArray(new String[mBoundTransports.size()]);
        }
    }

    ComponentName[] getAllTransportComponents() {
        synchronized (mTransportLock) {
            return mValidTransports.keySet().toArray(new ComponentName[mValidTransports.size()]);
        }
    }

    /**
     * An *eligible* transport is a service component that satisfies intent with action
     * android.backup.TRANSPORT_HOST and returns true for
     * {@link #isTransportTrusted(ComponentName)}. It may be registered or not registered.
     * This method returns the {@link ComponentName}s of those transports.
     */
    ComponentName[] getEligibleTransportComponents() {
        synchronized (mTransportLock) {
            return mEligibleTransports.toArray(new ComponentName[mEligibleTransports.size()]);
        }
    }

    Set<ComponentName> getTransportWhitelist() {
        return mTransportWhitelist;
    }

    /**
     * A *registered* transport is an eligible transport that has been successfully connected and
     * that returned true for method
     * {@link TransportBoundListener#onTransportBound(IBackupTransport)} of TransportBoundListener
     * provided in the constructor. This method returns the names of the registered transports.
     */
    String[] getRegisteredTransportNames() {
        synchronized (mTransportLock) {
            return mRegisteredTransportsDescriptionMap.values().stream()
                    .map(transportDescription -> transportDescription.name)
                    .toArray(String[]::new);
        }
    }

    /**
     * Updates given values for the transport already registered and identified with
     * {@param transportComponent}. If the transport is not registered it will log and return.
     */
    public void updateTransportAttributes(
            ComponentName transportComponent,
            String name,
            @Nullable Intent configurationIntent,
            String currentDestinationString,
            @Nullable Intent dataManagementIntent,
            @Nullable String dataManagementLabel) {
        synchronized (mTransportLock) {
            TransportDescription description =
                    mRegisteredTransportsDescriptionMap.get(transportComponent);
            if (description == null) {
                Slog.e(TAG, "Transport " + name + " not registered tried to change description");
                return;
            }
            description.name = name;
            description.configurationIntent = configurationIntent;
            description.currentDestinationString = currentDestinationString;
            description.dataManagementIntent = dataManagementIntent;
            description.dataManagementLabel = dataManagementLabel;
            Slog.d(TAG, "Transport " + name + " updated its attributes");
        }
    }

    @Nullable
    String getCurrentTransportName() {
        return mCurrentTransportName;
    }

    // This is for mocking, Mockito can't mock if package-protected and in the same package but
    // different class loaders. Checked with the debugger and class loaders are different
    // See https://github.com/mockito/mockito/issues/796
    @VisibleForTesting(visibility = PACKAGE)
    public void registerAllTransports() {
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
                final ComponentName infoComponentName = getComponentName(host.serviceInfo);
                boolean shouldBind = false;
                if (components != null && packageName != null) {
                    for (String component : components) {
                        ComponentName cn = new ComponentName(pkgInfo.packageName, component);
                        if (infoComponentName.equals(cn)) {
                            shouldBind = true;
                            break;
                        }
                    }
                } else {
                    shouldBind = true;
                }
                if (shouldBind && isTransportTrusted(infoComponentName)) {
                    tryBindTransport(infoComponentName);
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

    private void tryBindTransport(ComponentName transportComponentName) {
        Slog.d(TAG, "Binding to transport: " + transportComponentName.flattenToShortString());
        // TODO: b/22388012 (Multi user backup and restore)
        TransportConnection connection = new TransportConnection(transportComponentName);
        synchronized (mTransportLock) {
            mEligibleTransports.add(transportComponentName);
        }
        if (bindToTransport(transportComponentName, connection)) {
            synchronized (mTransportLock) {
                mValidTransports.put(transportComponentName, connection);
            }
        } else {
            Slog.w(TAG, "Couldn't bind to transport " + transportComponentName);
        }
    }

    private boolean bindToTransport(ComponentName componentName, ServiceConnection connection) {
        Intent intent = new Intent(mTransportServiceIntent)
                .setComponent(componentName);
        return mContext.bindServiceAsUser(intent, connection, Context.BIND_AUTO_CREATE,
                createSystemUserHandle());
    }

    String selectTransport(String transportName) {
        synchronized (mTransportLock) {
            String prevTransport = mCurrentTransportName;
            mCurrentTransportName = transportName;
            return prevTransport;
        }
    }

    /**
     * Tries to register the transport if not registered. If successful also selects the transport.
     *
     * @param transportComponent Host of the transport.
     * @return One of {@link BackupManager#SUCCESS}, {@link BackupManager#ERROR_TRANSPORT_INVALID}
     *     or {@link BackupManager#ERROR_TRANSPORT_UNAVAILABLE}.
     */
    public int registerAndSelectTransport(ComponentName transportComponent) {
        synchronized (mTransportLock) {
            if (!mRegisteredTransportsDescriptionMap.containsKey(transportComponent)) {
                int result = registerTransport(transportComponent);
                if (result != BackupManager.SUCCESS) {
                    return result;
                }
            }

            try {
                selectTransport(getTransportName(transportComponent));
                return BackupManager.SUCCESS;
            } catch (TransportNotRegisteredException e) {
                // Shouldn't happen because we are holding the lock
                Slog.wtf(TAG, "Transport unexpectedly not registered");
                return BackupManager.ERROR_TRANSPORT_UNAVAILABLE;
            }
        }
    }

    /**
     * Tries to register transport represented by {@code transportComponent}.
     *
     * @param transportComponent Host of the transport that we want to register.
     * @return One of {@link BackupManager#SUCCESS}, {@link BackupManager#ERROR_TRANSPORT_INVALID}
     *     or {@link BackupManager#ERROR_TRANSPORT_UNAVAILABLE}.
     */
    private int registerTransport(ComponentName transportComponent) {
        String transportString = transportComponent.flattenToShortString();

        String callerLogString = "TransportManager.registerTransport()";
        TransportClient transportClient =
                mTransportClientManager.getTransportClient(transportComponent, callerLogString);

        final IBackupTransport transport;
        try {
            transport = transportClient.connectOrThrow(callerLogString);
        } catch (TransportNotAvailableException e) {
            Slog.e(TAG, "Couldn't connect to transport " + transportString + " for registration");
            mTransportClientManager.disposeOfTransportClient(transportClient, callerLogString);
            return BackupManager.ERROR_TRANSPORT_UNAVAILABLE;
        }

        EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE, transportString, 1);

        int result;
        if (isTransportValid(transport)) {
            try {
                registerTransport(transportComponent, transport);
                // If registerTransport() hasn't thrown...
                result = BackupManager.SUCCESS;
            } catch (RemoteException e) {
                Slog.e(TAG, "Transport " + transportString + " died while registering");
                result = BackupManager.ERROR_TRANSPORT_UNAVAILABLE;
            }
        } else {
            Slog.w(TAG, "Can't register invalid transport " + transportString);
            result = BackupManager.ERROR_TRANSPORT_INVALID;
        }

        mTransportClientManager.disposeOfTransportClient(transportClient, callerLogString);
        if (result == BackupManager.SUCCESS) {
            Slog.d(TAG, "Transport " + transportString + " registered");
        } else {
            EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE, transportString, 0);
        }
        return result;
    }

    /** If {@link RemoteException} is thrown the transport is guaranteed to not be registered. */
    private void registerTransport(ComponentName transportComponent, IBackupTransport transport)
            throws RemoteException {
        synchronized (mTransportLock) {
            String name = transport.name();
            TransportDescription description = new TransportDescription(
                    name,
                    transport.transportDirName(),
                    transport.configurationIntent(),
                    transport.currentDestinationString(),
                    transport.dataManagementIntent(),
                    transport.dataManagementLabel());
            mRegisteredTransportsDescriptionMap.put(transportComponent, description);
        }
    }

    private boolean isTransportValid(IBackupTransport transport) {
        if (mTransportBoundListener == null) {
            Slog.w(TAG, "setTransportBoundListener() not called, assuming transport invalid");
            return false;
        }
        return mTransportBoundListener.onTransportBound(transport);
    }

    private class TransportConnection implements ServiceConnection {

        // Hold mTransportLock to access these fields so as to provide a consistent view of them.
        private volatile IBackupTransport mBinder;
        private volatile String mTransportName;

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
                    if (isTransportValid(mBinder)) {
                        // We're now using the always-bound connection to do the registration but
                        // when we remove the always-bound code this will be in the first binding
                        // TODO: Move registration to first binding
                        registerTransport(component, mBinder);
                        // If registerTransport() hasn't thrown...
                        success = true;
                    }
                } catch (RemoteException e) {
                    success = false;
                    Slog.e(TAG, "Couldn't get transport name.", e);
                } finally {
                    // we need to intern() the String of the component, so that we can use it with
                    // Handler's removeMessages(), which uses == operator to compare the tokens
                    String componentShortString = component.flattenToShortString().intern();
                    if (success) {
                        Slog.d(TAG, "Bound to transport: " + componentShortString);
                        mBoundTransports.put(mTransportName, component);
                        // cancel rebinding on timeout for this component as we've already connected
                        mHandler.removeMessages(REBINDING_TIMEOUT_MSG, componentShortString);
                    } else {
                        Slog.w(TAG, "Bound to transport " + componentShortString +
                                " but it is invalid");
                        EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE,
                                componentShortString, 0);
                        mContext.unbindService(this);
                        mValidTransports.remove(component);
                        mEligibleTransports.remove(component);
                        mBinder = null;
                    }
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName component) {
            synchronized (mTransportLock) {
                mBinder = null;
                mBoundTransports.remove(mTransportName);
            }
            String componentShortString = component.flattenToShortString();
            EventLog.writeEvent(EventLogTags.BACKUP_TRANSPORT_LIFECYCLE, componentShortString, 0);
            Slog.w(TAG, "Disconnected from transport " + componentShortString);
            scheduleRebindTimeout(component);
        }

        /**
         * We'll attempt to explicitly rebind to a transport if it hasn't happened automatically
         * for a few minutes after the binding went away.
         */
        private void scheduleRebindTimeout(ComponentName component) {
            // we need to intern() the String of the component, so that we can use it with Handler's
            // removeMessages(), which uses == operator to compare the tokens
            final String componentShortString = component.flattenToShortString().intern();
            final long rebindTimeout = getRebindTimeout();
            mHandler.removeMessages(REBINDING_TIMEOUT_MSG, componentShortString);
            Message msg = mHandler.obtainMessage(REBINDING_TIMEOUT_MSG);
            msg.obj = componentShortString;
            mHandler.sendMessageDelayed(msg, rebindTimeout);
            Slog.d(TAG, "Scheduled explicit rebinding for " + componentShortString + " in "
                    + rebindTimeout + "ms");
        }

        // Intentionally not synchronized -- the variable is volatile and changes to its value
        // are inside synchronized blocks, providing a memory sync barrier; and this method
        // does not touch any other state protected by that lock.
        private IBackupTransport getBinder() {
            return mBinder;
        }

        // Intentionally not synchronized; same as getBinder()
        private String getName() {
            return mTransportName;
        }

        // Intentionally not synchronized; same as getBinder()
        private void bindIfUnbound() {
            if (mBinder == null) {
                Slog.d(TAG,
                        "Rebinding to transport " + mTransportComponent.flattenToShortString());
                bindToTransport(mTransportComponent, this);
            }
        }

        private long getRebindTimeout() {
            final boolean isDeviceProvisioned = Settings.Global.getInt(
                    mContext.getContentResolver(),
                    Settings.Global.DEVICE_PROVISIONED, 0) != 0;
            return isDeviceProvisioned
                    ? REBINDING_TIMEOUT_PROVISIONED_MS
                    : REBINDING_TIMEOUT_UNPROVISIONED_MS;
        }
    }

    public interface TransportBoundListener {
        /** Should return true if this is a valid transport. */
        boolean onTransportBound(IBackupTransport binder);
    }

    private class RebindOnTimeoutHandler extends Handler {

        RebindOnTimeoutHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            if (msg.what == REBINDING_TIMEOUT_MSG) {
                String componentShortString = (String) msg.obj;
                ComponentName transportComponent =
                        ComponentName.unflattenFromString(componentShortString);
                synchronized (mTransportLock) {
                    if (mBoundTransports.containsValue(transportComponent)) {
                        Slog.d(TAG, "Explicit rebinding timeout passed, but already bound to "
                                + componentShortString + " so not attempting to rebind");
                        return;
                    }
                    Slog.d(TAG, "Explicit rebinding timeout passed, attempting rebinding to: "
                            + componentShortString);
                    // unbind the existing (broken) connection
                    TransportConnection conn = mValidTransports.get(transportComponent);
                    if (conn != null) {
                        mContext.unbindService(conn);
                        Slog.d(TAG, "Unbinding the existing (broken) connection to transport: "
                                + componentShortString);
                    }
                }
                // rebind to transport
                tryBindTransport(transportComponent);
            } else {
                Slog.e(TAG, "Unknown message sent to RebindOnTimeoutHandler, msg.what: "
                        + msg.what);
            }
        }
    }

    private static void log_verbose(String message) {
        if (Log.isLoggable(TAG, Log.VERBOSE)) {
            Slog.v(TAG, message);
        }
    }

    // These only exists to make it testable with Robolectric, which is not updated to API level 24
    // yet.
    // TODO: Get rid of this once Robolectric is updated.
    private static ComponentName getComponentName(ServiceInfo serviceInfo) {
        return new ComponentName(serviceInfo.packageName, serviceInfo.name);
    }

    // These only exists to make it testable with Robolectric, which is not updated to API level 24
    // yet.
    // TODO: Get rid of this once Robolectric is updated.
    public static UserHandle createSystemUserHandle() {
        return new UserHandle(UserHandle.USER_SYSTEM);
    }

    private static class TransportDescription {
        private String name;
        private final String transportDirName;
        @Nullable private Intent configurationIntent;
        private String currentDestinationString;
        @Nullable private Intent dataManagementIntent;
        @Nullable private String dataManagementLabel;

        private TransportDescription(
                String name,
                String transportDirName,
                @Nullable Intent configurationIntent,
                String currentDestinationString,
                @Nullable Intent dataManagementIntent,
                @Nullable String dataManagementLabel) {
            this.name = name;
            this.transportDirName = transportDirName;
            this.configurationIntent = configurationIntent;
            this.currentDestinationString = currentDestinationString;
            this.dataManagementIntent = dataManagementIntent;
            this.dataManagementLabel = dataManagementLabel;
        }
    }
}
