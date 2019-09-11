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

import static com.android.server.rollback.Rollback.rollbackStateFromString;

import android.annotation.NonNull;
import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.PackageRollbackInfo.RestoreInfo;
import android.content.rollback.RollbackInfo;
import android.util.IntArray;
import android.util.Slog;
import android.util.SparseLongArray;

import libcore.io.IoUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.text.ParseException;
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
    // following directory structure to store persisted data for rollbacks:
    //   /data/rollback/
    //       XXX/
    //           rollback.json
    //           com.package.A/
    //               base.apk
    //           com.package.B/
    //               base.apk
    //       YYY/
    //           rollback.json
    //
    // * XXX, YYY are the rollbackIds for the corresponding rollbacks.
    // * rollback.json contains all relevant metadata for the rollback.
    //
    // TODO: Use AtomicFile for all the .json files?
    private final File mRollbackDataDir;

    RollbackStore(File rollbackDataDir) {
        mRollbackDataDir = rollbackDataDir;
    }

    /**
     * Reads the rollbacks from persistent storage.
     */
    List<Rollback> loadRollbacks() {
        List<Rollback> rollbacks = new ArrayList<>();
        mRollbackDataDir.mkdirs();
        for (File rollbackDir : mRollbackDataDir.listFiles()) {
            if (rollbackDir.isDirectory()) {
                try {
                    rollbacks.add(loadRollback(rollbackDir));
                } catch (IOException e) {
                    Slog.e(TAG, "Unable to read rollback at " + rollbackDir, e);
                    removeFile(rollbackDir);
                }
            }
        }
        return rollbacks;
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

    private static JSONObject rollbackInfoToJson(RollbackInfo rollback) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("rollbackId", rollback.getRollbackId());
        json.put("packages", toJson(rollback.getPackages()));
        json.put("isStaged", rollback.isStaged());
        json.put("causePackages", versionedPackagesToJson(rollback.getCausePackages()));
        json.put("committedSessionId", rollback.getCommittedSessionId());
        return json;
    }

    private static RollbackInfo rollbackInfoFromJson(JSONObject json) throws JSONException {
        return new RollbackInfo(
                json.getInt("rollbackId"),
                packageRollbackInfosFromJson(json.getJSONArray("packages")),
                json.getBoolean("isStaged"),
                versionedPackagesFromJson(json.getJSONArray("causePackages")),
                json.getInt("committedSessionId"));
    }

    /**
     * Creates a new Rollback instance for a non-staged rollback with
     * backupDir assigned.
     */
    Rollback createNonStagedRollback(int rollbackId) {
        File backupDir = new File(mRollbackDataDir, Integer.toString(rollbackId));
        return new Rollback(rollbackId, backupDir, -1);
    }

    /**
     * Creates a new Rollback instance for a staged rollback with
     * backupDir assigned.
     */
    Rollback createStagedRollback(int rollbackId, int stagedSessionId) {
        File backupDir = new File(mRollbackDataDir, Integer.toString(rollbackId));
        return new Rollback(rollbackId, backupDir, stagedSessionId);
    }

    /**
     * Creates a backup copy of an apk or apex for a package.
     * For packages containing splits, this method should be called for each
     * of the package's split apks in addition to the base apk.
     */
    static void backupPackageCodePath(Rollback rollback, String packageName, String codePath)
            throws IOException {
        File sourceFile = new File(codePath);
        File targetDir = new File(rollback.getBackupDir(), packageName);
        targetDir.mkdirs();
        File targetFile = new File(targetDir, sourceFile.getName());

        // TODO: Copy by hard link instead to save on cpu and storage space?
        Files.copy(sourceFile.toPath(), targetFile.toPath());
    }

    /**
     * Returns the apk or apex files backed up for the given package.
     * Includes the base apk and any splits. Returns null if none found.
     */
    static File[] getPackageCodePaths(Rollback rollback, String packageName) {
        File targetDir = new File(rollback.getBackupDir(), packageName);
        File[] files = targetDir.listFiles();
        if (files == null || files.length == 0) {
            return null;
        }
        return files;
    }

    /**
     * Deletes all backed up apks and apex files associated with the given
     * rollback.
     */
    static void deletePackageCodePaths(Rollback rollback) {
        for (PackageRollbackInfo info : rollback.info.getPackages()) {
            File targetDir = new File(rollback.getBackupDir(), info.getPackageName());
            removeFile(targetDir);
        }
    }

    /**
     * Saves the given rollback to persistent storage.
     */
    void saveRollback(Rollback rollback) throws IOException {
        try {
            JSONObject dataJson = new JSONObject();
            dataJson.put("info", rollbackInfoToJson(rollback.info));
            dataJson.put("timestamp", rollback.getTimestamp().toString());
            dataJson.put("stagedSessionId", rollback.getStagedSessionId());
            dataJson.put("state", rollback.getStateAsString());
            dataJson.put("apkSessionId", rollback.getApkSessionId());
            dataJson.put("restoreUserDataInProgress", rollback.isRestoreUserDataInProgress());

            PrintWriter pw = new PrintWriter(new File(rollback.getBackupDir(), "rollback.json"));
            pw.println(dataJson.toString());
            pw.close();
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    /**
     * Removes all persistent storage associated with the given rollback.
     */
    void deleteRollback(Rollback rollback) {
        removeFile(rollback.getBackupDir());
    }

    /**
     * Reads the metadata for a rollback from the given directory.
     * @throws IOException in case of error reading the data.
     */
    private static Rollback loadRollback(File backupDir) throws IOException {
        try {
            File rollbackJsonFile = new File(backupDir, "rollback.json");
            JSONObject dataJson = new JSONObject(
                    IoUtils.readFileAsString(rollbackJsonFile.getAbsolutePath()));

            return new Rollback(
                    rollbackInfoFromJson(dataJson.getJSONObject("info")),
                    backupDir,
                    Instant.parse(dataJson.getString("timestamp")),
                    dataJson.getInt("stagedSessionId"),
                    rollbackStateFromString(dataJson.getString("state")),
                    dataJson.getInt("apkSessionId"),
                    dataJson.getBoolean("restoreUserDataInProgress"));
        } catch (JSONException | DateTimeParseException | ParseException e) {
            throw new IOException(e);
        }
    }

    private static JSONObject toJson(VersionedPackage pkg) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("packageName", pkg.getPackageName());
        json.put("longVersionCode", pkg.getLongVersionCode());
        return json;
    }

    private static VersionedPackage versionedPackageFromJson(JSONObject json) throws JSONException {
        String packageName = json.getString("packageName");
        long longVersionCode = json.getLong("longVersionCode");
        return new VersionedPackage(packageName, longVersionCode);
    }

    private static JSONObject toJson(PackageRollbackInfo info) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("versionRolledBackFrom", toJson(info.getVersionRolledBackFrom()));
        json.put("versionRolledBackTo", toJson(info.getVersionRolledBackTo()));

        IntArray pendingBackups = info.getPendingBackups();
        List<RestoreInfo> pendingRestores = info.getPendingRestores();
        IntArray snapshottedUsers = info.getSnapshottedUsers();
        json.put("pendingBackups", convertToJsonArray(pendingBackups));
        json.put("pendingRestores", convertToJsonArray(pendingRestores));

        json.put("isApex", info.isApex());

        // Field is named 'installedUsers' for legacy reasons.
        json.put("installedUsers", convertToJsonArray(snapshottedUsers));
        json.put("ceSnapshotInodes", ceSnapshotInodesToJson(info.getCeSnapshotInodes()));

        return json;
    }

    private static PackageRollbackInfo packageRollbackInfoFromJson(JSONObject json)
            throws JSONException {
        VersionedPackage versionRolledBackFrom = versionedPackageFromJson(
                json.getJSONObject("versionRolledBackFrom"));
        VersionedPackage versionRolledBackTo = versionedPackageFromJson(
                json.getJSONObject("versionRolledBackTo"));

        final IntArray pendingBackups = convertToIntArray(
                json.getJSONArray("pendingBackups"));
        final ArrayList<RestoreInfo> pendingRestores = convertToRestoreInfoArray(
                json.getJSONArray("pendingRestores"));

        final boolean isApex = json.getBoolean("isApex");

        // Field is named 'installedUsers' for legacy reasons.
        final IntArray snapshottedUsers = convertToIntArray(json.getJSONArray("installedUsers"));
        final SparseLongArray ceSnapshotInodes = ceSnapshotInodesFromJson(
                json.getJSONArray("ceSnapshotInodes"));

        return new PackageRollbackInfo(versionRolledBackFrom, versionRolledBackTo,
                pendingBackups, pendingRestores, isApex, snapshottedUsers, ceSnapshotInodes);
    }

    private static JSONArray versionedPackagesToJson(List<VersionedPackage> packages)
            throws JSONException {
        JSONArray json = new JSONArray();
        for (VersionedPackage pkg : packages) {
            json.put(toJson(pkg));
        }
        return json;
    }

    private static List<VersionedPackage> versionedPackagesFromJson(JSONArray json)
            throws JSONException {
        List<VersionedPackage> packages = new ArrayList<>();
        for (int i = 0; i < json.length(); ++i) {
            packages.add(versionedPackageFromJson(json.getJSONObject(i)));
        }
        return packages;
    }

    private static JSONArray toJson(List<PackageRollbackInfo> infos) throws JSONException {
        JSONArray json = new JSONArray();
        for (PackageRollbackInfo info : infos) {
            json.put(toJson(info));
        }
        return json;
    }

    private static List<PackageRollbackInfo> packageRollbackInfosFromJson(JSONArray json)
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
    private static void removeFile(File file) {
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
