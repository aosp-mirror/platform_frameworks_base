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

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.annotation.WorkerThread;
import android.app.backup.BackupManager;
import android.app.backup.BackupTransport;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.backup.IBackupTransport;
import com.android.internal.util.Preconditions;
import com.android.server.backup.transport.OnTransportRegisteredListener;
import com.android.server.backup.transport.TransportClient;
import com.android.server.backup.transport.TransportClientManager;
import com.android.server.backup.transport.TransportConnectionListener;
import com.android.server.backup.transport.TransportNotAvailableException;
import com.android.server.backup.transport.TransportNotRegisteredException;
import com.android.server.backup.transport.TransportStats;

import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Handles in-memory bookkeeping of all BackupTransport objects. */
public class TransportManager {
    private static final String TAG = "BackupTransportManager";
    private static final boolean MORE_DEBUG = false;

    @VisibleForTesting
    public static final String SERVICE_ACTION_TRANSPORT_HOST = "android.backup.TRANSPORT_HOST";

    private final Intent mTransportServiceIntent = new Intent(SERVICE_ACTION_TRANSPORT_HOST);
    private final @UserIdInt int mUserId;
    private final PackageManager mPackageManager;
    private final Set<ComponentName> mTransportWhitelist;
    private final TransportClientManager mTransportClientManager;
    private final TransportStats mTransportStats;
    private OnTransportRegisteredListener mOnTransportRegisteredListener = (c, n) -> {};

    /**
     * Lock for registered transports and currently selected transport.
     *
     * <p><b>Warning:</b> No calls to {@link IBackupTransport} or calls that result in transport
     * code being executed such as {@link TransportClient#connect(String)}} and its variants should
     * be made with this lock held, risk of deadlock.
     */
    private final Object mTransportLock = new Object();

    /** @see #getRegisteredTransportNames() */
    @GuardedBy("mTransportLock")
    private final Map<ComponentName, TransportDescription> mRegisteredTransportsDescriptionMap =
            new ArrayMap<>();

    @GuardedBy("mTransportLock")
    @Nullable
    private volatile String mCurrentTransportName;

    TransportManager(@UserIdInt int userId, Context context, Set<ComponentName> whitelist,
            String selectedTransport) {
        mUserId = userId;
        mPackageManager = context.getPackageManager();
        mTransportWhitelist = Preconditions.checkNotNull(whitelist);
        mCurrentTransportName = selectedTransport;
        mTransportStats = new TransportStats();
        mTransportClientManager = new TransportClientManager(mUserId, context, mTransportStats);
    }

    @VisibleForTesting
    TransportManager(
            @UserIdInt int userId,
            Context context,
            Set<ComponentName> whitelist,
            String selectedTransport,
            TransportClientManager transportClientManager) {
        mUserId = userId;
        mPackageManager = context.getPackageManager();
        mTransportWhitelist = Preconditions.checkNotNull(whitelist);
        mCurrentTransportName = selectedTransport;
        mTransportStats = new TransportStats();
        mTransportClientManager = transportClientManager;
    }

    /* Sets a listener to be called whenever a transport is registered. */
    public void setOnTransportRegisteredListener(OnTransportRegisteredListener listener) {
        mOnTransportRegisteredListener = listener;
    }

    @WorkerThread
    void onPackageAdded(String packageName) {
        registerTransportsFromPackage(packageName, transportComponent -> true);
    }

    void onPackageRemoved(String packageName) {
        synchronized (mTransportLock) {
            mRegisteredTransportsDescriptionMap.keySet().removeIf(fromPackageFilter(packageName));
        }
    }

    void onPackageEnabled(String packageName) {
        onPackageAdded(packageName);
    }

    void onPackageDisabled(String packageName) {
        onPackageRemoved(packageName);
    }

