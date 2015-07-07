/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageStats;
import android.os.Build;
import android.text.TextUtils;
import android.util.Slog;

import dalvik.system.VMRuntime;

import com.android.internal.os.InstallerConnection;
import com.android.server.SystemService;

public final class Installer extends SystemService {
    private static final String TAG = "Installer";

    private final InstallerConnection mInstaller;

    public Installer(Context context) {
        super(context);
        mInstaller = new InstallerConnection();
    }

    @Override
    public void onStart() {
        Slog.i(TAG, "Waiting for installd to be ready.");
        mInstaller.waitForConnection();
    }

    private static String escapeNull(String arg) {
        if (TextUtils.isEmpty(arg)) {
            return "!";
        } else {
            if (arg.indexOf('\0') != -1 || arg.indexOf(' ') != -1) {
                throw new IllegalArgumentException(arg);
            }
            return arg;
        }
    }

    @Deprecated
    public int install(String name, int uid, int gid, String seinfo) {
        return install(null, name, uid, gid, seinfo);
    }

    public int install(String uuid, String name, int uid, int gid, String seinfo) {
        StringBuilder builder = new StringBuilder("install");
        builder.append(' ');
        builder.append(escapeNull(uuid));
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        builder.append(uid);
        builder.append(' ');
        builder.append(gid);
        builder.append(' ');
        builder.append(seinfo != null ? seinfo : "!");
        return mInstaller.execute(builder.toString());
    }

    public int dexopt(String apkPath, int uid, boolean isPublic,
            String instructionSet, int dexoptNeeded) {
        if (!isValidInstructionSet(instructionSet)) {
            Slog.e(TAG, "Invalid instruction set: " + instructionSet);
            return -1;
        }

        return mInstaller.dexopt(apkPath, uid, isPublic, instructionSet, dexoptNeeded);
    }

    public int dexopt(String apkPath, int uid, boolean isPublic, String pkgName,
            String instructionSet, int dexoptNeeded, boolean vmSafeMode,
            boolean debuggable, @Nullable String outputPath) {
        if (!isValidInstructionSet(instructionSet)) {
            Slog.e(TAG, "Invalid instruction set: " + instructionSet);
            return -1;
        }
        return mInstaller.dexopt(apkPath, uid, isPublic, pkgName,
                instructionSet, dexoptNeeded, vmSafeMode,
                debuggable, outputPath);
    }

    public int idmap(String targetApkPath, String overlayApkPath, int uid) {
        StringBuilder builder = new StringBuilder("idmap");
        builder.append(' ');
        builder.append(targetApkPath);
        builder.append(' ');
        builder.append(overlayApkPath);
        builder.append(' ');
        builder.append(uid);
        return mInstaller.execute(builder.toString());
    }

    public int movedex(String srcPath, String dstPath, String instructionSet) {
        if (!isValidInstructionSet(instructionSet)) {
            Slog.e(TAG, "Invalid instruction set: " + instructionSet);
            return -1;
        }

        StringBuilder builder = new StringBuilder("movedex");
        builder.append(' ');
        builder.append(srcPath);
        builder.append(' ');
        builder.append(dstPath);
        builder.append(' ');
        builder.append(instructionSet);
        return mInstaller.execute(builder.toString());
    }

    public int rmdex(String codePath, String instructionSet) {
        if (!isValidInstructionSet(instructionSet)) {
            Slog.e(TAG, "Invalid instruction set: " + instructionSet);
            return -1;
        }

        StringBuilder builder = new StringBuilder("rmdex");
        builder.append(' ');
        builder.append(codePath);
        builder.append(' ');
        builder.append(instructionSet);
        return mInstaller.execute(builder.toString());
    }

    /**
     * Removes packageDir or its subdirectory
     */
    public int rmPackageDir(String packageDir) {
        StringBuilder builder = new StringBuilder("rmpackagedir");
        builder.append(' ');
        builder.append(packageDir);
        return mInstaller.execute(builder.toString());
    }

    @Deprecated
    public int remove(String name, int userId) {
        return remove(null, name, userId);
    }

    public int remove(String uuid, String name, int userId) {
        StringBuilder builder = new StringBuilder("remove");
        builder.append(' ');
        builder.append(escapeNull(uuid));
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        builder.append(userId);
        return mInstaller.execute(builder.toString());
    }

    public int rename(String oldname, String newname) {
        StringBuilder builder = new StringBuilder("rename");
        builder.append(' ');
        builder.append(oldname);
        builder.append(' ');
        builder.append(newname);
        return mInstaller.execute(builder.toString());
    }

    @Deprecated
    public int fixUid(String name, int uid, int gid) {
        return fixUid(null, name, uid, gid);
    }

