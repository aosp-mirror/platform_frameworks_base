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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.print.IPrinterDiscoveryObserver;
import android.print.PrintJobInfo;
import android.print.PrinterId;
import android.print.PrinterInfo;
import android.printservice.PrintServiceInfo;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Slog;

import com.android.internal.os.SomeArgs;
import com.android.server.print.RemotePrintSpooler.PrintSpoolerCallbacks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents the print state for a user.
 */
final class UserState implements PrintSpoolerCallbacks {

    private static final String LOG_TAG = "UserState";

    private static final boolean DEBUG = true && Build.IS_DEBUGGABLE;

    private static final int MAX_ITEMS_PER_CALLBACK = 100;

    private static final char COMPONENT_NAME_SEPARATOR = ':';

    private final SimpleStringSplitter mStringColonSplitter =
            new SimpleStringSplitter(COMPONENT_NAME_SEPARATOR);

    private final Intent mQueryIntent =
            new Intent(android.printservice.PrintService.SERVICE_INTERFACE);

    private final Map<ComponentName, RemotePrintService> mActiveServices =
            new HashMap<ComponentName, RemotePrintService>();

    private final List<PrintServiceInfo> mInstalledServices =
            new ArrayList<PrintServiceInfo>();

    private final Set<ComponentName> mEnabledServices =
            new HashSet<ComponentName>();

    private final Object mLock;

    private final Context mContext;

    private final int mUserId;

    private final RemotePrintSpooler mSpooler;

    private PrinterDiscoverySessionMediator mPrinterDiscoverySession;

    private boolean mDestroyed;

    public UserState(Context context, int userId, Object lock) {
        mContext = context;
        mUserId = userId;
        mLock = lock;
        mSpooler = new RemotePrintSpooler(context, userId, this);
        synchronized (mLock) {
            enableSystemPrintServicesOnFirstBootLocked();
        }
    }

    @Override
    public void onPrintJobQueued(PrintJobInfo printJob) {
        final RemotePrintService service;
        synchronized (mLock) {
            throwIfDestroyedLocked();
            ComponentName printServiceName = printJob.getPrinterId().getServiceName();
            service = mActiveServices.get(printServiceName);
        }
        if (service != null) {
            service.onPrintJobQueued(printJob);
        }
    }

    @Override
    public void onAllPrintJobsForServiceHandled(ComponentName printService) {
        final RemotePrintService service;
        synchronized (mLock) {
            throwIfDestroyedLocked();
            service = mActiveServices.get(printService);
        }
        if (service != null) {
            service.onAllPrintJobsHandled();
        }
    }

