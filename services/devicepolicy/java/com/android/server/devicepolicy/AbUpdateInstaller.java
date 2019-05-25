/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.devicepolicy;

import android.app.admin.DevicePolicyManager.InstallSystemUpdateCallback;
import android.app.admin.StartInstallingUpdateCallback;
import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.os.UpdateEngine;
import android.os.UpdateEngineCallback;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Used for installing an update on <a href="https://source.android.com/devices/tech/ota/ab">AB
 * devices.</a>
 * <p>This logic is specific to GOTA and should be modified by OEMs using a different AB update
 * system.</p>
 */
class AbUpdateInstaller extends UpdateInstaller {
    private static final String PAYLOAD_BIN = "payload.bin";
    private static final String PAYLOAD_PROPERTIES_TXT = "payload_properties.txt";
    //https://en.wikipedia.org/wiki/Zip_(file_format)#Local_file_header
    private static final int OFFSET_TO_FILE_NAME = 30;
    // kDownloadStateInitializationError constant from system/update_engine/common/error_code.h.
    private static final int DOWNLOAD_STATE_INITIALIZATION_ERROR = 20;
    private long mSizeForUpdate;
    private long mOffsetForUpdate;
    private List<String> mProperties;
    private Enumeration<? extends ZipEntry> mEntries;
    private ZipFile mPackedUpdateFile;
    private static final Map<Integer, Integer> errorCodesMap = buildErrorCodesMap();
    private static final Map<Integer, String> errorStringsMap = buildErrorStringsMap();
    public static final String UNKNOWN_ERROR = "Unknown error with error code = ";
    private boolean mUpdateInstalled;

    private static Map<Integer, Integer> buildErrorCodesMap() {
        Map<Integer, Integer> map = new HashMap<>();
        map.put(
                UpdateEngine.ErrorCodeConstants.ERROR,
                InstallSystemUpdateCallback.UPDATE_ERROR_UNKNOWN);
        map.put(
                DOWNLOAD_STATE_INITIALIZATION_ERROR,
                InstallSystemUpdateCallback.UPDATE_ERROR_INCORRECT_OS_VERSION);
        map.put(
                UpdateEngine.ErrorCodeConstants.PAYLOAD_TIMESTAMP_ERROR,
                InstallSystemUpdateCallback.UPDATE_ERROR_INCORRECT_OS_VERSION);

        // Error constants corresponding to errors related to bad update file.
        map.put(
                UpdateEngine.ErrorCodeConstants.DOWNLOAD_PAYLOAD_VERIFICATION_ERROR,
                InstallSystemUpdateCallback.UPDATE_ERROR_UPDATE_FILE_INVALID);
        map.put(
                UpdateEngine.ErrorCodeConstants.PAYLOAD_SIZE_MISMATCH_ERROR,
                InstallSystemUpdateCallback.UPDATE_ERROR_UPDATE_FILE_INVALID);
        map.put(
                UpdateEngine.ErrorCodeConstants.PAYLOAD_MISMATCHED_TYPE_ERROR,
                InstallSystemUpdateCallback.UPDATE_ERROR_UPDATE_FILE_INVALID);
        map.put(
                UpdateEngine.ErrorCodeConstants.PAYLOAD_HASH_MISMATCH_ERROR,
                InstallSystemUpdateCallback.UPDATE_ERROR_UPDATE_FILE_INVALID);
        // TODO(b/133396459): replace with a constant.
        map.put(
                26 /* kDownloadMetadataSignatureMismatch */,
                InstallSystemUpdateCallback.UPDATE_ERROR_UPDATE_FILE_INVALID);

        // Error constants corresponding to errors related to devices bad state.
        map.put(
                UpdateEngine.ErrorCodeConstants.POST_INSTALL_RUNNER_ERROR,
                InstallSystemUpdateCallback.UPDATE_ERROR_UNKNOWN);
        map.put(
                UpdateEngine.ErrorCodeConstants.INSTALL_DEVICE_OPEN_ERROR,
                InstallSystemUpdateCallback.UPDATE_ERROR_UNKNOWN);
        map.put(
                UpdateEngine.ErrorCodeConstants.DOWNLOAD_TRANSFER_ERROR,
                InstallSystemUpdateCallback.UPDATE_ERROR_UNKNOWN);
        map.put(
                UpdateEngine.ErrorCodeConstants.UPDATED_BUT_NOT_ACTIVE,
                InstallSystemUpdateCallback.UPDATE_ERROR_UNKNOWN);

        return map;
    }

