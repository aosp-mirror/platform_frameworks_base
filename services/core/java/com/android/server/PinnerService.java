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

package com.android.server;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.util.EventLog;
import android.util.Slog;
import android.os.Binder;
import android.os.Build;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.util.ArrayList;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * <p>PinnerService pins important files for key processes in memory.</p>
 * <p>Files to pin are specified in the config_defaultPinnerServiceFiles
 * overlay. </p>
 */
public final class PinnerService extends SystemService {
    private static final boolean DEBUG = false;
    private static final String TAG = "PinnerService";

    private final Context mContext;
    private final ArrayList<String> mPinnedFiles = new ArrayList<String>();

    private BinderService mBinderService;


    public PinnerService(Context context) {
        super(context);

        mContext = context;

    }

    @Override
    public void onStart() {
        Slog.e(TAG, "Starting PinnerService");

        mBinderService = new BinderService();
        publishBinderService("pinner", mBinderService);

        // Files to pin come from the overlay and can be specified per-device config
        // Continue trying to pin remaining files even if there is a failure
        String[] filesToPin = mContext.getResources().getStringArray(com.android.internal.R.array.config_defaultPinnerServiceFiles);
        for (int i = 0; i < filesToPin.length; i++){
            boolean success = pinFile(filesToPin[i], 0, 0);
            if (success == true) {
                mPinnedFiles.add(filesToPin[i]);
                Slog.i(TAG, "Pinned file = " + filesToPin[i]);
            } else {
                Slog.e(TAG, "Failed to pin file = " + filesToPin[i]);
            }
        }
    }

    // mlock length bytes of fileToPin in memory, starting at offset
    // length == 0 means pin from offset to end of file
    private boolean pinFile(String fileToPin, long offset, long length) {
        FileDescriptor fd = new FileDescriptor();
        try {
            fd = Os.open(fileToPin, OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NOFOLLOW, OsConstants.O_RDONLY);

            StructStat sb = Os.fstat(fd);

            if (offset + length > sb.st_size) {
                Os.close(fd);
                return false;
            }

            if (length == 0) {
                length = sb.st_size - offset;
            }

            long address = Os.mmap(0, length, OsConstants.PROT_READ, OsConstants.MAP_PRIVATE, fd, offset);
            Os.close(fd);

            Os.mlock(address, length);

            return true;
        } catch (ErrnoException e) {
            Slog.e(TAG, "Failed to pin file " + fileToPin + " with error " + e.getMessage());
            if(fd.valid()) {
                try { Os.close(fd); }
                catch (ErrnoException eClose) {Slog.e(TAG, "Failed to close fd, error = " + eClose.getMessage());}
            }
            return false;
        }
    }


    private final class BinderService extends Binder {
        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            mContext.enforceCallingOrSelfPermission(android.Manifest.permission.DUMP, TAG);
            pw.println("Pinned Files:");
            for (int i = 0; i < mPinnedFiles.size(); i++) {
                pw.println(mPinnedFiles.get(i));
            }
        }
    }
}
