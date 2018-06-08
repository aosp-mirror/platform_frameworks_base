/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.content.res;

import android.annotation.NonNull;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.IOException;

/**
 * The loaded, immutable, in-memory representation of an APK.
 *
 * The main implementation is native C++ and there is very little API surface exposed here. The APK
 * is mainly accessed via {@link AssetManager}.
 *
 * Since the ApkAssets instance is immutable, it can be reused and shared across AssetManagers,
 * making the creation of AssetManagers very cheap.
 * @hide
 */
public final class ApkAssets {
    @GuardedBy("this") private final long mNativePtr;
    @GuardedBy("this") private StringBlock mStringBlock;

    /**
     * Creates a new ApkAssets instance from the given path on disk.
     *
     * @param path The path to an APK on disk.
     * @return a new instance of ApkAssets.
     * @throws IOException if a disk I/O error or parsing error occurred.
     */
    public static @NonNull ApkAssets loadFromPath(@NonNull String path) throws IOException {
        return new ApkAssets(path, false /*system*/, false /*forceSharedLib*/, false /*overlay*/);
    }

    /**
     * Creates a new ApkAssets instance from the given path on disk.
     *
     * @param path The path to an APK on disk.
     * @param system When true, the APK is loaded as a system APK (framework).
     * @return a new instance of ApkAssets.
     * @throws IOException if a disk I/O error or parsing error occurred.
     */
    public static @NonNull ApkAssets loadFromPath(@NonNull String path, boolean system)
            throws IOException {
        return new ApkAssets(path, system, false /*forceSharedLib*/, false /*overlay*/);
    }

    /**
     * Creates a new ApkAssets instance from the given path on disk.
     *
     * @param path The path to an APK on disk.
     * @param system When true, the APK is loaded as a system APK (framework).
     * @param forceSharedLibrary When true, any packages within the APK with package ID 0x7f are
     *                           loaded as a shared library.
     * @return a new instance of ApkAssets.
     * @throws IOException if a disk I/O error or parsing error occurred.
     */
    public static @NonNull ApkAssets loadFromPath(@NonNull String path, boolean system,
            boolean forceSharedLibrary) throws IOException {
        return new ApkAssets(path, system, forceSharedLibrary, false /*overlay*/);
    }

    /**
     * Creates a new ApkAssets instance from the given file descriptor. Not for use by applications.
     *
     * Performs a dup of the underlying fd, so you must take care of still closing
     * the FileDescriptor yourself (and can do that whenever you want).
     *
     * @param fd The FileDescriptor of an open, readable APK.
     * @param friendlyName The friendly name used to identify this ApkAssets when logging.
     * @param system When true, the APK is loaded as a system APK (framework).
     * @param forceSharedLibrary When true, any packages within the APK with package ID 0x7f are
     *                           loaded as a shared library.
     * @return a new instance of ApkAssets.
     * @throws IOException if a disk I/O error or parsing error occurred.
     */
    public static @NonNull ApkAssets loadFromFd(@NonNull FileDescriptor fd,
            @NonNull String friendlyName, boolean system, boolean forceSharedLibrary)
            throws IOException {
        return new ApkAssets(fd, friendlyName, system, forceSharedLibrary);
    }

    /**
     * Creates a new ApkAssets instance from the IDMAP at idmapPath. The overlay APK path
     * is encoded within the IDMAP.
     *
     * @param idmapPath Path to the IDMAP of an overlay APK.
     * @param system When true, the APK is loaded as a system APK (framework).
     * @return a new instance of ApkAssets.
     * @throws IOException if a disk I/O error or parsing error occurred.
     */
    public static @NonNull ApkAssets loadOverlayFromPath(@NonNull String idmapPath, boolean system)
            throws IOException {
        return new ApkAssets(idmapPath, system, false /*forceSharedLibrary*/, true /*overlay*/);
    }

    private ApkAssets(@NonNull String path, boolean system, boolean forceSharedLib, boolean overlay)
            throws IOException {
        Preconditions.checkNotNull(path, "path");
        mNativePtr = nativeLoad(path, system, forceSharedLib, overlay);
        mStringBlock = new StringBlock(nativeGetStringBlock(mNativePtr), true /*useSparse*/);
    }

    private ApkAssets(@NonNull FileDescriptor fd, @NonNull String friendlyName, boolean system,
            boolean forceSharedLib) throws IOException {
        Preconditions.checkNotNull(fd, "fd");
        Preconditions.checkNotNull(friendlyName, "friendlyName");
        mNativePtr = nativeLoadFromFd(fd, friendlyName, system, forceSharedLib);
        mStringBlock = new StringBlock(nativeGetStringBlock(mNativePtr), true /*useSparse*/);
    }

    public @NonNull String getAssetPath() {
        synchronized (this) {
            return nativeGetAssetPath(mNativePtr);
        }
    }

    CharSequence getStringFromPool(int idx) {
        synchronized (this) {
            return mStringBlock.get(idx);
        }
    }

    /**
     * Retrieve a parser for a compiled XML file. This is associated with a single APK and
     * <em>NOT</em> a full AssetManager. This means that shared-library references will not be
     * dynamically assigned runtime package IDs.
     *
     * @param fileName The path to the file within the APK.
     * @return An XmlResourceParser.
     * @throws IOException if the file was not found or an error occurred retrieving it.
     */
    public @NonNull XmlResourceParser openXml(@NonNull String fileName) throws IOException {
        Preconditions.checkNotNull(fileName, "fileName");
        synchronized (this) {
            long nativeXmlPtr = nativeOpenXml(mNativePtr, fileName);
            try (XmlBlock block = new XmlBlock(null, nativeXmlPtr)) {
                XmlResourceParser parser = block.newParser();
                // If nativeOpenXml doesn't throw, it will always return a valid native pointer,
                // which makes newParser always return non-null. But let's be paranoid.
                if (parser == null) {
                    throw new AssertionError("block.newParser() returned a null parser");
                }
                return parser;
            }
        }
    }

    /**
     * Returns false if the underlying APK was changed since this ApkAssets was loaded.
     */
    public boolean isUpToDate() {
        synchronized (this) {
            return nativeIsUpToDate(mNativePtr);
        }
    }

    @Override
    public String toString() {
        return "ApkAssets{path=" + getAssetPath() + "}";
    }

    @Override
    protected void finalize() throws Throwable {
        nativeDestroy(mNativePtr);
    }

    private static native long nativeLoad(
            @NonNull String path, boolean system, boolean forceSharedLib, boolean overlay)
            throws IOException;
    private static native long nativeLoadFromFd(@NonNull FileDescriptor fd,
            @NonNull String friendlyName, boolean system, boolean forceSharedLib)
            throws IOException;
    private static native void nativeDestroy(long ptr);
    private static native @NonNull String nativeGetAssetPath(long ptr);
    private static native long nativeGetStringBlock(long ptr);
    private static native boolean nativeIsUpToDate(long ptr);
    private static native long nativeOpenXml(long ptr, @NonNull String fileName) throws IOException;
}
