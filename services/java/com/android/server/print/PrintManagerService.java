/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.print;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;
import android.print.IPrintAdapter;
import android.print.IPrintClient;
import android.print.IPrintManager;
import android.print.IPrinterDiscoveryObserver;
import android.print.PrintAttributes;
import android.print.PrintJobInfo;
import android.print.PrintManager;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.IPrintService;
import android.printservice.IPrintServiceClient;
import android.printservice.PrintServiceInfo;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.util.Slog;

import com.android.internal.content.PackageMonitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

public final class PrintManagerService extends IPrintManager.Stub {

    private static final String LOG_TAG = PrintManagerService.class.getSimpleName();

    private static final char COMPONENT_NAME_SEPARATOR = ':';

    private final Object mLock = new Object();

    private final SimpleStringSplitter mStringColonSplitter =
            new SimpleStringSplitter(COMPONENT_NAME_SEPARATOR);

    private final Map<ComponentName, PrintServiceClient> mServices =
            new HashMap<ComponentName, PrintServiceClient>();

    private final List<PrintServiceInfo> mInstalledServices = new ArrayList<PrintServiceInfo>();

    private final Set<ComponentName> mEnabledServiceNames = new HashSet<ComponentName>();

    private final Context mContext;

    private final RemoteSpooler mSpooler;

    private final int mMyUid;

    private int mCurrentUserId = UserHandle.USER_OWNER;

    private IPrinterDiscoveryObserver mPrinterDiscoveryObserver;

    public PrintManagerService(Context context) {
        mContext = context;
        mSpooler = new RemoteSpooler(context);
        mMyUid = android.os.Process.myUid();
        registerContentObservers();
        registerBoradcastreceivers();
    }

