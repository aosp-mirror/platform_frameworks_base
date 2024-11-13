/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.companion.virtual;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Process;
import android.util.SparseArray;

import java.io.PrintWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;

final class VirtualDeviceLog {
    public static int TYPE_CREATED = 0x0;
    public static int TYPE_CLOSED = 0x1;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern(
            "MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final int MAX_ENTRIES = 16;

    private static final String VIRTUAL_DEVICE_OWNER_SYSTEM = "system";

    private final Context mContext;
    private final ArrayDeque<LogEntry> mLogEntries = new ArrayDeque<>();

    VirtualDeviceLog(Context context) {
        mContext = context;
    }

    void logCreated(int deviceId, int ownerUid) {
        final long token = Binder.clearCallingIdentity();
        try {
            addEntry(new LogEntry(TYPE_CREATED, deviceId, System.currentTimeMillis(), ownerUid));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    void logClosed(int deviceId, int ownerUid) {
        final long token = Binder.clearCallingIdentity();
        try {
            addEntry(new LogEntry(TYPE_CLOSED, deviceId, System.currentTimeMillis(), ownerUid));
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    private void addEntry(LogEntry entry) {
        mLogEntries.push(entry);
        if (mLogEntries.size() > MAX_ENTRIES) {
            mLogEntries.removeLast();
        }
    }

    void dump(PrintWriter pw) {
        final long token = Binder.clearCallingIdentity();
        try {
            pw.println("VirtualDevice Log:");
            UidToPackageNameCache packageNameCache = new UidToPackageNameCache(
                    mContext.getPackageManager());
            for (LogEntry logEntry : mLogEntries) {
                logEntry.dump(pw, "  ", packageNameCache);

            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    static class LogEntry {
        private final int mType;
        private final int mDeviceId;
        private final long mTimestamp;
        private final int mOwnerUid;

        LogEntry(int type, int deviceId, long timestamp, int ownerUid) {
            this.mType = type;
            this.mDeviceId = deviceId;
            this.mTimestamp = timestamp;
            this.mOwnerUid = ownerUid;
        }

        void dump(PrintWriter pw, String prefix, UidToPackageNameCache packageNameCache) {
            StringBuilder sb = new StringBuilder(prefix);
            sb.append(DATE_FORMAT.format(Instant.ofEpochMilli(mTimestamp)));
            sb.append(" - ");
            sb.append(mType == TYPE_CREATED ? "START" : "CLOSE");
            sb.append(" Device ID: ");
            sb.append(mDeviceId);
            sb.append(" ");
            sb.append(mOwnerUid);
            sb.append(" (");
            sb.append(packageNameCache.getPackageName(mOwnerUid));
            sb.append(")");
            pw.println(sb);
        }
    }

    private static class UidToPackageNameCache {
        private final PackageManager mPackageManager;
        private final SparseArray<String> mUidToPackagesCache = new SparseArray<>();

        public UidToPackageNameCache(PackageManager packageManager) {
            mPackageManager = packageManager;
        }

        String getPackageName(int ownerUid) {
            String[] packages;
            if (mUidToPackagesCache.contains(ownerUid)) {
                return mUidToPackagesCache.get(ownerUid);
            } else if (ownerUid == Process.SYSTEM_UID) {
                return VIRTUAL_DEVICE_OWNER_SYSTEM;
            } else {
                packages = mPackageManager.getPackagesForUid(ownerUid);
                String packageName = "";
                if (packages != null && packages.length > 0) {
                    packageName = packages[0];
                    if (packages.length > 1) {
                        StringBuilder sb = new StringBuilder();
                        sb.append(packageName)
                                .append(",...");
                        packageName = sb.toString();
                    }
                }
                mUidToPackagesCache.put(ownerUid, packageName);
                return packageName;
            }
        }
    }
}
