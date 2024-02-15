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
import android.content.pm.Flags;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.content.rollback.PackageRollbackInfo;
import android.content.rollback.PackageRollbackInfo.RestoreInfo;
import android.content.rollback.RollbackInfo;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;

import libcore.io.IoUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
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
    private final File mRollbackDataDir;
    private final File mRollbackHistoryDir;

    RollbackStore(File rollbackDataDir, File rollbackHistoryDir) {
        mRollbackDataDir = rollbackDataDir;
        mRollbackHistoryDir = rollbackHistoryDir;
    }

    /**
     * Reads the rollbacks from persistent storage.
     */
    private static List<Rollback> loadRollbacks(File rollbackDataDir) {
        List<Rollback> rollbacks = new ArrayList<>();
        rollbackDataDir.mkdirs();
        for (File rollbackDir : rollbackDataDir.listFiles()) {
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

    List<Rollback> loadRollbacks() {
        return loadRollbacks(mRollbackDataDir);
    }

    List<Rollback> loadHistorialRollbacks() {
        return loadRollbacks(mRollbackHistoryDir);
    }

    /**
     * Converts a {@code JSONArray} of integers to a {@code List<Integer>}.
     */
    private static @NonNull List<Integer> toIntList(@NonNull JSONArray jsonArray)
            throws JSONException {
        final List<Integer> ret = new ArrayList<>();
        for (int i = 0; i < jsonArray.length(); ++i) {
            ret.add(jsonArray.getInt(i));
        }

        return ret;
    }

    /**
     * Converts a {@code List<Integer>} into a {@code JSONArray} of integers.
     */
    private static @NonNull JSONArray fromIntList(@NonNull List<Integer> list) {
        JSONArray jsonArray = new JSONArray();
        for (int i = 0; i < list.size(); ++i) {
            jsonArray.put(list.get(i));
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

    private static @NonNull JSONArray extensionVersionsToJson(
            SparseIntArray extensionVersions) throws JSONException {
        JSONArray array = new JSONArray();
        for (int i = 0; i < extensionVersions.size(); i++) {
            JSONObject entryJson = new JSONObject();
            entryJson.put("sdkVersion", extensionVersions.keyAt(i));
            entryJson.put("extensionVersion", extensionVersions.valueAt(i));
            array.put(entryJson);
        }
        return array;
    }

    private static @NonNull SparseIntArray extensionVersionsFromJson(JSONArray json)
            throws JSONException {
        if (json == null) {
            return new SparseIntArray(0);
        }
        SparseIntArray extensionVersions = new SparseIntArray(json.length());
        for (int i = 0; i < json.length(); i++) {
            JSONObject entry = json.getJSONObject(i);
            extensionVersions.append(
                    entry.getInt("sdkVersion"), entry.getInt("extensionVersion"));
        }
        return extensionVersions;
    }

    private static JSONObject rollbackInfoToJson(RollbackInfo rollback) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("rollbackId", rollback.getRollbackId());
        json.put("packages", toJson(rollback.getPackages()));
        json.put("isStaged", rollback.isStaged());
        json.put("causePackages", versionedPackagesToJson(rollback.getCausePackages()));
        json.put("committedSessionId", rollback.getCommittedSessionId());
        if (Flags.recoverabilityDetection()) {
            json.put("rollbackImpactLevel", rollback.getRollbackImpactLevel());
        }
        return json;
    }

    private static RollbackInfo rollbackInfoFromJson(JSONObject json) throws JSONException {
        RollbackInfo rollbackInfo = new RollbackInfo(
                json.getInt("rollbackId"),
                packageRollbackInfosFromJson(json.getJSONArray("packages")),
                json.getBoolean("isStaged"),
                versionedPackagesFromJson(json.getJSONArray("causePackages")),
                json.getInt("committedSessionId"));

        if (Flags.recoverabilityDetection()) {
                // to make it backward compatible.
            rollbackInfo.setRollbackImpactLevel(json.optInt("rollbackImpactLevel",
                    PackageManager.ROLLBACK_USER_IMPACT_LOW));
        }

        return rollbackInfo;
    }

    /**
     * Creates a new Rollback instance for a non-staged rollback with
     * backupDir assigned.
     */
    Rollback createNonStagedRollback(int rollbackId, int originalSessionId, int userId,
            String installerPackageName, int[] packageSessionIds,
            SparseIntArray extensionVersions) {
        File backupDir = new File(mRollbackDataDir, Integer.toString(rollbackId));
        return new Rollback(rollbackId, backupDir, originalSessionId, /* isStaged */ false, userId,
                installerPackageName, packageSessionIds, extensionVersions);
    }

    /**
     * Creates a new Rollback instance for a staged rollback with
     * backupDir assigned.
     */
    Rollback createStagedRollback(int rollbackId, int originalSessionId, int userId,
            String installerPackageName, int[] packageSessionIds,
            SparseIntArray extensionVersions) {
        File backupDir = new File(mRollbackDataDir, Integer.toString(rollbackId));
        return new Rollback(rollbackId, backupDir, originalSessionId, /* isStaged */ true, userId,
                installerPackageName, packageSessionIds, extensionVersions);
    }

    private static boolean isLinkPossible(File oldFile, File newFile) {
        try {
            return Os.stat(oldFile.getAbsolutePath()).st_dev
                    == Os.stat(newFile.getAbsolutePath()).st_dev;
        } catch (ErrnoException ignore) {
            return false;
        }
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

        boolean fallbackToCopy = !isLinkPossible(sourceFile, targetDir);
        if (!fallbackToCopy) {
            try {
                // Create a hard link to avoid copy
                // TODO(b/168562373)
                // Linking between non-encrypted and encrypted is not supported and we have
                // encrypted /data/rollback and non-encrypted /data/apex/active. For now this works
                // because we happen to store encrypted files under /data/apex/active which is no
                // longer the case when compressed apex rolls out. We have to handle this case in
                // order not to fall back to copy.
                Os.link(sourceFile.getAbsolutePath(), targetFile.getAbsolutePath());
            } catch (ErrnoException e) {
                boolean isRollbackTest =
                        SystemProperties.getBoolean("persist.rollback.is_test", false);
                if (isRollbackTest) {
                    throw new IOException(e);
                } else {
                    fallbackToCopy = true;
                }
            }
        }

        if (fallbackToCopy) {
            // Fall back to copy if hardlink can't be created
            Files.copy(sourceFile.toPath(), targetFile.toPath());
        }
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
    private static void saveRollback(Rollback rollback, File backDir) {
        FileOutputStream fos = null;
        AtomicFile file = new AtomicFile(new File(backDir, "rollback.json"));
        try {
            backDir.mkdirs();
            JSONObject dataJson = new JSONObject();
            dataJson.put("info", rollbackInfoToJson(rollback.info));
            dataJson.put("timestamp", rollback.getTimestamp().toString());
            if (Flags.rollbackLifetime()) {
                dataJson.put("rollbackLifetimeMillis", rollback.getRollbackLifetimeMillis());
            }
            dataJson.put("originalSessionId", rollback.getOriginalSessionId());
            dataJson.put("state", rollback.getStateAsString());
            dataJson.put("stateDescription", rollback.getStateDescription());
            dataJson.put("restoreUserDataInProgress", rollback.isRestoreUserDataInProgress());
            dataJson.put("userId", rollback.getUserId());
            dataJson.putOpt("installerPackageName", rollback.getInstallerPackageName());
            dataJson.putOpt(
                    "extensionVersions", extensionVersionsToJson(rollback.getExtensionVersions()));

            fos = file.startWrite();
            fos.write(dataJson.toString().getBytes());
            fos.flush();
            file.finishWrite(fos);
        } catch (JSONException | IOException e) {
            Slog.e(TAG, "Unable to save rollback for: " + rollback.info.getRollbackId(), e);
            if (fos != null) {
                file.failWrite(fos);
            }
        }
    }

    static void saveRollback(Rollback rollback) {
        saveRollback(rollback, rollback.getBackupDir());
    }

    /**
     * Saves the rollback to $mRollbackHistoryDir/ROLLBACKID-HEX for debugging purpose.
     */
    void saveRollbackToHistory(Rollback rollback) {
        // The same id might be allocated to different historical rollbacks.
        // Let's add a suffix to avoid naming collision.
        String suffix = Long.toHexString(rollback.getTimestamp().getEpochSecond());
        String dirName = Integer.toString(rollback.info.getRollbackId());
        File backupDir = new File(mRollbackHistoryDir, dirName + "-" + suffix);
        saveRollback(rollback, backupDir);
    }

    /**
     * Removes all persistent storage associated with the given rollback.
     */
    static void deleteRollback(Rollback rollback) {
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

            return rollbackFromJson(dataJson, backupDir);
        } catch (JSONException | DateTimeParseException | ParseException e) {
            throw new IOException(e);
        }
    }

    @VisibleForTesting
    static Rollback rollbackFromJson(JSONObject dataJson, File backupDir)
            throws JSONException, ParseException {
        Rollback rollback = new Rollback(
                rollbackInfoFromJson(dataJson.getJSONObject("info")),
                backupDir,
                Instant.parse(dataJson.getString("timestamp")),
                // Backward compatibility: Historical rollbacks are not erased upon OTA update.
                //  Need to load the old field 'stagedSessionId' as fallback.
                dataJson.optInt("originalSessionId", dataJson.optInt("stagedSessionId", -1)),
                rollbackStateFromString(dataJson.getString("state")),
                dataJson.optString("stateDescription"),
                dataJson.getBoolean("restoreUserDataInProgress"),
                dataJson.optInt("userId", UserHandle.SYSTEM.getIdentifier()),
                dataJson.optString("installerPackageName", ""),
                extensionVersionsFromJson(dataJson.optJSONArray("extensionVersions")));
        if (Flags.rollbackLifetime()) {
            rollback.setRollbackLifetimeMillis(dataJson.optLong("rollbackLifetimeMillis"));
        }
        return rollback;
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

        List<Integer> pendingBackups = info.getPendingBackups();
        List<RestoreInfo> pendingRestores = info.getPendingRestores();
        List<Integer> snapshottedUsers = info.getSnapshottedUsers();
        json.put("pendingBackups", fromIntList(pendingBackups));
        json.put("pendingRestores", convertToJsonArray(pendingRestores));

        json.put("isApex", info.isApex());
        json.put("isApkInApex", info.isApkInApex());

        // Field is named 'installedUsers' for legacy reasons.
        json.put("installedUsers", fromIntList(snapshottedUsers));

        json.put("rollbackDataPolicy", info.getRollbackDataPolicy());

        return json;
    }

    private static PackageRollbackInfo packageRollbackInfoFromJson(JSONObject json)
            throws JSONException {
        VersionedPackage versionRolledBackFrom = versionedPackageFromJson(
                json.getJSONObject("versionRolledBackFrom"));
        VersionedPackage versionRolledBackTo = versionedPackageFromJson(
                json.getJSONObject("versionRolledBackTo"));

        final List<Integer> pendingBackups = toIntList(
                json.getJSONArray("pendingBackups"));
        final ArrayList<RestoreInfo> pendingRestores = convertToRestoreInfoArray(
                json.getJSONArray("pendingRestores"));

        final boolean isApex = json.getBoolean("isApex");
        final boolean isApkInApex = json.getBoolean("isApkInApex");

        // Field is named 'installedUsers' for legacy reasons.
        final List<Integer> snapshottedUsers = toIntList(json.getJSONArray("installedUsers"));

        // Backward compatibility: no such field for old versions.
        final int rollbackDataPolicy = json.optInt("rollbackDataPolicy",
                PackageManager.ROLLBACK_DATA_POLICY_RESTORE);

        return new PackageRollbackInfo(versionRolledBackFrom, versionRolledBackTo,
                pendingBackups, pendingRestores, isApex, isApkInApex, snapshottedUsers,
                rollbackDataPolicy);
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