    @WorkerThread
    void onPackageChanged(String packageName, String... components) {
        // Determine if the overall package has changed and not just its
        // components - see {@link EXTRA_CHANGED_COMPONENT_NAME_LIST}.  When we
        // know a package was enabled/disabled we'll handle that directly and
        // not continue with onPackageChanged.
        if (components.length == 1 && components[0].equals(packageName)) {
            final int enabled = mPackageManager.getApplicationEnabledSetting(packageName);
            switch (enabled) {
                case COMPONENT_ENABLED_STATE_ENABLED: {
                    if (MORE_DEBUG) {
                        Slog.d(TAG, "Package " + packageName + " was enabled.");
                    }
                    onPackageEnabled(packageName);
                    return;
                }
                case COMPONENT_ENABLED_STATE_DISABLED: {
                    if (MORE_DEBUG) {
                        Slog.d(TAG, "Package " + packageName + " was disabled.");
                    }
                    onPackageDisabled(packageName);
                    return;
                }
                default: {
                    Slog.w(TAG, "Package " + packageName + " enabled setting: " + enabled);
                    return;
                }
            }
        }
        // Unfortunately this can't be atomic because we risk a deadlock if
        // registerTransportsFromPackage() is put inside the synchronized block
        Set<ComponentName> transportComponents = new ArraySet<>(components.length);
        for (String componentName : components) {
            transportComponents.add(new ComponentName(packageName, componentName));
        }
        if (transportComponents.isEmpty()) {
            return;
        }
        synchronized (mTransportLock) {
            mRegisteredTransportsDescriptionMap.keySet().removeIf(transportComponents::contains);
        }
        registerTransportsFromPackage(packageName, transportComponents::contains);
    }

    /**
     * Returns the {@link ComponentName}s of the registered transports.
     *
     * <p>A *registered* transport is a transport that satisfies intent with action
     * android.backup.TRANSPORT_HOST, returns true for {@link #isTransportTrusted(ComponentName)}
     * and that we have successfully connected to once.
     */
    ComponentName[] getRegisteredTransportComponents() {
        synchronized (mTransportLock) {
            return mRegisteredTransportsDescriptionMap
                    .keySet()
                    .toArray(new ComponentName[mRegisteredTransportsDescriptionMap.size()]);
        }
    }

    /**
     * Returns the names of the registered transports.
     *
     * @see #getRegisteredTransportComponents()
     */
    String[] getRegisteredTransportNames() {
        synchronized (mTransportLock) {
            String[] transportNames = new String[mRegisteredTransportsDescriptionMap.size()];
            int i = 0;
            for (TransportDescription description : mRegisteredTransportsDescriptionMap.values()) {
                transportNames[i] = description.name;
                i++;
            }
            return transportNames;
        }
    }

    /** Returns a set with the allowlisted transports. */
    Set<ComponentName> getTransportWhitelist() {
        return mTransportWhitelist;
    }

    /** Returns the name of the selected transport or {@code null} if no transport selected. */
    @Nullable
    public String getCurrentTransportName() {
        return mCurrentTransportName;
    }

    /**
     * Returns the {@link ComponentName} of the host service of the selected transport or
     * {@code null} if no transport selected.
     *
     * @throws TransportNotRegisteredException if the selected transport is not registered.
     */
    @Nullable
    public ComponentName getCurrentTransportComponent()
            throws TransportNotRegisteredException {
        synchronized (mTransportLock) {
            if (mCurrentTransportName == null) {
                return null;
            }
            return getRegisteredTransportComponentOrThrowLocked(mCurrentTransportName);
        }
    }

    /**
     * Returns the transport name associated with {@code transportComponent}.
     *
     * @throws TransportNotRegisteredException if the transport is not registered.
     */
    public String getTransportName(ComponentName transportComponent)
            throws TransportNotRegisteredException {
        synchronized (mTransportLock) {
            return getRegisteredTransportDescriptionOrThrowLocked(transportComponent).name;
        }
    }

    /**
     * Retrieves the transport dir name of {@code transportComponent}.
     *
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
     * Retrieves the transport dir name of {@code transportName}.
     *
     * @throws TransportNotRegisteredException if the transport is not registered.
     */
    public String getTransportDirName(String transportName) throws TransportNotRegisteredException {
        synchronized (mTransportLock) {
            return getRegisteredTransportDescriptionOrThrowLocked(transportName).transportDirName;
        }
    }

