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
 * limitations under the License
 */

package com.android.server.pm.dex;

import android.os.Build;
import android.util.AtomicFile;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.FastPrintWriter;
import com.android.server.pm.AbstractStatsBase;
import com.android.server.pm.PackageManagerServiceUtils;

import dalvik.system.VMRuntime;

import libcore.io.IoUtils;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Stat file which store usage information about dex files.
 */
public class PackageDexUsage extends AbstractStatsBase<Void> {
    private static final String TAG = "PackageDexUsage";

    // We are currently at version 2.
    // Version 1 was introduced in Nougat and Version 2 in Oreo.
    // We dropped version 1 support in R since all devices should have updated
    // already.
    private static final int PACKAGE_DEX_USAGE_VERSION = 2;

    private static final String PACKAGE_DEX_USAGE_VERSION_HEADER =
            "PACKAGE_MANAGER__PACKAGE_DEX_USAGE__";

    private static final String SPLIT_CHAR = ",";
    private static final String CODE_PATH_LINE_CHAR = "+";
    private static final String DEX_LINE_CHAR = "#";
    private static final String LOADING_PACKAGE_CHAR = "@";

    // One of the things we record about dex files is the class loader context that was used to
    // load them. That should be stable but if it changes we don't keep track of variable contexts.
    // Instead we put a special marker in the dex usage file in order to recognize the case and
    // skip optimizations on that dex files.
    /*package*/ static final String VARIABLE_CLASS_LOADER_CONTEXT =
            "=VariableClassLoaderContext=";

    // The marker used for unsupported class loader contexts (no longer written, may occur in old
    // files so discarded on read). Note: this matches
    // ClassLoaderContext::kUnsupportedClassLoaderContextEncoding in the runtime.
    /*package*/ static final String UNSUPPORTED_CLASS_LOADER_CONTEXT =
            "=UnsupportedClassLoaderContext=";

    /**
     * Limit on how many secondary DEX paths we store for a single owner, to avoid one app causing
     * unbounded memory consumption.
     */
    @VisibleForTesting
    /* package */ static final int MAX_SECONDARY_FILES_PER_OWNER = 100;

    // Map which structures the information we have on a package.
    // Maps package name to package data (which stores info about UsedByOtherApps and
    // secondary dex files.).
    // Access to this map needs synchronized.
    @GuardedBy("mPackageUseInfoMap")
    private final Map<String, PackageUseInfo> mPackageUseInfoMap;

    /* package */ PackageDexUsage() {
        super("package-dex-usage.list", "PackageDexUsage_DiskWriter", /*lock*/ false);
        mPackageUseInfoMap = new HashMap<>();
    }

