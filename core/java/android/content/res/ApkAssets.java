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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.om.OverlayableInfo;
import android.content.res.loader.AssetsProvider;
import android.content.res.loader.ResourcesProvider;

import com.android.internal.annotations.GuardedBy;

import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

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

    /**
     * The apk assets contains framework resource values specified by the system.
     * This allows some functions to filter out this package when computing what
     * configurations/resources are available.
     */
    public static final int PROPERTY_SYSTEM = 1 << 0;

    /**
     * The apk assets is a shared library or was loaded as a shared library by force.
     * The package ids of dynamic apk assets are assigned at runtime instead of compile time.
     */
    public static final int PROPERTY_DYNAMIC = 1 << 1;

    /**
     * The apk assets has been loaded dynamically using a {@link ResourcesProvider}.
     * Loader apk assets overlay resources like RROs except they are not backed by an idmap.
     */
    public static final int PROPERTY_LOADER = 1 << 2;

    /**
     * The apk assets is a RRO.
     * An RRO overlays resource values of its target package.
     */
    private static final int PROPERTY_OVERLAY = 1 << 3;

    /** Flags that change the behavior of loaded apk assets. */
    @IntDef(prefix = { "PROPERTY_" }, value = {
            PROPERTY_SYSTEM,
            PROPERTY_DYNAMIC,
            PROPERTY_LOADER,
            PROPERTY_OVERLAY,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PropertyFlags {}

    /** The path used to load the apk assets represents an APK file. */
    private static final int FORMAT_APK = 0;

    /** The path used to load the apk assets represents an idmap file. */
    private static final int FORMAT_IDMAP = 1;

    /** The path used to load the apk assets represents an resources.arsc file. */
    private static final int FORMAT_ARSC = 2;

    /** the path used to load the apk assets represents a directory. */
    private static final int FORMAT_DIR = 3;

    // Format types that change how the apk assets are loaded.
    @IntDef(prefix = { "FORMAT_" }, value = {
            FORMAT_APK,
            FORMAT_IDMAP,
            FORMAT_ARSC,
            FORMAT_DIR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FormatType {}

    @GuardedBy("this")
    private long mNativePtr;  // final, except cleared in finalizer.

    @Nullable
    @GuardedBy("this")
    private final StringBlock mStringBlock;  // null or closed if mNativePtr = 0.

    @PropertyFlags
    private final int mFlags;

    @Nullable
    private final AssetsProvider mAssets;

    /**
     * Creates a new ApkAssets instance from the given path on disk.
     *
     * @param path The path to an APK on disk.
     * @return a new instance of ApkAssets.
     * @throws IOException if a disk I/O error or parsing error occurred.
     */
    public static @NonNull ApkAssets loadFromPath(@NonNull String path) throws IOException {
        return loadFromPath(path, 0 /* flags */);
    }

    /**
     * Creates a new ApkAssets instance from the given path on disk.
     *
     * @param path The path to an APK on disk.
     * @param flags flags that change the behavior of loaded apk assets
     * @return a new instance of ApkAssets.
     * @throws IOException if a disk I/O error or parsing error occurred.
     */
    public static @NonNull ApkAssets loadFromPath(@NonNull String path, @PropertyFlags int flags)
            throws IOException {
        return new ApkAssets(FORMAT_APK, path, flags, null /* assets */);
    }

    /**
     * Creates a new ApkAssets instance from the given path on disk.
     *
     * @param path The path to an APK on disk.
     * @param flags flags that change the behavior of loaded apk assets
     * @param assets The assets provider that overrides the loading of file-based resources
     * @return a new instance of ApkAssets.
     * @throws IOException if a disk I/O error or parsing error occurred.
     */
    public static @NonNull ApkAssets loadFromPath(@NonNull String path, @PropertyFlags int flags,
            @Nullable AssetsProvider assets) throws IOException {
        return new ApkAssets(FORMAT_APK, path, flags, assets);
    }

    /**
     * Creates a new ApkAssets instance from the given file descriptor.
     *
     * Performs a dup of the underlying fd, so you must take care of still closing
     * the FileDescriptor yourself (and can do that whenever you want).
     *
     * @param fd The FileDescriptor of an open, readable APK.
     * @param friendlyName The friendly name used to identify this ApkAssets when logging.
     * @param flags flags that change the behavior of loaded apk assets
     * @param assets The assets provider that overrides the loading of file-based resources
     * @return a new instance of ApkAssets.
     * @throws IOException if a disk I/O error or parsing error occurred.
     */
    public static @NonNull ApkAssets loadFromFd(@NonNull FileDescriptor fd,
            @NonNull String friendlyName, @PropertyFlags int flags,
            @Nullable AssetsProvider assets) throws IOException {
        return new ApkAssets(FORMAT_APK, fd, friendlyName, flags, assets);
    }

    /**
     * Creates a new ApkAssets instance from the given file descriptor.
     *
     * Performs a dup of the underlying fd, so you must take care of still closing
     * the FileDescriptor yourself (and can do that whenever you want).
     *
     * @param fd The FileDescriptor of an open, readable APK.
     * @param friendlyName The friendly name used to identify this ApkAssets when logging.
     * @param offset The location within the file that the apk starts. This must be 0 if length is
     *               {@link AssetFileDescriptor#UNKNOWN_LENGTH}.
     * @param length The number of bytes of the apk, or {@link AssetFileDescriptor#UNKNOWN_LENGTH}
     *               if it extends to the end of the file.
     * @param flags flags that change the behavior of loaded apk assets
     * @param assets The assets provider that overrides the loading of file-based resources
     * @return a new instance of ApkAssets.
     * @throws IOException if a disk I/O error or parsing error occurred.
     */
    public static @NonNull ApkAssets loadFromFd(@NonNull FileDescriptor fd,
            @NonNull String friendlyName, long offset, long length, @PropertyFlags int flags,
            @Nullable AssetsProvider assets)
            throws IOException {
        return new ApkAssets(FORMAT_APK, fd, friendlyName, offset, length, flags, assets);
    }

    /**
     * Creates a new ApkAssets instance from the IDMAP at idmapPath. The overlay APK path
     * is encoded within the IDMAP.
     *
     * @param idmapPath Path to the IDMAP of an overlay APK.
     * @param flags flags that change the behavior of loaded apk assets
     * @return a new instance of ApkAssets.
     * @throws IOException if a disk I/O error or parsing error occurred.
     */
    public static @NonNull ApkAssets loadOverlayFromPath(@NonNull String idmapPath,
            @PropertyFlags int flags) throws IOException {
        return new ApkAssets(FORMAT_IDMAP, idmapPath, flags, null /* assets */);
    }

    /**
     * Creates a new ApkAssets instance from the given file descriptor representing a resources.arsc
     * for use with a {@link ResourcesProvider}.
     *
     * Performs a dup of the underlying fd, so you must take care of still closing
     * the FileDescriptor yourself (and can do that whenever you want).
     *
     * @param fd The FileDescriptor of an open, readable resources.arsc.
     * @param friendlyName The friendly name used to identify this ApkAssets when logging.
     * @param flags flags that change the behavior of loaded apk assets
     * @param assets The assets provider that overrides the loading of file-based resources
     * @return a new instance of ApkAssets.
     * @throws IOException if a disk I/O error or parsing error occurred.
     */
    public static @NonNull ApkAssets loadTableFromFd(@NonNull FileDescriptor fd,
            @NonNull String friendlyName, @PropertyFlags int flags,
            @Nullable AssetsProvider assets) throws IOException {
        return new ApkAssets(FORMAT_ARSC, fd, friendlyName, flags, assets);
    }

    /**
     * Creates a new ApkAssets instance from the given file descriptor representing a resources.arsc
     * for use with a {@link ResourcesProvider}.
     *
     * Performs a dup of the underlying fd, so you must take care of still closing
     * the FileDescriptor yourself (and can do that whenever you want).
     *
     * @param fd The FileDescriptor of an open, readable resources.arsc.
     * @param friendlyName The friendly name used to identify this ApkAssets when logging.
     * @param offset The location within the file that the table starts. This must be 0 if length is
     *               {@link AssetFileDescriptor#UNKNOWN_LENGTH}.
     * @param length The number of bytes of the table, or {@link AssetFileDescriptor#UNKNOWN_LENGTH}
     *               if it extends to the end of the file.
     * @param flags flags that change the behavior of loaded apk assets
     * @param assets The assets provider that overrides the loading of file-based resources
     * @return a new instance of ApkAssets.
     * @throws IOException if a disk I/O error or parsing error occurred.
     */
    public static @NonNull ApkAssets loadTableFromFd(@NonNull FileDescriptor fd,
            @NonNull String friendlyName, long offset, long length, @PropertyFlags int flags,
            @Nullable AssetsProvider assets) throws IOException {
        return new ApkAssets(FORMAT_ARSC, fd, friendlyName, offset, length, flags, assets);
    }

    /**
     * Creates a new ApkAssets instance from the given directory path. The directory should have the
     * file structure of an APK.
     *
     * @param path The path to a directory on disk.
     * @param flags flags that change the behavior of loaded apk assets
     * @param assets The assets provider that overrides the loading of file-based resources
     * @return a new instance of ApkAssets.
     * @throws IOException if a disk I/O error or parsing error occurred.
     */
    public static @NonNull ApkAssets loadFromDir(@NonNull String path,
            @PropertyFlags int flags, @Nullable AssetsProvider assets) throws IOException {
        return new ApkAssets(FORMAT_DIR, path, flags, assets);
    }

    /**
     * Generates an entirely empty ApkAssets. Needed because the ApkAssets instance and presence
     * is required for a lot of APIs, and it's easier to have a non-null reference rather than
     * tracking a separate identifier.
     *
     * @param flags flags that change the behavior of loaded apk assets
     * @param assets The assets provider that overrides the loading of file-based resources
     */
    @NonNull
    public static ApkAssets loadEmptyForLoader(@PropertyFlags int flags,
            @Nullable AssetsProvider assets) {
        return new ApkAssets(flags, assets);
    }

    private ApkAssets(@FormatType int format, @NonNull String path, @PropertyFlags int flags,
            @Nullable AssetsProvider assets) throws IOException {
        Objects.requireNonNull(path, "path");
        mFlags = flags;
        mNativePtr = nativeLoad(format, path, flags, assets);
        mStringBlock = new StringBlock(nativeGetStringBlock(mNativePtr), true /*useSparse*/);
        mAssets = assets;
    }

    private ApkAssets(@FormatType int format, @NonNull FileDescriptor fd,
            @NonNull String friendlyName, @PropertyFlags int flags, @Nullable AssetsProvider assets)
            throws IOException {
        Objects.requireNonNull(fd, "fd");
        Objects.requireNonNull(friendlyName, "friendlyName");
        mFlags = flags;
        mNativePtr = nativeLoadFd(format, fd, friendlyName, flags, assets);
        mStringBlock = new StringBlock(nativeGetStringBlock(mNativePtr), true /*useSparse*/);
        mAssets = assets;
    }

    private ApkAssets(@FormatType int format, @NonNull FileDescriptor fd,
            @NonNull String friendlyName, long offset, long length, @PropertyFlags int flags,
            @Nullable AssetsProvider assets) throws IOException {
        Objects.requireNonNull(fd, "fd");
        Objects.requireNonNull(friendlyName, "friendlyName");
        mFlags = flags;
        mNativePtr = nativeLoadFdOffsets(format, fd, friendlyName, offset, length, flags, assets);
        mStringBlock = new StringBlock(nativeGetStringBlock(mNativePtr), true /*useSparse*/);
        mAssets = assets;
    }

    private ApkAssets(@PropertyFlags int flags, @Nullable AssetsProvider assets) {
        mFlags = flags;
        mNativePtr = nativeLoadEmpty(flags, assets);
        mStringBlock = null;
        mAssets = assets;
    }

    @UnsupportedAppUsage
    public @NonNull String getAssetPath() {
        synchronized (this) {
            return nativeGetAssetPath(mNativePtr);
        }
    }

    CharSequence getStringFromPool(int idx) {
        if (mStringBlock == null) {
            return null;
        }

        synchronized (this) {
            return mStringBlock.get(idx);
        }
    }

    /** Returns whether this apk assets was loaded using a {@link ResourcesProvider}. */
    public boolean isForLoader() {
        return (mFlags & PROPERTY_LOADER) != 0;
    }

    /**
     * Returns the assets provider that overrides the loading of assets present in this apk assets.
     */
    @Nullable
    public AssetsProvider getAssetsProvider() {
        return mAssets;
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
        Objects.requireNonNull(fileName, "fileName");
        synchronized (this) {
            long nativeXmlPtr = nativeOpenXml(mNativePtr, fileName);
            try (XmlBlock block = new XmlBlock(null, nativeXmlPtr)) {
                XmlResourceParser parser = block.newParser();
                // If nativeOpenXml doesn't throw, it will always return a valid native pointer,
                // which makes newParser always return non-null. But let's be careful.
                if (parser == null) {
                    throw new AssertionError("block.newParser() returned a null parser");
                }
                return parser;
            }
        }
    }

    /** @hide */
    @Nullable
    public OverlayableInfo getOverlayableInfo(String overlayableName) throws IOException {
        synchronized (this) {
            return nativeGetOverlayableInfo(mNativePtr, overlayableName);
        }
    }

    /** @hide */
    public boolean definesOverlayable() throws IOException {
        synchronized (this) {
            return nativeDefinesOverlayable(mNativePtr);
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
        close();
    }

    /**
     * Closes this class and the contained {@link #mStringBlock}.
     */
    public void close() {
        synchronized (this) {
            if (mNativePtr != 0) {
                if (mStringBlock != null) {
                    mStringBlock.close();
                }
                nativeDestroy(mNativePtr);
                mNativePtr = 0;
            }
        }
    }

    private static native long nativeLoad(@FormatType int format, @NonNull String path,
            @PropertyFlags int flags, @Nullable AssetsProvider asset) throws IOException;
    private static native long nativeLoadEmpty(@PropertyFlags int flags,
            @Nullable AssetsProvider asset);
    private static native long nativeLoadFd(@FormatType int format, @NonNull FileDescriptor fd,
            @NonNull String friendlyName, @PropertyFlags int flags,
            @Nullable AssetsProvider asset) throws IOException;
    private static native long nativeLoadFdOffsets(@FormatType int format,
            @NonNull FileDescriptor fd, @NonNull String friendlyName, long offset, long length,
            @PropertyFlags int flags, @Nullable AssetsProvider asset) throws IOException;
    private static native void nativeDestroy(long ptr);
    private static native @NonNull String nativeGetAssetPath(long ptr);
    private static native long nativeGetStringBlock(long ptr);
    private static native boolean nativeIsUpToDate(long ptr);
    private static native long nativeOpenXml(long ptr, @NonNull String fileName) throws IOException;
    private static native @Nullable OverlayableInfo nativeGetOverlayableInfo(long ptr,
            String overlayableName) throws IOException;
    private static native boolean nativeDefinesOverlayable(long ptr) throws IOException;
}
