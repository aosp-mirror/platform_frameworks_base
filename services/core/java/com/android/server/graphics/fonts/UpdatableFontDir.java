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

package com.android.server.graphics.fonts;

import static com.android.server.graphics.fonts.FontManagerService.SystemFontException;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.fonts.FontManager;
import android.graphics.fonts.FontUpdateRequest;
import android.graphics.fonts.SystemFonts;
import android.os.FileUtils;
import android.os.LocaleList;
import android.system.ErrnoException;
import android.system.Os;
import android.text.FontConfig;
import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Slog;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Manages set of updatable font files.
 *
 * <p>This class is not thread safe.
 */
final class UpdatableFontDir {

    private static final String TAG = "UpdatableFontDir";
    private static final String RANDOM_DIR_PREFIX = "~~";

    /** Interface to mock font file access in tests. */
    interface FontFileParser {
        String getPostScriptName(File file) throws IOException;

        String buildFontFileName(File file) throws IOException;

        long getRevision(File file) throws IOException;

        void tryToCreateTypeface(File file) throws Throwable;
    }

    /** Interface to mock fs-verity in tests. */
    interface FsverityUtil {
        boolean hasFsverity(String path);

        void setUpFsverity(String path, byte[] pkcs7Signature) throws IOException;

        boolean rename(File src, File dest);
    }

    /** Data class to hold font file path and revision. */
    private static final class FontFileInfo {
        private final File mFile;
        private final String mPsName;
        private final long mRevision;

        FontFileInfo(File file, String psName, long revision) {
            mFile = file;
            mPsName = psName;
            mRevision = revision;
        }

        public File getFile() {
            return mFile;
        }

        public String getPostScriptName() {
            return mPsName;
        }

        /** Returns the unique randomized font dir containing this font file. */
        public File getRandomizedFontDir() {
            return mFile.getParentFile();
        }

        public long getRevision() {
            return mRevision;
        }

        @Override
        public String toString() {
            return "FontFileInfo{mFile=" + mFile
                    + ", psName=" + mPsName
                    + ", mRevision=" + mRevision + '}';
        }
    }

    /**
     * Root directory for storing updated font files. Each font file is stored in a unique
     * randomized dir. The font file path would be {@code mFilesDir/~~{randomStr}/{fontFileName}}.
     */
    private final File mFilesDir;
    private final FontFileParser mParser;
    private final FsverityUtil mFsverityUtil;
    private final AtomicFile mConfigFile;
    private final Supplier<Long> mCurrentTimeSupplier;
    private final Function<Map<String, File>, FontConfig> mConfigSupplier;

    private long mLastModifiedMillis;
    private int mConfigVersion;

    /**
     * A mutable map containing mapping from font file name (e.g. "NotoColorEmoji.ttf") to {@link
     * FontFileInfo}. All files in this map are validated, and have higher revision numbers than
     * corresponding font files returned by {@link #mConfigSupplier}.
     */
    private final ArrayMap<String, FontFileInfo> mFontFileInfoMap = new ArrayMap<>();

    UpdatableFontDir(File filesDir, FontFileParser parser, FsverityUtil fsverityUtil,
            File configFile) {
        this(filesDir, parser, fsverityUtil, configFile,
                System::currentTimeMillis,
                (map) -> SystemFonts.getSystemFontConfig(map, 0, 0)
        );
    }

    // For unit testing
    UpdatableFontDir(File filesDir, FontFileParser parser, FsverityUtil fsverityUtil,
            File configFile, Supplier<Long> currentTimeSupplier,
            Function<Map<String, File>, FontConfig> configSupplier) {
        mFilesDir = filesDir;
        mParser = parser;
        mFsverityUtil = fsverityUtil;
        mConfigFile = new AtomicFile(configFile);
        mCurrentTimeSupplier = currentTimeSupplier;
        mConfigSupplier = configSupplier;
    }