    /**
     * Record a dex file load.
     *
     * Note this is called when apps load dex files and as such it should return
     * as fast as possible.
     *
     * @param owningPackageName the package owning the dex path
     * @param dexPath the path of the dex files being loaded
     * @param ownerUserId the user id which runs the code loading the dex files
     * @param loaderIsa the ISA of the app loading the dex files
     * @param primaryOrSplit whether or not the dex file is a primary/split dex. True indicates
     *        the file is either primary or a split. False indicates the file is secondary dex.
     * @param loadingPackageName the package performing the load. Recorded only if it is different
     *        than {@param owningPackageName}.
     * @param overwriteCLC if true, the class loader context will be overwritten instead of being
     *        merged
     * @return true if the dex load constitutes new information, or false if this information
     *         has been seen before.
     */
    /* package */ boolean record(String owningPackageName, String dexPath, int ownerUserId,
            String loaderIsa, boolean primaryOrSplit,
            String loadingPackageName, String classLoaderContext, boolean overwriteCLC) {
        if (!PackageManagerServiceUtils.checkISA(loaderIsa)) {
            throw new IllegalArgumentException("loaderIsa " + loaderIsa + " is unsupported");
        }
        if (classLoaderContext == null) {
            throw new IllegalArgumentException("Null classLoaderContext");
        }
        if (classLoaderContext.equals(UNSUPPORTED_CLASS_LOADER_CONTEXT)) {
            Slog.e(TAG, "Unsupported context?");
            return false;
        }

        boolean isUsedByOtherApps = !owningPackageName.equals(loadingPackageName);

        synchronized (mPackageUseInfoMap) {
            PackageUseInfo packageUseInfo = mPackageUseInfoMap.get(owningPackageName);
            if (packageUseInfo == null) {
                // This is the first time we see the package.
                packageUseInfo = new PackageUseInfo(owningPackageName);
                if (primaryOrSplit) {
                    // If we have a primary or a split apk, set isUsedByOtherApps.
                    // We do not need to record the loaderIsa or the owner because we compile
                    // primaries for all users and all ISAs.
                    packageUseInfo.mergePrimaryCodePaths(dexPath, loadingPackageName);
                } else {
                    // For secondary dex files record the loaderISA and the owner. We'll need
                    // to know under which user to compile and for what ISA.
                    DexUseInfo newData = new DexUseInfo(isUsedByOtherApps, ownerUserId,
                            classLoaderContext, loaderIsa);
                    packageUseInfo.mDexUseInfoMap.put(dexPath, newData);
                    maybeAddLoadingPackage(owningPackageName, loadingPackageName,
                            newData.mLoadingPackages);
                }
                mPackageUseInfoMap.put(owningPackageName, packageUseInfo);
                return true;
            } else {
                // We already have data on this package. Amend it.
                if (primaryOrSplit) {
                    // We have a possible update on the primary apk usage. Merge
                    // dex path information and return if there was an update.
                    return packageUseInfo.mergePrimaryCodePaths(dexPath, loadingPackageName);
                } else {
                    DexUseInfo newData = new DexUseInfo(
                            isUsedByOtherApps, ownerUserId, classLoaderContext, loaderIsa);
                    boolean updateLoadingPackages = maybeAddLoadingPackage(owningPackageName,
                            loadingPackageName, newData.mLoadingPackages);

                    DexUseInfo existingData = packageUseInfo.mDexUseInfoMap.get(dexPath);
                    if (existingData == null) {
                        // It's the first time we see this dex file.
                        if (packageUseInfo.mDexUseInfoMap.size() < MAX_SECONDARY_FILES_PER_OWNER) {
                            packageUseInfo.mDexUseInfoMap.put(dexPath, newData);
                            return true;
                        } else {
                            return updateLoadingPackages;
                        }
                    } else {
                        if (ownerUserId != existingData.mOwnerUserId) {
                            // Oups, this should never happen, the DexManager who calls this should
                            // do the proper checks and not call record if the user does not own the
                            // dex path.
                            // Secondary dex files are stored in the app user directory. A change in
                            // owningUser for the same path means that something went wrong at some
                            // higher level, and the loaderUser was allowed to cross
                            // user-boundaries and access data from what we know to be the owner
                            // user.
                            throw new IllegalArgumentException("Trying to change ownerUserId for "
                                    + " dex path " + dexPath + " from " + existingData.mOwnerUserId
                                    + " to " + ownerUserId);
                        }
                        // Merge the information into the existing data.
                        // Returns true if there was an update.
                        return existingData.merge(newData, overwriteCLC) || updateLoadingPackages;
                    }
                }
            }
        }
    }

    /**
     * Convenience method for sync reads which does not force the user to pass a useless
     * (Void) null.
     */
    /* package */ void read() {
      read((Void) null);
    }

    /**
     * Convenience method for async writes which does not force the user to pass a useless
     * (Void) null.
     */
    /*package*/ void maybeWriteAsync() {
      maybeWriteAsync(null);
    }

    /*package*/ void writeNow() {
        writeInternal(null);
    }

    @Override
    protected void writeInternal(Void data) {
        AtomicFile file = getFile();
        FileOutputStream f = null;

        try {
            f = file.startWrite();
            OutputStreamWriter osw = new OutputStreamWriter(f);
            write(osw);
            osw.flush();
            file.finishWrite(f);
        } catch (IOException e) {
            if (f != null) {
                file.failWrite(f);
            }
            Slog.e(TAG, "Failed to write usage for dex files", e);
        }
    }

