/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.pm;

import static android.content.pm.PackageManager.EXTRA_CHECKSUMS;

import android.app.admin.SecurityLog;
import android.content.Context;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApkChecksum;
import android.content.pm.Checksum;
import android.content.pm.IPackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcelable;
import android.os.RemoteException;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ProcessLoggingHandler extends Handler {
    private static final String TAG = "ProcessLoggingHandler";

    private static final int LOG_APP_PROCESS_START_MSG = 1;

    private static final int CHECKSUM_TYPE = Checksum.TYPE_WHOLE_SHA256;

    static class LoggingInfo {
        public String apkHash = null;
        public List<Bundle> pendingLogEntries = new ArrayList<>();
    }

    // Apk path to logging info map.
    private final ArrayMap<String, LoggingInfo> mLoggingInfo = new ArrayMap<>();

    ProcessLoggingHandler() {
        super(BackgroundThread.getHandler().getLooper());
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case LOG_APP_PROCESS_START_MSG: {
                Bundle bundle = msg.getData();
                long startTimestamp = bundle.getLong("startTimestamp");
                String processName = bundle.getString("processName");
                int uid = bundle.getInt("uid");
                String seinfo = bundle.getString("seinfo");
                int pid = bundle.getInt("pid");
                String apkHash = bundle.getString("apkHash");
                SecurityLog.writeEvent(SecurityLog.TAG_APP_PROCESS_START, processName,
                        startTimestamp, uid, pid, seinfo, apkHash);
                break;
            }
        }
    }

    void logAppProcessStart(Context context, IPackageManager pms, String apkFile,
            String packageName, String processName, int uid, String seinfo, int pid) {
        Bundle data = new Bundle();
        data.putLong("startTimestamp", System.currentTimeMillis());
        data.putString("processName", processName);
        data.putInt("uid", uid);
        data.putString("seinfo", seinfo);
        data.putInt("pid", pid);

        if (apkFile == null) {
            enqueueSecurityLogEvent(data, "No APK");
            return;
        }

        // Check cached apk hash.
        boolean requestChecksums;
        final LoggingInfo loggingInfo;
        synchronized (mLoggingInfo) {
            LoggingInfo cached = mLoggingInfo.get(apkFile);
            requestChecksums = cached == null;
            if (requestChecksums) {
                // Create a new pending cache entry.
                cached = new LoggingInfo();
                mLoggingInfo.put(apkFile, cached);
            }
            loggingInfo = cached;
        }

        synchronized (loggingInfo) {
            // Still pending?
            if (!TextUtils.isEmpty(loggingInfo.apkHash)) {
                enqueueSecurityLogEvent(data, loggingInfo.apkHash);
                return;
            }

            loggingInfo.pendingLogEntries.add(data);
        }

        if (!requestChecksums) {
            return;
        }

        // Request base checksums when first added entry.
        // Capturing local loggingInfo to still log even if hash was invalidated.
        try {
            pms.requestChecksums(packageName, false, 0, CHECKSUM_TYPE, null,
                    new IntentSender((IIntentSender) new IIntentSender.Stub() {
                        @Override
                        public void send(int code, Intent intent, String resolvedType,
                                IBinder allowlistToken, IIntentReceiver finishedReceiver,
                                String requiredPermission, Bundle options) {
                            processChecksums(loggingInfo, intent);
                        }
                    }), context.getUserId());
        } catch (RemoteException e) {
            Slog.e(TAG, "requestChecksums() failed", e);
            processChecksums(loggingInfo, null);
        }
    }

    void processChecksums(final LoggingInfo loggingInfo, Intent intent) {
        Parcelable[] parcelables = intent.getParcelableArrayExtra(EXTRA_CHECKSUMS);
        ApkChecksum[] checksums = Arrays.copyOf(parcelables, parcelables.length,
                ApkChecksum[].class);

        for (ApkChecksum checksum : checksums) {
            if (checksum.getType() == CHECKSUM_TYPE) {
                processChecksum(loggingInfo, checksum.getValue());
                break;
            }
        }
    }

    void processChecksum(final LoggingInfo loggingInfo, final byte[] hash) {
        final String apkHash;
        if (hash != null) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hash.length; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            apkHash = sb.toString();
        } else {
            apkHash = "Failed to count APK hash";
        }

        List<Bundle> pendingLogEntries;
        synchronized (loggingInfo) {
            if (!TextUtils.isEmpty(loggingInfo.apkHash)) {
                return;
            }
            loggingInfo.apkHash = apkHash;

            pendingLogEntries = loggingInfo.pendingLogEntries;
            loggingInfo.pendingLogEntries = null;
        }

        if (pendingLogEntries != null) {
            for (Bundle data : pendingLogEntries) {
                enqueueSecurityLogEvent(data, apkHash);
            }
        }
    }

    void enqueueSecurityLogEvent(Bundle data, String apkHash) {
        data.putString("apkHash", apkHash);

        Message msg = this.obtainMessage(LOG_APP_PROCESS_START_MSG);
        msg.setData(data);
        this.sendMessage(msg);
    }

    void invalidateBaseApkHash(String apkFile) {
        synchronized (mLoggingInfo) {
            mLoggingInfo.remove(apkFile);
        }
    }
}