    @Override
    public PrintJobInfo print(String printJobName, IPrintClient client, IPrintAdapter printAdapter,
            PrintAttributes attributes, int appId, int userId) {
        final int resolvedAppId = resolveCallingAppEnforcingPermissionsLocked(appId);
        final int resolvedUserId = resolveCallingUserEnforcingPermissionsIdLocked(userId);
        final long identity = Binder.clearCallingIdentity();
        try {
            return mSpooler.createPrintJob(printJobName, client, printAdapter,
                    attributes, resolvedAppId, resolvedUserId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public List<PrintJobInfo> getPrintJobs(int appId, int userId) {
        final int resolvedAppId = resolveCallingAppEnforcingPermissionsLocked(appId);
        final int resolvedUserId = resolveCallingUserEnforcingPermissionsIdLocked(userId);
        // TODO: Do we want to return jobs in STATE_CREATED? We should probably
        // have additional argument for the types to get
        final long identity = Binder.clearCallingIdentity();
        try {
            return mSpooler.getPrintJobs(null, PrintJobInfo.STATE_ANY,
                    resolvedAppId, resolvedUserId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public PrintJobInfo getPrintJob(int printJobId, int appId, int userId) {
        final int resolvedAppId = resolveCallingAppEnforcingPermissionsLocked(appId);
        final int resolvedUserId = resolveCallingUserEnforcingPermissionsIdLocked(userId);
        final long identity = Binder.clearCallingIdentity();
        try {
            return mSpooler.getPrintJobInfo(printJobId, resolvedAppId, resolvedUserId);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void cancelPrintJob(int printJobId, int appId, int userId) {
        final int resolvedAppId = resolveCallingAppEnforcingPermissionsLocked(appId);
        final int resolvedUserId = resolveCallingUserEnforcingPermissionsIdLocked(userId);
        final long identity = Binder.clearCallingIdentity();
        try {
            if (mSpooler.cancelPrintJob(printJobId, resolvedAppId, resolvedUserId)) {
                return;
            }
            PrintJobInfo printJob = getPrintJob(printJobId, resolvedAppId, resolvedUserId);
            if (printJob == null) {
                return;
            }
            ComponentName printServiceName = printJob.getPrinterId().getServiceComponentName();
            PrintServiceClient printService = null;
            synchronized (mLock) {
                printService = mServices.get(printServiceName);
            }
            if (printService == null) {
                return;
            }
            printService.requestCancelPrintJob(printJob);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    // Called only from the spooler.
    @Override
    public void onPrintJobQueued(PrinterId printerId, PrintJobInfo printJob) {
        throwIfCallerNotSignedWithSystemKey();
        PrintServiceClient printService = null;
        synchronized (mLock) {
            ComponentName printServiceName = printerId.getServiceComponentName();
            printService = mServices.get(printServiceName);
        } 
        if (printService != null) {
            final long identity = Binder.clearCallingIdentity();
            try {
                printService.notifyPrintJobQueued(printJob);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        }
    }

    // Called only from the spooler.
    @Override
    public void startDiscoverPrinters(IPrinterDiscoveryObserver observer) {
        throwIfCallerNotSignedWithSystemKey();
        List<PrintServiceClient> services = new ArrayList<PrintServiceClient>();
        synchronized (mLock) {
            mPrinterDiscoveryObserver = observer;
            services.addAll(mServices.values());
        }
        final int serviceCount = services.size();
        if (serviceCount <= 0) {
            return;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            for (int i = 0; i < serviceCount; i++) {
                PrintServiceClient service = services.get(i);
                service.startPrinterDiscovery();
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    // Called only from the spooler.
    @Override
    public void stopDiscoverPrinters() {
        throwIfCallerNotSignedWithSystemKey();
        List<PrintServiceClient> services = new ArrayList<PrintServiceClient>();
        synchronized (mLock) {
            mPrinterDiscoveryObserver = null;
            services.addAll(mServices.values());
        }
        final int serviceCount = services.size();
        if (serviceCount <= 0) {
            return;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            for (int i = 0; i < serviceCount; i++) {
                PrintServiceClient service = services.get(i);
                service.stopPrintersDiscovery();
            }
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void registerContentObservers() {
        final Uri enabledPrintServicesUri = Settings.Secure.getUriFor(
                Settings.Secure.ENABLED_PRINT_SERVICES);

        ContentObserver observer = new ContentObserver(new Handler(mContext.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (enabledPrintServicesUri.equals(uri)) {
                    synchronized (mLock) {
                        if (readEnabledPrintServicesChangedLocked()) {
                            onUserStateChangedLocked();
                        }
                    }
                }
            }
        };

        mContext.getContentResolver().registerContentObserver(enabledPrintServicesUri,
                false, observer, UserHandle.USER_ALL);
    }

    private void registerBoradcastreceivers() {
        PackageMonitor monitor = new PackageMonitor() {
            @Override
            public void onSomePackagesChanged() {
                synchronized (mLock) {
                    if (getChangingUserId() != mCurrentUserId) {
                        return;
                    }
                    if (readConfigurationForUserStateLocked()) {
                        onUserStateChangedLocked();
                    }
                }
            }

            @Override
            public void onPackageRemoved(String packageName, int uid) {
                synchronized (mLock) {
                    if (getChangingUserId() != mCurrentUserId) {
                        return;
                    }
                    Iterator<ComponentName> iterator = mEnabledServiceNames.iterator();
                    while (iterator.hasNext()) {
                        ComponentName componentName = iterator.next();
                        if (packageName.equals(componentName.getPackageName())) {
                            iterator.remove();
                            onEnabledServiceNamesChangedLocked();
                            return;
                        }
                    }
                }
            }

            @Override
            public boolean onHandleForceStop(Intent intent, String[] stoppedPackages,
                    int uid, boolean doit) {
                synchronized (mLock) {
                    if (getChangingUserId() != mCurrentUserId) {
                        return false;
                    }
                    Iterator<ComponentName> iterator = mEnabledServiceNames.iterator();
                    while (iterator.hasNext()) {
                        ComponentName componentName = iterator.next();
                        String componentPackage = componentName.getPackageName();
                        for (String stoppedPackage : stoppedPackages) {
                            if (componentPackage.equals(stoppedPackage)) {
                                if (!doit) {
                                    return true;
                                }
                                iterator.remove();
                                onEnabledServiceNamesChangedLocked();
                            }
                        }
                    }
                    return false;
                }
            }

            private void onEnabledServiceNamesChangedLocked() {
                // Update the enabled services setting.
                persistComponentNamesToSettingLocked(
                        Settings.Secure.ENABLED_PRINT_SERVICES,
                        mEnabledServiceNames, mCurrentUserId);
                // Update the current user state.
                onUserStateChangedLocked();
            }
        };

        // package changes
        monitor.register(mContext, null,  UserHandle.ALL, true);

        // user changes
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Intent.ACTION_USER_SWITCHED);

        mContext.registerReceiverAsUser(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                    switchUser(intent.getIntExtra(Intent.EXTRA_USER_HANDLE, 0));
                }
            }
        }, UserHandle.ALL, intentFilter, null, null);
    }

    private void throwIfCallerNotSignedWithSystemKey() {
        if (mContext.getPackageManager().checkSignatures(
                mMyUid, Binder.getCallingUid()) != PackageManager.SIGNATURE_MATCH) {
            throw new SecurityException("Caller must be signed with the system key!");
        }
    }

    private void onUserStateChangedLocked() {
        manageServicesLocked();
    }

    private void manageServicesLocked() {
        final int installedCount = mInstalledServices.size();
        for (int i = 0; i < installedCount; i++) {
            ResolveInfo resolveInfo = mInstalledServices.get(i).getResolveInfo();
            ComponentName serviceName = new ComponentName(resolveInfo.serviceInfo.packageName,
                    resolveInfo.serviceInfo.name);
            if (mEnabledServiceNames.contains(serviceName)) {
                if (!mServices.containsKey(serviceName)) {
                    new PrintServiceClient(serviceName, mCurrentUserId).ensureBoundLocked();
                }
            } else {
                PrintServiceClient service = mServices.get(serviceName);
                if (service != null) {
                    service.ensureUnboundLocked();
                }
            }
        }
    }

    private boolean readConfigurationForUserStateLocked() {
        boolean somethingChanged = false;
        somethingChanged |= readInstalledPrintServiceLocked();
        somethingChanged |= readEnabledPrintServicesChangedLocked();
        return somethingChanged;
    }

    private boolean readEnabledPrintServicesChangedLocked() {
        Set<ComponentName> tempEnabledServiceNameSet = new HashSet<ComponentName>();
        readComponentNamesFromSettingLocked(Settings.Secure.ENABLED_PRINT_SERVICES,
            mCurrentUserId, tempEnabledServiceNameSet);
        if (!tempEnabledServiceNameSet.equals(mEnabledServiceNames)) {
            mEnabledServiceNames.clear();
            mEnabledServiceNames.addAll(tempEnabledServiceNameSet);
            return true;
        }
        return false;
    }

    private boolean readInstalledPrintServiceLocked() {
        Set<PrintServiceInfo> tempPrintServices = new HashSet<PrintServiceInfo>();

        List<ResolveInfo> installedServices = mContext.getPackageManager()
                .queryIntentServicesAsUser(
                        new Intent(android.printservice.PrintService.SERVICE_INTERFACE),
                        PackageManager.GET_SERVICES | PackageManager.GET_META_DATA,
                        mCurrentUserId);

        final int installedCount = installedServices.size();
        for (int i = 0, count = installedCount; i < count; i++) {
            ResolveInfo installedService = installedServices.get(i);
            if (!android.Manifest.permission.BIND_PRINT_SERVICE.equals(
                    installedService.serviceInfo.permission)) {
                ComponentName serviceName = new ComponentName(
                        installedService.serviceInfo.packageName,
                        installedService.serviceInfo.name);
                Slog.w(LOG_TAG, "Skipping print service "
                        + serviceName.flattenToShortString()
                        + " since it does not require permission "
                        + android.Manifest.permission.BIND_PRINT_SERVICE);
                continue;
            }
            tempPrintServices.add(PrintServiceInfo.create(installedService, mContext));
        }

        if (!tempPrintServices.equals(mInstalledServices)) {
            mInstalledServices.clear();
            mInstalledServices.addAll(tempPrintServices);
            return true;
        }
        return false;
    }

    private void readComponentNamesFromSettingLocked(String settingName, int userId,
            Set<ComponentName> outComponentNames) {
        String settingValue = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                settingName, userId);
        outComponentNames.clear();
        if (!TextUtils.isEmpty(settingValue)) {
            TextUtils.SimpleStringSplitter splitter = mStringColonSplitter;
            splitter.setString(settingValue);
            while (splitter.hasNext()) {
                String string = splitter.next();
                if (TextUtils.isEmpty(string)) {
                    continue;
                }
                ComponentName componentName = ComponentName.unflattenFromString(string);
                if (componentName != null) {
                    outComponentNames.add(componentName);
                }
            }
        }
    }

    private void persistComponentNamesToSettingLocked(String settingName,
            Set<ComponentName> componentNames, int userId) {
        StringBuilder builder = new StringBuilder();
        for (ComponentName componentName : componentNames) {
            if (builder.length() > 0) {
                builder.append(COMPONENT_NAME_SEPARATOR);
            }
            builder.append(componentName.flattenToShortString());
        }
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                settingName, builder.toString(), userId);
    }

    private void switchUser(int newUserId) {
        synchronized (mLock) {
            // Disconnect services for the old user.
            mEnabledServiceNames.clear();
            onUserStateChangedLocked();

            // The user changed.
            mCurrentUserId = newUserId;

            // Update the user state based on current settings.
            readConfigurationForUserStateLocked();
            onUserStateChangedLocked();
        }

        // Unbind the spooler for the old user).
        mSpooler.unbind();

        // If we have queued jobs, advertise it, or we do
        // not need the spooler for now.
        if (notifyQueuedPrintJobs()) {
            mSpooler.unbind();
        }
    }

    private boolean notifyQueuedPrintJobs() {
        Map<PrintServiceClient, List<PrintJobInfo>> notifications =
                new HashMap<PrintServiceClient, List<PrintJobInfo>>();
        synchronized (mLock) {
            for (PrintServiceClient service : mServices.values()) {
                List<PrintJobInfo> printJobs = mSpooler.getPrintJobs(
                        service.mComponentName, PrintJobInfo.STATE_QUEUED,
                        PrintManager.APP_ID_ANY, service.mUserId);
                notifications.put(service, printJobs);
            }
        }
        if (notifications.isEmpty()) {
            return false;
        }
        for (Map.Entry<PrintServiceClient, List<PrintJobInfo>> notification
                : notifications.entrySet()) {
            PrintServiceClient service = notification.getKey();
            List<PrintJobInfo> printJobs = notification.getValue();
            final int printJobIdCount = printJobs.size();
            for (int i = 0; i < printJobIdCount; i++) {
                service.notifyPrintJobQueued(printJobs.get(i));
            }
        }
        return true;
    }

    private int resolveCallingUserEnforcingPermissionsIdLocked(int userId) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid == 0 || callingUid == Process.SYSTEM_UID
                || callingUid == Process.SHELL_UID) {
            return userId;
        }
        final int callingUserId = UserHandle.getUserId(callingUid);
        if (callingUserId == userId) {
            return userId;
        }
        if (mContext.checkCallingPermission(Manifest.permission.INTERACT_ACROSS_USERS_FULL)
                != PackageManager.PERMISSION_GRANTED
            ||  mContext.checkCallingPermission(Manifest.permission.INTERACT_ACROSS_USERS)
                != PackageManager.PERMISSION_GRANTED) {
            if (userId == UserHandle.USER_CURRENT_OR_SELF) {
                return callingUserId;
            }
            throw new SecurityException("Call from user " + callingUserId + " as user "
                    + userId + " without permission INTERACT_ACROSS_USERS or "
                    + "INTERACT_ACROSS_USERS_FULL not allowed.");
        }
        if (userId == UserHandle.USER_CURRENT || userId == UserHandle.USER_CURRENT_OR_SELF) {
            return mCurrentUserId;
        }
        throw new IllegalArgumentException("Calling user can be changed to only "
                + "UserHandle.USER_CURRENT or UserHandle.USER_CURRENT_OR_SELF.");
    }

    private int resolveCallingAppEnforcingPermissionsLocked(int appId) {
        final int callingUid = Binder.getCallingUid();
        if (callingUid == 0 || callingUid == Process.SYSTEM_UID
                || callingUid == Process.SHELL_UID) {
            return appId;
        }
        final int callingAppId = UserHandle.getAppId(callingUid);
        if (appId == callingAppId) {
            return appId;
        }
        if (mContext.checkCallingPermission(Manifest.permission.ACCESS_ALL_PRINT_JOBS)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Call from app " + callingAppId + " as app "
                    + appId + " without permission INTERACT_ACROSS_APPS");
        }
        return appId;
    }

    private final class PrintServiceClient extends IPrintServiceClient.Stub
            implements ServiceConnection, DeathRecipient {

        private final ComponentName mComponentName;

        private final Intent mIntent;

        private final int mUserId;

        private IPrintService mInterface;

        private boolean mBinding;

        private boolean mWasConnectedAndDied;

        public PrintServiceClient(ComponentName componentName, int userId) {
            mComponentName = componentName;
            mIntent = new Intent().setComponent(mComponentName);
            mUserId = userId;
        }

        @Override
        public List<PrintJobInfo> getPrintJobs() {
            return mSpooler.getPrintJobs(mComponentName, PrintJobInfo.STATE_ANY,
                    PrintManager.APP_ID_ANY, mUserId);
        }

        @Override
        public PrintJobInfo getPrintJob(int printJobId) {
            return mSpooler.getPrintJobInfo(printJobId,
                    PrintManager.APP_ID_ANY, mUserId);
        }

        @Override
        public boolean setPrintJobState(int printJobId, int state) {
            return mSpooler.setPrintJobState(printJobId, state, mUserId);
        }

        @Override
        public boolean setPrintJobTag(int printJobId, String tag) {
            return mSpooler.setPrintJobTag(printJobId, tag, mUserId);
        }

        @Override
        public void writePrintJobData(ParcelFileDescriptor fd, int printJobId) {
            mSpooler.writePrintJobData(fd, printJobId, mUserId);
        }

        @Override
        public void addDiscoveredPrinters(List<PrinterInfo> printers) {
            throwIfPrinterIdsForPrinterInfoTampered(printers);
            synchronized (mLock) {
                if (mPrinterDiscoveryObserver != null) {
                    try {
                        mPrinterDiscoveryObserver.addDiscoveredPrinters(printers);
                    } catch (RemoteException re) {
                        /* ignore */
                    }
                }
            }
        }

        @Override
        public void removeDiscoveredPrinters(List<PrinterId> printerIds) {
            throwIfPrinterIdsTampered(printerIds);
            synchronized (mLock) {
                if (mPrinterDiscoveryObserver != null) {
                    try {
                        mPrinterDiscoveryObserver.removeDiscoveredPrinters(printerIds);
                    } catch (RemoteException re) {
                        /* ignore */
                    }
                }
            }
        }

        public void requestCancelPrintJob(PrintJobInfo printJob) {
            synchronized (mLock) {
                try {
                    mInterface.requestCancelPrintJob(printJob);
                } catch (RemoteException re) {
                    Slog.e(LOG_TAG, "Error canceling pring job!", re);
                }
            }
        }

        public void notifyPrintJobQueued(PrintJobInfo printJob) {
            IPrintService service = mInterface;
            if (service != null) {
                try {
                    service.onPrintJobQueued(printJob);
                } catch (RemoteException re) {
                    /* ignore */
                }
            }
        }

        public void startPrinterDiscovery() {
            IPrintService service = mInterface;
            if (service != null) {
                try {
                    service.startPrinterDiscovery();
                } catch (RemoteException re) {
                    /* ignore */
                }
            }
        }

        public void stopPrintersDiscovery() {
            IPrintService service = mInterface;
            if (service != null) {
                try {
                    service.stopPrinterDiscovery();
                } catch (RemoteException re) {
                    /* ignore */
                }
            }
        }

        public void ensureBoundLocked() {
            if (mBinding) {
                return;
            }
            if (mInterface == null) {
                mBinding = true;
                mContext.bindServiceAsUser(mIntent, this,
                        Context.BIND_AUTO_CREATE, new UserHandle(mUserId));
            }
        }

        public void ensureUnboundLocked() {
            if (mBinding) {
                mBinding = false;
                return;
            }
            if (mInterface != null) {
                mContext.unbindService(this);
                destroyLocked();
            }
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mInterface = IPrintService.Stub.asInterface(service);
                mServices.put(mComponentName, this);
                try {
                    mInterface.asBinder().linkToDeath(this, 0);
                } catch (RemoteException re) {
                    destroyLocked();
                    return;
                }
                if (mUserId != mCurrentUserId) {
                    destroyLocked();
                    return;
                }
                if (mBinding || mWasConnectedAndDied) {
                    mBinding = false;
                    mWasConnectedAndDied = false;
                    onUserStateChangedLocked();
                    try {
                        mInterface.setClient(this);
                    } catch (RemoteException re) {
                        Slog.w(LOG_TAG, "Error while setting client for service: "
                                + service, re);
                    }
                } else {
                    destroyLocked();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            /* do nothing - #binderDied takes care */
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                if (isConnectedLocked()) {
                    mWasConnectedAndDied = true;
                }
                destroyLocked();
            }
        }

        private void destroyLocked() {
            if (mServices.remove(mComponentName) == null) {
                return;
            }
            if (isConnectedLocked()) {
                try {
                    mInterface.asBinder().unlinkToDeath(this, 0);
                } catch (NoSuchElementException nse) {
                    /* ignore */
                }
                try {
                    mInterface.setClient(null);
                } catch (RemoteException re) {
                    /* ignore */
                }
                mInterface = null;
            }
            mBinding = false;
        }

        private boolean isConnectedLocked() {
            return (mInterface != null);
        }

        private void throwIfPrinterIdsForPrinterInfoTampered(List<PrinterInfo> printerInfos) {
            final int printerInfoCount = printerInfos.size();
            for (int i = 0; i < printerInfoCount; i++) {
                PrinterId printerId = printerInfos.get(i).getId();
                throwIfPrinterIdTampered(printerId);
            }
        }

        private void throwIfPrinterIdsTampered(List<PrinterId> printerIds) {
            final int printerIdCount = printerIds.size();
            for (int i = 0; i < printerIdCount; i++) {
                PrinterId printerId = printerIds.get(i);
                throwIfPrinterIdTampered(printerId);
            }
        }

        private void throwIfPrinterIdTampered(PrinterId printerId) {
            if (printerId == null || printerId.getServiceComponentName() == null
                    || !printerId.getServiceComponentName().equals(mComponentName)) {
                throw new IllegalArgumentException("Invalid printer id: " + printerId);
            }
        }
    }
}
