/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.graphics.fonts;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontFileUtil;
import android.graphics.fonts.SystemFonts;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SharedMemory;
import android.system.ErrnoException;
import android.text.FontConfig;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.graphics.fonts.IFontManager;
import com.android.internal.util.DumpUtils;
import com.android.server.LocalServices;
import com.android.server.SystemService;
import com.android.server.security.FileIntegrityService;
import com.android.server.security.VerityUtils;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.NioUtils;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Map;

/** A service for managing system fonts. */
// TODO(b/173619554): Add API to update fonts.
public final class FontManagerService extends IFontManager.Stub {
    private static final String TAG = "FontManagerService";

    // TODO: make this a DeviceConfig flag.
    private static final boolean ENABLE_FONT_UPDATES = false;
    private static final String FONT_FILES_DIR = "/data/fonts/files";

    @Override
    public FontConfig getFontConfig() throws RemoteException {
        return getCurrentFontSettings().getSystemFontConfig();
    }

    /** Class to manage FontManagerService's lifecycle. */
    public static final class Lifecycle extends SystemService {
        private final FontManagerService mService;

        public Lifecycle(@NonNull Context context) {
            super(context);
            mService = new FontManagerService(context);
        }

        @Override
        public void onStart() {
            LocalServices.addService(FontManagerInternal.class,
                    new FontManagerInternal() {
                        @Override
                        @Nullable
                        public SharedMemory getSerializedSystemFontMap() {
                            if (!Typeface.ENABLE_LAZY_TYPEFACE_INITIALIZATION) {
                                return null;
                            }
                            return mService.getCurrentFontSettings().getSerializedSystemFontMap();
                        }
                    });
            publishBinderService(Context.FONT_SERVICE, mService);
        }
    }

    /* package */ static class OtfFontFileParser implements UpdatableFontDir.FontFileParser {
        @Override
        public String getPostScriptName(File file) throws IOException {
            ByteBuffer buffer = mmap(file);
            try {
                return FontFileUtil.getPostScriptName(buffer, 0);
            } finally {
                NioUtils.freeDirectBuffer(buffer);
            }
        }

        @Override
        public long getRevision(File file) throws IOException {
            ByteBuffer buffer = mmap(file);
            try {
                return FontFileUtil.getRevision(buffer, 0);
            } finally {
                NioUtils.freeDirectBuffer(buffer);
            }
        }

        private static ByteBuffer mmap(File file) throws IOException {
            try (FileInputStream in = new FileInputStream(file)) {
                FileChannel fileChannel = in.getChannel();
                return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
            }
        }
    }

    private static class FsverityUtilImpl implements UpdatableFontDir.FsverityUtil {
        @Override
        public boolean hasFsverity(String filePath) {
            return VerityUtils.hasFsverity(filePath);
        }

        @Override
        public void setUpFsverity(String filePath, byte[] pkcs7Signature) throws IOException {
            VerityUtils.setUpFsverity(filePath, pkcs7Signature);
        }

        @Override
        public boolean rename(File src, File dest) {
            // rename system call preserves fs-verity bit.
            return src.renameTo(dest);
        }
    }

    @Nullable
    private final UpdatableFontDir mUpdatableFontDir;

    @GuardedBy("FontManagerService.this")
    @Nullable
    private SystemFontSettings mCurrentFontSettings = null;

    private FontManagerService(Context context) {
        mContext = context;
        mUpdatableFontDir = createUpdatableFontDir();
    }

    @Nullable
    private static UpdatableFontDir createUpdatableFontDir() {
        if (!ENABLE_FONT_UPDATES) return null;
        // If apk verity is supported, fs-verity should be available.
        if (!FileIntegrityService.isApkVeritySupported()) return null;
        return new UpdatableFontDir(new File(FONT_FILES_DIR),
                Arrays.asList(new File(SystemFonts.SYSTEM_FONT_DIR),
                        new File(SystemFonts.OEM_FONT_DIR)),
                new OtfFontFileParser(), new FsverityUtilImpl());
    }


    @NonNull
    private final Context mContext;

    @NonNull
    public Context getContext() {
        return mContext;
    }

