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

import android.annotation.NonNull;
import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.PackageRollbackInfo.RestoreInfo;
import android.content.rollback.RollbackInfo;
import android.util.IntArray;
import android.util.Log;
import android.util.SparseLongArray;

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
    // * XXX, YYY are the rollbackIds for the corresponding rollbacks.
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
     * Converts an {@code JSONArray} of integers to an {@code IntArray}.
     */
    private static @NonNull IntArray convertToIntArray(@NonNull JSONArray jsonArray)
            throws JSONException {
        if (jsonArray.length() == 0) {
            return new IntArray();
        }

        final int[] ret = new int[jsonArray.length()];
        for (int i = 0; i < ret.length; ++i) {
            ret[i] = jsonArray.getInt(i);
        }

        return IntArray.wrap(ret);
    }

    /**
     * Converts an {@code IntArray} into an {@code JSONArray} of integers.
     */
    private static @NonNull JSONArray convertToJsonArray(@NonNull IntArray intArray) {
        JSONArray jsonArray = new JSONArray();
        for (int i = 0; i < intArray.size(); ++i) {
            jsonArray.put(intArray.get(i));
        }

        return jsonArray;
    }

    private static @NonNull JSONArray convertToJsonArray(@NonNull List<RestoreInfo> list)
            throws JSONException {
        JSONArray jsonArray = new JSONArray();
        for (RestoreInfo ri : list) {
            JSONObject jo = new JSONObject();
            jo.put("userId", ri.userId);
            jo.put("appId", ri.appId);
            jo.put("seInfo", ri.seInfo);
            jsonArray.put(jo);
        }

        return jsonArray;
    }

    private static @NonNull ArrayList<RestoreInfo> convertToRestoreInfoArray(
            @NonNull JSONArray array) throws JSONException {
        ArrayList<RestoreInfo> restoreInfos = new ArrayList<>();

        for (int i = 0; i < array.length(); ++i) {
            JSONObject jo = array.getJSONObject(i);
            restoreInfos.add(new RestoreInfo(
                    jo.getInt("userId"),
                    jo.getInt("appId"),
                    jo.getString("seInfo")));
        }

        return restoreInfos;
    }

    private static @NonNull JSONArray ceSnapshotInodesToJson(
            @NonNull SparseLongArray ceSnapshotInodes) throws JSONException {
        JSONArray array = new JSONArray();
        for (int i = 0; i < ceSnapshotInodes.size(); i++) {
            JSONObject entryJson = new JSONObject();
            entryJson.put("userId", ceSnapshotInodes.keyAt(i));
            entryJson.put("ceSnapshotInode", ceSnapshotInodes.valueAt(i));
            array.put(entryJson);
        }
        return array;
    }

    private static @NonNull SparseLongArray ceSnapshotInodesFromJson(JSONArray json)
            throws JSONException {
        SparseLongArray ceSnapshotInodes = new SparseLongArray(json.length());
        for (int i = 0; i < json.length(); i++) {
            JSONObject entry = json.getJSONObject(i);
            ceSnapshotInodes.append(entry.getInt("userId"), entry.getLong("ceSnapshotInode"));
        }
        return ceSnapshotInodes;
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
                    int rollbackId = element.getInt("rollbackId");
                    List<PackageRollbackInfo> packages = packageRollbackInfosFromJson(
                            element.getJSONArray("packages"));
                    boolean isStaged = element.getBoolean("isStaged");
                    List<VersionedPackage> causePackages = versionedPackagesFromJson(
                            element.getJSONArray("causePackages"));
                    int committedSessionId = element.getInt("committedSessionId");
                    RollbackInfo rollback = new RollbackInfo(rollbackId, packages, isStaged,
                            causePackages, committedSessionId);
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
    RollbackData createAvailableRollback(int rollbackId) throws IOException {
        File backupDir = new File(mAvailableRollbacksDir, Integer.toString(rollbackId));
        return new RollbackData(rollbackId, backupDir, -1, true);
    }

    RollbackData createPendingStagedRollback(int rollbackId, int stagedSessionId)
            throws IOException {
        File backupDir = new File(mAvailableRollbacksDir, Integer.toString(rollbackId));
        return new RollbackData(rollbackId, backupDir, stagedSessionId, false);
    }

    /**
     * Creates a backup copy of an apk or apex for a package.
     * For packages containing splits, this method should be called for each
     * of the package's split apks in addition to the base apk.
     */
    static void backupPackageCodePath(RollbackData data, String packageName, String codePath)
            throws IOException {
        File sourceFile = new File(codePath);
        File targetDir = new File(data.backupDir, packageName);
        targetDir.mkdirs();
        File targetFile = new File(targetDir, sourceFile.getName());

        // TODO: Copy by hard link instead to save on cpu and storage space?
        Files.copy(sourceFile.toPath(), targetFile.toPath());
    }

    /**
     * Returns the apk or apex files backed up for the given package.
     * Includes the base apk and any splits. Returns null if none found.
     */
    static File[] getPackageCodePaths(RollbackData data, String packageName) {
        File targetDir = new File(data.backupDir, packageName);
        File[] files = targetDir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }
        return files;
    }

    /**
     * Writes the metadata for an available rollback to persistent storage.
     */
    void saveAvailableRollback(RollbackData data) throws IOException {
        try {
            JSONObject dataJson = new JSONObject();
            dataJson.put("rollbackId", data.rollbackId);
            dataJson.put("packages", toJson(data.packages));
            dataJson.put("timestamp", data.timestamp.toString());
            dataJson.put("stagedSessionId", data.stagedSessionId);
            dataJson.put("isAvailable", data.isAvailable);
            dataJson.put("apkSessionId", data.apkSessionId);

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
                element.put("rollbackId", rollback.getRollbackId());
                element.put("packages", toJson(rollback.getPackages()));
                element.put("isStaged", rollback.isStaged());
                element.put("causePackages", versionedPackagesToJson(rollback.getCausePackages()));
                element.put("committedSessionId", rollback.getCommittedSessionId());
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
            File rollbackJsonFile = new File(backupDir, "rollback.json");
            JSONObject dataJson = new JSONObject(
                    IoUtils.readFileAsString(rollbackJsonFile.getAbsolutePath()));

            int rollbackId = dataJson.getInt("rollbackId");
            int stagedSessionId = dataJson.getInt("stagedSessionId");
            boolean isAvailable = dataJson.getBoolean("isAvailable");
            RollbackData data = new RollbackData(rollbackId, backupDir,
                    stagedSessionId, isAvailable);
            data.packages.addAll(packageRollbackInfosFromJson(dataJson.getJSONArray("packages")));
            data.timestamp = Instant.parse(dataJson.getString("timestamp"));
            data.apkSessionId = dataJson.getInt("apkSessionId");
            return data;
        } catch (JSONException | DateTimeParseException e) {
            throw new IOException(e);
        }
    }

    private JSONObject toJson(VersionedPackage pkg) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("packageName", pkg.getPackageName());
        json.put("longVersionCode", pkg.getLongVersionCode());
        return json;
    }

    private VersionedPackage versionedPackageFromJson(JSONObject json) throws JSONException {
        String packageName = json.getString("packageName");
        long longVersionCode = json.getLong("longVersionCode");
        return new VersionedPackage(packageName, longVersionCode);
    }

    private JSONObject toJson(PackageRollbackInfo info) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("versionRolledBackFrom", toJson(info.getVersionRolledBackFrom()));
        json.put("versionRolledBackTo", toJson(info.getVersionRolledBackTo()));

        IntArray pendingBackups = info.getPendingBackups();
        List<RestoreInfo> pendingRestores = info.getPendingRestores();
        IntArray installedUsers = info.getInstalledUsers();
        json.put("pendingBackups", convertToJsonArray(pendingBackups));
        json.put("pendingRestores", convertToJsonArray(pendingRestores));

        json.put("isApex", info.isApex());

        json.put("installedUsers", convertToJsonArray(installedUsers));
        json.put("ceSnapshotInodes", ceSnapshotInodesToJson(info.getCeSnapshotInodes()));

        return json;
    }

    private PackageRollbackInfo packageRollbackInfoFromJson(JSONObject json) throws JSONException {
        VersionedPackage versionRolledBackFrom = versionedPackageFromJson(
                json.getJSONObject("versionRolledBackFrom"));
        VersionedPackage versionRolledBackTo = versionedPackageFromJson(
                json.getJSONObject("versionRolledBackTo"));

        final IntArray pendingBackups = convertToIntArray(
                json.getJSONArray("pendingBackups"));
        final ArrayList<RestoreInfo> pendingRestores = convertToRestoreInfoArray(
                json.getJSONArray("pendingRestores"));

        final boolean isApex = json.getBoolean("isApex");

        final IntArray installedUsers = convertToIntArray(json.getJSONArray("installedUsers"));
        final SparseLongArray ceSnapshotInodes = ceSnapshotInodesFromJson(
                json.getJSONArray("ceSnapshotInodes"));

        return new PackageRollbackInfo(versionRolledBackFrom, versionRolledBackTo,
                pendingBackups, pendingRestores, isApex, installedUsers, ceSnapshotInodes);
    }

    private JSONArray versionedPackagesToJson(List<VersionedPackage> packages)
            throws JSONException {
        JSONArray json = new JSONArray();
        for (VersionedPackage pkg : packages) {
            json.put(toJson(pkg));
        }
        return json;
    }

    private List<VersionedPackage> versionedPackagesFromJson(JSONArray json) throws JSONException {
        List<VersionedPackage> packages = new ArrayList<>();
        for (int i = 0; i < json.length(); ++i) {
            packages.add(versionedPackageFromJson(json.getJSONObject(i)));
        }
        return packages;
    }

    private JSONArray toJson(List<PackageRollbackInfo> infos) throws JSONException {
        JSONArray json = new JSONArray();
        for (PackageRollbackInfo info : infos) {
            json.put(toJson(info));
        }
        return json;
    }

    private List<PackageRollbackInfo> packageRollbackInfosFromJson(JSONArray json)
            throws JSONException {
        List<PackageRollbackInfo> infos = new ArrayList<>();
        for (int i = 0; i < json.length(); ++i) {
            infos.add(packageRollbackInfoFromJson(json.getJSONObject(i)));
        }
        return infos;
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
