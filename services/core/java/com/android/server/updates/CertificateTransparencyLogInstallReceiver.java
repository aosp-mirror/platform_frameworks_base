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

package com.android.server.updates;

import android.os.FileUtils;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Base64;
import android.util.Slog;

import com.android.internal.util.HexDump;

import libcore.io.Streams;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public class CertificateTransparencyLogInstallReceiver extends ConfigUpdateInstallReceiver {

    private static final String TAG = "CTLogInstallReceiver";
    private static final String LOGDIR_PREFIX = "logs-";

    public CertificateTransparencyLogInstallReceiver() {
        super("/data/misc/keychain/trusted_ct_logs/", "ct_logs", "metadata/", "version");
    }

    @Override
    protected void install(InputStream inputStream, int version) throws IOException {
        /* Install is complicated here because we translate the input, which is a JSON file
         * containing log information to a directory with a file per log. To support atomically
         * replacing the old configuration directory with the new there's a bunch of steps. We
         * create a new directory with the logs and then do an atomic update of the current symlink
         * to point to the new directory.
         */
        // 1. Ensure that the update dir exists and is readable
        updateDir.mkdir();
        if (!updateDir.isDirectory()) {
            throw new IOException("Unable to make directory " + updateDir.getCanonicalPath());
        }
        if (!updateDir.setReadable(true, false)) {
            throw new IOException("Unable to set permissions on " +
                    updateDir.getCanonicalPath());
        }
        File currentSymlink = new File(updateDir, "current");
        File newVersion = new File(updateDir, LOGDIR_PREFIX + String.valueOf(version));
        File oldDirectory;
        // 2. Handle the corner case where the new directory already exists.
        if (newVersion.exists()) {
            // If the symlink has already been updated then the update died between steps 7 and 8
            // and so we cannot delete the directory since its in use. Instead just bump the version
            // and return.
            if (newVersion.getCanonicalPath().equals(currentSymlink.getCanonicalPath())) {
                writeUpdate(updateDir, updateVersion,
                        new ByteArrayInputStream(Long.toString(version).getBytes()));
                deleteOldLogDirectories();
                return;
            } else {
                FileUtils.deleteContentsAndDir(newVersion);
            }
        }
        try {
            // 3. Create /data/misc/keychain/trusted_ct_logs/<new_version>/ .
            newVersion.mkdir();
            if (!newVersion.isDirectory()) {
                throw new IOException("Unable to make directory " + newVersion.getCanonicalPath());
            }
            if (!newVersion.setReadable(true, false)) {
                throw new IOException("Failed to set " +newVersion.getCanonicalPath() +
                        " readable");
            }

            // 4. For each log in the log file create the corresponding file in <new_version>/ .
            try {
                byte[] content = Streams.readFullyNoClose(inputStream);
                JSONObject json = new JSONObject(new String(content, StandardCharsets.UTF_8));
                JSONArray logs = json.getJSONArray("logs");
                for (int i = 0; i < logs.length(); i++) {
                    JSONObject log = logs.getJSONObject(i);
                    installLog(newVersion, log);
                }
            } catch (JSONException e) {
                throw new IOException("Failed to parse logs", e);
            }

            // 5. Create the temp symlink. We'll rename this to the target symlink to get an atomic
            // update.
            File tempSymlink = new File(updateDir, "new_symlink");
            try {
                Os.symlink(newVersion.getCanonicalPath(), tempSymlink.getCanonicalPath());
            } catch (ErrnoException e) {
                throw new IOException("Failed to create symlink", e);
            }

            // 6. Update the symlink target, this is the actual update step.
            tempSymlink.renameTo(currentSymlink.getAbsoluteFile());
        } catch (IOException | RuntimeException e) {
            FileUtils.deleteContentsAndDir(newVersion);
            throw e;
        }
        Slog.i(TAG, "CT log directory updated to " + newVersion.getAbsolutePath());
        // 7. Update the current version information
        writeUpdate(updateDir, updateVersion,
                new ByteArrayInputStream(Long.toString(version).getBytes()));
        // 8. Cleanup
        deleteOldLogDirectories();
    }

    private void installLog(File directory, JSONObject logObject) throws IOException {
        try {
            String logFilename = getLogFileName(logObject.getString("key"));
            File file = new File(directory, logFilename);
            try (OutputStreamWriter out =
                    new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                writeLogEntry(out, "key", logObject.getString("key"));
                writeLogEntry(out, "url", logObject.getString("url"));
                writeLogEntry(out, "description", logObject.getString("description"));
            }
            if (!file.setReadable(true, false)) {
                throw new IOException("Failed to set permissions on " + file.getCanonicalPath());
            }
        } catch (JSONException e) {
            throw new IOException("Failed to parse log", e);
        }

    }

    /**
     * Get the filename for a log based on its public key. This must be kept in sync with
     * org.conscrypt.ct.CTLogStoreImpl.
     */
    private String getLogFileName(String base64PublicKey) {
        byte[] keyBytes = Base64.decode(base64PublicKey, Base64.DEFAULT);
        try {
            byte[] id = MessageDigest.getInstance("SHA-256").digest(keyBytes);
            return HexDump.toHexString(id, false);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed to be available.
            throw new RuntimeException(e);
        }
    }

    private void writeLogEntry(OutputStreamWriter out, String key, String value)
            throws IOException {
        out.write(key + ":" + value + "\n");
    }

    private void deleteOldLogDirectories() throws IOException {
        if (!updateDir.exists()) {
            return;
        }
        File currentTarget = new File(updateDir, "current").getCanonicalFile();
        FileFilter filter = new FileFilter() {
            @Override
            public boolean accept(File file) {
                return !currentTarget.equals(file) && file.getName().startsWith(LOGDIR_PREFIX);
            }
        };
        for (File f : updateDir.listFiles(filter)) {
            FileUtils.deleteContentsAndDir(f);
        }
    }
}