    @NonNull /* package */ SystemFontSettings getCurrentFontSettings() {
        synchronized (FontManagerService.this) {
            if (mCurrentFontSettings == null) {
                mCurrentFontSettings = SystemFontSettings.create(mUpdatableFontDir);
            }
            return mCurrentFontSettings;
        }
    }

    // TODO(b/173619554): Expose as API.
    private boolean installFontFile(FileDescriptor fd, byte[] pkcs7Signature) {
        if (mUpdatableFontDir == null) return false;
        synchronized (FontManagerService.this) {
            try {
                mUpdatableFontDir.installFontFile(fd, pkcs7Signature);
            } catch (IOException e) {
                Slog.w(TAG, "Failed to install font file");
                return false;
            }
            // Create updated font map in the next getSerializedSystemFontMap() call.
            mCurrentFontSettings = null;
            return true;
        }
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter writer,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) return;
        new FontManagerShellCommand(this).dumpAll(new IndentingPrintWriter(writer, "  "));
    }

    @Override
    public int handleShellCommand(@NonNull ParcelFileDescriptor in,
            @NonNull ParcelFileDescriptor out, @NonNull ParcelFileDescriptor err,
            @NonNull String[] args) {
        return new FontManagerShellCommand(this).exec(this,
                in.getFileDescriptor(), out.getFileDescriptor(), err.getFileDescriptor(), args);
    }

    /* package */ static class SystemFontSettings {
        private final @NonNull SharedMemory mSerializedSystemFontMap;
        private final @NonNull FontConfig mSystemFontConfig;
        private final @NonNull Map<String, FontFamily[]> mSystemFallbackMap;
        private final @NonNull Map<String, Typeface> mSystemTypefaceMap;

        SystemFontSettings(
                @NonNull SharedMemory serializedSystemFontMap,
                @NonNull FontConfig systemFontConfig,
                @NonNull Map<String, FontFamily[]> systemFallbackMap,
                @NonNull Map<String, Typeface> systemTypefaceMap) {
            mSerializedSystemFontMap = serializedSystemFontMap;
            mSystemFontConfig = systemFontConfig;
            mSystemFallbackMap = systemFallbackMap;
            mSystemTypefaceMap = systemTypefaceMap;
        }

        public @NonNull SharedMemory getSerializedSystemFontMap() {
            return mSerializedSystemFontMap;
        }

        public @NonNull FontConfig getSystemFontConfig() {
            return mSystemFontConfig;
        }

        public @NonNull Map<String, FontFamily[]> getSystemFallbackMap() {
            return mSystemFallbackMap;
        }

        public @NonNull Map<String, Typeface> getSystemTypefaceMap() {
            return mSystemTypefaceMap;
        }

        public static @Nullable SystemFontSettings create(
                @Nullable UpdatableFontDir updatableFontDir) {
            if (updatableFontDir != null) {
                final FontConfig fontConfig = SystemFonts.getSystemFontConfig(
                        updatableFontDir.getFontFileMap());
                final Map<String, FontFamily[]> fallback =
                        SystemFonts.buildSystemFallback(fontConfig);
                final Map<String, Typeface> typefaceMap =
                        SystemFonts.buildSystemTypefaces(fontConfig, fallback);

                try {
                    final SharedMemory shm = Typeface.serializeFontMap(typefaceMap);
                    return new SystemFontSettings(shm, fontConfig, fallback, typefaceMap);
                } catch (IOException | ErrnoException e) {
                    Slog.w(TAG, "Failed to serialize updatable font map. "
                            + "Retrying with system image fonts.", e);
                }
            }

            final FontConfig fontConfig = SystemFonts.getSystemPreinstalledFontConfig();
            final Map<String, FontFamily[]> fallback = SystemFonts.buildSystemFallback(fontConfig);
            final Map<String, Typeface> typefaceMap =
                    SystemFonts.buildSystemTypefaces(fontConfig, fallback);
            try {
                final SharedMemory shm = Typeface.serializeFontMap(typefaceMap);
                return new SystemFontSettings(shm, fontConfig, fallback, typefaceMap);
            } catch (IOException | ErrnoException e) {
                Slog.e(TAG, "Failed to serialize SystemServer system font map", e);
            }
            return null;
        }
    }
}
