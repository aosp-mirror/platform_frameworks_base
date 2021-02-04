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
import android.system.ErrnoException;
import android.system.Os;
import android.text.FontConfig;
import android.util.Base64;
import android.util.Slog;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages set of updatable font files.
 *
 * <p>This class is not thread safe.
 */
final class UpdatableFontDir {

    private static final String TAG = "UpdatableFontDir";
    private static final String RANDOM_DIR_PREFIX = "~~";
    // TODO: Support .otf
    private static final String ALLOWED_EXTENSION = ".ttf";

    private static final String CONFIG_XML_FILE = "/data/fonts/config/config.xml";

    /** Interface to mock font file access in tests. */
    interface FontFileParser {
        String getPostScriptName(File file) throws IOException;

        long getRevision(File file) throws IOException;
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
        private final long mRevision;

        FontFileInfo(File file, long revision) {
            mFile = file;
            mRevision = revision;
        }

        public File getFile() {
            return mFile;
        }

        /** Returns the unique randomized font dir containing this font file. */
        public File getRandomizedFontDir() {
            return mFile.getParentFile();
        }

        public long getRevision() {
            return mRevision;
        }
    }

    /**
     * Root directory for storing updated font files. Each font file is stored in a unique
     * randomized dir. The font file path would be {@code mFilesDir/~~{randomStr}/{fontFileName}}.
     */
    private final File mFilesDir;
    private final List<File> mPreinstalledFontDirs;
    private final FontFileParser mParser;
    private final FsverityUtil mFsverityUtil;
    private final File mConfigFile;
    private final File mTmpConfigFile;

    private long mLastModifiedDate;
    private int mConfigVersion = 1;

    /**
     * A mutable map containing mapping from font file name (e.g. "NotoColorEmoji.ttf") to {@link
     * FontFileInfo}. All files in this map are validated, and have higher revision numbers than
     * corresponding font files in {@link #mPreinstalledFontDirs}.
     */
    private final Map<String, FontFileInfo> mFontFileInfoMap = new HashMap<>();

    UpdatableFontDir(File filesDir, List<File> preinstalledFontDirs, FontFileParser parser,
            FsverityUtil fsverityUtil) {
        this(filesDir, preinstalledFontDirs, parser, fsverityUtil, new File(CONFIG_XML_FILE));
    }

    // For unit testing
    UpdatableFontDir(File filesDir, List<File> preinstalledFontDirs, FontFileParser parser,
            FsverityUtil fsverityUtil, File configFile) {
        mFilesDir = filesDir;
        mPreinstalledFontDirs = preinstalledFontDirs;
        mParser = parser;
        mFsverityUtil = fsverityUtil;
        mConfigFile = configFile;
        mTmpConfigFile = new File(configFile.getAbsoluteFile() + ".tmp");
    }

