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

package com.android.server.backup.params;

import android.annotation.Nullable;
import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IRestoreObserver;
import android.content.pm.PackageInfo;

import com.android.server.backup.internal.OnTaskFinishedListener;
import com.android.server.backup.transport.TransportClient;

import java.util.Map;
import java.util.Set;

public class RestoreParams {
    public final TransportClient transportClient;
    public final IRestoreObserver observer;
    public final IBackupManagerMonitor monitor;
    public final long token;
    @Nullable public final PackageInfo packageInfo;
    public final int pmToken; // in post-install restore, the PM's token for this transaction
    public final boolean isSystemRestore;
    @Nullable public final String[] filterSet;
    public final OnTaskFinishedListener listener;

    /**
     * No kill after restore.
     */
    public static RestoreParams createForSinglePackage(
            TransportClient transportClient,
            IRestoreObserver observer,
            IBackupManagerMonitor monitor,
            long token,
            PackageInfo packageInfo,
            OnTaskFinishedListener listener) {
        return new RestoreParams(
                transportClient,
                observer,
                monitor,
                token,
                packageInfo,
                /* pmToken */ 0,
                /* isSystemRestore */ false,
                /* filterSet */ null,
                listener);
    }

    /**
     * Kill after restore.
     */
    public static RestoreParams createForRestoreAtInstall(
            TransportClient transportClient,
            IRestoreObserver observer,
            IBackupManagerMonitor monitor,
            long token,
            String packageName,
            int pmToken,
            OnTaskFinishedListener listener) {
        String[] filterSet = {packageName};
        return new RestoreParams(
                transportClient,
                observer,
                monitor,
                token,
                /* packageInfo */ null,
                pmToken,
                /* isSystemRestore */ false,
                filterSet,
                listener);
    }

    /**
     * This is the form that Setup Wizard or similar restore UXes use.
     */
    public static RestoreParams createForRestoreAll(
            TransportClient transportClient,
            IRestoreObserver observer,
            IBackupManagerMonitor monitor,
            long token,
            OnTaskFinishedListener listener) {
        return new RestoreParams(
                transportClient,
                observer,
                monitor,
                token,
                /* packageInfo */ null,
                /* pmToken */ 0,
                /* isSystemRestore */ true,
                /* filterSet */ null,
                listener);
    }

    /**
     * Caller specifies whether is considered a system-level restore.
     */
    public static RestoreParams createForRestorePackages(
            TransportClient transportClient,
            IRestoreObserver observer,
            IBackupManagerMonitor monitor,
            long token,
            String[] filterSet,
            boolean isSystemRestore,
            OnTaskFinishedListener listener) {
        return new RestoreParams(
                transportClient,
                observer,
                monitor,
                token,
                /* packageInfo */ null,
                /* pmToken */ 0,
                isSystemRestore,
                filterSet,
                listener);
    }

    private RestoreParams(
            TransportClient transportClient,
            IRestoreObserver observer,
            IBackupManagerMonitor monitor,
            long token,
            @Nullable PackageInfo packageInfo,
            int pmToken,
            boolean isSystemRestore,
            @Nullable String[] filterSet,
            OnTaskFinishedListener listener) {
        this.transportClient = transportClient;
        this.observer = observer;
        this.monitor = monitor;
        this.token = token;
        this.packageInfo = packageInfo;
        this.pmToken = pmToken;
        this.isSystemRestore = isSystemRestore;
        this.filterSet = filterSet;
        this.listener = listener;
    }
}
