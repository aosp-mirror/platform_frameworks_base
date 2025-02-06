/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.server.display;


import android.annotation.Nullable;
import android.app.backup.BlobBackupHelper;
import android.hardware.display.DisplayManagerInternal;
import android.util.AtomicFile;
import android.util.AtomicFileOutputStream;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalServices;
import com.android.server.display.feature.DisplayManagerFlags;
import com.android.server.display.utils.DebugUtils;

import java.io.IOException;

/**
 * Display manager specific information backup helper. Backs-up the entire files for the given
 * user.
 * @hide
 */
public class DisplayBackupHelper extends BlobBackupHelper {
    private static final String TAG = "DisplayBackupHelper";

    // current schema of the backup state blob
    private static final int BLOB_VERSION = 1;

    // key under which the data blob is committed to back up
    private static final String KEY_DISPLAY = "display";

    // To enable these logs, run:
    // adb shell setprop persist.log.tag.DisplayBackupHelper DEBUG
    // adb reboot
    private static final boolean DEBUG = DebugUtils.isDebuggable(TAG);

    private final int mUserId;
    private final Injector mInjector;

    /**
     * Construct a helper to manage backup/restore of entire files within Display Manager.
     *
     * @param userId  id of the user for which backup will be done.
     */
    public DisplayBackupHelper(int userId) {
        this(userId, new Injector());
    }

    @VisibleForTesting
    DisplayBackupHelper(int userId, Injector injector) {
        super(BLOB_VERSION, KEY_DISPLAY);
        mUserId = userId;
        mInjector = injector;
    }

    @Override
    protected byte[] getBackupPayload(String key) {
        if (!KEY_DISPLAY.equals(key) || !mInjector.isDisplayTopologyFlagEnabled()) {
            return null;
        }
        try {
            var result = mInjector.readTopologyFile(mUserId);
            Slog.i(TAG, "getBackupPayload for " + key + " done, size=" + result.length);
            return result;
        } catch (IOException e) {
            if (DEBUG) Slog.d(TAG, "Skip topology backup", e);
            return null;
        }
    }

    @Override
    protected void applyRestoredPayload(String key, byte[] payload) {
        if (!KEY_DISPLAY.equals(key) || !mInjector.isDisplayTopologyFlagEnabled()) {
            return;
        }
        try (var oStream = mInjector.writeTopologyFile(mUserId)) {
            oStream.write(payload);
            oStream.markSuccess();
            Slog.i(TAG, "applyRestoredPayload for " + key + " size=" + payload.length
                    + " to " + oStream);
        } catch (IOException e) {
            Slog.e(TAG, "applyRestoredPayload failed", e);
            return;
        }
        var displayManagerInternal = mInjector.getDisplayManagerInternal();
        if (displayManagerInternal == null) {
            Slog.e(TAG, "DisplayManagerInternal is null");
            return;
        }

        displayManagerInternal.reloadTopologies(mUserId);
    }

    @VisibleForTesting
    static class Injector {
        private final boolean mIsDisplayTopologyEnabled =
                new DisplayManagerFlags().isDisplayTopologyEnabled();

        boolean isDisplayTopologyFlagEnabled() {
            return mIsDisplayTopologyEnabled;
        }

        @Nullable
        DisplayManagerInternal getDisplayManagerInternal() {
            return LocalServices.getService(DisplayManagerInternal.class);
        }

        byte[] readTopologyFile(int userId) throws IOException {
            return getTopologyFile(userId).readFully();
        }

        AtomicFileOutputStream writeTopologyFile(int userId) throws IOException {
            return new AtomicFileOutputStream(getTopologyFile(userId));
        }

        private AtomicFile getTopologyFile(int userId) {
            return new AtomicFile(DisplayTopologyXmlStore.getUserTopologyFile(userId),
                    /*commitTag=*/ "topology-state");
        }
    };
}
