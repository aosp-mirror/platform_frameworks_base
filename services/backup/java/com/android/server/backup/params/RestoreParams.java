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

import android.app.backup.IBackupManagerMonitor;
import android.app.backup.IRestoreObserver;
import android.content.pm.PackageInfo;

import com.android.internal.backup.IBackupTransport;

public class RestoreParams {

    public IBackupTransport transport;
    public String dirName;
    public IRestoreObserver observer;
    public IBackupManagerMonitor monitor;
    public long token;
    public PackageInfo pkgInfo;
    public int pmToken; // in post-install restore, the PM's token for this transaction
    public boolean isSystemRestore;
    public String[] filterSet;

    /**
     * Restore a single package; no kill after restore
     */
    public RestoreParams(IBackupTransport _transport, String _dirName, IRestoreObserver _obs,
            IBackupManagerMonitor _monitor, long _token, PackageInfo _pkg) {
        transport = _transport;
        dirName = _dirName;
        observer = _obs;
        monitor = _monitor;
        token = _token;
        pkgInfo = _pkg;
        pmToken = 0;
        isSystemRestore = false;
        filterSet = null;
    }

    /**
     * Restore at install: PM token needed, kill after restore
     */
    public RestoreParams(IBackupTransport _transport, String _dirName, IRestoreObserver _obs,
            IBackupManagerMonitor _monitor, long _token, String _pkgName, int _pmToken) {
        transport = _transport;
        dirName = _dirName;
        observer = _obs;
        monitor = _monitor;
        token = _token;
        pkgInfo = null;
        pmToken = _pmToken;
        isSystemRestore = false;
        filterSet = new String[]{_pkgName};
    }

    /**
     * Restore everything possible.  This is the form that Setup Wizard or similar
     * restore UXes use.
     */
    public RestoreParams(IBackupTransport _transport, String _dirName, IRestoreObserver _obs,
            IBackupManagerMonitor _monitor, long _token) {
        transport = _transport;
        dirName = _dirName;
        observer = _obs;
        monitor = _monitor;
        token = _token;
        pkgInfo = null;
        pmToken = 0;
        isSystemRestore = true;
        filterSet = null;
    }

    /**
     * Restore some set of packages.  Leave this one up to the caller to specify
     * whether it's to be considered a system-level restore.
     */
    public RestoreParams(IBackupTransport _transport, String _dirName, IRestoreObserver _obs,
            IBackupManagerMonitor _monitor, long _token,
            String[] _filterSet, boolean _isSystemRestore) {
        transport = _transport;
        dirName = _dirName;
        observer = _obs;
        monitor = _monitor;
        token = _token;
        pkgInfo = null;
        pmToken = 0;
        isSystemRestore = _isSystemRestore;
        filterSet = _filterSet;
    }
}
