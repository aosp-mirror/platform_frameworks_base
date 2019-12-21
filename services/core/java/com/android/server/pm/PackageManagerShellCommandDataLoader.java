/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.pm.DataLoaderParams;
import android.content.pm.InstallationFile;
import android.os.ParcelFileDescriptor;
import android.service.dataloader.DataLoaderService;
import android.text.TextUtils;
import android.util.Slog;

import libcore.io.IoUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

/**
 * Callback data loader for PackageManagerShellCommand installations.
 */
public class PackageManagerShellCommandDataLoader extends DataLoaderService {
    public static final String TAG = "PackageManagerShellCommandDataLoader";

    static class DataLoader implements DataLoaderService.DataLoader {
        private ParcelFileDescriptor mInFd = null;
        private FileSystemConnector mConnector = null;

        private static final String STDIN_PATH = "-";

        @Override
        public boolean onCreate(@NonNull DataLoaderParams dataLoaderParams,
                @NonNull FileSystemConnector connector) {
            mConnector = connector;
            return true;
        }
        @Override
        public boolean onPrepareImage(Collection<InstallationFile> addedFiles,
                Collection<String> removedFiles) {
            try {
                for (InstallationFile fileInfo : addedFiles) {
                    String filePath = new String(fileInfo.getMetadata(), StandardCharsets.UTF_8);
                    if (STDIN_PATH.equals(filePath) || TextUtils.isEmpty(filePath)) {
                        // TODO(b/146080380): add support for STDIN installations.
                        if (mInFd == null) {
                            Slog.e(TAG, "Invalid stdin file descriptor.");
                            return false;
                        }
                        ParcelFileDescriptor inFd = ParcelFileDescriptor.dup(
                                mInFd.getFileDescriptor());
                        mConnector.writeData(fileInfo.getName(), 0, fileInfo.getSize(), inFd);
                    } else {
                        File localFile = new File(filePath);
                        ParcelFileDescriptor incomingFd = null;
                        try {
                            // TODO(b/146080380): open files via callback into shell command.
                            incomingFd = ParcelFileDescriptor.open(localFile,
                                    ParcelFileDescriptor.MODE_READ_ONLY);
                            mConnector.writeData(fileInfo.getName(), 0, localFile.length(),
                                    incomingFd);
                        } finally {
                            IoUtils.closeQuietly(incomingFd);
                        }
                    }
                }
                return true;
            } catch (IOException e) {
                return false;
            }
        }
    }

    @Override
    public DataLoaderService.DataLoader onCreateDataLoader() {
        return new DataLoader();
    }
}