    /**
     * Loads fonts from file system, validate them, and delete obsolete font files.
     * Note that this method may be called by multiple times in integration tests via {@link
     * FontManagerService#restart()}.
     */
    /* package */ void loadFontFileMap() {
        mFontFileInfoMap.clear();
        mLastModifiedMillis = 0;
        mConfigVersion = 1;
        boolean success = false;
        try {
            PersistentSystemFontConfig.Config config = readPersistentConfig();
            mLastModifiedMillis = config.lastModifiedMillis;

            File[] dirs = mFilesDir.listFiles();
            if (dirs == null) {
                // mFilesDir should be created by init script.
                Slog.e(TAG, "Could not read: " + mFilesDir);
                return;
            }
            FontConfig fontConfig = null;
            for (File dir : dirs) {
                if (!dir.getName().startsWith(RANDOM_DIR_PREFIX)) {
                    Slog.e(TAG, "Unexpected dir found: " + dir);
                    return;
                }
                if (!config.updatedFontDirs.contains(dir.getName())) {
                    Slog.i(TAG, "Deleting obsolete dir: " + dir);
                    FileUtils.deleteContentsAndDir(dir);
                    continue;
                }
                File[] files = dir.listFiles();
                if (files == null || files.length != 1) {
                    Slog.e(TAG, "Unexpected files in dir: " + dir);
                    return;
                }
                FontFileInfo fontFileInfo = validateFontFile(files[0]);
                if (fontConfig == null) {
                    fontConfig = getSystemFontConfig();
                }
                addFileToMapIfSameOrNewer(fontFileInfo, fontConfig, true /* deleteOldFile */);
            }
            success = true;
        } catch (Throwable t) {
            // If something happened during loading system fonts, clear all contents in finally
            // block. Here, just dumping errors.
            Slog.e(TAG, "Failed to load font mappings.", t);
        } finally {
            // Delete all files just in case if we find a problematic file.
            if (!success) {
                mFontFileInfoMap.clear();
                mLastModifiedMillis = 0;
                FileUtils.deleteContents(mFilesDir);
            }
        }
    }

    /**
     * Applies multiple {@link FontUpdateRequest}s in transaction.
     * If one of the request fails, the fonts and config are rolled back to the previous state
     * before this method is called.
     */
    public void update(List<FontUpdateRequest> requests) throws SystemFontException {
        for (FontUpdateRequest request : requests) {
            switch (request.getType()) {
                case FontUpdateRequest.TYPE_UPDATE_FONT_FILE:
                    Objects.requireNonNull(request.getFd());
                    Objects.requireNonNull(request.getSignature());
                    break;
                case FontUpdateRequest.TYPE_UPDATE_FONT_FAMILY:
                    Objects.requireNonNull(request.getFontFamily());
                    Objects.requireNonNull(request.getFontFamily().getName());
                    break;
            }
        }
        // Backup the mapping for rollback.
        ArrayMap<String, FontFileInfo> backupMap = new ArrayMap<>(mFontFileInfoMap);
        PersistentSystemFontConfig.Config curConfig = readPersistentConfig();
        Map<String, FontUpdateRequest.Family> familyMap = new HashMap<>();
        for (int i = 0; i < curConfig.fontFamilies.size(); ++i) {
            FontUpdateRequest.Family family = curConfig.fontFamilies.get(i);
            familyMap.put(family.getName(), family);
        }

        long backupLastModifiedDate = mLastModifiedMillis;
        boolean success = false;
        try {
            for (FontUpdateRequest request : requests) {
                switch (request.getType()) {
                    case FontUpdateRequest.TYPE_UPDATE_FONT_FILE:
                        installFontFile(
                                request.getFd().getFileDescriptor(), request.getSignature());
                        break;
                    case FontUpdateRequest.TYPE_UPDATE_FONT_FAMILY:
                        FontUpdateRequest.Family family = request.getFontFamily();
                        familyMap.put(family.getName(), family);
                        break;
                }
            }

            // Before processing font family update, check all family points the available fonts.
            for (FontUpdateRequest.Family family : familyMap.values()) {
                if (resolveFontFiles(family) == null) {
                    throw new SystemFontException(
                            FontManager.RESULT_ERROR_FONT_NOT_FOUND,
                            "Required fonts are not available");
                }
            }

            // Write config file.
            mLastModifiedMillis = mCurrentTimeSupplier.get();

            PersistentSystemFontConfig.Config newConfig = new PersistentSystemFontConfig.Config();
            newConfig.lastModifiedMillis = mLastModifiedMillis;
            for (FontFileInfo info : mFontFileInfoMap.values()) {
                newConfig.updatedFontDirs.add(info.getRandomizedFontDir().getName());
            }
            newConfig.fontFamilies.addAll(familyMap.values());
            writePersistentConfig(newConfig);
            mConfigVersion++;
            success = true;
        } finally {
            if (!success) {
                mFontFileInfoMap.clear();
                mFontFileInfoMap.putAll(backupMap);
                mLastModifiedMillis = backupLastModifiedDate;
            }
        }
    }