    /**
     * Retrieves the configuration intent of {@code transportName}.
     *
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
     * Retrieves the current destination string of {@code transportName}.
     *
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
     * Retrieves the data management intent of {@code transportName}.
     *
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
     * Retrieves the data management label of {@code transportName}.
     *
     * @throws TransportNotRegisteredException if the transport is not registered.
     */
    @Nullable
    public CharSequence getTransportDataManagementLabel(String transportName)
            throws TransportNotRegisteredException {
        synchronized (mTransportLock) {
            return getRegisteredTransportDescriptionOrThrowLocked(transportName)
                    .dataManagementLabel;
        }
    }

    /* Returns true if the transport identified by {@code transportName} is registered. */
    public boolean isTransportRegistered(String transportName) {
        synchronized (mTransportLock) {
            return getRegisteredTransportEntryLocked(transportName) != null;
        }
    }

    /**
     * Execute {@code transportConsumer} for each registered transport passing the transport name.
     * This is called with an internal lock held, ensuring that the transport will remain registered
     * while {@code transportConsumer} is being executed. Don't do heavy operations in {@code
     * transportConsumer}.
     *
     * <p><b>Warning:</b> Do NOT make any calls to {@link IBackupTransport} or call any variants of
     * {@link TransportClient#connect(String)} here, otherwise you risk deadlock.
     */
    public void forEachRegisteredTransport(Consumer<String> transportConsumer) {
        synchronized (mTransportLock) {
            for (TransportDescription transportDescription :
                    mRegisteredTransportsDescriptionMap.values()) {
                transportConsumer.accept(transportDescription.name);
            }
        }
    }

