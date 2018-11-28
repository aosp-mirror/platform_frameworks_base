/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.internal.content;

import static android.os.storage.VolumeInfo.ID_PRIVATE_INTERNAL;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller.SessionParams;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageParser.PackageLite;
import android.content.pm.dex.DexMetadataHelper;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.storage.IStorageManager;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.os.storage.VolumeInfo;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * Constants used internally between the PackageManager
 * and media container service transports.
 * Some utility methods to invoke StorageManagerService api.
 */
public class PackageHelper {
    public static final int RECOMMEND_INSTALL_INTERNAL = 1;
    public static final int RECOMMEND_INSTALL_EXTERNAL = 2;
    public static final int RECOMMEND_INSTALL_EPHEMERAL = 3;
    public static final int RECOMMEND_FAILED_INSUFFICIENT_STORAGE = -1;
    public static final int RECOMMEND_FAILED_INVALID_APK = -2;
    public static final int RECOMMEND_FAILED_INVALID_LOCATION = -3;
    public static final int RECOMMEND_FAILED_ALREADY_EXISTS = -4;
    public static final int RECOMMEND_MEDIA_UNAVAILABLE = -5;
    public static final int RECOMMEND_FAILED_INVALID_URI = -6;
    public static final int RECOMMEND_FAILED_VERSION_DOWNGRADE = -7;

    private static final String TAG = "PackageHelper";
    // App installation location settings values
    public static final int APP_INSTALL_AUTO = 0;
    public static final int APP_INSTALL_INTERNAL = 1;
    public static final int APP_INSTALL_EXTERNAL = 2;

    private static TestableInterface sDefaultTestableInterface = null;

    public static IStorageManager getStorageManager() throws RemoteException {
        IBinder service = ServiceManager.getService("mount");
        if (service != null) {
            return IStorageManager.Stub.asInterface(service);
        } else {
            Log.e(TAG, "Can't get storagemanager service");
            throw new RemoteException("Could not contact storagemanager service");
        }
    }

    /**
     * A group of external dependencies used in
     * {@link #resolveInstallVolume(Context, String, int, long)}. It can be backed by real values
     * from the system or mocked ones for testing purposes.
     */
    public static abstract class TestableInterface {
        abstract public StorageManager getStorageManager(Context context);
        abstract public boolean getForceAllowOnExternalSetting(Context context);
        abstract public boolean getAllow3rdPartyOnInternalConfig(Context context);
        abstract public ApplicationInfo getExistingAppInfo(Context context, String packageName);
        abstract public File getDataDirectory();
    }

    private synchronized static TestableInterface getDefaultTestableInterface() {
        if (sDefaultTestableInterface == null) {
            sDefaultTestableInterface = new TestableInterface() {
                @Override
                public StorageManager getStorageManager(Context context) {
                    return context.getSystemService(StorageManager.class);
                }

                @Override
                public boolean getForceAllowOnExternalSetting(Context context) {
                    return Settings.Global.getInt(context.getContentResolver(),
                            Settings.Global.FORCE_ALLOW_ON_EXTERNAL, 0) != 0;
                }

                @Override
                public boolean getAllow3rdPartyOnInternalConfig(Context context) {
                    return context.getResources().getBoolean(
                            com.android.internal.R.bool.config_allow3rdPartyAppOnInternal);
                }

                @Override
                public ApplicationInfo getExistingAppInfo(Context context, String packageName) {
                    ApplicationInfo existingInfo = null;
                    try {
                        existingInfo = context.getPackageManager().getApplicationInfo(packageName,
                                PackageManager.MATCH_ANY_USER);
                    } catch (NameNotFoundException ignored) {
                    }
                    return existingInfo;
                }

                @Override
                public File getDataDirectory() {
                    return Environment.getDataDirectory();
                }
            };
        }
        return sDefaultTestableInterface;
    }

