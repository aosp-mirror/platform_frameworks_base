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

import android.app.admin.SecurityLog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import com.android.internal.os.BackgroundThread;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import android.util.Slog;

public final class ProcessLoggingHandler extends Handler {

    private static final String TAG = "ProcessLoggingHandler";
    static final int LOG_APP_PROCESS_START_MSG = 1;
    static final int INVALIDATE_BASE_APK_HASH_MSG = 2;

    private final HashMap<String, String> mProcessLoggingBaseApkHashes = new HashMap();

    ProcessLoggingHandler() {
        super(BackgroundThread.getHandler().getLooper());
    }

    @Override
    public void handleMessage(Message msg) {
        switch (msg.what) {
            case LOG_APP_PROCESS_START_MSG: {
                Bundle bundle = msg.getData();
                String processName = bundle.getString("processName");
                int uid = bundle.getInt("uid");
                String seinfo = bundle.getString("seinfo");
                String apkFile = bundle.getString("apkFile");
                int pid = bundle.getInt("pid");
                long startTimestamp = bundle.getLong("startTimestamp");
                String apkHash = computeStringHashOfApk(apkFile);
                SecurityLog.writeEvent(SecurityLog.TAG_APP_PROCESS_START, processName,
                        startTimestamp, uid, pid, seinfo, apkHash);
                break;
            }
            case INVALIDATE_BASE_APK_HASH_MSG: {
                Bundle bundle = msg.getData();
                mProcessLoggingBaseApkHashes.remove(bundle.getString("apkFile"));
                break;
            }
        }
    }

    void invalidateProcessLoggingBaseApkHash(String apkPath) {
        Bundle data = new Bundle();
        data.putString("apkFile", apkPath);
        Message msg = obtainMessage(INVALIDATE_BASE_APK_HASH_MSG);
        msg.setData(data);
        sendMessage(msg);
    }

    private String computeStringHashOfApk(String apkFile) {
        if (apkFile == null) {
            return "No APK";
        }
        String apkHash = mProcessLoggingBaseApkHashes.get(apkFile);
        if (apkHash == null) {
            try {
                byte[] hash = computeHashOfApkFile(apkFile);
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < hash.length; i++) {
                    sb.append(String.format("%02x", hash[i]));
                }
                apkHash = sb.toString();
                mProcessLoggingBaseApkHashes.put(apkFile, apkHash);
            } catch (IOException | NoSuchAlgorithmException e) {
                Slog.w(TAG, "computeStringHashOfApk() failed", e);
            }
        }
        return apkHash != null ? apkHash : "Failed to count APK hash";
    }

    private byte[] computeHashOfApkFile(String packageArchiveLocation)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        FileInputStream input = new FileInputStream(new File(packageArchiveLocation));
        byte[] buffer = new byte[65536];
        int size;
        while ((size = input.read(buffer)) > 0) {
            md.update(buffer, 0, size);
        }
        input.close();
        return md.digest();
    }
}
