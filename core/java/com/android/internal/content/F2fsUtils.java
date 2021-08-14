/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.content.ContentResolver;
import android.os.Environment;
import android.os.incremental.IncrementalManager;
import android.provider.Settings.Secure;
import android.text.TextUtils;
import android.util.Slog;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility methods to work with the f2fs file system.
 */
public final class F2fsUtils {
    private static final String TAG = "F2fsUtils";
    private static final boolean DEBUG_F2FS = false;

    /** Directory containing kernel features */
    private static final File sKernelFeatures =
            new File("/sys/fs/f2fs/features");
    /** File containing features enabled on "/data" */
    private static final File sUserDataFeatures =
            new File("/dev/sys/fs/by-name/userdata/features");
    private static final File sDataDirectory = Environment.getDataDirectory();
    /** Name of the compression feature */
    private static final String COMPRESSION_FEATURE = "compression";

    private static final boolean sKernelCompressionAvailable;
    private static final boolean sUserDataCompressionAvailable;

    static {
        sKernelCompressionAvailable = isCompressionEnabledInKernel();
        if (!sKernelCompressionAvailable) {
            if (DEBUG_F2FS) {
                Slog.d(TAG, "f2fs compression DISABLED; feature not part of the kernel");
            }
        }
        sUserDataCompressionAvailable = isCompressionEnabledOnUserData();
        if (!sUserDataCompressionAvailable) {
            if (DEBUG_F2FS) {
                Slog.d(TAG, "f2fs compression DISABLED; feature not enabled on filesystem");
            }
        }
    }

    /**
     * Releases compressed blocks from eligible installation artifacts.
     * <p>
     * Modern f2fs implementations starting in {@code S} support compression
     * natively within the file system. The data blocks of specific installation
     * artifacts [eg. .apk, .so, ...] can be compressed at the file system level,
     * making them look and act like any other uncompressed file, but consuming
     * a fraction of the space.
     * <p>
     * However, the unused space is not free'd automatically. Instead, we must
     * manually tell the file system to release the extra blocks [the delta between
     * the compressed and uncompressed block counts] back to the free pool.
     * <p>
     * Because of how compression works within the file system, once the blocks
     * have been released, the file becomes read-only and cannot be modified until
     * the free'd blocks have again been reserved from the free pool.
     */
    public static void releaseCompressedBlocks(ContentResolver resolver, File file) {
        if (!sKernelCompressionAvailable || !sUserDataCompressionAvailable) {
            return;
        }

        // NOTE: Retrieving this setting means we need to delay releasing cblocks
        // of any APKs installed during the PackageManagerService constructor. Instead
        // of being able to release them in the constructor, they can only be released
        // immediately prior to the system being available. When we no longer need to
        // read this setting, move cblock release back to the package manager constructor.
        final boolean releaseCompressBlocks =
                Secure.getInt(resolver, Secure.RELEASE_COMPRESS_BLOCKS_ON_INSTALL, 1) != 0;
        if (!releaseCompressBlocks) {
            if (DEBUG_F2FS) {
                Slog.d(TAG, "SKIP; release compress blocks not enabled");
            }
            return;
        }
        if (!isCompressionAllowed(file)) {
            if (DEBUG_F2FS) {
                Slog.d(TAG, "SKIP; compression not allowed");
            }
            return;
        }
        final File[] files = getFilesToRelease(file);
        if (files == null || files.length == 0) {
            if (DEBUG_F2FS) {
                Slog.d(TAG, "SKIP; no files to compress");
            }
            return;
        }
        for (int i = files.length - 1; i >= 0; --i) {
            final long releasedBlocks = nativeReleaseCompressedBlocks(files[i].getAbsolutePath());
            if (DEBUG_F2FS) {
                Slog.d(TAG, "RELEASED " + releasedBlocks + " blocks"
                        + " from \"" + files[i] + "\"");
            }
        }
    }

    /**
     * Returns {@code true} if compression is allowed on the file system containing
     * the given file.
     * <p>
     * NOTE: The return value does not mean if the given file, or any other file
     * on the same file system, is actually compressed. It merely determines whether
     * not files <em>may</em> be compressed.
     */
    private static boolean isCompressionAllowed(@NonNull File file) {
        final String filePath;
        try {
            filePath = file.getCanonicalPath();
        } catch (IOException e) {
            if (DEBUG_F2FS) {
                Slog.d(TAG, "f2fs compression DISABLED; could not determine path");
            }
            return false;
        }
        if (IncrementalManager.isIncrementalPath(filePath)) {
            if (DEBUG_F2FS) {
                Slog.d(TAG, "f2fs compression DISABLED; file on incremental fs");
            }
            return false;
        }
        if (!isChild(sDataDirectory, filePath)) {
            if (DEBUG_F2FS) {
                Slog.d(TAG, "f2fs compression DISABLED; file not on /data");
            }
            return false;
        }
        if (DEBUG_F2FS) {
            Slog.d(TAG, "f2fs compression ENABLED");
        }
        return true;
    }