    /**
     * File format:
     *
     * file_magic_version
     * package_name_1
     * +code_path1
     * @ loading_package_1_1, loading_package_1_2...
     * +code_path2
     * @ loading_package_2_1, loading_package_2_2...
     * #dex_file_path_1_1
     * user_1_1, used_by_other_app_1_1, user_isa_1_1_1, user_isa_1_1_2
     * @ loading_package_1_1_1, loading_package_1_1_2...
     * class_loader_context_1_1
     * #dex_file_path_1_2
     * user_1_2, used_by_other_app_1_2, user_isa_1_2_1, user_isa_1_2_2
     * @ loading_package_1_2_1, loading_package_1_2_2...
     * class_loader_context_1_2
     * ...
    */
    /* package */ void write(Writer out) {
        // Make a clone to avoid locking while writing to disk.
        Map<String, PackageUseInfo> packageUseInfoMapClone = clonePackageUseInfoMap();

        FastPrintWriter fpw = new FastPrintWriter(out);

        // Write the header.
        fpw.print(PACKAGE_DEX_USAGE_VERSION_HEADER);
        fpw.println(PACKAGE_DEX_USAGE_VERSION);

        for (Map.Entry<String, PackageUseInfo> pEntry : packageUseInfoMapClone.entrySet()) {
            // Write the package line.
            String packageName = pEntry.getKey();
            PackageUseInfo packageUseInfo = pEntry.getValue();
            fpw.println(packageName);

            // Write the code paths used by other apps.
            for (Map.Entry<String, Set<String>> codeEntry :
                    packageUseInfo.mPrimaryCodePaths.entrySet()) {
                String codePath = codeEntry.getKey();
                Set<String> loadingPackages = codeEntry.getValue();
                fpw.println(CODE_PATH_LINE_CHAR + codePath);
                fpw.println(LOADING_PACKAGE_CHAR + String.join(SPLIT_CHAR, loadingPackages));
            }

            // Write dex file lines.
            for (Map.Entry<String, DexUseInfo> dEntry : packageUseInfo.mDexUseInfoMap.entrySet()) {
                String dexPath = dEntry.getKey();
                DexUseInfo dexUseInfo = dEntry.getValue();
                fpw.println(DEX_LINE_CHAR + dexPath);
                fpw.print(String.join(SPLIT_CHAR, Integer.toString(dexUseInfo.mOwnerUserId),
                    writeBoolean(dexUseInfo.mIsUsedByOtherApps)));
                for (String isa : dexUseInfo.mLoaderIsas) {
                    fpw.print(SPLIT_CHAR + isa);
                }
                fpw.println();
                fpw.println(LOADING_PACKAGE_CHAR
                        + String.join(SPLIT_CHAR, dexUseInfo.mLoadingPackages));
                fpw.println(dexUseInfo.getClassLoaderContext());
            }
        }
        fpw.flush();
    }