    @VisibleForTesting
    @Deprecated
    public static String resolveInstallVolume(Context context, String packageName,
            int installLocation, long sizeBytes, TestableInterface testInterface)
            throws IOException {
        final SessionParams params = new SessionParams(SessionParams.MODE_INVALID);
        params.appPackageName = packageName;
        params.installLocation = installLocation;
        params.sizeBytes = sizeBytes;
        return resolveInstallVolume(context, params, testInterface);
    }

    /**
     * Given a requested {@link PackageInfo#installLocation} and calculated
     * install size, pick the actual volume to install the app. Only considers
     * internal and private volumes, and prefers to keep an existing package on
     * its current volume.
     *
     * @return the {@link VolumeInfo#fsUuid} to install onto, or {@code null}
     *         for internal storage.
     */
    public static String resolveInstallVolume(Context context, SessionParams params)
            throws IOException {
        TestableInterface testableInterface = getDefaultTestableInterface();
        return resolveInstallVolume(context, params.appPackageName, params.installLocation,
                params.sizeBytes, testableInterface);
    }

    @VisibleForTesting
    public static String resolveInstallVolume(Context context, SessionParams params,
            TestableInterface testInterface) throws IOException {
        final StorageManager storageManager = testInterface.getStorageManager(context);
        final boolean forceAllowOnExternal = testInterface.getForceAllowOnExternalSetting(context);
        final boolean allow3rdPartyOnInternal =
                testInterface.getAllow3rdPartyOnInternalConfig(context);
        // TODO: handle existing apps installed in ASEC; currently assumes
        // they'll end up back on internal storage
        ApplicationInfo existingInfo = testInterface.getExistingAppInfo(context,
                params.appPackageName);

        // Figure out best candidate volume, and also if we fit on internal
        final ArraySet<String> allCandidates = new ArraySet<>();
        boolean fitsOnInternal = false;
        VolumeInfo bestCandidate = null;
        long bestCandidateAvailBytes = Long.MIN_VALUE;
        for (VolumeInfo vol : storageManager.getVolumes()) {
            if (vol.type == VolumeInfo.TYPE_PRIVATE && vol.isMountedWritable()) {
                final boolean isInternalStorage = ID_PRIVATE_INTERNAL.equals(vol.id);
                final UUID target = storageManager.getUuidForPath(new File(vol.path));
                final long availBytes = storageManager.getAllocatableBytes(target,
                        translateAllocateFlags(params.installFlags));
                if (isInternalStorage) {
                    fitsOnInternal = (params.sizeBytes <= availBytes);
                }
                if (!isInternalStorage || allow3rdPartyOnInternal) {
                    if (availBytes >= params.sizeBytes) {
                        allCandidates.add(vol.fsUuid);
                    }
                    if (availBytes >= bestCandidateAvailBytes) {
                        bestCandidate = vol;
                        bestCandidateAvailBytes = availBytes;
                    }
                }
            }
        }

        // System apps always forced to internal storage
        if (existingInfo != null && existingInfo.isSystemApp()) {
            if (fitsOnInternal) {
                return StorageManager.UUID_PRIVATE_INTERNAL;
            } else {
                throw new IOException("Not enough space on existing volume "
                        + existingInfo.volumeUuid + " for system app " + params.appPackageName
                        + " upgrade");
            }
        }

        // If app expresses strong desire for internal storage, honor it
        if (!forceAllowOnExternal
                && params.installLocation == PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY) {
            if (existingInfo != null && !Objects.equals(existingInfo.volumeUuid,
                    StorageManager.UUID_PRIVATE_INTERNAL)) {
                throw new IOException("Cannot automatically move " + params.appPackageName
                        + " from " + existingInfo.volumeUuid + " to internal storage");
            }

            if (!allow3rdPartyOnInternal) {
                throw new IOException("Not allowed to install non-system apps on internal storage");
            }

            if (fitsOnInternal) {
                return StorageManager.UUID_PRIVATE_INTERNAL;
            } else {
                throw new IOException("Requested internal only, but not enough space");
            }
        }

        // If app already exists somewhere, we must stay on that volume
        if (existingInfo != null) {
            if (Objects.equals(existingInfo.volumeUuid, StorageManager.UUID_PRIVATE_INTERNAL)
                    && fitsOnInternal) {
                return StorageManager.UUID_PRIVATE_INTERNAL;
            } else if (allCandidates.contains(existingInfo.volumeUuid)) {
                return existingInfo.volumeUuid;
            } else {
                throw new IOException("Not enough space on existing volume "
                        + existingInfo.volumeUuid + " for " + params.appPackageName + " upgrade");
            }
        }

        // We're left with new installations with either preferring external or auto, so just pick
        // volume with most space
        if (bestCandidate != null) {
            return bestCandidate.fsUuid;
        } else {
            throw new IOException("No special requests, but no room on allowed volumes. "
                + " allow3rdPartyOnInternal? " + allow3rdPartyOnInternal);
        }
    }

