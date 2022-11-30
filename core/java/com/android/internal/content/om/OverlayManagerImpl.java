/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.content.om;

import static android.content.Context.MODE_PRIVATE;
import static android.content.om.OverlayManagerTransaction.Request.BUNDLE_FABRICATED_OVERLAY;
import static android.content.om.OverlayManagerTransaction.Request.TYPE_REGISTER_FABRICATED;
import static android.content.om.OverlayManagerTransaction.Request.TYPE_UNREGISTER_FABRICATED;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;
import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;
import static com.android.internal.content.om.OverlayConfig.DEFAULT_PRIORITY;

import android.annotation.NonNull;
import android.annotation.NonUiContext;
import android.content.Context;
import android.content.om.OverlayIdentifier;
import android.content.om.OverlayInfo;
import android.content.om.OverlayManagerTransaction;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.parsing.FrameworkParsingPackageUtils;
import android.os.FabricatedOverlayInfo;
import android.os.FabricatedOverlayInternal;
import android.os.FabricatedOverlayInternalEntry;
import android.os.FileUtils;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

/**
 * This class provides the functionalities of registering an overlay, unregistering an overlay, and
 * getting the list of overlays information.
 */
public class OverlayManagerImpl {
    private static final String TAG = "OverlayManagerImpl";
    private static final boolean DEBUG = false;

    private static final String FRRO_EXTENSION = ".frro";

    private static final String IDMAP_EXTENSION = ".idmap";

    @VisibleForTesting(visibility = PRIVATE)
    public static final String SELF_TARGET = ".self_target";

    @NonNull
    private final Context mContext;
    private Path mBasePath;

    /**
     * Init a OverlayManagerImpl by the context.
     *
     * @param context the context to create overlay environment
     */
    @VisibleForTesting(visibility = PACKAGE)
    public OverlayManagerImpl(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);

