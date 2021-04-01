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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontFileUtil;
import android.graphics.fonts.FontManager;
import android.graphics.fonts.FontUpdateRequest;
import android.graphics.fonts.SystemFonts;
import android.os.ResultReceiver;
import android.os.SharedMemory;
import android.os.ShellCallback;
import android.system.ErrnoException;
import android.text.FontConfig;
import android.text.TextUtils;
import android.util.AndroidException;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.graphics.fonts.IFontManager;
import com.android.internal.security.VerityUtils;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.NioUtils;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A service for managing system fonts. */
// TODO(b/173619554): Add API to update fonts.
public final class FontManagerService extends IFontManager.Stub {
    private static final String TAG = "FontManagerService";

    private static final String FONT_FILES_DIR = "/data/fonts/files";

    @Override
    public FontConfig getFontConfig() {
        getContext().enforceCallingPermission(Manifest.permission.UPDATE_FONTS,
                "UPDATE_FONTS permission required.");
        return getSystemFontConfig();
    }

    @Override
    public int updateFontFile(@NonNull FontUpdateRequest request, int baseVersion) {
        Preconditions.checkArgumentNonnegative(baseVersion);
        Objects.requireNonNull(request);
        Objects.requireNonNull(request.getFd());
        Objects.requireNonNull(request.getSignature());
        getContext().enforceCallingPermission(Manifest.permission.UPDATE_FONTS,
                "UPDATE_FONTS permission required.");
        try {
            update(baseVersion, Collections.singletonList(request));
            return FontManager.RESULT_SUCCESS;
        } catch (SystemFontException e) {
            Slog.e(TAG, "Failed to update font file", e);
            return e.getErrorCode();
        }
    }

    @Override
    public int updateFontFamily(@NonNull List<FontUpdateRequest> requests, int baseVersion) {
        Preconditions.checkArgumentNonnegative(baseVersion);
        Objects.requireNonNull(requests);
        getContext().enforceCallingPermission(Manifest.permission.UPDATE_FONTS,
                "UPDATE_FONTS permission required.");
        try {
            update(baseVersion, requests);
            return FontManager.RESULT_SUCCESS;
        } catch (SystemFontException e) {
            Slog.e(TAG, "Failed to update font family", e);
            return e.getErrorCode();
        }
    }