    /**
     * Installs a new font file, or updates an existing font file.
     *
     * <p>The new font will be immediately available for new Zygote-forked processes through
     * {@link #getPostScriptMap()}. Old font files will be kept until next system server reboot,
     * because existing Zygote-forked processes have paths to old font files.
     *
     * @param fd             A file descriptor to the font file.
     * @param pkcs7Signature A PKCS#7 detached signature to enable fs-verity for the font file.
     * @throws SystemFontException if error occurs.
     */
    private void installFontFile(FileDescriptor fd, byte[] pkcs7Signature)
            throws SystemFontException {
        File newDir = getRandomDir(mFilesDir);
        if (!newDir.mkdir()) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                    "Failed to create font directory.");
        }
        try {
            // Make newDir executable so that apps can access font file inside newDir.
            Os.chmod(newDir.getAbsolutePath(), 0711);
        } catch (ErrnoException e) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                    "Failed to change mode to 711", e);
        }
        boolean success = false;
        try {
            File tempNewFontFile = new File(newDir, "font.ttf");
            try (FileOutputStream out = new FileOutputStream(tempNewFontFile)) {
                FileUtils.copy(fd, out.getFD());
            } catch (IOException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to write font file to storage.", e);
            }
            try {
                // Do not parse font file before setting up fs-verity.
                // setUpFsverity throws IOException if failed.
                mFsverityUtil.setUpFsverity(tempNewFontFile.getAbsolutePath(),
                        pkcs7Signature);
            } catch (IOException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_VERIFICATION_FAILURE,
                        "Failed to setup fs-verity.", e);
            }
            String fontFileName;
            try {
                fontFileName = mParser.buildFontFileName(tempNewFontFile);
            } catch (IOException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                        "Failed to read PostScript name from font file", e);
            }
            if (fontFileName == null) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_FONT_NAME,
                        "Failed to read PostScript name from font file");
            }
            File newFontFile = new File(newDir, fontFileName);
            if (!mFsverityUtil.rename(tempNewFontFile, newFontFile)) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to move verified font file.");
            }
            try {
                // Make the font file readable by apps.
                Os.chmod(newFontFile.getAbsolutePath(), 0644);
            } catch (ErrnoException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_TO_WRITE_FONT_FILE,
                        "Failed to change mode to 711", e);
            }
            FontFileInfo fontFileInfo = validateFontFile(newFontFile);

            // Try to create Typeface and treat as failure something goes wrong.
            try {
                mParser.tryToCreateTypeface(fontFileInfo.getFile());
            } catch (Throwable t) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                        "Failed to create Typeface from file", t);
            }

            FontConfig fontConfig = getSystemFontConfig();
            if (!addFileToMapIfSameOrNewer(fontFileInfo, fontConfig, false)) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_DOWNGRADING,
                        "Downgrading font file is forbidden.");
            }
            success = true;
        } finally {
            if (!success) {
                FileUtils.deleteContentsAndDir(newDir);
            }
        }
    }

    /**
     * Given {@code parent}, returns {@code parent/~~[randomStr]}.
     * Makes sure that {@code parent/~~[randomStr]} directory doesn't exist.
     * Notice that this method doesn't actually create any directory.
     */
    private static File getRandomDir(File parent) {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[16];
        File dir;
        do {
            random.nextBytes(bytes);
            String dirName = RANDOM_DIR_PREFIX
                    + Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP);
            dir = new File(parent, dirName);
        } while (dir.exists());
        return dir;
    }

    private FontFileInfo lookupFontFileInfo(String psName) {
        return mFontFileInfoMap.get(psName);
    }

    private void putFontFileInfo(FontFileInfo info) {
        mFontFileInfoMap.put(info.getPostScriptName(), info);
    }

    /**
     * Add the given {@link FontFileInfo} to {@link #mFontFileInfoMap} if its font revision is
     * equal to or higher than the revision of currently used font file (either in
     * {@link #mFontFileInfoMap} or {@code fontConfig}).
     */
    private boolean addFileToMapIfSameOrNewer(FontFileInfo fontFileInfo, FontConfig fontConfig,
            boolean deleteOldFile) {
        FontFileInfo existingInfo = lookupFontFileInfo(fontFileInfo.getPostScriptName());
        final boolean shouldAddToMap;
        if (existingInfo == null) {
            // We got a new updatable font. We need to check if it's newer than preinstalled fonts.
            // Note that getPreinstalledFontRevision() returns -1 if there is no preinstalled font
            // with 'name'.
            long preInstalledRev = getPreinstalledFontRevision(fontFileInfo, fontConfig);
            shouldAddToMap = preInstalledRev <= fontFileInfo.getRevision();
        } else {
            shouldAddToMap = existingInfo.getRevision() <= fontFileInfo.getRevision();
        }
        if (shouldAddToMap) {
            if (deleteOldFile && existingInfo != null) {
                FileUtils.deleteContentsAndDir(existingInfo.getRandomizedFontDir());
            }
            putFontFileInfo(fontFileInfo);
        } else {
            if (deleteOldFile) {
                FileUtils.deleteContentsAndDir(fontFileInfo.getRandomizedFontDir());
            }
        }
        return shouldAddToMap;
    }

    private long getPreinstalledFontRevision(FontFileInfo info, FontConfig fontConfig) {
        String psName = info.getPostScriptName();
        FontConfig.Font targetFont = null;
        for (int i = 0; i < fontConfig.getFontFamilies().size(); i++) {
            FontConfig.FontFamily family = fontConfig.getFontFamilies().get(i);
            for (int j = 0; j < family.getFontList().size(); ++j) {
                FontConfig.Font font = family.getFontList().get(j);
                if (font.getPostScriptName().equals(psName)) {
                    targetFont = font;
                    break;
                }
            }
        }
        if (targetFont == null) {
            return -1;
        }

        File preinstalledFontFile = targetFont.getOriginalFile() != null
                ? targetFont.getOriginalFile() : targetFont.getFile();
        if (!preinstalledFontFile.exists()) {
            return -1;
        }
        long revision = getFontRevision(preinstalledFontFile);
        if (revision == -1) {
            Slog.w(TAG, "Invalid preinstalled font file");
        }
        return revision;
    }

    /**
     * Checks the fs-verity protection status of the given font file, validates the file name, and
     * returns a {@link FontFileInfo} on success. This method does not check if the font revision
     * is higher than the currently used font.
     */
    @NonNull
    private FontFileInfo validateFontFile(File file) throws SystemFontException {
        if (!mFsverityUtil.hasFsverity(file.getAbsolutePath())) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_VERIFICATION_FAILURE,
                    "Font validation failed. Fs-verity is not enabled: " + file);
        }
        final String psName;
        try {
            psName = mParser.getPostScriptName(file);
        } catch (IOException e) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_INVALID_FONT_NAME,
                    "Font validation failed. Could not read PostScript name name: " + file);
        }
        long revision = getFontRevision(file);
        if (revision == -1) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                    "Font validation failed. Could not read font revision: " + file);
        }
        return new FontFileInfo(file, psName, revision);
    }
    /** Returns the non-negative font revision of the given font file, or -1. */
    private long getFontRevision(File file) {
        try {
            return mParser.getRevision(file);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read font file", e);
            return -1;
        }
    }

    @Nullable
    private FontConfig.FontFamily resolveFontFiles(FontUpdateRequest.Family fontFamily) {
        List<FontUpdateRequest.Font> fontList = fontFamily.getFonts();
        List<FontConfig.Font> resolvedFonts = new ArrayList<>(fontList.size());
        for (int i = 0; i < fontList.size(); i++) {
            FontUpdateRequest.Font font = fontList.get(i);
            FontFileInfo info = mFontFileInfoMap.get(font.getPostScriptName());
            if (info == null) {
                Slog.e(TAG, "Failed to lookup font file that has " + font.getPostScriptName());
                return null;
            }
            resolvedFonts.add(new FontConfig.Font(info.mFile, null, info.getPostScriptName(),
                    font.getFontStyle(), font.getIndex(), font.getFontVariationSettings(), null));
        }
        return new FontConfig.FontFamily(resolvedFonts, fontFamily.getName(),
                LocaleList.getEmptyLocaleList(), FontConfig.FontFamily.VARIANT_DEFAULT);
    }

    Map<String, File> getPostScriptMap() {
        Map<String, File> map = new ArrayMap<>();
        for (int i = 0; i < mFontFileInfoMap.size(); ++i) {
            FontFileInfo info = mFontFileInfoMap.valueAt(i);
            map.put(info.getPostScriptName(), info.getFile());
        }
        return map;
    }

    /* package */ FontConfig getSystemFontConfig() {
        FontConfig config = mConfigSupplier.apply(getPostScriptMap());
        PersistentSystemFontConfig.Config persistentConfig = readPersistentConfig();
        List<FontUpdateRequest.Family> families = persistentConfig.fontFamilies;

        List<FontConfig.FontFamily> mergedFamilies =
                new ArrayList<>(config.getFontFamilies().size() + families.size());
        // We should keep the first font family (config.getFontFamilies().get(0)) because it's used
        // as a fallback font. See SystemFonts.java.
        mergedFamilies.addAll(config.getFontFamilies());
        // When building Typeface, a latter font family definition will override the previous font
        // family definition with the same name. An exception is config.getFontFamilies.get(0),
        // which will be used as a fallback font without being overridden.
        for (int i = 0; i < families.size(); ++i) {
            FontConfig.FontFamily family = resolveFontFiles(families.get(i));
            if (family != null) {
                mergedFamilies.add(family);
            }
        }

        return new FontConfig(
                mergedFamilies, config.getAliases(), mLastModifiedMillis, mConfigVersion);
    }

    private PersistentSystemFontConfig.Config readPersistentConfig() {
        PersistentSystemFontConfig.Config config = new PersistentSystemFontConfig.Config();
        try (FileInputStream fis = mConfigFile.openRead()) {
            PersistentSystemFontConfig.loadFromXml(fis, config);
        } catch (IOException | XmlPullParserException e) {
            // The font config file is missing on the first boot. Just do nothing.
        }
        return config;
    }

    private void writePersistentConfig(PersistentSystemFontConfig.Config config)
            throws SystemFontException {
        FileOutputStream fos = null;
        try {
            fos = mConfigFile.startWrite();
            PersistentSystemFontConfig.writeToXml(fos, config);
            mConfigFile.finishWrite(fos);
        } catch (IOException e) {
            if (fos != null) {
                mConfigFile.failWrite(fos);
            }
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_FAILED_UPDATE_CONFIG,
                    "Failed to write config XML.", e);
        }
    }

    /* package */ int getConfigVersion() {
        return mConfigVersion;
    }

    public Map<String, FontConfig.FontFamily> getFontFamilyMap() {
        PersistentSystemFontConfig.Config curConfig = readPersistentConfig();
        Map<String, FontConfig.FontFamily> familyMap = new HashMap<>();
        for (int i = 0; i < curConfig.fontFamilies.size(); ++i) {
            FontUpdateRequest.Family family = curConfig.fontFamilies.get(i);
            FontConfig.FontFamily resolvedFamily = resolveFontFiles(family);
            if (resolvedFamily != null) {
                familyMap.put(family.getName(), resolvedFamily);
            }
        }
        return familyMap;
    }

    /* package */ static void deleteAllFiles(File filesDir, File configFile) {
        // As this method is called in safe mode, try to delete all files even though an exception
        // is thrown.
        try {
            new AtomicFile(configFile).delete();
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to delete " + configFile);
        }
        try {
            FileUtils.deleteContents(filesDir);
        } catch (Throwable t) {
            Slog.w(TAG, "Failed to delete " + filesDir);
        }
    }
}