    /**
     * Returns {@code true} if the given child is a descendant of the base.
     */
    private static boolean isChild(@NonNull File base, @NonNull String childPath) {
        try {
            base = base.getCanonicalFile();

            File parentFile = new File(childPath).getCanonicalFile();
            while (parentFile != null) {
                if (base.equals(parentFile)) {
                    return true;
                }
                parentFile = parentFile.getParentFile();
            }
            return false;
        } catch (IOException ignore) {
            return false;
        }
    }

    /**
     * Returns whether or not the compression feature is enabled in the kernel.
     * <p>
     * NOTE: This doesn't mean compression is enabled on a particular file system
     * or any files have been compressed. Only that the functionality is enabled
     * on the device.
     */
    private static boolean isCompressionEnabledInKernel() {
        final File[] features = sKernelFeatures.listFiles();
        if (features == null || features.length == 0) {
            if (DEBUG_F2FS) {
                Slog.d(TAG, "ERROR; no kernel features");
            }
            return false;
        }
        for (int i = features.length - 1; i >= 0; --i) {
            final File feature = features[i];
            if (COMPRESSION_FEATURE.equals(features[i].getName())) {
                if (DEBUG_F2FS) {
                    Slog.d(TAG, "FOUND kernel compression feature");
                }
                return true;
            }
        }
        if (DEBUG_F2FS) {
            Slog.d(TAG, "ERROR; kernel compression feature not found");
        }
        return false;
    }

    /**
     * Returns whether or not the compression feature is enabled on user data [ie. "/data"].
     * <p>
     * NOTE: This doesn't mean any files have been compressed. Only that the functionality
     * is enabled on the file system.
     */
    private static boolean isCompressionEnabledOnUserData() {
        if (!sUserDataFeatures.exists()
                || !sUserDataFeatures.isFile()
                || !sUserDataFeatures.canRead()) {
            if (DEBUG_F2FS) {
                Slog.d(TAG, "ERROR; filesystem features not available");
            }
            return false;
        }
        final List<String> configLines;
        try {
            configLines = Files.readAllLines(sUserDataFeatures.toPath());
        } catch (IOException ignore) {
            if (DEBUG_F2FS) {
                Slog.d(TAG, "ERROR; couldn't read filesystem features");
            }
            return false;
        }
        if (configLines == null
                || configLines.size() > 1
                || TextUtils.isEmpty(configLines.get(0))) {
            if (DEBUG_F2FS) {
                Slog.d(TAG, "ERROR; no filesystem features");
            }
            return false;
        }
        final String[] features = configLines.get(0).split(",");
        for (int i = features.length - 1; i >= 0; --i) {
            if (COMPRESSION_FEATURE.equals(features[i].trim())) {
                if (DEBUG_F2FS) {
                    Slog.d(TAG, "FOUND filesystem compression feature");
                }
                return true;
            }
        }
        if (DEBUG_F2FS) {
            Slog.d(TAG, "ERROR; filesystem compression feature not found");
        }
        return false;
    }

    /**
     * Returns all files contained within the directory at any depth from the given path.
     */
    private static List<File> getFilesRecursive(@NonNull File path) {
        final File[] allFiles = path.listFiles();
        if (allFiles == null) {
            return null;
        }
        final ArrayList<File> files = new ArrayList<>();
        for (File f : allFiles) {
            if (f.isDirectory()) {
                files.addAll(getFilesRecursive(f));
            } else if (f.isFile()) {
                files.add(f);
            }
        }
        return files;
    }

    /**
     * Returns all files contained within the directory at any depth from the given path.
     */
    private static File[] getFilesToRelease(@NonNull File codePath) {
        final List<File> files = getFilesRecursive(codePath);
        if (files == null) {
            if (codePath.isFile()) {
                return new File[] { codePath };
            }
            return null;
        }
        if (files.size() == 0) {
            return null;
        }
        return files.toArray(new File[files.size()]);
    }

    private static native long nativeReleaseCompressedBlocks(String path);

}