    private static Map<Integer, String> buildErrorStringsMap() {
        Map<Integer, String> map = new HashMap<>();
        map.put(UpdateEngine.ErrorCodeConstants.ERROR, UNKNOWN_ERROR);
        map.put(
                DOWNLOAD_STATE_INITIALIZATION_ERROR,
                "The delta update payload was targeted for another version or the source partition"
                        + "was modified after it was installed");
        map.put(
                UpdateEngine.ErrorCodeConstants.POST_INSTALL_RUNNER_ERROR,
                "Failed to finish the configured postinstall works.");
        map.put(
                UpdateEngine.ErrorCodeConstants.INSTALL_DEVICE_OPEN_ERROR,
                "Failed to open one of the partitions it tried to write to or read data from.");
        map.put(
                UpdateEngine.ErrorCodeConstants.PAYLOAD_MISMATCHED_TYPE_ERROR,
                "Payload mismatch error.");
        map.put(
                UpdateEngine.ErrorCodeConstants.DOWNLOAD_TRANSFER_ERROR,
                "Failed to read the payload data from the given URL.");
        map.put(
                UpdateEngine.ErrorCodeConstants.PAYLOAD_HASH_MISMATCH_ERROR, "Payload hash error.");
        map.put(
                UpdateEngine.ErrorCodeConstants.PAYLOAD_SIZE_MISMATCH_ERROR,
                "Payload size mismatch error.");
        map.put(
                UpdateEngine.ErrorCodeConstants.DOWNLOAD_PAYLOAD_VERIFICATION_ERROR,
                "Failed to verify the signature of the payload.");
        map.put(
                UpdateEngine.ErrorCodeConstants.UPDATED_BUT_NOT_ACTIVE,
                "The payload has been successfully installed,"
                        + "but the active slot was not flipped.");
        return map;
    }

    AbUpdateInstaller(Context context, ParcelFileDescriptor updateFileDescriptor,
            StartInstallingUpdateCallback callback, DevicePolicyManagerService.Injector injector,
            DevicePolicyConstants constants) {
        super(context, updateFileDescriptor, callback, injector, constants);
        mUpdateInstalled = false;
    }

    @Override
    public void installUpdateInThread() {
        if (mUpdateInstalled) {
            throw new IllegalStateException("installUpdateInThread can be called only once.");
        }
        try {
            setState();
            applyPayload(Paths.get(mCopiedUpdateFile.getAbsolutePath()).toUri().toString());
        } catch (ZipException e) {
            Log.w(UpdateInstaller.TAG, e);
            notifyCallbackOnError(
                    InstallSystemUpdateCallback.UPDATE_ERROR_UPDATE_FILE_INVALID,
                    Log.getStackTraceString(e));
        } catch (IOException e) {
            Log.w(UpdateInstaller.TAG, e);
            notifyCallbackOnError(
                    InstallSystemUpdateCallback.UPDATE_ERROR_UNKNOWN,
                    Log.getStackTraceString(e));
        }
    }

    private void setState() throws IOException {
        mUpdateInstalled = true;
        mPackedUpdateFile = new ZipFile(mCopiedUpdateFile);
        mProperties = new ArrayList<>();
        mSizeForUpdate = -1;
        mOffsetForUpdate = 0;
        mEntries = mPackedUpdateFile.entries();
    }

    private UpdateEngine buildBoundUpdateEngine() {
        UpdateEngine updateEngine = new UpdateEngine();
        updateEngine.bind(new DelegatingUpdateEngineCallback(this, updateEngine));
        return updateEngine;
    }

