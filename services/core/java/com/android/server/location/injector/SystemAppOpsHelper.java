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

package com.android.server.location.injector;

import android.app.AppOpsManager;
import android.content.Context;
import android.location.util.identity.CallerIdentity;
import android.os.Binder;

import com.android.internal.util.Preconditions;
import com.android.server.FgThread;

import java.util.Objects;

/**
 * Provides helpers and listeners for appops.
 */
public class SystemAppOpsHelper extends AppOpsHelper {

    private final Context mContext;

    private AppOpsManager mAppOps;

    public SystemAppOpsHelper(Context context) {
        mContext = context;
    }

    /** Called when system is ready. */
    public void onSystemReady() {
        if (mAppOps != null) {
            return;
        }

        mAppOps = Objects.requireNonNull(mContext.getSystemService(AppOpsManager.class));
        mAppOps.startWatchingMode(
                AppOpsManager.OP_COARSE_LOCATION,
                null,
                AppOpsManager.WATCH_FOREGROUND_CHANGES,
                (op, packageName) -> {
                    // invoked on ui thread, move to fg thread so ui thread isn't blocked
                    FgThread.getHandler().post(() -> notifyAppOpChanged(packageName));
                });
    }

    @Override
    public boolean startOpNoThrow(int appOp, CallerIdentity callerIdentity) {
        Preconditions.checkState(mAppOps != null);

        final long identity = Binder.clearCallingIdentity();
        try {
            return mAppOps.startOpNoThrow(
                    appOp,
                    callerIdentity.getUid(),
                    callerIdentity.getPackageName(),
                    false,
                    callerIdentity.getAttributionTag(),
                    callerIdentity.getListenerId()) == AppOpsManager.MODE_ALLOWED;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public void finishOp(int appOp, CallerIdentity callerIdentity) {
        Preconditions.checkState(mAppOps != null);

        final long identity = Binder.clearCallingIdentity();
        try {
            mAppOps.finishOp(
                    appOp,
                    callerIdentity.getUid(),
                    callerIdentity.getPackageName(),
                    callerIdentity.getAttributionTag());
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public boolean checkOpNoThrow(int appOp, CallerIdentity callerIdentity) {
        Preconditions.checkState(mAppOps != null);

        final long identity = Binder.clearCallingIdentity();
        try {
            return mAppOps.checkOpNoThrow(
                    appOp,
                    callerIdentity.getUid(),
                    callerIdentity.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public boolean noteOp(int appOp, CallerIdentity callerIdentity) {
        Preconditions.checkState(mAppOps != null);

        final long identity = Binder.clearCallingIdentity();
        try {
            return mAppOps.noteOp(
                    appOp,
                    callerIdentity.getUid(),
                    callerIdentity.getPackageName(),
                    callerIdentity.getAttributionTag(),
                    callerIdentity.getListenerId()) == AppOpsManager.MODE_ALLOWED;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @Override
    public boolean noteOpNoThrow(int appOp, CallerIdentity callerIdentity) {
        Preconditions.checkState(mAppOps != null);

        final long identity = Binder.clearCallingIdentity();
        try {
            return mAppOps.noteOpNoThrow(
                    appOp,
                    callerIdentity.getUid(),
                    callerIdentity.getPackageName(),
                    callerIdentity.getAttributionTag(),
                    callerIdentity.getListenerId()) == AppOpsManager.MODE_ALLOWED;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }
}
