/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.timezone;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.Executor;
import libcore.io.Streams;

/**
 * A single class that implements multiple helper interfaces for use by {@link RulesManagerService}.
 */
final class RulesManagerServiceHelperImpl
        implements PermissionHelper, Executor, FileDescriptorHelper {

    private final Context mContext;

    RulesManagerServiceHelperImpl(Context context) {
        mContext = context;
    }

    @Override
    public void enforceCallerHasPermission(String requiredPermission) {
        mContext.enforceCallingPermission(requiredPermission, null /* message */);
    }

    // TODO Wake lock required?
    @Override
    public void execute(Runnable runnable) {
        // TODO Is there a better way?
        new Thread(runnable).start();
    }

    @Override
    public byte[] readFully(ParcelFileDescriptor parcelFileDescriptor) throws IOException {
        try (ParcelFileDescriptor pfd = parcelFileDescriptor) {
            // Read bytes
            FileInputStream in = new FileInputStream(pfd.getFileDescriptor(), false /* isOwner */);
            return Streams.readFully(in);
        }
    }
}
