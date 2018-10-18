/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.server.adb;

import android.content.Context;
import android.debug.AdbManagerInternal;
import android.debug.IAdbManager;
import android.os.Binder;

import com.android.internal.util.DumpUtils;
import com.android.internal.util.IndentingPrintWriter;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * The Android Debug Bridge (ADB) service. This controls the availability of ADB and authorization
 * of devices allowed to connect to ADB.
 */
public class AdbService extends IAdbManager.Stub {
    /**
     * Manages the service lifecycle for {@code AdbService} in {@code SystemServer}.
     */
    public static class Lifecycle extends SystemService {
        private AdbService mAdbService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mAdbService = new AdbService(getContext());
            publishBinderService(Context.ADB_SERVICE, mAdbService);
        }
    }

    private class AdbManagerInternalImpl extends AdbManagerInternal {
    }

    private static final String TAG = "AdbService";

    private final Context mContext;

    private AdbService(Context context) {
        mContext = context;

        LocalServices.addService(AdbManagerInternal.class, new AdbManagerInternalImpl());
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) return;

        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");
        final long ident = Binder.clearCallingIdentity();
        try {
            if (args == null || args.length == 0 || "-a".equals(args[0])) {
                pw.println("ADB Manager State:");
                pw.increaseIndent();
                pw.println("None");
                // TODO: flesh out with status.
            } else {
                pw.println("Dump current ADB state");
                pw.println("  No commands available");
            }
        } finally {
            Binder.restoreCallingIdentity(ident);
        }
    }
}