    /**
     * Updates given values for the transport already registered and identified with {@param
     * transportComponent}. If the transport is not registered it will log and return.
     */
    public void updateTransportAttributes(
            ComponentName transportComponent,
            String name,
            @Nullable Intent configurationIntent,
            String currentDestinationString,
            @Nullable Intent dataManagementIntent,
            @Nullable CharSequence dataManagementLabel) {
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

    @GuardedBy("mTransportLock")
    private ComponentName getRegisteredTransportComponentOrThrowLocked(String transportName)
            throws TransportNotRegisteredException {
        ComponentName transportComponent = getRegisteredTransportComponentLocked(transportName);
        if (transportComponent == null) {
            throw new TransportNotRegisteredException(transportName);
        }
        return transportComponent;
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
    @Nullable
    private Map.Entry<ComponentName, TransportDescription> getRegisteredTransportEntryLocked(
            String transportName) {
        for (Map.Entry<ComponentName, TransportDescription> entry :
                mRegisteredTransportsDescriptionMap.entrySet()) {
            TransportDescription description = entry.getValue();
            if (transportName.equals(description.name)) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Returns a {@link TransportClient} for {@code transportName} or {@code null} if not
     * registered.
     *
     * @param transportName The name of the transport.
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link TransportClient#connectAsync(TransportConnectionListener, String)} for more
     *     details.
     * @return A {@link TransportClient} or null if not registered.
     */
    @Nullable
    public TransportClient getTransportClient(String transportName, String caller) {
        try {
            return getTransportClientOrThrow(transportName, caller);
        } catch (TransportNotRegisteredException e) {
            Slog.w(TAG, "Transport " + transportName + " not registered");
            return null;
        }
    }

    /**
     * Returns a {@link TransportClient} for {@code transportName} or throws if not registered.
     *
     * @param transportName The name of the transport.
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link TransportClient#connectAsync(TransportConnectionListener, String)} for more
     *     details.
     * @return A {@link TransportClient}.
     * @throws TransportNotRegisteredException if the transport is not registered.
     */
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

    /**
     * Returns a {@link TransportClient} for the current transport or {@code null} if not
     * registered.
     *
     * @param caller A {@link String} identifying the caller for logging/debugging purposes. Check
     *     {@link TransportClient#connectAsync(TransportConnectionListener, String)} for more
     *     details.
     * @return A {@link TransportClient} or null if not registered.
     * @throws IllegalStateException if no transport is selected.
     */
    @Nullable
    public TransportClient getCurrentTransportClient(String caller) {
        if (mCurrentTransportName == null) {
            throw new IllegalStateException("No transport selected");
        }
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
     * @throws IllegalStateException if no transport is selected.
     */
    public TransportClient getCurrentTransportClientOrThrow(String caller)
            throws TransportNotRegisteredException {
        if (mCurrentTransportName == null) {
            throw new IllegalStateException("No transport selected");
        }
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

    /**
     * Sets {@code transportName} as selected transport and returns previously selected transport
     * name. If there was no previous transport it returns null.
     *
     * <p>You should NOT call this method in new code. This won't make any checks against {@code
     * transportName}, putting any operation at risk of a {@link TransportNotRegisteredException} or
     * another error at the time it's being executed.
     *
     * <p>{@link Deprecated} as public, this method can be used as private.
     */
    @Deprecated
    @Nullable
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
    @WorkerThread
    public int registerAndSelectTransport(ComponentName transportComponent) {
        // If it's already registered we select and return
        synchronized (mTransportLock) {
            try {
                selectTransport(getTransportName(transportComponent));
                return BackupManager.SUCCESS;
            } catch (TransportNotRegisteredException e) {
                // Fall through and release lock
            }
        }

        // We can't call registerTransport() with the transport lock held
        int result = registerTransport(transportComponent);
        if (result != BackupManager.SUCCESS) {
            return result;
        }
        synchronized (mTransportLock) {
            try {
                selectTransport(getTransportName(transportComponent));
                return BackupManager.SUCCESS;
            } catch (TransportNotRegisteredException e) {
                Slog.wtf(TAG, "Transport got unregistered");
                return BackupManager.ERROR_TRANSPORT_UNAVAILABLE;
            }
        }
    }

    @WorkerThread
    public void registerTransports() {
        registerTransportsForIntent(mTransportServiceIntent, transportComponent -> true);
    }

    @WorkerThread
    private void registerTransportsFromPackage(
            String packageName, Predicate<ComponentName> transportComponentFilter) {
        try {
            mPackageManager.getPackageInfoAsUser(packageName, 0, mUserId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Trying to register transports from package not found " + packageName);
            return;
        }

        registerTransportsForIntent(
                new Intent(mTransportServiceIntent).setPackage(packageName),
                transportComponentFilter.and(fromPackageFilter(packageName)));
    }

    @WorkerThread
    private void registerTransportsForIntent(
            Intent intent, Predicate<ComponentName> transportComponentFilter) {
        List<ResolveInfo> hosts =
                mPackageManager.queryIntentServicesAsUser(intent, 0, mUserId);
        if (hosts == null) {
            return;
        }
        for (ResolveInfo host : hosts) {
            ComponentName transportComponent = host.serviceInfo.getComponentName();
            if (transportComponentFilter.test(transportComponent)
                    && isTransportTrusted(transportComponent)) {
                registerTransport(transportComponent);
            }
        }
    }

    /** Transport has to be allowlisted and privileged. */
    private boolean isTransportTrusted(ComponentName transport) {
        if (!mTransportWhitelist.contains(transport)) {
            Slog.w(
                    TAG,
                    "BackupTransport " + transport.flattenToShortString() + " not whitelisted.");
            return false;
        }
        try {
            PackageInfo packInfo =
                    mPackageManager.getPackageInfoAsUser(transport.getPackageName(), 0, mUserId);
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

    /**
     * Tries to register transport represented by {@code transportComponent}.
     *
     * <p><b>Warning:</b> Don't call this with the transport lock held.
     *
     * @param transportComponent Host of the transport that we want to register.
     * @return One of {@link BackupManager#SUCCESS}, {@link BackupManager#ERROR_TRANSPORT_INVALID}
     *     or {@link BackupManager#ERROR_TRANSPORT_UNAVAILABLE}.
     */
    @WorkerThread
    private int registerTransport(ComponentName transportComponent) {
        checkCanUseTransport();

        if (!isTransportTrusted(transportComponent)) {
            return BackupManager.ERROR_TRANSPORT_INVALID;
        }

        String transportString = transportComponent.flattenToShortString();
        String callerLogString = "TransportManager.registerTransport()";

        Bundle extras = new Bundle();
        extras.putBoolean(BackupTransport.EXTRA_TRANSPORT_REGISTRATION, true);

        TransportClient transportClient =
                mTransportClientManager.getTransportClient(
                        transportComponent, extras, callerLogString);
        final IBackupTransport transport;
        try {
            transport = transportClient.connectOrThrow(callerLogString);
        } catch (TransportNotAvailableException e) {
            Slog.e(TAG, "Couldn't connect to transport " + transportString + " for registration");
            mTransportClientManager.disposeOfTransportClient(transportClient, callerLogString);
            return BackupManager.ERROR_TRANSPORT_UNAVAILABLE;
        }

        int result;
        try {
            // This is a temporary fix to allow blocking calls.
            // TODO: b/147702043. Redesign IBackupTransport so as to make the calls non-blocking.
            Binder.allowBlocking(transport.asBinder());

            String transportName = transport.name();
            String transportDirName = transport.transportDirName();
            registerTransport(transportComponent, transport);
            // If registerTransport() hasn't thrown...
            Slog.d(TAG, "Transport " + transportString + " registered");
            mOnTransportRegisteredListener.onTransportRegistered(transportName, transportDirName);
            result = BackupManager.SUCCESS;
        } catch (RemoteException e) {
            Slog.e(TAG, "Transport " + transportString + " died while registering");
            result = BackupManager.ERROR_TRANSPORT_UNAVAILABLE;
        }

        mTransportClientManager.disposeOfTransportClient(transportClient, callerLogString);
        return result;
    }

    /** If {@link RemoteException} is thrown the transport is guaranteed to not be registered. */
    private void registerTransport(ComponentName transportComponent, IBackupTransport transport)
            throws RemoteException {
        checkCanUseTransport();

        TransportDescription description =
                new TransportDescription(
                        transport.name(),
                        transport.transportDirName(),
                        transport.configurationIntent(),
                        transport.currentDestinationString(),
                        transport.dataManagementIntent(),
                        transport.dataManagementIntentLabel());
        synchronized (mTransportLock) {
            mRegisteredTransportsDescriptionMap.put(transportComponent, description);
        }
    }

    private void checkCanUseTransport() {
        Preconditions.checkState(
                !Thread.holdsLock(mTransportLock), "Can't call transport with transport lock held");
    }

    public void dumpTransportClients(PrintWriter pw) {
        mTransportClientManager.dump(pw);
    }

    public void dumpTransportStats(PrintWriter pw) {
        mTransportStats.dump(pw);
    }

    private static Predicate<ComponentName> fromPackageFilter(String packageName) {
        return transportComponent -> packageName.equals(transportComponent.getPackageName());
    }

    private static class TransportDescription {
        private String name;
        private final String transportDirName;
        @Nullable private Intent configurationIntent;
        private String currentDestinationString;
        @Nullable private Intent dataManagementIntent;
        @Nullable private CharSequence dataManagementLabel;

        private TransportDescription(
                String name,
                String transportDirName,
                @Nullable Intent configurationIntent,
                String currentDestinationString,
                @Nullable Intent dataManagementIntent,
                @Nullable CharSequence dataManagementLabel) {
            this.name = name;
            this.transportDirName = transportDirName;
            this.configurationIntent = configurationIntent;
            this.currentDestinationString = currentDestinationString;
            this.dataManagementIntent = dataManagementIntent;
            this.dataManagementLabel = dataManagementLabel;
        }
    }
}
