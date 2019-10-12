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
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.res.ApkAssets;
import android.content.res.Resources;
import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;

import com.android.internal.util.ArrayUtils;

import java.io.Closeable;
import java.io.IOException;

/**
 * Provides methods to load resources from an .apk or .arsc file to pass to
 * {@link Resources#addLoader(ResourceLoader, ResourcesProvider, int)}.
 *
 * It is the responsibility of the app to close any instances.
 */
public final class ResourcesProvider implements AutoCloseable, Closeable {

    /**
     * Contains no data, assuming that any resource loading behavior will be handled in the
     * corresponding {@link ResourceLoader}.
     */
    @NonNull
    public static ResourcesProvider empty() {
        return new ResourcesProvider(ApkAssets.loadEmptyForLoader());
    }

    /**
     * Read from an .apk file descriptor.
     *
     * The file descriptor is duplicated and the one passed in may be closed by the application
     * at any time.
     */
    @NonNull
    public static ResourcesProvider loadFromApk(@NonNull ParcelFileDescriptor fileDescriptor)
            throws IOException {
        return new ResourcesProvider(
                ApkAssets.loadApkForLoader(fileDescriptor.getFileDescriptor()));
    }

    /**
     * Read from an .apk file representation in memory.
     */
    @NonNull
    public static ResourcesProvider loadFromApk(@NonNull SharedMemory sharedMemory)
            throws IOException {
        return new ResourcesProvider(
                ApkAssets.loadApkForLoader(sharedMemory.getFileDescriptor()));
    }

    /**
     * Read from an .arsc file descriptor.
     *
     * The file descriptor is duplicated and the one passed in may be closed by the application
     * at any time.
     */
    @NonNull
    public static ResourcesProvider loadFromArsc(@NonNull ParcelFileDescriptor fileDescriptor)
            throws IOException {
        return new ResourcesProvider(
                ApkAssets.loadArscForLoader(fileDescriptor.getFileDescriptor()));
    }

    /**
     * Read from an .arsc file representation in memory.
     */
    @NonNull
    public static ResourcesProvider loadFromArsc(@NonNull SharedMemory sharedMemory)
            throws IOException {
        return new ResourcesProvider(
                ApkAssets.loadArscForLoader(sharedMemory.getFileDescriptor()));
    }

    /**
     * Read from a split installed alongside the application, which may not have been
     * loaded initially because the application requested isolated split loading.
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
        return new ResourcesProvider(ApkAssets.loadApkForLoader(splitPath));
    }


    @NonNull
    private final ApkAssets mApkAssets;

    private ResourcesProvider(@NonNull ApkAssets apkAssets) {
        this.mApkAssets = apkAssets;
    }

    /** @hide */
    @NonNull
    public ApkAssets getApkAssets() {
        return mApkAssets;
    }

    @Override
    public void close() {
        try {
            mApkAssets.close();
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void finalize() throws Throwable {
        close();
        super.finalize();
    }
}