        if (!Process.myUserHandle().equals(context.getUser())) {
            throw new SecurityException("Self-Targeting doesn't support multiple user now!");
        }
    }

    private static void cleanExpiredOverlays(Path selfTargetingBasePath,
            Path folderForCurrentBaseApk) {
        try {
            final String currentBaseFolder = folderForCurrentBaseApk.toString();
            final String selfTargetingDir = selfTargetingBasePath.getFileName().toString();
            Files.walkFileTree(
                    selfTargetingBasePath,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir,
                                                                 BasicFileAttributes attrs)
                                throws IOException {
                            final String fileName = dir.getFileName().toString();
                            return fileName.equals(currentBaseFolder)
                                    ? FileVisitResult.SKIP_SUBTREE
                                    : super.preVisitDirectory(dir, attrs);
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                            if (!file.toFile().delete()) {
                                Log.w(TAG, "Failed to delete file " + file);
                            }
                            return super.visitFile(file, attrs);
                        }

                        @Override
                        public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                                throws IOException {
                            final String fileName = dir.getFileName().toString();
                            if (!fileName.equals(currentBaseFolder)
                                    && !fileName.equals(selfTargetingDir)) {
                                if (!dir.toFile().delete()) {
                                    Log.w(TAG, "Failed to delete dir " + dir);
                                }
                            }
                            return super.postVisitDirectory(dir, exc);
                        }
                    });
        } catch (IOException e) {
            Log.w(TAG, "Unknown fail " + e);
        }
    }

    /** Ensure the base dir for self-targeting is valid. */
    @VisibleForTesting
    @NonUiContext
    public void ensureBaseDir() {
        final String baseApkPath = mContext.getApplicationInfo().getBaseCodePath();
        final Path baseApkFolderName = Path.of(baseApkPath).getParent().getFileName();
        final File selfTargetingBaseFile = mContext.getDir(SELF_TARGET, MODE_PRIVATE);
        Preconditions.checkArgument(
                selfTargetingBaseFile.isDirectory()
                        && selfTargetingBaseFile.exists()
                        && selfTargetingBaseFile.canWrite()
                        && selfTargetingBaseFile.canRead()
                        && selfTargetingBaseFile.canExecute(),
                "Can't work for this context");
        cleanExpiredOverlays(selfTargetingBaseFile.toPath(), baseApkFolderName);

        final File baseFile = new File(selfTargetingBaseFile, baseApkFolderName.toString());
        if (!baseFile.exists()) {
            if (!baseFile.mkdirs()) {
                Log.w(TAG, "Failed to create directory " + baseFile);
            }

            // It fails to create frro and idmap files without this setting.
            FileUtils.setPermissions(
                    baseFile,
                    FileUtils.S_IRWXU,
                    -1 /* uid unchanged */,
                    -1 /* gid unchanged */);
        }
        Preconditions.checkArgument(
                baseFile.isDirectory()
                        && baseFile.exists()
                        && baseFile.canWrite()
                        && baseFile.canRead()
                        && baseFile.canExecute(), // 'list' capability
                "Can't create a workspace for this context");

        mBasePath = baseFile.toPath();
    }

    private boolean isSameWithTargetSignature(final String targetPackage) {
        final PackageManager packageManager = mContext.getPackageManager();
        final String packageName = mContext.getPackageName();
        if (TextUtils.equals(packageName, targetPackage)) {
            return true;
        }
        return packageManager.checkSignatures(packageName, targetPackage)
                == PackageManager.SIGNATURE_MATCH;
    }

    /**
     * Check if the overlay name is valid or not.
     *
     * @param name the non-check overlay name
     * @return the valid overlay name
     */
    public static String checkOverlayNameValid(@NonNull String name) {
        final String overlayName =
                Preconditions.checkStringNotEmpty(
                        name, "overlayName should be neither empty nor null string");
        final String checkOverlayNameResult =
                FrameworkParsingPackageUtils.validateName(
                        overlayName, false /* requireSeparator */, true /* requireFilename */);
        Preconditions.checkArgument(
                checkOverlayNameResult == null,
                TextUtils.formatSimple(
                        "Invalid overlayName \"%s\". The check result is %s.",
                        overlayName, checkOverlayNameResult));
        return overlayName;
    }

    private void checkPackageName(@NonNull String packageName) {
        Preconditions.checkStringNotEmpty(packageName);
        Preconditions.checkArgument(
                TextUtils.equals(mContext.getPackageName(), packageName),
                TextUtils.formatSimple(
                        "UID %d doesn't own the package %s", Process.myUid(), packageName));
    }

    /**
     * Save FabricatedOverlay instance as frro and idmap files.
     *
     * <p>In order to fill the overlayable policy, it's necessary to collect the information from
     * app. And then, the information is passed to native layer to fill the overlayable policy
     *
     * @param overlayInternal the FabricatedOverlayInternal to be saved.
     */
    @NonUiContext
    public void registerFabricatedOverlay(@NonNull FabricatedOverlayInternal overlayInternal)
            throws IOException, PackageManager.NameNotFoundException {
        ensureBaseDir();
        Objects.requireNonNull(overlayInternal);
        final List<FabricatedOverlayInternalEntry> entryList =
                Objects.requireNonNull(overlayInternal.entries);
        Preconditions.checkArgument(!entryList.isEmpty(), "overlay entries shouldn't be empty");
        final String overlayName = checkOverlayNameValid(overlayInternal.overlayName);
        checkPackageName(overlayInternal.packageName);
        checkPackageName(overlayInternal.targetPackageName);
        Preconditions.checkStringNotEmpty(
                overlayInternal.targetOverlayable,
                "Target overlayable should be neither null nor empty string.");

        final ApplicationInfo applicationInfo = mContext.getApplicationInfo();
        final String targetPackage = Preconditions.checkStringNotEmpty(
                applicationInfo.getBaseCodePath());
        final Path frroPath = mBasePath.resolve(overlayName + FRRO_EXTENSION);
        final Path idmapPath = mBasePath.resolve(overlayName + IDMAP_EXTENSION);

        createFrroFile(frroPath.toString(), overlayInternal);
        try {
            createIdmapFile(
                    targetPackage,
                    frroPath.toString(),
                    idmapPath.toString(),
                    overlayName,
                    applicationInfo.isSystemApp() || applicationInfo.isSystemExt() /* isSystem */,
                    applicationInfo.isVendor(),
                    applicationInfo.isProduct(),
                    isSameWithTargetSignature(overlayInternal.targetPackageName),
                    applicationInfo.isOdm(),
                    applicationInfo.isOem());
        } catch (IOException e) {
            if (!frroPath.toFile().delete()) {
                Log.w(TAG, "Failed to delete file " + frroPath);
            }
            throw e;
        }
    }

    /**
     * Remove the overlay with the specific name
     *
     * @param overlayName the specific name
     */
    @NonUiContext
    public void unregisterFabricatedOverlay(@NonNull String overlayName) {
        ensureBaseDir();
        checkOverlayNameValid(overlayName);
        final Path frroPath = mBasePath.resolve(overlayName + FRRO_EXTENSION);
        final Path idmapPath = mBasePath.resolve(overlayName + IDMAP_EXTENSION);

        if (!frroPath.toFile().delete()) {
            Log.w(TAG, "Failed to delete file " + frroPath);
        }
        if (!idmapPath.toFile().delete()) {
            Log.w(TAG, "Failed to delete file " + idmapPath);
        }
    }

    /**
     * Commit the overlay manager transaction
     *
     * @param transaction the overlay manager transaction
     */
    @NonUiContext
    public void commit(@NonNull OverlayManagerTransaction transaction)
            throws PackageManager.NameNotFoundException, IOException {
        Objects.requireNonNull(transaction);

        for (Iterator<OverlayManagerTransaction.Request> it = transaction.iterator();
                it.hasNext(); ) {
            final OverlayManagerTransaction.Request request = it.next();
            if (request.type == TYPE_REGISTER_FABRICATED) {
                final FabricatedOverlayInternal fabricatedOverlayInternal =
                        Objects.requireNonNull(
                                request.extras.getParcelable(
                                        BUNDLE_FABRICATED_OVERLAY,
                                        FabricatedOverlayInternal.class));

                // populate the mandatory data
                if (TextUtils.isEmpty(fabricatedOverlayInternal.packageName)) {
                    fabricatedOverlayInternal.packageName = mContext.getPackageName();
                } else {
                    if (!TextUtils.equals(
                            fabricatedOverlayInternal.packageName, mContext.getPackageName())) {
                        throw new IllegalArgumentException("Unknown package name in transaction");
                    }
                }

                registerFabricatedOverlay(fabricatedOverlayInternal);
            } else if (request.type == TYPE_UNREGISTER_FABRICATED) {
                final OverlayIdentifier overlayIdentifier = Objects.requireNonNull(request.overlay);
                unregisterFabricatedOverlay(overlayIdentifier.getOverlayName());
            } else {
                throw new IllegalArgumentException("Unknown request in transaction " + request);
            }
        }
    }

    /**
     * Get the list of overlays information for the target package name.
     *
     * @param targetPackage the target package name
     * @return the list of overlays information.
     */
    @NonNull
    public List<OverlayInfo> getOverlayInfosForTarget(@NonNull String targetPackage) {
        ensureBaseDir();

        final File base = mBasePath.toFile();
        final File[] frroFiles = base.listFiles((dir, name) -> {
            if (!name.endsWith(FRRO_EXTENSION)) {
                return false;
            }

            final String idmapFileName = name.substring(0, name.length() - FRRO_EXTENSION.length())
                    + IDMAP_EXTENSION;
            final File idmapFile = new File(dir, idmapFileName);
            return idmapFile.exists();
        });

        final ArrayList<OverlayInfo> overlayInfos = new ArrayList<>();
        for (File file : frroFiles) {
            final FabricatedOverlayInfo fabricatedOverlayInfo;
            try {
                fabricatedOverlayInfo = getFabricatedOverlayInfo(file.getAbsolutePath());
            } catch (IOException e) {
                Log.w(TAG, "can't load " + file);
                continue;
            }
            if (!TextUtils.equals(targetPackage, fabricatedOverlayInfo.targetPackageName)) {
                continue;
            }
            if (DEBUG) {
                Log.i(TAG, "load " + file);
            }

            final OverlayInfo overlayInfo =
                    new OverlayInfo(
                            fabricatedOverlayInfo.packageName,
                            fabricatedOverlayInfo.overlayName,
                            fabricatedOverlayInfo.targetPackageName,
                            fabricatedOverlayInfo.targetOverlayable,
                            null,
                            file.getAbsolutePath(),
                            OverlayInfo.STATE_ENABLED,
                            UserHandle.myUserId(),
                            DEFAULT_PRIORITY,
                            true /* isMutable */,
                            true /* isFabricated */);
            overlayInfos.add(overlayInfo);
        }
        return overlayInfos;
    }

    private static native void createFrroFile(
            @NonNull String frroFile, @NonNull FabricatedOverlayInternal fabricatedOverlayInternal)
            throws IOException;

    private static native void createIdmapFile(
            @NonNull String targetPath,
            @NonNull String overlayPath,
            @NonNull String idmapPath,
            @NonNull String overlayName,
            boolean isSystem,
            boolean isVendor,
            boolean isProduct,
            boolean isSameWithTargetSignature,
            boolean isOdm,
            boolean isOem)
            throws IOException;

    private static native FabricatedOverlayInfo getFabricatedOverlayInfo(
            @NonNull String overlayPath) throws IOException;
}
