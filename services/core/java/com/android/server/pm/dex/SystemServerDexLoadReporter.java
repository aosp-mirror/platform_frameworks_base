/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.dex;

import static com.android.server.pm.PackageManagerService.PLATFORM_PACKAGE_NAME;

import android.content.pm.IPackageManager;
import android.os.RemoteException;
import android.util.Log;
import android.util.Slog;

import dalvik.system.BaseDexClassLoader;
import dalvik.system.VMRuntime;

import java.util.Map;

/**
 * Reports dex file use to the package manager on behalf of system server.
 */
public class SystemServerDexLoadReporter implements BaseDexClassLoader.Reporter {
    private static final String TAG = "SystemServerDexLoadReporter";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final IPackageManager mPackageManager;

    public SystemServerDexLoadReporter(IPackageManager pm) {
        mPackageManager = pm;
    }

    @Override
    public void report(Map<String, String> classLoaderContextMap) {
        if (DEBUG) {
            Slog.i(TAG, "Reporting "  + classLoaderContextMap);
        }
        if (classLoaderContextMap.isEmpty()) {
            Slog.wtf(TAG, "Bad call to DexLoadReporter: empty classLoaderContextMap");
            return;
        }

        try {
            mPackageManager.notifyDexLoad(
                    PLATFORM_PACKAGE_NAME,
                    classLoaderContextMap,
                    VMRuntime.getRuntime().vmInstructionSet());
        } catch (RemoteException ignored) {
            // We're in system server, it can't happen.
        }
    }
}