    @Override
    protected void readInternal(Void data) {
        AtomicFile file = getFile();
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(file.openRead()));
            read(in);
        } catch (FileNotFoundException expected) {
            // The file may not be there. E.g. When we first take the OTA with this feature.
        } catch (IOException e) {
            Slog.w(TAG, "Failed to parse package dex usage.", e);
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    /* package */ void read(Reader reader) throws IOException {
        Map<String, PackageUseInfo> data = new HashMap<>();
        BufferedReader in = new BufferedReader(reader);
        // Read header, do version check.
        String versionLine = in.readLine();
        int version;
        if (versionLine == null) {
            throw new IllegalStateException("No version line found.");
        } else {
            if (!versionLine.startsWith(PACKAGE_DEX_USAGE_VERSION_HEADER)) {
                // TODO(calin): the caller is responsible to clear the file.
                throw new IllegalStateException("Invalid version line: " + versionLine);
            }
            version = Integer.parseInt(
                    versionLine.substring(PACKAGE_DEX_USAGE_VERSION_HEADER.length()));
            if (!isSupportedVersion(version)) {
                Slog.w(TAG, "Unexpected package-dex-use version: " + version
                        + ". Not reading from it");
                return;
            }
        }

        String line;
        String currentPackage = null;
        PackageUseInfo currentPackageData = null;

        Set<String> supportedIsas = new HashSet<>();
        for (String abi : Build.SUPPORTED_ABIS) {
            supportedIsas.add(VMRuntime.getInstructionSet(abi));
        }
        while ((line = in.readLine()) != null) {
            if (line.startsWith(DEX_LINE_CHAR)) {
                // This is the start of the the dex lines.
                // We expect 4 lines for each dex entry:
                // #dexPaths
                // @loading_package_1,loading_package_2,...
                // class_loader_context
                // onwerUserId,isUsedByOtherApps,isa1,isa2
                if (currentPackage == null) {
                    throw new IllegalStateException(
                        "Malformed PackageDexUsage file. Expected package line before dex line.");
                }

                // Line 1 is the dex path.
                String dexPath = line.substring(DEX_LINE_CHAR.length());

                // Line 2 is the dex data: (userId, isUsedByOtherApps, isa).
                line = in.readLine();
                if (line == null) {
                    throw new IllegalStateException("Could not find dexUseInfo line");
                }
                String[] elems = line.split(SPLIT_CHAR);
                if (elems.length < 3) {
                    throw new IllegalStateException("Invalid PackageDexUsage line: " + line);
                }

                Set<String> loadingPackages = readLoadingPackages(in, version);
                String classLoaderContext = readClassLoaderContext(in, version);

                if (UNSUPPORTED_CLASS_LOADER_CONTEXT.equals(classLoaderContext)) {
                    // We used to record use of unsupported class loaders, but we no longer do.
                    // Discard such entries; they will be deleted when we next write the file.
                    continue;
                }

                int ownerUserId = Integer.parseInt(elems[0]);
                boolean isUsedByOtherApps = readBoolean(elems[1]);
                DexUseInfo dexUseInfo = new DexUseInfo(isUsedByOtherApps, ownerUserId,
                        classLoaderContext, /*isa*/ null);
                dexUseInfo.mLoadingPackages.addAll(loadingPackages);
                for (int i = 2; i < elems.length; i++) {
                    String isa = elems[i];
                    if (supportedIsas.contains(isa)) {
                        dexUseInfo.mLoaderIsas.add(elems[i]);
                    } else {
                        // Should never happen unless someone crafts the file manually.
                        // In theory it could if we drop a supported ISA after an OTA but we don't
                        // do that.
                        Slog.wtf(TAG, "Unsupported ISA when parsing PackageDexUsage: " + isa);
                    }
                }
                if (supportedIsas.isEmpty()) {
                    Slog.wtf(TAG, "Ignore dexPath when parsing PackageDexUsage because of " +
                            "unsupported isas. dexPath=" + dexPath);
                    continue;
                }
                currentPackageData.mDexUseInfoMap.put(dexPath, dexUseInfo);
            } else if (line.startsWith(CODE_PATH_LINE_CHAR)) {
                // Expects 2 lines:
                //    +code_paths
                //    @loading_packages
                String codePath = line.substring(CODE_PATH_LINE_CHAR.length());
                Set<String> loadingPackages = readLoadingPackages(in, version);
                currentPackageData.mPrimaryCodePaths.put(codePath, loadingPackages);
            } else {
                // This is a package line.
                currentPackage = line;
                currentPackageData = new PackageUseInfo(currentPackage);
                data.put(currentPackage, currentPackageData);
            }
        }

        synchronized (mPackageUseInfoMap) {
            mPackageUseInfoMap.clear();
            mPackageUseInfoMap.putAll(data);
        }
    }

    /**
     * Reads the class loader context encoding from the buffer {@code in}.
     */
    private String readClassLoaderContext(BufferedReader in, int version) throws IOException {
        String context = in.readLine();
        if (context == null) {
            throw new IllegalStateException("Could not find the classLoaderContext line.");
        }
        return context;
    }

    /**
     * Reads the list of loading packages from the buffer {@code in}.
     */
    private Set<String> readLoadingPackages(BufferedReader in, int version)
            throws IOException {
        String line = in.readLine();
        if (line == null) {
            throw new IllegalStateException("Could not find the loadingPackages line.");
        }
        Set<String> result = new HashSet<>();
        if (line.length() != LOADING_PACKAGE_CHAR.length()) {
            Collections.addAll(result,
                    line.substring(LOADING_PACKAGE_CHAR.length()).split(SPLIT_CHAR));
        }
        return result;
    }

    /**
     * Utility method which adds {@param loadingPackage} to {@param loadingPackages} only if it's
     * not equal to {@param owningPackage}
     */
    private boolean maybeAddLoadingPackage(String owningPackage, String loadingPackage,
            Set<String> loadingPackages) {
        return !owningPackage.equals(loadingPackage) && loadingPackages.add(loadingPackage);
    }

    private boolean isSupportedVersion(int version) {
        return version == PACKAGE_DEX_USAGE_VERSION;
    }

    /**
     * Syncs the existing data with the set of available packages by removing obsolete entries.
     */
    /*package*/ void syncData(Map<String, Set<Integer>> packageToUsersMap,
            Map<String, Set<String>> packageToCodePaths,
            List<String> packagesToKeepDataAbout) {
        synchronized (mPackageUseInfoMap) {
            Iterator<Map.Entry<String, PackageUseInfo>> pIt =
                    mPackageUseInfoMap.entrySet().iterator();
            while (pIt.hasNext()) {
                Map.Entry<String, PackageUseInfo> pEntry = pIt.next();
                String packageName = pEntry.getKey();
                if (packagesToKeepDataAbout.contains(packageName)) {
                    // This is a package for which we should keep the data even if it's not
                    // in the list of user packages.
                    continue;
                }
                PackageUseInfo packageUseInfo = pEntry.getValue();
                Set<Integer> users = packageToUsersMap.get(packageName);
                if (users == null) {
                    // The package doesn't exist anymore, remove the record.
                    pIt.remove();
                } else {
                    // The package exists but we can prune the entries associated with non existing
                    // users.
                    Iterator<Map.Entry<String, DexUseInfo>> dIt =
                            packageUseInfo.mDexUseInfoMap.entrySet().iterator();
                    while (dIt.hasNext()) {
                        DexUseInfo dexUseInfo = dIt.next().getValue();
                        if (!users.contains(dexUseInfo.mOwnerUserId)) {
                            // User was probably removed. Delete its dex usage info.
                            dIt.remove();
                        }
                    }

                    // Sync the code paths.
                    Set<String> codePaths = packageToCodePaths.get(packageName);

                    Iterator<Map.Entry<String, Set<String>>> recordedIt =
                            packageUseInfo.mPrimaryCodePaths.entrySet().iterator();
                    while (recordedIt.hasNext()) {
                        Map.Entry<String, Set<String>> entry = recordedIt.next();
                        String recordedCodePath = entry.getKey();
                        if (!codePaths.contains(recordedCodePath)) {
                            // Clean up a non existing code path.
                            recordedIt.remove();
                        } else {
                            // Clean up a non existing loading package.
                            Set<String> recordedLoadingPackages = entry.getValue();
                            Iterator<String> recordedLoadingPackagesIt =
                                    recordedLoadingPackages.iterator();
                            while (recordedLoadingPackagesIt.hasNext()) {
                                String recordedLoadingPackage = recordedLoadingPackagesIt.next();
                                if (!packagesToKeepDataAbout.contains(recordedLoadingPackage)
                                        && !packageToUsersMap.containsKey(recordedLoadingPackage)) {
                                    recordedLoadingPackagesIt.remove();
                                }
                            }
                        }
                    }

                    if (!packageUseInfo.isAnyCodePathUsedByOtherApps()
                        && packageUseInfo.mDexUseInfoMap.isEmpty()) {
                        // The package is not used by other apps and we removed all its dex files
                        // records. Remove the entire package record as well.
                        pIt.remove();
                    }
                }
            }
        }
    }

    /**
     * Clears the {@code usesByOtherApps} marker for the package {@code packageName}.
     * @return true if the package usage info was updated.
     */
    /*package*/ boolean clearUsedByOtherApps(String packageName) {
        synchronized (mPackageUseInfoMap) {
            PackageUseInfo packageUseInfo = mPackageUseInfoMap.get(packageName);
            if (packageUseInfo == null) {
                return false;
            }
            return packageUseInfo.clearCodePathUsedByOtherApps();
        }
    }

    /**
     * Remove the usage data associated with package {@code packageName}.
     * @return true if the package usage was found and removed successfully.
     */
    /* package */ boolean removePackage(String packageName) {
        synchronized (mPackageUseInfoMap) {
            return mPackageUseInfoMap.remove(packageName) != null;
        }
    }

    /**
     * Remove all the records about package {@code packageName} belonging to user {@code userId}.
     * If the package is left with no records of secondary dex usage and is not used by other
     * apps it will be removed as well.
     * @return true if the record was found and actually deleted,
     *         false if the record doesn't exist
     */
    /*package*/ boolean removeUserPackage(String packageName, int userId) {
        synchronized (mPackageUseInfoMap) {
            PackageUseInfo packageUseInfo = mPackageUseInfoMap.get(packageName);
            if (packageUseInfo == null) {
                return false;
            }
            boolean updated = false;
            Iterator<Map.Entry<String, DexUseInfo>> dIt =
                            packageUseInfo.mDexUseInfoMap.entrySet().iterator();
            while (dIt.hasNext()) {
                DexUseInfo dexUseInfo = dIt.next().getValue();
                if (dexUseInfo.mOwnerUserId == userId) {
                    dIt.remove();
                    updated = true;
                }
            }
            // If no secondary dex info is left and the package is not used by other apps
            // remove the data since it is now useless.
            if (packageUseInfo.mDexUseInfoMap.isEmpty()
                    && !packageUseInfo.isAnyCodePathUsedByOtherApps()) {
                mPackageUseInfoMap.remove(packageName);
                updated = true;
            }
            return updated;
        }
    }

    /**
     * Remove the secondary dex file record belonging to the package {@code packageName}
     * and user {@code userId}.
     * @return true if the record was found and actually deleted,
     *         false if the record doesn't exist
     */
    /*package*/ boolean removeDexFile(String packageName, String dexFile, int userId) {
        synchronized (mPackageUseInfoMap) {
            PackageUseInfo packageUseInfo = mPackageUseInfoMap.get(packageName);
            if (packageUseInfo == null) {
                return false;
            }
            return removeDexFile(packageUseInfo, dexFile, userId);
        }
    }

    private boolean removeDexFile(PackageUseInfo packageUseInfo, String dexFile, int userId) {
        DexUseInfo dexUseInfo = packageUseInfo.mDexUseInfoMap.get(dexFile);
        if (dexUseInfo == null) {
            return false;
        }
        if (dexUseInfo.mOwnerUserId == userId) {
            packageUseInfo.mDexUseInfoMap.remove(dexFile);
            return true;
        }
        return false;
    }

    /*package*/ PackageUseInfo getPackageUseInfo(String packageName) {
        synchronized (mPackageUseInfoMap) {
            PackageUseInfo useInfo = mPackageUseInfoMap.get(packageName);
            // The useInfo contains a map for secondary dex files which could be modified
            // concurrently after this method returns and thus outside the locking we do here.
            // (i.e. the map is updated when new class loaders are created, which can happen anytime
            // after this method returns)
            // Make a defensive copy to be sure we don't get concurrent modifications.
            return useInfo == null ? null : new PackageUseInfo(useInfo);
        }
    }

    /**
     * Return all packages that contain records of secondary dex files.
     */
    /*package*/ Set<String> getAllPackagesWithSecondaryDexFiles() {
        Set<String> packages = new HashSet<>();
        synchronized (mPackageUseInfoMap) {
            for (Map.Entry<String, PackageUseInfo> entry : mPackageUseInfoMap.entrySet()) {
                if (!entry.getValue().mDexUseInfoMap.isEmpty()) {
                    packages.add(entry.getKey());
                }
            }
        }
        return packages;
    }

    /* package */ void clear() {
        synchronized (mPackageUseInfoMap) {
            mPackageUseInfoMap.clear();
        }
    }

    // Creates a deep copy of the class' mPackageUseInfoMap.
    private Map<String, PackageUseInfo> clonePackageUseInfoMap() {
        Map<String, PackageUseInfo> clone = new HashMap<>();
        synchronized (mPackageUseInfoMap) {
            for (Map.Entry<String, PackageUseInfo> e : mPackageUseInfoMap.entrySet()) {
                clone.put(e.getKey(), new PackageUseInfo(e.getValue()));
            }
        }
        return clone;
    }

    private String writeBoolean(boolean bool) {
        return bool ? "1" : "0";
    }

    private boolean readBoolean(String bool) {
        if ("0".equals(bool)) return false;
        if ("1".equals(bool)) return true;
        throw new IllegalArgumentException("Unknown bool encoding: " + bool);
    }

    /* package */ String dump() {
        StringWriter sw = new StringWriter();
        write(sw);
        return sw.toString();
    }

    /**
     * Stores data on how a package and its dex files are used.
     */
    public static class PackageUseInfo {
        // The name of the package this info belongs to.
        private final String mPackageName;
        // The app's code paths that are used by other apps.
        // The key is the code path and the value is the set of loading packages.
        private final Map<String, Set<String>> mPrimaryCodePaths;
        // Map dex paths to their data (isUsedByOtherApps, owner id, loader isa).
        private final Map<String, DexUseInfo> mDexUseInfoMap;

        /*package*/ PackageUseInfo(String packageName) {
            mPrimaryCodePaths = new HashMap<>();
            mDexUseInfoMap = new HashMap<>();
            mPackageName = packageName;
        }

        // Creates a deep copy of the `other`.
        private PackageUseInfo(PackageUseInfo other) {
            mPackageName = other.mPackageName;
            mPrimaryCodePaths = new HashMap<>();
            for (Map.Entry<String, Set<String>> e : other.mPrimaryCodePaths.entrySet()) {
                mPrimaryCodePaths.put(e.getKey(), new HashSet<>(e.getValue()));
            }

            mDexUseInfoMap = new HashMap<>();
            for (Map.Entry<String, DexUseInfo> e : other.mDexUseInfoMap.entrySet()) {
                mDexUseInfoMap.put(e.getKey(), new DexUseInfo(e.getValue()));
            }
        }

        private boolean mergePrimaryCodePaths(String codePath, String loadingPackage) {
            Set<String> loadingPackages = mPrimaryCodePaths.get(codePath);
            if (loadingPackages == null) {
                loadingPackages = new HashSet<>();
                mPrimaryCodePaths.put(codePath, loadingPackages);
            }
            return loadingPackages.add(loadingPackage);
        }

        public boolean isUsedByOtherApps(String codePath) {
            if (mPrimaryCodePaths.containsKey(codePath)) {
                Set<String> loadingPackages = mPrimaryCodePaths.get(codePath);
                if (loadingPackages.contains(mPackageName)) {
                    // If the owning package is in the list then this code path
                    // is used by others if there are other packages in the list.
                    return loadingPackages.size() > 1;
                } else {
                    // The owning package is not in the loading packages. So if
                    // the list is non-empty then the code path is used by others.
                    return !loadingPackages.isEmpty();
                }
            }
            return false;
        }

        public Map<String, DexUseInfo> getDexUseInfoMap() {
            return mDexUseInfoMap;
        }

        public Set<String> getLoadingPackages(String codePath) {
            return mPrimaryCodePaths.getOrDefault(codePath, null);
        }

        public boolean isAnyCodePathUsedByOtherApps() {
            return !mPrimaryCodePaths.isEmpty();
        }

        /**
         * Clears the usedByOtherApps markers from all code paths.
         * Returns whether or not there was an update.
         */
        /*package*/ boolean clearCodePathUsedByOtherApps() {
            boolean updated = false;
            List<String> retainOnlyOwningPackage = new ArrayList<>(1);
            retainOnlyOwningPackage.add(mPackageName);
            for (Map.Entry<String, Set<String>> entry : mPrimaryCodePaths.entrySet()) {
                // Remove or loading packages but the owning one.
                if (entry.getValue().retainAll(retainOnlyOwningPackage)) {
                    updated = true;
                }
            }
            return updated;
        }
    }

    /**
     * Stores data about a loaded dex files.
     */
    public static class DexUseInfo {
        private boolean mIsUsedByOtherApps;
        private final int mOwnerUserId;
        // The class loader context for the dex file. This encodes the class loader chain
        // (class loader type + class path) in a format compatible to dex2oat.
        // See {@code DexoptUtils.processContextForDexLoad}.
        private String mClassLoaderContext;
        // The instructions sets of the applications loading the dex file.
        private final Set<String> mLoaderIsas;
        // Packages who load this dex file.
        private final Set<String> mLoadingPackages;

        @VisibleForTesting
        /* package */ DexUseInfo(boolean isUsedByOtherApps, int ownerUserId,
                String classLoaderContext, String loaderIsa) {
            mIsUsedByOtherApps = isUsedByOtherApps;
            mOwnerUserId = ownerUserId;
            mClassLoaderContext = classLoaderContext;
            mLoaderIsas = new HashSet<>();
            if (loaderIsa != null) {
                mLoaderIsas.add(loaderIsa);
            }
            mLoadingPackages = new HashSet<>();
        }

        // Creates a deep copy of the `other`.
        private DexUseInfo(DexUseInfo other) {
            mIsUsedByOtherApps = other.mIsUsedByOtherApps;
            mOwnerUserId = other.mOwnerUserId;
            mClassLoaderContext = other.mClassLoaderContext;
            mLoaderIsas = new HashSet<>(other.mLoaderIsas);
            mLoadingPackages = new HashSet<>(other.mLoadingPackages);
        }

        private boolean merge(DexUseInfo dexUseInfo, boolean overwriteCLC) {
            boolean oldIsUsedByOtherApps = mIsUsedByOtherApps;
            mIsUsedByOtherApps = mIsUsedByOtherApps || dexUseInfo.mIsUsedByOtherApps;
            boolean updateIsas = mLoaderIsas.addAll(dexUseInfo.mLoaderIsas);
            boolean updateLoadingPackages = mLoadingPackages.addAll(dexUseInfo.mLoadingPackages);

            String oldClassLoaderContext = mClassLoaderContext;
            if (overwriteCLC) {
                mClassLoaderContext = dexUseInfo.mClassLoaderContext;
            } else if (isUnsupportedContext(mClassLoaderContext)) {
                mClassLoaderContext = dexUseInfo.mClassLoaderContext;
            } else if (!Objects.equals(mClassLoaderContext, dexUseInfo.mClassLoaderContext)) {
                // We detected a context change.
                mClassLoaderContext = VARIABLE_CLASS_LOADER_CONTEXT;
            }

            return updateIsas ||
                    (oldIsUsedByOtherApps != mIsUsedByOtherApps) ||
                    updateLoadingPackages
                    || !Objects.equals(oldClassLoaderContext, mClassLoaderContext);
        }

        private static boolean isUnsupportedContext(String context) {
            return UNSUPPORTED_CLASS_LOADER_CONTEXT.equals(context);
        }

        public boolean isUsedByOtherApps() {
            return mIsUsedByOtherApps;
        }

        /* package */ int getOwnerUserId() {
            return mOwnerUserId;
        }

        public Set<String> getLoaderIsas() {
            return mLoaderIsas;
        }

        public Set<String> getLoadingPackages() {
            return mLoadingPackages;
        }

        public String getClassLoaderContext() { return mClassLoaderContext; }

        public boolean isUnsupportedClassLoaderContext() {
            return isUnsupportedContext(mClassLoaderContext);
        }

        public boolean isVariableClassLoaderContext() {
            return VARIABLE_CLASS_LOADER_CONTEXT.equals(mClassLoaderContext);
        }
    }
}
