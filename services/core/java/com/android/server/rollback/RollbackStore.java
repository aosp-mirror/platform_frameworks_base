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

package com.android.server.rollback;

import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.RollbackInfo;
import android.util.Log;

import libcore.io.IoUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * Helper class for loading and saving rollback data to persistent storage.
 */
class RollbackStore {
    private static final String TAG = "RollbackManager";

    // Assuming the rollback data directory is /data/rollback, we use the
    // following directory structure to store persisted data for available and
    // recently executed rollbacks:
    //   /data/rollback/
    //      available/
    //          XXX/
    //              rollback.json
    //              com.package.A/
    //                  base.apk
    //              com.package.B/
    //                  base.apk
    //          YYY/
    //              rollback.json
    //              com.package.C/
    //                  base.apk
    //      recently_executed.json
    //
    // * XXX, YYY are random strings from Files.createTempDirectory
    // * rollback.json contains all relevant metadata for the rollback. This
    //   file is not written until the rollback is made available.
    //
    // TODO: Use AtomicFile for all the .json files?
    private final File mRollbackDataDir;
    private final File mAvailableRollbacksDir;
    private final File mRecentlyExecutedRollbacksFile;

    RollbackStore(File rollbackDataDir) {
        mRollbackDataDir = rollbackDataDir;
        mAvailableRollbacksDir = new File(mRollbackDataDir, "available");
        mRecentlyExecutedRollbacksFile = new File(mRollbackDataDir, "recently_executed.json");
    }

    /**
     * Reads the list of available rollbacks from persistent storage.
     */
    List<RollbackData> loadAvailableRollbacks() {
        List<RollbackData> availableRollbacks = new ArrayList<>();
        mAvailableRollbacksDir.mkdirs();
        for (File rollbackDir : mAvailableRollbacksDir.listFiles()) {
            if (rollbackDir.isDirectory()) {
                try {
                    RollbackData data = loadRollbackData(rollbackDir);
                    availableRollbacks.add(data);
                } catch (IOException e) {
                    // Note: Deleting the rollbackDir here will cause pending
                    // rollbacks to be deleted. This should only ever happen
                    // if reloadPersistedData is called while there are
                    // pending rollbacks. The reloadPersistedData method is
                    // currently only for testing, so that should be okay.
                    Log.e(TAG, "Unable to read rollback data at " + rollbackDir, e);
                    removeFile(rollbackDir);
                }
            }
        }
        return availableRollbacks;
    }

    /**
     * Reads the list of recently executed rollbacks from persistent storage.
     */
    List<RollbackInfo> loadRecentlyExecutedRollbacks() {
        List<RollbackInfo> recentlyExecutedRollbacks = new ArrayList<>();
        if (mRecentlyExecutedRollbacksFile.exists()) {
            try {
                // TODO: How to cope with changes to the format of this file from
                // when RollbackStore is updated in the future?
                String jsonString = IoUtils.readFileAsString(
                        mRecentlyExecutedRollbacksFile.getAbsolutePath());
                JSONObject object = new JSONObject(jsonString);
                JSONArray array = object.getJSONArray("recentlyExecuted");
                for (int i = 0; i < array.length(); ++i) {
                    JSONObject element = array.getJSONObject(i);
                    String packageName = element.getString("packageName");
                    long higherVersionCode = element.getLong("higherVersionCode");
                    long lowerVersionCode = element.getLong("lowerVersionCode");
                    PackageRollbackInfo target = new PackageRollbackInfo(packageName,
                            new PackageRollbackInfo.PackageVersion(higherVersionCode),
                            new PackageRollbackInfo.PackageVersion(lowerVersionCode));
                    RollbackInfo rollback = new RollbackInfo(target);
                    recentlyExecutedRollbacks.add(rollback);
                }
            } catch (IOException | JSONException e) {
                // TODO: What to do here? Surely we shouldn't just forget about
                // everything after the point of exception?
                Log.e(TAG, "Failed to read recently executed rollbacks", e);
            }
        }

        return recentlyExecutedRollbacks;
    }

    /**
     * Creates a new RollbackData instance with backupDir assigned.
     */
    RollbackData createAvailableRollback() throws IOException {
        File backupDir = Files.createTempDirectory(mAvailableRollbacksDir.toPath(), null).toFile();
        return new RollbackData(backupDir);
    }

