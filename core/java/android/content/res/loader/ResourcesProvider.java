/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.content.res.loader;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.ApkAssets;
import android.content.res.AssetFileDescriptor;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;

/**
 * Provides methods to load resources data from APKs ({@code .apk}) and resources tables
 * (eg. {@code resources.arsc}) for use with {@link ResourcesLoader ResourcesLoader(s)}.
 */
public class ResourcesProvider implements AutoCloseable, Closeable {
    private static final String TAG = "ResourcesProvider";
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private boolean mOpen = true;

    @GuardedBy("mLock")
    private int mOpenCount = 0;

    @GuardedBy("mLock")
    private final ApkAssets mApkAssets;

    /**
     * Creates an empty ResourcesProvider with no resource data. This is useful for loading
     * file-based assets not associated with resource identifiers.
     *
     * @param assetsProvider the assets provider that implements the loading of file-based resources
     */
    @NonNull
    public static ResourcesProvider empty(@NonNull AssetsProvider assetsProvider) {
        return new ResourcesProvider(ApkAssets.loadEmptyForLoader(ApkAssets.PROPERTY_LOADER,
                assetsProvider));
    }

    /**
     * Creates a ResourcesProvider from an APK ({@code .apk}) file descriptor.
     *
     * <p>The file descriptor is duplicated and the original may be closed by the application at any
     * time without affecting the ResourcesProvider.
     *
     * @param fileDescriptor the file descriptor of the APK to load
     *
     * @see ParcelFileDescriptor#open(File, int)
     * @see android.system.Os#memfd_create(String, int)
     */
    @NonNull
    public static ResourcesProvider loadFromApk(@NonNull ParcelFileDescriptor fileDescriptor)
            throws IOException {
        return loadFromApk(fileDescriptor, null /* assetsProvider */);
    }

    /**
     * Creates a ResourcesProvider from an APK ({@code .apk}) file descriptor.
     *
     * <p>The file descriptor is duplicated and the original may be closed by the application at any
     * time without affecting the ResourcesProvider.
     *
     * <p>The assets provider can override the loading of files within the APK and can provide
     * entirely new files that do not exist in the APK.
     *
     * @param fileDescriptor the file descriptor of the APK to load
     * @param assetsProvider the assets provider that overrides the loading of file-based resources
     *
     * @see ParcelFileDescriptor#open(File, int)
     * @see android.system.Os#memfd_create(String, int)
     */
    @NonNull
    public static ResourcesProvider loadFromApk(@NonNull ParcelFileDescriptor fileDescriptor,
            @Nullable AssetsProvider assetsProvider)
            throws IOException {
        return new ResourcesProvider(ApkAssets.loadFromFd(fileDescriptor.getFileDescriptor(),
                fileDescriptor.toString(), ApkAssets.PROPERTY_LOADER, assetsProvider));
    }

    /**
     * Creates a ResourcesProvider from an APK ({@code .apk}) file descriptor.
     *
     * <p>The file descriptor is duplicated and the original may be closed by the application at any
     * time without affecting the ResourcesProvider.
     *
     * <p>The assets provider can override the loading of files within the APK and can provide
     * entirely new files that do not exist in the APK.
     *
     * @param fileDescriptor the file descriptor of the APK to load
     * @param offset The location within the file that the apk starts. This must be 0 if length is
     *               {@link AssetFileDescriptor#UNKNOWN_LENGTH}.
     * @param length The number of bytes of the apk, or {@link AssetFileDescriptor#UNKNOWN_LENGTH}
     *               if it extends to the end of the file.
     * @param assetsProvider the assets provider that overrides the loading of file-based resources
     *
     * @see ParcelFileDescriptor#open(File, int)
     * @see android.system.Os#memfd_create(String, int)
     * @hide
     */
    @VisibleForTesting
    @NonNull
    public static ResourcesProvider loadFromApk(@NonNull ParcelFileDescriptor fileDescriptor,
            long offset, long length, @Nullable AssetsProvider assetsProvider)
            throws IOException {
        return new ResourcesProvider(ApkAssets.loadFromFd(fileDescriptor.getFileDescriptor(),
                fileDescriptor.toString(), offset, length, ApkAssets.PROPERTY_LOADER,
                assetsProvider));
    }

    /**
     * Creates a ResourcesProvider from a resources table ({@code .arsc}) file descriptor.
     *
     * <p>The file descriptor is duplicated and the original may be closed by the application at any
     * time without affecting the ResourcesProvider.
     *
     * <p>The resources table format is not an archive format and therefore cannot asset files
     * within itself. The assets provider can instead provide files that are potentially referenced
     * by path in the resources table.
     *
     * @param fileDescriptor the file descriptor of the resources table to load
     * @param assetsProvider the assets provider that implements the loading of file-based resources
     *
     * @see ParcelFileDescriptor#open(File, int)
     * @see android.system.Os#memfd_create(String, int)
     */
    @NonNull
    public static ResourcesProvider loadFromTable(@NonNull ParcelFileDescriptor fileDescriptor,
            @Nullable AssetsProvider assetsProvider)
            throws IOException {
        return new ResourcesProvider(
                ApkAssets.loadTableFromFd(fileDescriptor.getFileDescriptor(),
                        fileDescriptor.toString(), ApkAssets.PROPERTY_LOADER, assetsProvider));
    }

