/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.os.SELinux;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Base64;
import android.util.Slog;

import java.io.File;
import java.io.IOException;

import libcore.io.IoUtils;

public class SELinuxPolicyInstallReceiver extends ConfigUpdateInstallReceiver {

    private static final String TAG = "SELinuxPolicyInstallReceiver";

    private static final String sepolicyPath = "sepolicy";
    private static final String fileContextsPath = "file_contexts";
    private static final String propertyContextsPath = "property_contexts";
    private static final String seappContextsPath = "seapp_contexts";

    public SELinuxPolicyInstallReceiver() {
        super("/data/security/", "sepolicy_bundle", "metadata/", "version");
    }

    private void installFile(File destination, String content) throws IOException {
        backupFile(destination);
        writeUpdate(updateDir, destination, Base64.decode(content, Base64.DEFAULT));
    }

    private void rollBackFile(File replace) throws IOException {
        File backup = new File(replace.getCanonicalPath() + "_backup");
        FileUtils.copyFile(backup, replace);
    }

    private void backupFile(File state) throws IOException {
        File backup = new File(state.getCanonicalPath() + "_backup");
        FileUtils.copyFile(state, backup);
    }

    private void unpackBundle() throws IOException {
        // read the bundle
        String bundle = IoUtils.readFileAsString(updateContent.getCanonicalPath());
        // split it into newline-separated base64'd chunks
        String[] chunks = bundle.split("\n\n");
        // chunks are:
        //      1. sepolicy
        //      2. file_contexts
        //      3. property_contexts
        //      4. seapp_contexts
        if (chunks.length != 4) {
            throw new IOException("Invalid number of chunks");
        }
        // install each of these
        installFile(new File(updateDir, sepolicyPath), chunks[0]);
        installFile(new File(updateDir, fileContextsPath), chunks[1]);
        installFile(new File(updateDir, propertyContextsPath), chunks[2]);
        installFile(new File(updateDir, seappContextsPath), chunks[3]);
    }

    private void rollBackUpdate() {
        try {
            rollBackFile(new File(updateDir, sepolicyPath));
            rollBackFile(new File(updateDir, fileContextsPath));
            rollBackFile(new File(updateDir, propertyContextsPath));
            rollBackFile(new File(updateDir, seappContextsPath));
        } catch (IOException e) {
            Slog.e(TAG, "Could not roll back selinux policy update: ", e);
        }
    }

    private void applyUpdate() {
        Slog.i(TAG, "Reloading SELinux policy");
        SystemProperties.set("selinux.reload_policy", "1");
    }

    private void setEnforcingMode(Context context) {
        boolean mode = Settings.Global.getInt(context.getContentResolver(),
            Settings.Global.SELINUX_STATUS, 0) == 1;
        SELinux.setSELinuxEnforce(mode);
    }

    @Override
    protected void postInstall(Context context, Intent intent) {
        try {
            unpackBundle();
            applyUpdate();
            setEnforcingMode(context);
        } catch (IOException e) {
            Slog.e(TAG, "Could not update selinux policy: ", e);
            rollBackUpdate();
        }
    }
}