    /* package */ void loadFontFileMap() {
        mFontFileInfoMap.clear();
        mLastModifiedDate = 0;
        boolean success = false;
        try {
            PersistentSystemFontConfig.Config config = new PersistentSystemFontConfig.Config();
            try (FileInputStream fis = new FileInputStream(mConfigFile)) {
                PersistentSystemFontConfig.loadFromXml(fis, config);
            } catch (IOException | XmlPullParserException e) {
                Slog.e(TAG, "Failed to load config xml file", e);
                return;
            }
            mLastModifiedDate = config.lastModifiedDate;

            File[] dirs = mFilesDir.listFiles();
            if (dirs == null) return;
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
                addFileToMapIfNewer(fontFileInfo, true /* deleteOldFile */);
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
                mLastModifiedDate = 0;
                FileUtils.deleteContents(mFilesDir);
            }
        }
    }

    /* package */ void clearUpdates() throws SystemFontException {
        mFontFileInfoMap.clear();
        FileUtils.deleteContents(mFilesDir);

        mLastModifiedDate = Instant.now().getEpochSecond();
        try (FileOutputStream fos = new FileOutputStream(mConfigFile)) {
            PersistentSystemFontConfig.writeToXml(fos, getPersistentConfig());
        } catch (Exception e) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_FAILED_UPDATE_CONFIG,
                    "Failed to write config XML.", e);
        }
        mConfigVersion++;
    }

    /**
     * Applies multiple {@link FontUpdateRequest}s in transaction.
     * If one of the request fails, the fonts and config are rolled back to the previous state
     * before this method is called.
     */
    public void update(List<FontUpdateRequest> requests) throws SystemFontException {
        // Backup the mapping for rollback.
        HashMap<String, FontFileInfo> backupMap = new HashMap<>(mFontFileInfoMap);
        long backupLastModifiedDate = mLastModifiedDate;
        boolean success = false;
        try {
            for (FontUpdateRequest request : requests) {
                installFontFile(request.getFd().getFileDescriptor(), request.getSignature());
            }

            // Write config file.
            mLastModifiedDate = Instant.now().getEpochSecond();
            try (FileOutputStream fos = new FileOutputStream(mTmpConfigFile)) {
                PersistentSystemFontConfig.writeToXml(fos, getPersistentConfig());
            } catch (Exception e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_UPDATE_CONFIG,
                        "Failed to write config XML.", e);
            }

            if (!mFsverityUtil.rename(mTmpConfigFile, mConfigFile)) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_FAILED_UPDATE_CONFIG,
                        "Failed to stage the config file.");
            }
            mConfigVersion++;
            success = true;
        } finally {
            if (!success) {
                mFontFileInfoMap.clear();
                mFontFileInfoMap.putAll(backupMap);
                mLastModifiedDate = backupLastModifiedDate;
            }
        }
    }

    /**
     * Installs a new font file, or updates an existing font file.
     *
     * <p>The new font will be immediately available for new Zygote-forked processes through
     * {@link #getFontFileMap()}. Old font files will be kept until next system server reboot,
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
            String postScriptName;
            try {
                postScriptName = mParser.getPostScriptName(tempNewFontFile);
            } catch (IOException e) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                        "Failed to read PostScript name from font file", e);
            }
            if (postScriptName == null) {
                throw new SystemFontException(
                        FontManager.RESULT_ERROR_INVALID_FONT_NAME,
                        "Failed to read PostScript name from font file");
            }
            File newFontFile = new File(newDir, postScriptName + ALLOWED_EXTENSION);
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
            if (!addFileToMapIfNewer(fontFileInfo, false)) {
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

    /**
     * Add the given {@link FontFileInfo} to {@link #mFontFileInfoMap} if its font revision is
     * higher than the currently used font file (either in {@link #mFontFileInfoMap} or {@link
     * #mPreinstalledFontDirs}).
     */
    private boolean addFileToMapIfNewer(FontFileInfo fontFileInfo, boolean deleteOldFile) {
        String name = fontFileInfo.getFile().getName();
        FontFileInfo existingInfo = mFontFileInfoMap.get(name);
        final boolean shouldAddToMap;
        if (existingInfo == null) {
            // We got a new updatable font. We need to check if it's newer than preinstalled fonts.
            // Note that getPreinstalledFontRevision() returns -1 if there is no preinstalled font
            // with 'name'.
            shouldAddToMap = getPreinstalledFontRevision(name) < fontFileInfo.getRevision();
        } else {
            shouldAddToMap = existingInfo.getRevision() < fontFileInfo.getRevision();
        }
        if (shouldAddToMap) {
            if (deleteOldFile && existingInfo != null) {
                FileUtils.deleteContentsAndDir(existingInfo.getRandomizedFontDir());
            }
            mFontFileInfoMap.put(name, fontFileInfo);
        } else {
            if (deleteOldFile) {
                FileUtils.deleteContentsAndDir(fontFileInfo.getRandomizedFontDir());
            }
        }
        return shouldAddToMap;
    }

    private long getPreinstalledFontRevision(String name) {
        long maxRevision = -1;
        for (File dir : mPreinstalledFontDirs) {
            File preinstalledFontFile = new File(dir, name);
            if (!preinstalledFontFile.exists()) continue;
            long revision = getFontRevision(preinstalledFontFile);
            if (revision == -1) {
                Slog.w(TAG, "Invalid preinstalled font file");
                continue;
            }
            if (revision > maxRevision) {
                maxRevision = revision;
            }
        }
        return maxRevision;
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
        if (!validateFontFileName(file)) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_INVALID_FONT_NAME,
                    "Font validation failed. Could not validate font file name: " + file);
        }
        long revision = getFontRevision(file);
        if (revision == -1) {
            throw new SystemFontException(
                    FontManager.RESULT_ERROR_INVALID_FONT_FILE,
                    "Font validation failed. Could not read font revision: " + file);
        }
        return new FontFileInfo(file, revision);
    }

    /**
     * Returns true if the font file's file name matches with the PostScript name metadata in the
     * font file.
     *
     * <p>We check the font file names because apps use file name to look up fonts.
     * <p>Because PostScript name does not include extension, the extension is appended for
     * comparison. For example, if the file name is "NotoColorEmoji.ttf", the PostScript name should
     * be "NotoColorEmoji".
     */
    private boolean validateFontFileName(File file) {
        String fileName = file.getName();
        String postScriptName = getPostScriptName(file);
        return (postScriptName + ALLOWED_EXTENSION).equals(fileName);
    }

    /** Returns the PostScript name of the given font file, or null. */
    @Nullable
    private String getPostScriptName(File file) {
        try {
            return mParser.getPostScriptName(file);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read font file", e);
            return null;
        }
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

    private PersistentSystemFontConfig.Config getPersistentConfig() {
        PersistentSystemFontConfig.Config config = new PersistentSystemFontConfig.Config();
        config.lastModifiedDate = mLastModifiedDate;
        for (FontFileInfo info : mFontFileInfoMap.values()) {
            config.updatedFontDirs.add(info.getRandomizedFontDir().getName());
        }
        return config;
    }

    Map<String, File> getFontFileMap() {
        Map<String, File> map = new HashMap<>();
        for (Map.Entry<String, FontFileInfo> entry : mFontFileInfoMap.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getFile());
        }
        return map;
    }

    /* package */ FontConfig getSystemFontConfig() {
        return SystemFonts.getSystemFontConfig(getFontFileMap(), mLastModifiedDate, mConfigVersion);
    }

    /* package */ int getConfigVersion() {
        return mConfigVersion;
    }
}
