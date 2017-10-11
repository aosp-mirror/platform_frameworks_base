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
 * limitations under the License.
 */

package com.android.server.timezone;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Binder;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.Executor;
import libcore.io.Streams;

/**
 * A single class that implements multiple helper interfaces for use by {@link RulesManagerService}.
 */
final class RulesManagerServiceHelperImpl implements PermissionHelper, Executor {

    private final Context mContext;

    RulesManagerServiceHelperImpl(Context context) {
        mContext = context;
    }

    @Override
    public void enforceCallerHasPermission(String requiredPermission) {
        mContext.enforceCallingPermission(requiredPermission, null /* message */);
    }

    @Override
    public boolean checkDumpPermission(String tag, PrintWriter pw) {
        // TODO(nfuller): Switch to DumpUtils.checkDumpPermission() when it is available in AOSP.
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump LocationManagerService from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid());
            return false;
        }
        return true;
    }

    @Override
    public void execute(Runnable runnable) {
        AsyncTask.execute(runnable);
    }
}