    public int fixUid(String uuid, String name, int uid, int gid) {
        StringBuilder builder = new StringBuilder("fixuid");
        builder.append(' ');
        builder.append(escapeNull(uuid));
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        builder.append(uid);
        builder.append(' ');
        builder.append(gid);
        return mInstaller.execute(builder.toString());
    }

    @Deprecated
    public int deleteCacheFiles(String name, int userId) {
        return deleteCacheFiles(null, name, userId);
    }

    public int deleteCacheFiles(String uuid, String name, int userId) {
        StringBuilder builder = new StringBuilder("rmcache");
        builder.append(' ');
        builder.append(escapeNull(uuid));
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        builder.append(userId);
        return mInstaller.execute(builder.toString());
    }

    @Deprecated
    public int deleteCodeCacheFiles(String name, int userId) {
        return deleteCodeCacheFiles(null, name, userId);
    }

    public int deleteCodeCacheFiles(String uuid, String name, int userId) {
        StringBuilder builder = new StringBuilder("rmcodecache");
        builder.append(' ');
        builder.append(escapeNull(uuid));
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        builder.append(userId);
        return mInstaller.execute(builder.toString());
    }

    @Deprecated
    public int createUserData(String name, int uid, int userId, String seinfo) {
        return createUserData(null, name, uid, userId, seinfo);
    }

    public int createUserData(String uuid, String name, int uid, int userId, String seinfo) {
        StringBuilder builder = new StringBuilder("mkuserdata");
        builder.append(' ');
        builder.append(escapeNull(uuid));
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        builder.append(uid);
        builder.append(' ');
        builder.append(userId);
        builder.append(' ');
        builder.append(seinfo != null ? seinfo : "!");
        return mInstaller.execute(builder.toString());
    }

    public int createUserConfig(int userId) {
        StringBuilder builder = new StringBuilder("mkuserconfig");
        builder.append(' ');
        builder.append(userId);
        return mInstaller.execute(builder.toString());
    }

    @Deprecated
    public int removeUserDataDirs(int userId) {
        return removeUserDataDirs(null, userId);
    }

    public int removeUserDataDirs(String uuid, int userId) {
        StringBuilder builder = new StringBuilder("rmuser");
        builder.append(' ');
        builder.append(escapeNull(uuid));
        builder.append(' ');
        builder.append(userId);
        return mInstaller.execute(builder.toString());
    }

    public int copyCompleteApp(String fromUuid, String toUuid, String packageName,
            String dataAppName, int appId, String seinfo) {
        StringBuilder builder = new StringBuilder("cpcompleteapp");
        builder.append(' ');
        builder.append(escapeNull(fromUuid));
        builder.append(' ');
        builder.append(escapeNull(toUuid));
        builder.append(' ');
        builder.append(packageName);
        builder.append(' ');
        builder.append(dataAppName);
        builder.append(' ');
        builder.append(appId);
        builder.append(' ');
        builder.append(seinfo);
        return mInstaller.execute(builder.toString());
    }

    @Deprecated
    public int clearUserData(String name, int userId) {
        return clearUserData(null, name, userId);
    }

    public int clearUserData(String uuid, String name, int userId) {
        StringBuilder builder = new StringBuilder("rmuserdata");
        builder.append(' ');
        builder.append(escapeNull(uuid));
        builder.append(' ');
        builder.append(name);
        builder.append(' ');
        builder.append(userId);
        return mInstaller.execute(builder.toString());
    }

    public int markBootComplete(String instructionSet) {
        if (!isValidInstructionSet(instructionSet)) {
            Slog.e(TAG, "Invalid instruction set: " + instructionSet);
            return -1;
        }

        StringBuilder builder = new StringBuilder("markbootcomplete");
        builder.append(' ');
        builder.append(instructionSet);
        return mInstaller.execute(builder.toString());
    }

    @Deprecated
    public int freeCache(long freeStorageSize) {
        return freeCache(null, freeStorageSize);
    }

    public int freeCache(String uuid, long freeStorageSize) {
        StringBuilder builder = new StringBuilder("freecache");
        builder.append(' ');
        builder.append(escapeNull(uuid));
        builder.append(' ');
        builder.append(String.valueOf(freeStorageSize));
        return mInstaller.execute(builder.toString());
    }

    @Deprecated
    public int getSizeInfo(String pkgName, int persona, String apkPath, String libDirPath,
            String fwdLockApkPath, String asecPath, String[] instructionSets, PackageStats pStats) {
        return getSizeInfo(null, pkgName, persona, apkPath, libDirPath, fwdLockApkPath, asecPath,
                instructionSets, pStats);
    }

