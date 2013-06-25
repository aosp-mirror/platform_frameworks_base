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
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.print.IPrinterDiscoveryObserver;
import android.print.PrintJobInfo;
import android.printservice.PrintServiceInfo;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.TextUtils.SimpleStringSplitter;
import android.util.Slog;

import com.android.server.print.RemotePrintSpooler.PrintSpoolerCallbacks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents the print state for a user.
 */
final class UserState implements PrintSpoolerCallbacks {

    private static final String LOG_TAG = "UserState";

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

    private boolean mDestroyed;

    public UserState(Context context, int userId, Object lock) {
        mContext = context;
        mUserId = userId;
        mLock = lock;
        mSpooler = new RemotePrintSpooler(context, userId, this);
    }

    @Override
    public void onPrintJobQueued(PrintJobInfo printJob) {
        final RemotePrintService service;
        synchronized (mLock) {
            throwIfDestroyedLocked();
            ComponentName printServiceName = printJob.getPrinterId().getService();
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

    @Override
    public void onStartPrinterDiscovery(IPrinterDiscoveryObserver observer) {
        final List<RemotePrintService> services;
        synchronized (mLock) {
            throwIfDestroyedLocked();
            if (mActiveServices.isEmpty()) {
                return;
            }
            services = new ArrayList<RemotePrintService>(mActiveServices.values());
        }
        final int serviceCount = services.size();
        for (int i = 0; i < serviceCount; i++) {
            RemotePrintService service = services.get(i);
            service.onStartPrinterDiscovery(observer);
        }
    }

    @Override
    public void onStopPrinterDiscovery() {
        final List<RemotePrintService> services;
        synchronized (mLock) {
            throwIfDestroyedLocked();
            if (mActiveServices.isEmpty()) {
                return;
            }
            services = new ArrayList<RemotePrintService>(mActiveServices.values());
        }
        final int serviceCount = services.size();
        for (int i = 0; i < serviceCount; i++) {
            RemotePrintService service = services.get(i);
            service.onStopPrinterDiscovery();
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

    public Map<ComponentName, RemotePrintService> getActiveServices() {
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

        String settingValue = Settings.Secure.getStringForUser(mContext.getContentResolver(),
                Settings.Secure.ENABLED_PRINT_SERVICES, mUserId);
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
                    tempEnabledServiceNameSet.add(componentName);
                }
            }
        }

        if (!tempEnabledServiceNameSet.equals(mEnabledServices)) {
            mEnabledServices.clear();
            mEnabledServices.addAll(tempEnabledServiceNameSet);
            return true;
        }

        return false;
    }

    private void onConfigurationChangedLocked() {
        final int installedCount = mInstalledServices.size();
        for (int i = 0; i < installedCount; i++) {
            ResolveInfo resolveInfo = mInstalledServices.get(i).getResolveInfo();
            ComponentName serviceName = new ComponentName(resolveInfo.serviceInfo.packageName,
                    resolveInfo.serviceInfo.name);
            if (mEnabledServices.contains(serviceName)) {
                if (!mActiveServices.containsKey(serviceName)) {
                    mActiveServices.put(serviceName, new RemotePrintService(
                            mContext, serviceName, mUserId, mSpooler));
                }
            } else {
                RemotePrintService service = mActiveServices.remove(serviceName);
                if (service != null) {
                    service.destroy();
                }
            }
        }
    }

    private void throwIfDestroyedLocked() {
        if (mDestroyed) {
            throw new IllegalStateException("Cannot interact with a destroyed instance.");
        }
    }
}