    public static boolean fitsOnInternal(Context context, SessionParams params) throws IOException {
        final StorageManager storage = context.getSystemService(StorageManager.class);
        final UUID target = storage.getUuidForPath(Environment.getDataDirectory());
        return (params.sizeBytes <= storage.getAllocatableBytes(target,
                translateAllocateFlags(params.installFlags)));
    }

    public static boolean fitsOnExternal(Context context, SessionParams params) {
        final StorageManager storage = context.getSystemService(StorageManager.class);
        final StorageVolume primary = storage.getPrimaryVolume();
        return (params.sizeBytes > 0) && !primary.isEmulated()
                && Environment.MEDIA_MOUNTED.equals(primary.getState())
                && params.sizeBytes <= storage.getStorageBytesUntilLow(primary.getPathFile());
    }

    @Deprecated
    public static int resolveInstallLocation(Context context, String packageName,
            int installLocation, long sizeBytes, int installFlags) {
        final SessionParams params = new SessionParams(SessionParams.MODE_INVALID);
        params.appPackageName = packageName;
        params.installLocation = installLocation;
        params.sizeBytes = sizeBytes;
        params.installFlags = installFlags;
        try {
            return resolveInstallLocation(context, params);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Given a requested {@link PackageInfo#installLocation} and calculated
     * install size, pick the actual location to install the app.
     */
    public static int resolveInstallLocation(Context context, SessionParams params)
            throws IOException {
        ApplicationInfo existingInfo = null;
        try {
            existingInfo = context.getPackageManager().getApplicationInfo(params.appPackageName,
                    PackageManager.MATCH_ANY_USER);
        } catch (NameNotFoundException ignored) {
        }

        final int prefer;
        final boolean checkBoth;
        boolean ephemeral = false;
        if ((params.installFlags & PackageManager.INSTALL_INSTANT_APP) != 0) {
            prefer = RECOMMEND_INSTALL_INTERNAL;
            ephemeral = true;
            checkBoth = false;
        } else if ((params.installFlags & PackageManager.INSTALL_INTERNAL) != 0) {
            prefer = RECOMMEND_INSTALL_INTERNAL;
            checkBoth = false;
        } else if (params.installLocation == PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY) {
            prefer = RECOMMEND_INSTALL_INTERNAL;
            checkBoth = false;
        } else if (params.installLocation == PackageInfo.INSTALL_LOCATION_PREFER_EXTERNAL) {
            prefer = RECOMMEND_INSTALL_EXTERNAL;
            checkBoth = true;
        } else if (params.installLocation == PackageInfo.INSTALL_LOCATION_AUTO) {
            // When app is already installed, prefer same medium
            if (existingInfo != null) {
                // TODO: distinguish if this is external ASEC
                if ((existingInfo.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0) {
                    prefer = RECOMMEND_INSTALL_EXTERNAL;
                } else {
                    prefer = RECOMMEND_INSTALL_INTERNAL;
                }
            } else {
                prefer = RECOMMEND_INSTALL_INTERNAL;
            }
            checkBoth = true;
        } else {
            prefer = RECOMMEND_INSTALL_INTERNAL;
            checkBoth = false;
        }

        boolean fitsOnInternal = false;
        if (checkBoth || prefer == RECOMMEND_INSTALL_INTERNAL) {
            fitsOnInternal = fitsOnInternal(context, params);
        }

        boolean fitsOnExternal = false;
        if (checkBoth || prefer == RECOMMEND_INSTALL_EXTERNAL) {
            fitsOnExternal = fitsOnExternal(context, params);
        }

        if (prefer == RECOMMEND_INSTALL_INTERNAL) {
            // The ephemeral case will either fit and return EPHEMERAL, or will not fit
            // and will fall through to return INSUFFICIENT_STORAGE
            if (fitsOnInternal) {
                return (ephemeral)
                        ? PackageHelper.RECOMMEND_INSTALL_EPHEMERAL
                        : PackageHelper.RECOMMEND_INSTALL_INTERNAL;
            }
        } else if (prefer == RECOMMEND_INSTALL_EXTERNAL) {
            if (fitsOnExternal) {
                return PackageHelper.RECOMMEND_INSTALL_EXTERNAL;
            }
        }

        if (checkBoth) {
            if (fitsOnInternal) {
                return PackageHelper.RECOMMEND_INSTALL_INTERNAL;
            } else if (fitsOnExternal) {
                return PackageHelper.RECOMMEND_INSTALL_EXTERNAL;
            }
        }

        return PackageHelper.RECOMMEND_FAILED_INSUFFICIENT_STORAGE;
    }

    @Deprecated
    public static long calculateInstalledSize(PackageLite pkg, boolean isForwardLocked,
            String abiOverride) throws IOException {
        return calculateInstalledSize(pkg, abiOverride);
    }

    public static long calculateInstalledSize(PackageLite pkg, String abiOverride)
            throws IOException {
        return calculateInstalledSize(pkg, abiOverride, null);
    }

    public static long calculateInstalledSize(PackageLite pkg, String abiOverride,
            FileDescriptor fd) throws IOException {
        NativeLibraryHelper.Handle handle = null;
        try {
            handle = fd != null ? NativeLibraryHelper.Handle.createFd(pkg, fd)
                    : NativeLibraryHelper.Handle.create(pkg);
            return calculateInstalledSize(pkg, handle, abiOverride);
        } finally {
            IoUtils.closeQuietly(handle);
        }
    }

    @Deprecated
    public static long calculateInstalledSize(PackageLite pkg, boolean isForwardLocked,
            NativeLibraryHelper.Handle handle, String abiOverride) throws IOException {
        return calculateInstalledSize(pkg, handle, abiOverride);
    }

    public static long calculateInstalledSize(PackageLite pkg, NativeLibraryHelper.Handle handle,
            String abiOverride) throws IOException {
        long sizeBytes = 0;

        // Include raw APKs, and possibly unpacked resources
        for (String codePath : pkg.getAllCodePaths()) {
            final File codeFile = new File(codePath);
            sizeBytes += codeFile.length();
        }

        // Include raw dex metadata files
        sizeBytes += DexMetadataHelper.getPackageDexMetadataSize(pkg);

        // Include all relevant native code
        sizeBytes += NativeLibraryHelper.sumNativeBinariesWithOverride(handle, abiOverride);

        return sizeBytes;
    }

    public static String replaceEnd(String str, String before, String after) {
        if (!str.endsWith(before)) {
            throw new IllegalArgumentException(
                    "Expected " + str + " to end with " + before);
        }
        return str.substring(0, str.length() - before.length()) + after;
    }

    public static int translateAllocateFlags(int installFlags) {
        if ((installFlags & PackageManager.INSTALL_ALLOCATE_AGGRESSIVE) != 0) {
            return StorageManager.FLAG_ALLOCATE_AGGRESSIVE;
        } else {
            return 0;
        }
    }
}
