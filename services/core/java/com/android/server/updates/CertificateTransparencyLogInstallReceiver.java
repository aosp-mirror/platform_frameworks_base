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

import android.content.Context;
import android.content.Intent;
import android.os.FileUtils;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Slog;

import libcore.io.Streams;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class CertificateTransparencyLogInstallReceiver extends ConfigUpdateInstallReceiver {

    private static final String TAG = "CTLogInstallReceiver";
    private static final String LOGDIR_PREFIX = "logs-";

    public CertificateTransparencyLogInstallReceiver() {
        super("/data/misc/keychain/ct/", "ct_logs", "metadata/", "version");
    }

    @Override
    protected void install(InputStream inputStream, int version) throws IOException {
        if (!Flags.certificateTransparencyInstaller()) {
            return;
        }
        // To support atomically replacing the old configuration directory with the new there's a
        // bunch of steps. We create a new directory with the logs and then do an atomic update of
        // the current symlink to point to the new directory.
        // 1. Ensure that the update dir exists and is readable
        updateDir.mkdir();
        if (!updateDir.isDirectory()) {
            throw new IOException("Unable to make directory " + updateDir.getCanonicalPath());
        }
        if (!updateDir.setReadable(true, false)) {
            throw new IOException("Unable to set permissions on " + updateDir.getCanonicalPath());
        }
        File currentSymlink = new File(updateDir, "current");
        File newVersion = new File(updateDir, LOGDIR_PREFIX + String.valueOf(version));
        // 2. Handle the corner case where the new directory already exists.
        if (newVersion.exists()) {
            // If the symlink has already been updated then the update died between steps 7 and 8
            // and so we cannot delete the directory since its in use. Instead just bump the version
            // and return.
            if (newVersion.getCanonicalPath().equals(currentSymlink.getCanonicalPath())) {
                writeUpdate(
                        updateDir,
                        updateVersion,
                        new ByteArrayInputStream(Long.toString(version).getBytes()));
                deleteOldLogDirectories();
                return;
            } else {
                FileUtils.deleteContentsAndDir(newVersion);
            }
        }
        try {
            // 3. Create /data/misc/keychain/ct/<new_version>/ .
            newVersion.mkdir();
            if (!newVersion.isDirectory()) {
                throw new IOException("Unable to make directory " + newVersion.getCanonicalPath());
            }
            if (!newVersion.setReadable(true, false)) {
                throw new IOException(
                        "Failed to set " + newVersion.getCanonicalPath() + " readable");
            }

            // 4. Validate the log list json and move the file in <new_version>/ .
            installLogList(newVersion, inputStream);

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
        writeUpdate(
                updateDir,
                updateVersion,
                new ByteArrayInputStream(Long.toString(version).getBytes()));
        // 8. Cleanup
        deleteOldLogDirectories();
    }

    @Override
    protected void postInstall(Context context, Intent intent) {
        if (!Flags.certificateTransparencyInstaller()) {
            return;
        }
    }

    private void installLogList(File directory, InputStream inputStream) throws IOException {
        try {
            byte[] content = Streams.readFullyNoClose(inputStream);
            if (new JSONObject(new String(content, StandardCharsets.UTF_8)).length() == 0) {
                throw new IOException("Log list data not valid");
            }

            File file = new File(directory, "log_list.json");
            try (FileOutputStream outputStream = new FileOutputStream(file)) {
                outputStream.write(content);
            }
            if (!file.setReadable(true, false)) {
                throw new IOException("Failed to set permissions on " + file.getCanonicalPath());
            }
        } catch (JSONException e) {
            throw new IOException("Malformed json in log list", e);
        }
    }

    private void deleteOldLogDirectories() throws IOException {
        if (!updateDir.exists()) {
            return;
        }
        File currentTarget = new File(updateDir, "current").getCanonicalFile();
        FileFilter filter =
                new FileFilter() {
                    @Override
                    public boolean accept(File file) {
                        return !currentTarget.equals(file)
                                && file.getName().startsWith(LOGDIR_PREFIX);
                    }
                };
        for (File f : updateDir.listFiles(filter)) {
            FileUtils.deleteContentsAndDir(f);
        }
    }
}
