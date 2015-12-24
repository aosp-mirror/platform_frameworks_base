/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.updates;

import com.android.server.EventLogTags;
import com.android.internal.util.HexDump;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.EventLog;
import android.util.Slog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import libcore.io.IoUtils;
import libcore.io.Streams;

public class ConfigUpdateInstallReceiver extends BroadcastReceiver {

    private static final String TAG = "ConfigUpdateInstallReceiver";

    private static final String EXTRA_REQUIRED_HASH = "REQUIRED_HASH";
    private static final String EXTRA_VERSION_NUMBER = "VERSION";

    protected final File updateDir;
    protected final File updateContent;
    protected final File updateVersion;

    public ConfigUpdateInstallReceiver(String updateDir, String updateContentPath,
                                       String updateMetadataPath, String updateVersionPath) {
        this.updateDir = new File(updateDir);
        this.updateContent = new File(updateDir, updateContentPath);
        File updateMetadataDir = new File(updateDir, updateMetadataPath);
        this.updateVersion = new File(updateMetadataDir, updateVersionPath);
    }

    @Override
    public void onReceive(final Context context, final Intent intent) {
        new Thread() {
            @Override
            public void run() {
                try {
                    // get the content path from the extras
                    byte[] altContent = getAltContent(context, intent);
                    // get the version from the extras
                    int altVersion = getVersionFromIntent(intent);
                    // get the previous value from the extras
                    String altRequiredHash = getRequiredHashFromIntent(intent);
                    // get the version currently being used
                    int currentVersion = getCurrentVersion();
                    // get the hash of the currently used value
                    String currentHash = getCurrentHash(getCurrentContent());
                    if (!verifyVersion(currentVersion, altVersion)) {
                        Slog.i(TAG, "Not installing, new version is <= current version");
                    } else if (!verifyPreviousHash(currentHash, altRequiredHash)) {
                        EventLog.writeEvent(EventLogTags.CONFIG_INSTALL_FAILED,
                                            "Current hash did not match required value");
                    } else {
                        // install the new content
                        Slog.i(TAG, "Found new update, installing...");
                        install(altContent, altVersion);
                        Slog.i(TAG, "Installation successful");
                        postInstall(context, intent);
                    }
                } catch (Exception e) {
                    Slog.e(TAG, "Could not update content!", e);
                    // keep the error message <= 100 chars
                    String errMsg = e.toString();
                    if (errMsg.length() > 100) {
                        errMsg = errMsg.substring(0, 99);
                    }
                    EventLog.writeEvent(EventLogTags.CONFIG_INSTALL_FAILED, errMsg);
                }
            }
        }.start();
    }

    private Uri getContentFromIntent(Intent i) {
        Uri data = i.getData();
        if (data == null) {
            throw new IllegalStateException("Missing required content path, ignoring.");
        }
        return data;
    }

    private int getVersionFromIntent(Intent i) throws NumberFormatException {
        String extraValue = i.getStringExtra(EXTRA_VERSION_NUMBER);
        if (extraValue == null) {
            throw new IllegalStateException("Missing required version number, ignoring.");
        }
        return Integer.parseInt(extraValue.trim());
    }

    private String getRequiredHashFromIntent(Intent i) {
        String extraValue = i.getStringExtra(EXTRA_REQUIRED_HASH);
        if (extraValue == null) {
            throw new IllegalStateException("Missing required previous hash, ignoring.");
        }
        return extraValue.trim();
    }

    private int getCurrentVersion() throws NumberFormatException {
        try {
            String strVersion = IoUtils.readFileAsString(updateVersion.getCanonicalPath()).trim();
            return Integer.parseInt(strVersion);
        } catch (IOException e) {
            Slog.i(TAG, "Couldn't find current metadata, assuming first update");
            return 0;
        }
    }

    private byte[] getAltContent(Context c, Intent i) throws IOException {
        Uri content = getContentFromIntent(i);
        InputStream is = c.getContentResolver().openInputStream(content);
        try {
            return Streams.readFullyNoClose(is);
        } finally {
            is.close();
        }
    }

    private byte[] getCurrentContent() {
        try {
            return IoUtils.readFileAsByteArray(updateContent.getCanonicalPath());
        } catch (IOException e) {
            Slog.i(TAG, "Failed to read current content, assuming first update!");
            return null;
        }
    }

    private static String getCurrentHash(byte[] content) {
        if (content == null) {
            return "0";
        }
        try {
            MessageDigest dgst = MessageDigest.getInstance("SHA512");
            byte[] fingerprint = dgst.digest(content);
            return HexDump.toHexString(fingerprint, false);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private boolean verifyVersion(int current, int alternative) {
        return (current < alternative);
    }

    private boolean verifyPreviousHash(String current, String required) {
        // this is an optional value- if the required field is NONE then we ignore it
        if (required.equals("NONE")) {
            return true;
        }
        // otherwise, verify that we match correctly
        return current.equals(required);
    }

    protected void writeUpdate(File dir, File file, byte[] content) throws IOException {
        FileOutputStream out = null;
        File tmp = null;
        try {
            // create the parents for the destination file
            File parent = file.getParentFile();
            parent.mkdirs();
            // check that they were created correctly
            if (!parent.exists()) {
                throw new IOException("Failed to create directory " + parent.getCanonicalPath());
            }
            // create the temporary file
            tmp = File.createTempFile("journal", "", dir);
            // mark tmp -rw-r--r--
            tmp.setReadable(true, false);
            // write to it
            out = new FileOutputStream(tmp);
            out.write(content);
            // sync to disk
            out.getFD().sync();
            // atomic rename
            if (!tmp.renameTo(file)) {
                throw new IOException("Failed to atomically rename " + file.getCanonicalPath());
            }
        } finally {
            if (tmp != null) {
                tmp.delete();
            }
            IoUtils.closeQuietly(out);
        }
    }

    protected void install(byte[] content, int version) throws IOException {
        writeUpdate(updateDir, updateContent, content);
        writeUpdate(updateDir, updateVersion, Long.toString(version).getBytes());
    }

    protected void postInstall(Context context, Intent intent) {
    }
}