    /**
     * Creates a ResourcesProvider from a resources table ({@code .arsc}) file descriptor.
     *
     * The file descriptor is duplicated and the original may be closed by the application at any
     * time without affecting the ResourcesProvider.
     *
     * <p>The resources table format is not an archive format and therefore cannot asset files
     * within itself. The assets provider can instead provide files that are potentially referenced
     * by path in the resources table.
     *
     * @param fileDescriptor the file descriptor of the resources table to load
     * @param offset The location within the file that the table starts. This must be 0 if length is
     *               {@link AssetFileDescriptor#UNKNOWN_LENGTH}.
     * @param length The number of bytes of the table, or {@link AssetFileDescriptor#UNKNOWN_LENGTH}
     *               if it extends to the end of the file.
     * @param assetsProvider the assets provider that overrides the loading of file-based resources
     *
     * @see ParcelFileDescriptor#open(File, int)
     * @see android.system.Os#memfd_create(String, int)
     * @hide
     */
    @VisibleForTesting
    @NonNull
    public static ResourcesProvider loadFromTable(@NonNull ParcelFileDescriptor fileDescriptor,
            long offset, long length, @Nullable AssetsProvider assetsProvider)
            throws IOException {
        return new ResourcesProvider(
                ApkAssets.loadTableFromFd(fileDescriptor.getFileDescriptor(),
                        fileDescriptor.toString(), offset, length, ApkAssets.PROPERTY_LOADER,
                        assetsProvider));
    }

    /**
     * Read from a split installed alongside the application, which may not have been
     * loaded initially because the application requested isolated split loading.
     *
     * @param context a context of the package that contains the split
     * @param splitName the name of the split to load
     */
    @NonNull
    public static ResourcesProvider loadFromSplit(@NonNull Context context,
            @NonNull String splitName) throws IOException {
        ApplicationInfo appInfo = context.getApplicationInfo();
        int splitIndex = ArrayUtils.indexOf(appInfo.splitNames, splitName);
        if (splitIndex < 0) {
            throw new IllegalArgumentException("Split " + splitName + " not found");
        }

        String splitPath = appInfo.getSplitCodePaths()[splitIndex];
        return new ResourcesProvider(ApkAssets.loadFromPath(splitPath, ApkAssets.PROPERTY_LOADER,
                null /* assetsProvider */));
    }

    /**
     * Creates a ResourcesProvider from a directory path.
     *
     * File-based resources will be resolved within the directory as if the directory is an APK.
     *
     * @param path the path of the directory to treat as an APK
     * @param assetsProvider the assets provider that overrides the loading of file-based resources
     */
    @NonNull
    public static ResourcesProvider loadFromDirectory(@NonNull String path,
            @Nullable AssetsProvider assetsProvider) throws IOException {
        return new ResourcesProvider(ApkAssets.loadFromDir(path, ApkAssets.PROPERTY_LOADER,
                assetsProvider));
    }


    private ResourcesProvider(@NonNull ApkAssets apkAssets) {
        this.mApkAssets = apkAssets;
    }

    /** @hide */
    @NonNull
    public ApkAssets getApkAssets() {
        return mApkAssets;
    }

    final void incrementRefCount() {
        synchronized (mLock) {
            if (!mOpen) {
                throw new IllegalStateException("Operation failed: resources provider is closed");
            }
            mOpenCount++;
        }
    }

    final void decrementRefCount() {
        synchronized (mLock) {
            mOpenCount--;
        }
    }

    /**
     * Frees internal data structures. Closed providers can no longer be added to
     * {@link ResourcesLoader ResourcesLoader(s)}.
     *
     * @throws IllegalStateException if provider is currently used by a ResourcesLoader
     */
    @Override
    public void close() {
        synchronized (mLock) {
            if (!mOpen) {
                return;
            }

            if (mOpenCount != 0) {
                throw new IllegalStateException("Failed to close provider used by " + mOpenCount
                        + " ResourcesLoader instances");
            }
            mOpen = false;
        }

        try {
            mApkAssets.close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void finalize() throws Throwable {
        synchronized (mLock) {
            if (mOpenCount != 0) {
                Log.w(TAG, "ResourcesProvider " + this + " finalized with non-zero refs: "
                        + mOpenCount);
            }
        }
    }
}