    public void createPrinterDiscoverySession(IPrinterDiscoveryObserver observer) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            if (mActiveServices.isEmpty()) {
                return;
            }
            if (mPrinterDiscoverySession == null) {
                // If we do not have a session, tell all service to create one.
                mPrinterDiscoverySession = new PrinterDiscoverySessionMediator(mContext) {
                    @Override
                    public void onDestroyed() {
                        mPrinterDiscoverySession = null;
                    }
                };
                // Add the observer to the brand new session.
                mPrinterDiscoverySession.addObserverLocked(observer);
            } else {
                // If services have created session, just add the observer.
                mPrinterDiscoverySession.addObserverLocked(observer);
            }
        }
    }

    public void destroyPrinterDiscoverySession(IPrinterDiscoveryObserver observer) {
        synchronized (mLock) {
            // Already destroyed - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            // Remove this observer.
            mPrinterDiscoverySession.removeObserverLocked(observer);
        }
    }

    public void startPrinterDiscovery(IPrinterDiscoveryObserver observer,
            List<PrinterId> printerIds) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            // No services - nothing to do.
            if (mActiveServices.isEmpty()) {
                return;
            }
            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            // Kick of discovery.
            mPrinterDiscoverySession.startPrinterDiscoveryLocked(observer,
                    printerIds);
        }
    }

    public void stopPrinterDiscovery(IPrinterDiscoveryObserver observer) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            // No services - nothing to do.
            if (mActiveServices.isEmpty()) {
                return;
            }
            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            // Kick of discovery.
            mPrinterDiscoverySession.stopPrinterDiscoveryLocked(observer);
        }
    }

    public void validatePrinters(List<PrinterId> printerIds) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            // No services - nothing to do.
            if (mActiveServices.isEmpty()) {
                return;
            }
            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            // Request an updated.
            mPrinterDiscoverySession.validatePrintersLocked(printerIds);
        }
    }

    public void startPrinterStateTracking(PrinterId printerId) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            // No services - nothing to do.
            if (mActiveServices.isEmpty()) {
                return;
            }
            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            // Request start tracking the printer.
            mPrinterDiscoverySession.startPrinterStateTrackingLocked(printerId);
        }
    }

    public void stopPrinterStateTracking(PrinterId printerId) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            // No services - nothing to do.
            if (mActiveServices.isEmpty()) {
                return;
            }
            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            // Request stop tracking the printer.
            mPrinterDiscoverySession.stopPrinterStateTrackingLocked(printerId);
        }
    }

    public void onPrintersAdded(List<PrinterInfo> printers) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            // No services - nothing to do.
            if (mActiveServices.isEmpty()) {
                return;
            }
            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            // Request an updated.
            mPrinterDiscoverySession.onPrintersAddedLocked(printers);
        }
    }

    public void onPrintersRemoved(List<PrinterId> printerIds) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            // No services - nothing to do.
            if (mActiveServices.isEmpty()) {
                return;
            }
            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            // Request an updated.
            mPrinterDiscoverySession.onPrintersRemovedLocked(printerIds);
        }
    }

    public void onPrintersUpdated(List<PrinterInfo> printers) {
        synchronized (mLock) {
            throwIfDestroyedLocked();
            // No services - nothing to do.
            if (mActiveServices.isEmpty()) {
                return;
            }
            // No session - nothing to do.
            if (mPrinterDiscoverySession == null) {
                return;
            }
            // Request an updated.
            mPrinterDiscoverySession.onPrintersUpdatedLocked(printers);
        }
    }

    public void updateIfNeededLocked() {
        throwIfDestroyedLocked();
        if (readConfigurationLocked()) {
            onConfigurationChangedLocked();
        }
    }

    public RemotePrintSpooler getSpoolerLocked() {
        throwIfDestroyedLocked();
        return mSpooler;
    }

    public Map<ComponentName, RemotePrintService> getActiveServicesLocked() {
        synchronized(mLock) {
            throwIfDestroyedLocked();
            return mActiveServices;
        }
    }

    public Set<ComponentName> getEnabledServices() {
        synchronized(mLock) {
            throwIfDestroyedLocked();
            return mEnabledServices;
        }
    }

    public void destroyLocked() {
        throwIfDestroyedLocked();
        mSpooler.destroy();
        for (RemotePrintService service : mActiveServices.values()) {
            service.destroy();
        }
        mActiveServices.clear();
        mInstalledServices.clear();
        mEnabledServices.clear();
        if (mPrinterDiscoverySession != null) {
            mPrinterDiscoverySession.destroyLocked();
            mPrinterDiscoverySession = null;
        }
        mDestroyed = true;
    }

    private boolean readConfigurationLocked() {
        boolean somethingChanged = false;
        somethingChanged |= readInstalledPrintServicesLocked();
        somethingChanged |= readEnabledPrintServicesLocked();
        return somethingChanged;
    }

    private boolean readInstalledPrintServicesLocked() {
        Set<PrintServiceInfo> tempPrintServices = new HashSet<PrintServiceInfo>();

        List<ResolveInfo> installedServices = mContext.getPackageManager()
                .queryIntentServicesAsUser(mQueryIntent, PackageManager.GET_SERVICES
                        | PackageManager.GET_META_DATA, mUserId);

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


    private boolean readEnabledPrintServicesLocked() {
        Set<ComponentName> tempEnabledServiceNameSet = new HashSet<ComponentName>();
        readPrintServicesFromSettingLocked(Settings.Secure.ENABLED_PRINT_SERVICES,
                tempEnabledServiceNameSet);
        if (!tempEnabledServiceNameSet.equals(mEnabledServices)) {
            mEnabledServices.clear();
            mEnabledServices.addAll(tempEnabledServiceNameSet);
            return true;
        }
        return false;
    }

    private void readPrintServicesFromSettingLocked(String setting,
            Set<ComponentName> outServiceNames) {
        String settingValue = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                setting, mUserId);
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
                    outServiceNames.add(componentName);
                }
            }
        }
    }

    private void enableSystemPrintServicesOnFirstBootLocked() {
        // Load enabled and installed services.
        readEnabledPrintServicesLocked();
        readInstalledPrintServicesLocked();

        // Load the system services once enabled on first boot.
        Set<ComponentName> enabledOnFirstBoot = new HashSet<ComponentName>();
        readPrintServicesFromSettingLocked(
                Settings.Secure.ENABLED_ON_FIRST_BOOT_SYSTEM_PRINT_SERVICES,
                enabledOnFirstBoot);

        StringBuilder builder = new StringBuilder();

        final int serviceCount = mInstalledServices.size();
        for (int i = 0; i < serviceCount; i++) {
            ServiceInfo serviceInfo = mInstalledServices.get(i).getResolveInfo().serviceInfo;
            // Enable system print services if we never did that and are not enabled.
            if ((serviceInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                ComponentName serviceName = new ComponentName(
                        serviceInfo.packageName, serviceInfo.name);
                if (!mEnabledServices.contains(serviceName)
                        && !enabledOnFirstBoot.contains(serviceName)) {
                    if (builder.length() > 0) {
                        builder.append(":");
                    }
                    builder.append(serviceName.flattenToString());
                }
            }
        }

        // Nothing to be enabled - done.
        if (builder.length() <= 0) {
            return;
        }

        String servicesToEnable = builder.toString();

        // Update the enabled services setting.
        String enabledServices = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), Settings.Secure.ENABLED_PRINT_SERVICES, mUserId);
        if (TextUtils.isEmpty(enabledServices)) {
            enabledServices = servicesToEnable;
        } else {
            enabledServices = enabledServices + ":" + servicesToEnable;
        }
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.ENABLED_PRINT_SERVICES, enabledServices, mUserId);

        // Update the enabled on first boot services setting.
        String enabledOnFirstBootServices = Settings.Secure.getStringForUser(
                mContext.getContentResolver(),
                Settings.Secure.ENABLED_ON_FIRST_BOOT_SYSTEM_PRINT_SERVICES, mUserId);
        if (TextUtils.isEmpty(enabledOnFirstBootServices)) {
            enabledOnFirstBootServices = servicesToEnable;
        } else {
            enabledOnFirstBootServices = enabledOnFirstBootServices + ":" + enabledServices;
        }
        Settings.Secure.putStringForUser(mContext.getContentResolver(),
                Settings.Secure.ENABLED_ON_FIRST_BOOT_SYSTEM_PRINT_SERVICES,
                enabledOnFirstBootServices, mUserId);
    }

    private void onConfigurationChangedLocked() {
        final int installedCount = mInstalledServices.size();
        for (int i = 0; i < installedCount; i++) {
            ResolveInfo resolveInfo = mInstalledServices.get(i).getResolveInfo();
            ComponentName serviceName = new ComponentName(resolveInfo.serviceInfo.packageName,
                    resolveInfo.serviceInfo.name);
            if (mEnabledServices.contains(serviceName)) {
                if (!mActiveServices.containsKey(serviceName)) {
                    RemotePrintService service = new RemotePrintService(
                            mContext, serviceName, mUserId, mSpooler, this);
                    mActiveServices.put(serviceName, service);
                    if (mPrinterDiscoverySession != null) {
                        mPrinterDiscoverySession.onServiceAddedLocked(service);
                    }
                }
            } else {
                RemotePrintService service = mActiveServices.remove(serviceName);
                if (service != null) {
                    service.destroy();
                    if (mPrinterDiscoverySession != null) {
                        mPrinterDiscoverySession.onServiceRemovedLocked(serviceName);
                    }
                }
            }
        }
    }

    private void throwIfDestroyedLocked() {
        if (mDestroyed) {
            throw new IllegalStateException("Cannot interact with a destroyed instance.");
        }
    }

    private class PrinterDiscoverySessionMediator {
        private final ArrayMap<PrinterId, PrinterInfo> mPrinters =
                new ArrayMap<PrinterId, PrinterInfo>();

        private final RemoteCallbackList<IPrinterDiscoveryObserver> mDiscoveryObservers =
                new RemoteCallbackList<IPrinterDiscoveryObserver>() {
            @Override
            public void onCallbackDied(IPrinterDiscoveryObserver observer) {
                synchronized (mLock) {
                    stopPrinterDiscoveryLocked(observer);
                    removeObserverLocked(observer);
                }
            }
        };

        private final List<IBinder> mStartedPrinterDiscoveryTokens = new ArrayList<IBinder>();

        private final List<PrinterId> mStateTrackedPrinters = new ArrayList<PrinterId>();

        private final Handler mHandler;

        private boolean mIsDestroyed;

        public PrinterDiscoverySessionMediator(Context context) {
            mHandler = new SessionHandler(context.getMainLooper());
            // Kick off the session creation.
            List<RemotePrintService> services = new ArrayList<RemotePrintService>(
                    mActiveServices.values());
            mHandler.obtainMessage(SessionHandler
                    .MSG_DISPATCH_CREATE_PRINTER_DISCOVERY_SESSION, services)
                    .sendToTarget();
        }

        public void addObserverLocked(IPrinterDiscoveryObserver observer) {
            // Add the observer.
            mDiscoveryObservers.register(observer);

            // Bring the added observer up to speed with the printers.
            if (!mPrinters.isEmpty()) {
                List<PrinterInfo> printers = new ArrayList<PrinterInfo>(mPrinters.values());
                SomeArgs args = SomeArgs.obtain();
                args.arg1 = observer;
                args.arg2 = printers;
                mHandler.obtainMessage(SessionHandler.MSG_PRINTERS_ADDED,
                        args).sendToTarget();
            }
        }

        public void removeObserverLocked(IPrinterDiscoveryObserver observer) {
            // Remove the observer.
            mDiscoveryObservers.unregister(observer);
            // No one else observing - then kill it.
            if (mDiscoveryObservers.getRegisteredCallbackCount() == 0) {
                destroyLocked();
            }
        }

        public final void startPrinterDiscoveryLocked(IPrinterDiscoveryObserver observer,
                List<PrinterId> priorityList) {
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not starting dicovery - session destroyed");
                return;
            }

            // If printer discovery is ongoing and the start request has a list
            // of printer to be checked, then we just request validating them.
            if (!mStartedPrinterDiscoveryTokens.isEmpty()
                    && priorityList != null && !priorityList.isEmpty()) {
                validatePrinters(priorityList);
                return;
            }

            // Remember we got a start request to match with an end.
            mStartedPrinterDiscoveryTokens.add(observer.asBinder());
            // The service are already performing discovery - nothing to do.
            if (mStartedPrinterDiscoveryTokens.size() > 1) {
                return;
            }
            List<RemotePrintService> services = new ArrayList<RemotePrintService>(
                    mActiveServices.values());
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = services;
            args.arg2 = priorityList;
            mHandler.obtainMessage(SessionHandler
                    .MSG_DISPATCH_START_PRINTER_DISCOVERY, args)
                    .sendToTarget();
        }

        public final void stopPrinterDiscoveryLocked(IPrinterDiscoveryObserver observer) {
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not stopping dicovery - session destroyed");
                return;
            }
            // This one did not make an active discovery request - nothing to do.
            if (!mStartedPrinterDiscoveryTokens.remove(observer.asBinder())) {
                return;
            }
            // There are other interested observers - do not stop discovery.
            if (!mStartedPrinterDiscoveryTokens.isEmpty()) {
                return;
            }
            List<RemotePrintService> services = new ArrayList<RemotePrintService>(
                    mActiveServices.values());
            mHandler.obtainMessage(SessionHandler
                    .MSG_DISPATCH_STOP_PRINTER_DISCOVERY, services)
                    .sendToTarget();
        }

        public void validatePrintersLocked(List<PrinterId> printerIds) {
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not validating pritners - session destroyed");
                return;
            }

            List<PrinterId> remainingList = new ArrayList<PrinterId>(printerIds);
            while (!remainingList.isEmpty()) {
                Iterator<PrinterId> iterator = remainingList.iterator();
                // Gather the printers per service and request a validation.
                List<PrinterId> updateList = new ArrayList<PrinterId>();
                ComponentName serviceName = null;
                while (iterator.hasNext()) {
                    PrinterId printerId = iterator.next();
                    if (updateList.isEmpty()) {
                        updateList.add(printerId);
                        serviceName = printerId.getServiceName();
                        iterator.remove();
                    } else if (printerId.getServiceName().equals(serviceName)) {
                        updateList.add(printerId);
                        iterator.remove();
                    }
                }
                // Schedule a notification of the service.
                RemotePrintService service = mActiveServices.get(serviceName);
                if (service != null) {
                    SomeArgs args = SomeArgs.obtain();
                    args.arg1 = service;
                    args.arg2 = updateList;
                    mHandler.obtainMessage(SessionHandler
                            .MSG_VALIDATE_PRINTERS, args)
                            .sendToTarget();
                }
            }
        }

        public final void startPrinterStateTrackingLocked(PrinterId printerId) {
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not starting printer state tracking - session destroyed");
                return;
            }
            // If printer discovery is not started - nothing to do.
            if (mStartedPrinterDiscoveryTokens.isEmpty()) {
                return;
            }
            final boolean containedPrinterId = mStateTrackedPrinters.contains(printerId);
            // Keep track of the number of requests to track this one.
            mStateTrackedPrinters.add(printerId);
            // If we were tracking this printer - nothing to do.
            if (containedPrinterId) {
                return;
            }
            // No service - nothing to do.
            RemotePrintService service = mActiveServices.get(printerId.getServiceName());
            if (service == null) {
                return;
            }
            // Ask the service to start tracking.
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = service;
            args.arg2 = printerId;
            mHandler.obtainMessage(SessionHandler
                    .MSG_START_PRINTER_STATE_TRACKING, args)
                    .sendToTarget();
        }

        public final void stopPrinterStateTrackingLocked(PrinterId printerId) {
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not stopping printer state tracking - session destroyed");
                return;
            }
            // If printer discovery is not started - nothing to do.
            if (mStartedPrinterDiscoveryTokens.isEmpty()) {
                return;
            }
            // If we did not track this printer - nothing to do.
            if (!mStateTrackedPrinters.remove(printerId)) {
                return;
            }
            // No service - nothing to do.
            RemotePrintService service = mActiveServices.get(printerId.getServiceName());
            if (service == null) {
                return;
            }
            // Ask the service to start tracking.
            SomeArgs args = SomeArgs.obtain();
            args.arg1 = service;
            args.arg2 = printerId;
            mHandler.obtainMessage(SessionHandler
                    .MSG_STOP_PRINTER_STATE_TRACKING, args)
                    .sendToTarget();
        }

        public void onDestroyed() {
            /* do nothing */
        }

        public void destroyLocked() {
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not destroying - session destroyed");
                return;
            }
            // Make sure printer tracking is stopped.
            final int printerCount = mStateTrackedPrinters.size();
            for (int i = 0; i < printerCount; i++) {
                PrinterId printerId = mStateTrackedPrinters.get(i);
                stopPrinterStateTracking(printerId);
            }
            // Make sure discovery is stopped.
            final int observerCount = mStartedPrinterDiscoveryTokens.size();
            for (int i = 0; i < observerCount; i++) {
                IBinder token = mStartedPrinterDiscoveryTokens.get(i);
                stopPrinterDiscoveryLocked(IPrinterDiscoveryObserver.Stub.asInterface(token));
            }
            // Tell the services we are done.
            List<RemotePrintService> services = new ArrayList<RemotePrintService>(
                    mActiveServices.values());
            mHandler.obtainMessage(SessionHandler
                    .MSG_DISPATCH_DESTROY_PRINTER_DISCOVERY_SESSION, services)
                    .sendToTarget();
        }

        public void onPrintersAddedLocked(List<PrinterInfo> printers) {
            if (DEBUG) {
                Log.i(LOG_TAG, "onPrintersAddedLocked()");
            }
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not adding printers - session destroyed");
                return;
            }
            List<PrinterInfo> addedPrinters = null;
            final int addedPrinterCount = printers.size();
            for (int i = 0; i < addedPrinterCount; i++) {
                PrinterInfo printer = printers.get(i);
                if (!mPrinters.containsKey(printer.getId())) {
                    mPrinters.put(printer.getId(), printer);
                    if (addedPrinters == null) {
                        addedPrinters = new ArrayList<PrinterInfo>();
                    }
                    addedPrinters.add(printer);
                }
            }
            if (addedPrinters != null) {
                mHandler.obtainMessage(SessionHandler.MSG_DISPATCH_PRINTERS_ADDED,
                        addedPrinters).sendToTarget();
            }
        }

        public void onPrintersRemovedLocked(List<PrinterId> printerIds) {
            if (DEBUG) {
                Log.i(LOG_TAG, "onPrintersRemovedLocked()");
            }
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not removing printers - session destroyed");
                return;
            }
            List<PrinterId> removedPrinterIds = null;
            final int removedPrinterCount = printerIds.size();
            for (int i = 0; i < removedPrinterCount; i++) {
                PrinterId removedPrinterId = printerIds.get(i);
                if (mPrinters.remove(removedPrinterId) != null) {
                    if (removedPrinterIds == null) {
                        removedPrinterIds = new ArrayList<PrinterId>();
                    }
                    removedPrinterIds.add(removedPrinterId);
                }
            }
            if (removedPrinterIds != null) {
                mHandler.obtainMessage(SessionHandler.MSG_DISPATCH_PRINTERS_REMOVED,
                        removedPrinterIds).sendToTarget();
            }
        }

        public void onPrintersUpdatedLocked(List<PrinterInfo> printers) {
            if (DEBUG) {
                Log.i(LOG_TAG, "onPrintersUpdatedLocked()");
            }
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not updating printers - session destroyed");
                return;
            }
            List<PrinterInfo> updatedPrinters = null;
            final int updatedPrinterCount = printers.size();
            for (int i = 0; i < updatedPrinterCount; i++) {
                PrinterInfo updatedPrinter = printers.get(i);
                if (mPrinters.containsKey(updatedPrinter.getId())) {
                    mPrinters.put(updatedPrinter.getId(), updatedPrinter);
                    if (updatedPrinters == null) {
                        updatedPrinters = new ArrayList<PrinterInfo>();
                    }
                    updatedPrinters.add(updatedPrinter);
                }
            }
            if (updatedPrinters != null) {
                mHandler.obtainMessage(SessionHandler.MSG_DISPATCH_PRINTERS_UPDATED,
                        updatedPrinters).sendToTarget();
            }
        }

        public void onServiceRemovedLocked(ComponentName serviceName) {
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not updating removed service - session destroyed");
                return;
            }
            // No printers - nothing to do.
            if (mPrinters.isEmpty()) {
                return;
            }
            // Remove the printers for that service.
            List<PrinterInfo> removedPrinters = null;
            final int printerCount = mPrinters.size();
            for (int i = 0; i < printerCount; i++) {
                PrinterInfo printer = mPrinters.get(i);
                if (printer.getId().getServiceName().equals(serviceName)) {
                    if (removedPrinters == null) {
                        removedPrinters = new ArrayList<PrinterInfo>();
                    }
                    removedPrinters.add(printer);
                }
            }
            if (!removedPrinters.isEmpty()) {
                mHandler.obtainMessage(
                        SessionHandler.MSG_DISPATCH_PRINTERS_REMOVED,
                        removedPrinters).sendToTarget();
            }
        }

        public void onServiceAddedLocked(RemotePrintService service) {
            if (mIsDestroyed) {
                Log.w(LOG_TAG, "Not updating added service - session destroyed");
                return;
            }
            // Tell the service to create a session.
            mHandler.obtainMessage(
                    SessionHandler.MSG_CREATE_PRINTER_DISCOVERY_SESSION,
                    service).sendToTarget();
            // If there are some observers that started discovery - tell the service.
            if (mDiscoveryObservers.getRegisteredCallbackCount() > 0) {
                mHandler.obtainMessage(
                        SessionHandler.MSG_START_PRINTER_DISCOVERY,
                        service).sendToTarget();
            }
        }

        private void handleDispatchPrintersAdded(List<PrinterInfo> addedPrinters) {
            final int observerCount = mDiscoveryObservers.beginBroadcast();
            for (int i = 0; i < observerCount; i++) {
                IPrinterDiscoveryObserver observer = mDiscoveryObservers.getBroadcastItem(i);
                try {
                    observer.onPrintersAdded(addedPrinters);
                } catch (RemoteException re) {
                    Log.i(LOG_TAG, "Error dispatching added printers", re);
                }
            }
            mDiscoveryObservers.finishBroadcast();
        }

        private void handleDispatchPrintersRemoved(List<PrinterId> removedPrinterIds) {
            final int observerCount = mDiscoveryObservers.beginBroadcast();
            for (int i = 0; i < observerCount; i++) {
                IPrinterDiscoveryObserver observer = mDiscoveryObservers.getBroadcastItem(i);
                try {
                    observer.onPrintersRemoved(removedPrinterIds);
                } catch (RemoteException re) {
                    Log.i(LOG_TAG, "Error dispatching removed printers", re);
                }
            }
            mDiscoveryObservers.finishBroadcast();
        }

        private void handleDispatchPrintersUpdated(List<PrinterInfo> updatedPrinters) {
            final int observerCount = mDiscoveryObservers.beginBroadcast();
            for (int i = 0; i < observerCount; i++) {
                IPrinterDiscoveryObserver observer = mDiscoveryObservers.getBroadcastItem(i);
                try {
                    observer.onPrintersUpdated(updatedPrinters);
                } catch (RemoteException re) {
                    Log.i(LOG_TAG, "Error dispatching updated printers", re);
                }
            }
            mDiscoveryObservers.finishBroadcast();
        }

        private void handleDispatchCreatePrinterDiscoverySession(
                List<RemotePrintService> services) {
            final int serviceCount = services.size();
            for (int i = 0; i < serviceCount; i++) {
                RemotePrintService service = services.get(i);
                service.createPrinterDiscoverySession();
            }
        }

        private void handleDispatchDestroyPrinterDiscoverySession(
                List<RemotePrintService> services) {
            final int serviceCount = services.size();
            for (int i = 0; i < serviceCount; i++) {
                RemotePrintService service = services.get(i);
                service.destroyPrinterDiscoverySession();
            }
            onDestroyed();
        }

        private void handleDispatchStartPrinterDiscovery(
                List<RemotePrintService> services, List<PrinterId> printerIds) {
            final int serviceCount = services.size();
            for (int i = 0; i < serviceCount; i++) {
                RemotePrintService service = services.get(i);
                service.startPrinterDiscovery(printerIds);
            }
        }

        private void handleDispatchStopPrinterDiscovery(List<RemotePrintService> services) {
            final int serviceCount = services.size();
            for (int i = 0; i < serviceCount; i++) {
                RemotePrintService service = services.get(i);
                service.stopPrinterDiscovery();
            }
        }

        private void handleValidatePrinters(RemotePrintService service,
                List<PrinterId> printerIds) {
            service.validatePrinters(printerIds);
        }

        private void handleStartPrinterStateTracking(RemotePrintService service,
                PrinterId printerId) {
            service.startPrinterStateTracking(printerId);
        }

        private void handleStopPrinterStateTracking(RemotePrintService service,
                PrinterId printerId) {
            service.stopPrinterStateTracking(printerId);
        }

        private void handlePrintersAdded(IPrinterDiscoveryObserver observer,
            List<PrinterInfo> printers) {
            try {
                final int printerCount = printers.size();
                if (printerCount <= MAX_ITEMS_PER_CALLBACK) {
                    observer.onPrintersAdded(printers);
                } else {
                    // Send the added printers in chunks avoiding the binder transaction limit.
                    final int transactionCount = (printerCount / MAX_ITEMS_PER_CALLBACK) + 1;
                    for (int i = 0; i < transactionCount; i++) {
                        final int start = i * MAX_ITEMS_PER_CALLBACK;
                        final int end = Math.min(start + MAX_ITEMS_PER_CALLBACK, printerCount);
                        List<PrinterInfo> subPrinters = printers.subList(start, end);
                        observer.onPrintersAdded(subPrinters);
                    }
                }
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error sending added printers", re);
            }
        }

        private void handlePrintersRemoved(IPrinterDiscoveryObserver observer,
            List<PrinterId> printerIds) {
            try {
                final int printerCount = printerIds.size();
                if (printerCount <= MAX_ITEMS_PER_CALLBACK) {
                    observer.onPrintersRemoved(printerIds);
                } else {
                    // Send the added printers in chunks avoiding the binder transaction limit.
                    final int transactionCount = (printerCount / MAX_ITEMS_PER_CALLBACK) + 1;
                    for (int i = 0; i < transactionCount; i++) {
                        final int start = i * MAX_ITEMS_PER_CALLBACK;
                        final int end = Math.min(start + MAX_ITEMS_PER_CALLBACK, printerCount);
                        List<PrinterId> subPrinterIds = printerIds.subList(start, end);
                        observer.onPrintersRemoved(subPrinterIds);
                    }
               }
            } catch (RemoteException re) {
                Log.e(LOG_TAG, "Error sending added printers", re);
            }
        }

        private final class SessionHandler extends Handler {
            public static final int MSG_PRINTERS_ADDED = 1;
            public static final int MSG_PRINTERS_REMOVED = 2;
            public static final int MSG_DISPATCH_PRINTERS_ADDED = 3;
            public static final int MSG_DISPATCH_PRINTERS_REMOVED = 4;
            public static final int MSG_DISPATCH_PRINTERS_UPDATED = 5;

            public static final int MSG_CREATE_PRINTER_DISCOVERY_SESSION = 6;
            public static final int MSG_START_PRINTER_DISCOVERY = 7;
            public static final int MSG_DISPATCH_CREATE_PRINTER_DISCOVERY_SESSION = 8;
            public static final int MSG_DISPATCH_DESTROY_PRINTER_DISCOVERY_SESSION = 9;
            public static final int MSG_DISPATCH_START_PRINTER_DISCOVERY = 10;
            public static final int MSG_DISPATCH_STOP_PRINTER_DISCOVERY = 11;
            public static final int MSG_VALIDATE_PRINTERS = 12;
            public static final int MSG_START_PRINTER_STATE_TRACKING = 13;
            public static final int MSG_STOP_PRINTER_STATE_TRACKING = 14;

            SessionHandler(Looper looper) {
                super(looper, null, false);
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_PRINTERS_ADDED: {
                        SomeArgs args = (SomeArgs) message.obj;
                        IPrinterDiscoveryObserver observer = (IPrinterDiscoveryObserver) args.arg1;
                        List<PrinterInfo> addedPrinters = (List<PrinterInfo>) args.arg2;
                        args.recycle();
                        handlePrintersAdded(observer, addedPrinters);
                    } break;

                    case MSG_PRINTERS_REMOVED: {
                        SomeArgs args = (SomeArgs) message.obj;
                        IPrinterDiscoveryObserver observer = (IPrinterDiscoveryObserver) args.arg1;
                        List<PrinterId> removedPrinterIds = (List<PrinterId>) args.arg2;
                        args.recycle();
                        handlePrintersRemoved(observer, removedPrinterIds);
                    }

                    case MSG_DISPATCH_PRINTERS_ADDED: {
                        List<PrinterInfo> addedPrinters = (List<PrinterInfo>) message.obj;
                        handleDispatchPrintersAdded(addedPrinters);
                    } break;

                    case MSG_DISPATCH_PRINTERS_REMOVED: {
                        List<PrinterId> removedPrinterIds = (List<PrinterId>) message.obj;
                        handleDispatchPrintersRemoved(removedPrinterIds);
                    } break;

                    case MSG_DISPATCH_PRINTERS_UPDATED: {
                        List<PrinterInfo> updatedPrinters = (List<PrinterInfo>) message.obj;
                        handleDispatchPrintersUpdated(updatedPrinters);
                    } break;

                    case MSG_CREATE_PRINTER_DISCOVERY_SESSION: {
                        RemotePrintService service = (RemotePrintService) message.obj;
                        service.createPrinterDiscoverySession();
                    } break;

                    case MSG_START_PRINTER_DISCOVERY: {
                        RemotePrintService service = (RemotePrintService) message.obj;
                        service.startPrinterDiscovery(null);
                    } break;

                    case MSG_DISPATCH_CREATE_PRINTER_DISCOVERY_SESSION: {
                        List<RemotePrintService> services = (List<RemotePrintService>) message.obj;
                        handleDispatchCreatePrinterDiscoverySession(services);
                    } break;

                    case MSG_DISPATCH_DESTROY_PRINTER_DISCOVERY_SESSION: {
                        List<RemotePrintService> services = (List<RemotePrintService>) message.obj;
                        handleDispatchDestroyPrinterDiscoverySession(services);
                    } break;

                    case MSG_DISPATCH_START_PRINTER_DISCOVERY: {
                        SomeArgs args = (SomeArgs) message.obj;
                        List<RemotePrintService> services = (List<RemotePrintService>) args.arg1;
                        List<PrinterId> printerIds = (List<PrinterId>) args.arg2;
                        args.recycle();
                        handleDispatchStartPrinterDiscovery(services, printerIds);
                    } break;

                    case MSG_DISPATCH_STOP_PRINTER_DISCOVERY: {
                        List<RemotePrintService> services = (List<RemotePrintService>) message.obj;
                        handleDispatchStopPrinterDiscovery(services);
                    } break;

                    case MSG_VALIDATE_PRINTERS: {
                        SomeArgs args = (SomeArgs) message.obj;
                        RemotePrintService service = (RemotePrintService) args.arg1;
                        List<PrinterId> printerIds = (List<PrinterId>) args.arg2;
                        args.recycle();
                        handleValidatePrinters(service, printerIds);
                    } break;

                    case MSG_START_PRINTER_STATE_TRACKING: {
                        SomeArgs args = (SomeArgs) message.obj;
                        RemotePrintService service = (RemotePrintService) args.arg1;
                        PrinterId printerId = (PrinterId) args.arg2;
                        args.recycle();
                        handleStartPrinterStateTracking(service, printerId);
                    } break;

                    case MSG_STOP_PRINTER_STATE_TRACKING: {
                        SomeArgs args = (SomeArgs) message.obj;
                        RemotePrintService service = (RemotePrintService) args.arg1;
                        PrinterId printerId = (PrinterId) args.arg2;
                        args.recycle();
                        handleStopPrinterStateTracking(service, printerId);
                    }
                }
            }
        }
    }
}