    private void applyPayload(String updatePath) throws IOException {
        if (!updateStateForPayload()) {
            return;
        }
        String[] headerKeyValuePairs = mProperties.stream().toArray(String[]::new);
        if (mSizeForUpdate == -1) {
            Log.w(UpdateInstaller.TAG, "Failed to find payload entry in the given package.");
            notifyCallbackOnError(
                    InstallSystemUpdateCallback.UPDATE_ERROR_UPDATE_FILE_INVALID,
                    "Failed to find payload entry in the given package.");
            return;
        }

        UpdateEngine updateEngine = buildBoundUpdateEngine();
        try {
            updateEngine.applyPayload(
                    updatePath, mOffsetForUpdate, mSizeForUpdate, headerKeyValuePairs);
        } catch (Exception e) {
            // Prevent an automatic restart when an update is already being processed
            // (http://b/124106342).
            Log.w(UpdateInstaller.TAG, "Failed to install update from file.", e);
            notifyCallbackOnError(
                    InstallSystemUpdateCallback.UPDATE_ERROR_UNKNOWN,
                    "Failed to install update from file.");
        }
    }

    private boolean updateStateForPayload() throws IOException {
        long offset = 0;
        while (mEntries.hasMoreElements()) {
            ZipEntry entry = mEntries.nextElement();

            String name = entry.getName();
            offset += buildOffsetForEntry(entry, name);
            if (entry.isDirectory()) {
                offset -= entry.getCompressedSize();
                continue;
            }
            if (PAYLOAD_BIN.equals(name)) {
                if (entry.getMethod() != ZipEntry.STORED) {
                    Log.w(UpdateInstaller.TAG, "Invalid compression method.");
                    notifyCallbackOnError(
                            InstallSystemUpdateCallback.UPDATE_ERROR_UPDATE_FILE_INVALID,
                            "Invalid compression method.");
                    return false;
                }
                mSizeForUpdate = entry.getCompressedSize();
                mOffsetForUpdate = offset - entry.getCompressedSize();
            } else if (PAYLOAD_PROPERTIES_TXT.equals(name)) {
                updatePropertiesForEntry(entry);
            }
        }
        return true;
    }

    private long buildOffsetForEntry(ZipEntry entry, String name) {
        return OFFSET_TO_FILE_NAME + name.length() + entry.getCompressedSize()
                + (entry.getExtra() == null ? 0 : entry.getExtra().length);
    }

    private void updatePropertiesForEntry(ZipEntry entry) throws IOException {
        try (BufferedReader bufferedReader = new BufferedReader(
                new InputStreamReader(mPackedUpdateFile.getInputStream(entry)))) {
            String line;
            /* Neither @line nor @mProperties are size constraint since there is a few properties
            with limited size. */
            while ((line = bufferedReader.readLine()) != null) {
                mProperties.add(line);
            }
        }
    }

    private static class DelegatingUpdateEngineCallback extends UpdateEngineCallback {
        private UpdateInstaller mUpdateInstaller;
        private UpdateEngine mUpdateEngine;

        DelegatingUpdateEngineCallback(
                UpdateInstaller updateInstaller, UpdateEngine updateEngine) {
            mUpdateInstaller = updateInstaller;
            mUpdateEngine = updateEngine;
        }

        @Override
        public void onStatusUpdate(int statusCode, float percentage) {
            return;
        }

        @Override
        public void onPayloadApplicationComplete(int errorCode) {
            mUpdateEngine.unbind();
            if (errorCode == UpdateEngine.ErrorCodeConstants.SUCCESS) {
                mUpdateInstaller.notifyCallbackOnSuccess();
            } else {
                mUpdateInstaller.notifyCallbackOnError(
                        errorCodesMap.getOrDefault(
                                errorCode, InstallSystemUpdateCallback.UPDATE_ERROR_UNKNOWN),
                        errorStringsMap.getOrDefault(errorCode, UNKNOWN_ERROR + errorCode));
            }
        }
    }
}