    /* package */ static class SystemFontException extends AndroidException {
        private final int mErrorCode;

        SystemFontException(@FontManager.ResultCode int errorCode, String msg, Throwable cause) {
            super(msg, cause);
            mErrorCode = errorCode;
        }

        SystemFontException(int errorCode, String msg) {
            super(msg);
            mErrorCode = errorCode;
        }

        @FontManager.ResultCode
        int getErrorCode() {
            return mErrorCode;
        }
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
                            return mService.getCurrentFontMap();
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
        public String buildFontFileName(File file) throws IOException {
            ByteBuffer buffer = mmap(file);
            try {
                String psName = FontFileUtil.getPostScriptName(buffer, 0);
                int isType1Font = FontFileUtil.isPostScriptType1Font(buffer, 0);
                int isCollection = FontFileUtil.isCollectionFont(buffer);

                if (TextUtils.isEmpty(psName) || isType1Font == -1 || isCollection == -1) {
                    return null;
                }

                String extension;
                if (isCollection == 1) {
                    extension = isType1Font == 1 ? ".otc" : ".ttc";
                } else {
                    extension = isType1Font == 1 ? ".otf" : ".ttf";
                }
                return psName + extension;
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

    @NonNull
    private final Context mContext;

    private final Object mUpdatableFontDirLock = new Object();

    @GuardedBy("mUpdatableFontDirLock")
    @Nullable
    private final UpdatableFontDir mUpdatableFontDir;

    // mSerializedFontMapLock can be acquired while holding mUpdatableFontDirLock.
    // mUpdatableFontDirLock should not be newly acquired while holding mSerializedFontMapLock.
    private final Object mSerializedFontMapLock = new Object();

    @GuardedBy("mSerializedFontMapLock")
    @Nullable
    private SharedMemory mSerializedFontMap = null;

    private FontManagerService(Context context) {
        mContext = context;
        mUpdatableFontDir = createUpdatableFontDir();
        initialize();
    }

    @Nullable
    private static UpdatableFontDir createUpdatableFontDir() {
        // If apk verity is supported, fs-verity should be available.
        if (!VerityUtils.isFsVeritySupported()) return null;
        return new UpdatableFontDir(new File(FONT_FILES_DIR),
                Arrays.asList(new File(SystemFonts.SYSTEM_FONT_DIR),
                        new File(SystemFonts.OEM_FONT_DIR)),
                new OtfFontFileParser(), new FsverityUtilImpl());
    }

    private void initialize() {
        synchronized (mUpdatableFontDirLock) {
            if (mUpdatableFontDir == null) {
                synchronized (mSerializedFontMapLock) {
                    try {
                        mSerializedFontMap = Typeface.serializeFontMap(Typeface.getSystemFontMap());
                    } catch (IOException | ErrnoException e) {
                        mSerializedFontMap = null;
                    }
                }
                return;
            }
            mUpdatableFontDir.loadFontFileMap();
            updateSerializedFontMap();
        }
    }

    @NonNull
    public Context getContext() {
        return mContext;
    }

    @Nullable /* package */ SharedMemory getCurrentFontMap() {
        synchronized (mSerializedFontMapLock) {
            return mSerializedFontMap;
        }
    }

    /* package */ void update(int baseVersion, List<FontUpdateRequest> requests)
            throws SystemFontException {
        if (mUpdatableFontDir == null) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_FONT_UPDATER_DISABLED,
                    "The font updater is disabled.");
        }
        synchronized (mUpdatableFontDirLock) {
            // baseVersion == -1 only happens from shell command. This is filtered and treated as
            // error from SystemApi call.
            if (baseVersion != -1 && mUpdatableFontDir.getConfigVersion() != baseVersion) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_VERSION_MISMATCH,
                        "The base config version is older than current.");
            }
            mUpdatableFontDir.update(requests);
            updateSerializedFontMap();
        }
    }

    /* package */ void clearUpdates() throws SystemFontException {
        if (mUpdatableFontDir == null) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_FONT_UPDATER_DISABLED,
                    "The font updater is disabled.");
        }
        synchronized (mUpdatableFontDirLock) {
            mUpdatableFontDir.clearUpdates();
            updateSerializedFontMap();
        }
    }

    /* package */ Map<String, File> getFontFileMap() {
        if (mUpdatableFontDir == null) {
            return Collections.emptyMap();
        }
        synchronized (mUpdatableFontDirLock) {
            return mUpdatableFontDir.getFontFileMap();
        }
    }

    @Override
    public void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter writer,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, writer)) return;
        new FontManagerShellCommand(this).dumpAll(new IndentingPrintWriter(writer, "  "));
    }

    @Override
    public void onShellCommand(@Nullable FileDescriptor in,
            @Nullable FileDescriptor out,
            @Nullable FileDescriptor err,
            @NonNull String[] args,
            @Nullable ShellCallback callback,
            @NonNull ResultReceiver result) {
        new FontManagerShellCommand(this).exec(this, in, out, err, args, callback, result);
    }

    /**
     * Returns an active system font configuration.
     */
    public @NonNull FontConfig getSystemFontConfig() {
        if (mUpdatableFontDir == null) {
            return SystemFonts.getSystemPreinstalledFontConfig();
        }
        synchronized (mUpdatableFontDirLock) {
            return mUpdatableFontDir.getSystemFontConfig();
        }
    }

    /**
     * Makes new serialized font map data and updates mSerializedFontMap.
     */
    public void updateSerializedFontMap() {
        try {
            final FontConfig fontConfig = getSystemFontConfig();
            final Map<String, FontFamily[]> fallback = SystemFonts.buildSystemFallback(fontConfig);
            final Map<String, Typeface> typefaceMap =
                    SystemFonts.buildSystemTypefaces(fontConfig, fallback);

            SharedMemory serializeFontMap = Typeface.serializeFontMap(typefaceMap);
            synchronized (mSerializedFontMapLock) {
                mSerializedFontMap = serializeFontMap;
            }
            return;
        } catch (IOException | ErrnoException e) {
            Slog.w(TAG, "Failed to serialize updatable font map. "
                    + "Retrying with system image fonts.", e);
        }

        try {
            final FontConfig fontConfig = SystemFonts.getSystemPreinstalledFontConfig();
            final Map<String, FontFamily[]> fallback = SystemFonts.buildSystemFallback(fontConfig);
            final Map<String, Typeface> typefaceMap =
                    SystemFonts.buildSystemTypefaces(fontConfig, fallback);

            SharedMemory serializeFontMap = Typeface.serializeFontMap(typefaceMap);
            synchronized (mSerializedFontMapLock) {
                mSerializedFontMap = serializeFontMap;
            }
        } catch (IOException | ErrnoException e) {
            Slog.e(TAG, "Failed to serialize SystemServer system font map", e);
        }
    }

}
