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

package com.android.server.security;

import static android.os.ParcelFileDescriptor.MODE_READ_ONLY;

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.ParcelFileDescriptor;

import com.android.internal.security.VerityUtils;

import java.io.File;
import java.io.IOException;


/**
 * In-process API for server side FileIntegrity related infrastructure.
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public final class FileIntegrity {
    private FileIntegrity() {}

    /**
     * Enables fs-verity, if supported by the filesystem. This operation is atomic, i.e. it's either
     * enabled or not, even in case of power failure during or after the call.
     * @see <a href="https://www.kernel.org/doc/html/latest/filesystems/fsverity.html">
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    public static void setUpFsVerity(@NonNull File file) throws IOException {
        try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, MODE_READ_ONLY)) {
            setUpFsVerity(pfd);
        }
    }

    /**
     * Enables fs-verity, if supported by the filesystem. This operation is atomic, i.e. it's either
     * enabled or not, even in case of power failure during or after the call.
     * @see <a href="https://www.kernel.org/doc/html/latest/filesystems/fsverity.html">
     *
     * @param parcelFileDescriptor an FD opened in {@link ParcelFileDescriptor#MODE_READ_ONLY}.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
    public static void setUpFsVerity(@NonNull ParcelFileDescriptor parcelFileDescriptor)
            throws IOException {
        VerityUtils.setUpFsverity(parcelFileDescriptor.getFd());
    }
}