    public int getSizeInfo(String uuid, String pkgName, int persona, String apkPath,
            String libDirPath, String fwdLockApkPath, String asecPath, String[] instructionSets,
            PackageStats pStats) {
        for (String instructionSet : instructionSets) {
            if (!isValidInstructionSet(instructionSet)) {
                Slog.e(TAG, "Invalid instruction set: " + instructionSet);
                return -1;
            }
        }

        StringBuilder builder = new StringBuilder("getsize");
        builder.append(' ');
        builder.append(escapeNull(uuid));
        builder.append(' ');
        builder.append(pkgName);
        builder.append(' ');
        builder.append(persona);
        builder.append(' ');
        builder.append(apkPath);
        builder.append(' ');
        // TODO: Extend getSizeInfo to look at the full subdirectory tree,
        // not just the first level.
        builder.append(libDirPath != null ? libDirPath : "!");
        builder.append(' ');
        builder.append(fwdLockApkPath != null ? fwdLockApkPath : "!");
        builder.append(' ');
        builder.append(asecPath != null ? asecPath : "!");
        builder.append(' ');
        // TODO: Extend getSizeInfo to look at *all* instrution sets, not
        // just the primary.
        builder.append(instructionSets[0]);

        String s = mInstaller.transact(builder.toString());
        String res[] = s.split(" ");

        if ((res == null) || (res.length != 5)) {
            return -1;
        }
        try {
            pStats.codeSize = Long.parseLong(res[1]);
            pStats.dataSize = Long.parseLong(res[2]);
            pStats.cacheSize = Long.parseLong(res[3]);
            pStats.externalCodeSize = Long.parseLong(res[4]);
            return Integer.parseInt(res[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public int moveFiles() {
        return mInstaller.execute("movefiles");
    }

    @Deprecated
    public int linkNativeLibraryDirectory(String dataPath, String nativeLibPath32, int userId) {
        return linkNativeLibraryDirectory(null, dataPath, nativeLibPath32, userId);
    }

    /**
     * Links the 32 bit native library directory in an application's data directory to the
     * real location for backward compatibility. Note that no such symlink is created for
     * 64 bit shared libraries.
     *
     * @return -1 on error
     */
    public int linkNativeLibraryDirectory(String uuid, String dataPath, String nativeLibPath32,
            int userId) {
        if (dataPath == null) {
            Slog.e(TAG, "linkNativeLibraryDirectory dataPath is null");
            return -1;
        } else if (nativeLibPath32 == null) {
            Slog.e(TAG, "linkNativeLibraryDirectory nativeLibPath is null");
            return -1;
        }

        StringBuilder builder = new StringBuilder("linklib");
        builder.append(' ');
        builder.append(escapeNull(uuid));
        builder.append(' ');
        builder.append(dataPath);
        builder.append(' ');
        builder.append(nativeLibPath32);
        builder.append(' ');
        builder.append(userId);

        return mInstaller.execute(builder.toString());
    }

    @Deprecated
    public boolean restoreconData(String pkgName, String seinfo, int uid) {
        return restoreconData(null, pkgName, seinfo, uid);
    }

    public boolean restoreconData(String uuid, String pkgName, String seinfo, int uid) {
        StringBuilder builder = new StringBuilder("restorecondata");
        builder.append(' ');
        builder.append(escapeNull(uuid));
        builder.append(' ');
        builder.append(pkgName);
        builder.append(' ');
        builder.append(seinfo != null ? seinfo : "!");
        builder.append(' ');
        builder.append(uid);
        return (mInstaller.execute(builder.toString()) == 0);
    }

    public int createOatDir(String oatDir, String dexInstructionSet) {
        StringBuilder builder = new StringBuilder("createoatdir");
        builder.append(' ');
        builder.append(oatDir);
        builder.append(' ');
        builder.append(dexInstructionSet);
        return mInstaller.execute(builder.toString());
    }


    public int linkFile(String relativePath, String fromBase, String toBase) {
        StringBuilder builder = new StringBuilder("linkfile");
        builder.append(' ');
        builder.append(relativePath);
        builder.append(' ');
        builder.append(fromBase);
        builder.append(' ');
        builder.append(toBase);
        return mInstaller.execute(builder.toString());
    }

    /**
     * Returns true iff. {@code instructionSet} is a valid instruction set.
     */
    private static boolean isValidInstructionSet(String instructionSet) {
        if (instructionSet == null) {
            return false;
        }

        for (String abi : Build.SUPPORTED_ABIS) {
            if (instructionSet.equals(VMRuntime.getInstructionSet(abi))) {
                return true;
            }
        }

        return false;
    }
}