    /**
     * Returns the directory where the code for a package should be stored for
     * given rollback <code>data</code> and <code>packageName</code>.
     */
    File packageCodePathForAvailableRollback(RollbackData data, String packageName) {
        return new File(data.backupDir, packageName);
    }

    /**
     * Writes the metadata for an available rollback to persistent storage.
     */
    void saveAvailableRollback(RollbackData data) throws IOException {
        try {
            JSONObject dataJson = new JSONObject();
            JSONArray packagesJson = new JSONArray();
            for (PackageRollbackInfo info : data.packages) {
                JSONObject infoJson = new JSONObject();
                infoJson.put("packageName", info.packageName);
                infoJson.put("higherVersionCode", info.higherVersion.versionCode);
                infoJson.put("lowerVersionCode", info.lowerVersion.versionCode);
                packagesJson.put(infoJson);
            }
            dataJson.put("packages", packagesJson);
            dataJson.put("timestamp", data.timestamp.toString());

            PrintWriter pw = new PrintWriter(new File(data.backupDir, "rollback.json"));
            pw.println(dataJson.toString());
            pw.close();
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /**
     * Removes all persistant storage associated with the given available
     * rollback.
     */
    void deleteAvailableRollback(RollbackData data) {
        // TODO(narayan): Make sure we delete the userdata snapshot along with the backup of the
        // actual app.
        removeFile(data.backupDir);
    }

    /**
     * Writes the list of recently executed rollbacks to storage.
     */
    void saveRecentlyExecutedRollbacks(List<RollbackInfo> recentlyExecutedRollbacks) {
        try {
            JSONObject json = new JSONObject();
            JSONArray array = new JSONArray();
            json.put("recentlyExecuted", array);

            for (int i = 0; i < recentlyExecutedRollbacks.size(); ++i) {
                RollbackInfo rollback = recentlyExecutedRollbacks.get(i);
                JSONObject element = new JSONObject();
                element.put("packageName", rollback.targetPackage.packageName);
                element.put("higherVersionCode", rollback.targetPackage.higherVersion.versionCode);
                element.put("lowerVersionCode", rollback.targetPackage.lowerVersion.versionCode);
                array.put(element);
            }

            PrintWriter pw = new PrintWriter(mRecentlyExecutedRollbacksFile);
            pw.println(json.toString());
            pw.close();
        } catch (IOException | JSONException e) {
            // TODO: What to do here?
            Log.e(TAG, "Failed to save recently executed rollbacks", e);
        }
    }

    /**
     * Reads the metadata for a rollback from the given directory.
     * @throws IOException in case of error reading the data.
     */
    private RollbackData loadRollbackData(File backupDir) throws IOException {
        try {
            RollbackData data = new RollbackData(backupDir);
            File rollbackJsonFile = new File(backupDir, "rollback.json");
            JSONObject dataJson = new JSONObject(
                    IoUtils.readFileAsString(rollbackJsonFile.getAbsolutePath()));
            JSONArray packagesJson = dataJson.getJSONArray("packages");
            for (int i = 0; i < packagesJson.length(); ++i) {
                JSONObject infoJson = packagesJson.getJSONObject(i);
                String packageName = infoJson.getString("packageName");
                long higherVersionCode = infoJson.getLong("higherVersionCode");
                long lowerVersionCode = infoJson.getLong("lowerVersionCode");
                data.packages.add(new PackageRollbackInfo(packageName,
                        new PackageRollbackInfo.PackageVersion(higherVersionCode),
                        new PackageRollbackInfo.PackageVersion(lowerVersionCode)));
            }

            data.timestamp = Instant.parse(dataJson.getString("timestamp"));
            return data;
        } catch (JSONException | DateTimeParseException e) {
            throw new IOException(e);
        }
    }

    /**
     * Deletes a file completely.
     * If the file is a directory, its contents are deleted as well.
     * Has no effect if the directory does not exist.
     */
    private void removeFile(File file) {
        if (file.isDirectory()) {
            for (File child : file.listFiles()) {
                removeFile(child);
            }
        }
        if (file.exists()) {
            file.delete();
        }
    }
}
