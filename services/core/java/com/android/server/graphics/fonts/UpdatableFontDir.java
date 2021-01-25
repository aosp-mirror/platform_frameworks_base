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

import android.annotation.Nullable;
import android.os.FileUtils;
import android.util.Base64;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class UpdatableFontDir {

    private static final String TAG = "UpdatableFontDir";
    private static final String RANDOM_DIR_PREFIX = "~~";
    // TODO: Support .otf
    private static final String ALLOWED_EXTENSION = ".ttf";

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
    /**
     * A mutable map containing mapping from font file name (e.g. "NotoColorEmoji.ttf") to {@link
     * FontFileInfo}. All files in this map are validated, and have higher revision numbers than
     * corresponding font files in {@link #mPreinstalledFontDirs}.
     */
    @GuardedBy("UpdatableFontDir.this")
    private final Map<String, FontFileInfo> mFontFileInfoMap = new HashMap<>();

    UpdatableFontDir(File filesDir, List<File> preinstalledFontDirs, FontFileParser parser,
            FsverityUtil fsverityUtil) {
        mFilesDir = filesDir;
        mPreinstalledFontDirs = preinstalledFontDirs;
        mParser = parser;
        mFsverityUtil = fsverityUtil;
        loadFontFileMap();
    }

    private void loadFontFileMap() {
        // TODO: SIGBUS crash protection
        synchronized (UpdatableFontDir.this) {
            boolean success = false;
            mFontFileInfoMap.clear();
            try {
                File[] dirs = mFilesDir.listFiles();
                if (dirs == null) return;
                for (File dir : dirs) {
                    if (!dir.getName().startsWith(RANDOM_DIR_PREFIX)) return;
                    File[] files = dir.listFiles();
                    if (files == null || files.length != 1) return;
                    FontFileInfo fontFileInfo = validateFontFile(files[0]);
                    if (fontFileInfo == null) {
                        Slog.w(TAG, "Broken file is found. Clearing files.");
                        return;
                    }
                    addFileToMapLocked(fontFileInfo, true /* deleteOldFile */);
                }
                success = true;
            } finally {
                // Delete all files just in case if we find a problematic file.
                if (!success) {
                    mFontFileInfoMap.clear();
                    FileUtils.deleteContents(mFilesDir);
                }
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
     */
    void installFontFile(FileDescriptor fd, byte[] pkcs7Signature) throws IOException {
        synchronized (UpdatableFontDir.this) {
            File newDir = getRandomDir(mFilesDir);
            if (!newDir.mkdir()) {
                // TODO: Define and return an error code for API
                throw new IOException("Failed to create a new dir");
            }
            boolean success = false;
            try {
                File tempNewFontFile = new File(newDir, "font.ttf");
                try (FileOutputStream out = new FileOutputStream(tempNewFontFile)) {
                    FileUtils.copy(fd, out.getFD());
                }
                // Do not parse font file before setting up fs-verity.
                // setUpFsverity throws IOException if failed.
                mFsverityUtil.setUpFsverity(tempNewFontFile.getAbsolutePath(), pkcs7Signature);
                String postScriptName = mParser.getPostScriptName(tempNewFontFile);
                File newFontFile = new File(newDir, postScriptName + ALLOWED_EXTENSION);
                if (!mFsverityUtil.rename(tempNewFontFile, newFontFile)) {
                    // TODO: Define and return an error code for API
                    throw new IOException("Failed to rename");
                }
                FontFileInfo fontFileInfo = validateFontFile(newFontFile);
                if (fontFileInfo == null) {
                    // TODO: Define and return an error code for API
                    throw new IllegalArgumentException("Invalid file");
                }
                if (!addFileToMapLocked(fontFileInfo, false)) {
                    // TODO: Define and return an error code for API
                    throw new IllegalArgumentException("Version downgrade");
                }
                success = true;
            } finally {
                if (!success) {
                    FileUtils.deleteContentsAndDir(newDir);
                }
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
    private boolean addFileToMapLocked(FontFileInfo fontFileInfo, boolean deleteOldFile) {
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
            return true;
        } else {
            if (deleteOldFile) {
                FileUtils.deleteContentsAndDir(fontFileInfo.getRandomizedFontDir());
            }
            return false;
        }
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
    @Nullable
    private FontFileInfo validateFontFile(File file) {
        if (!mFsverityUtil.hasFsverity(file.getAbsolutePath())) {
            Slog.w(TAG, "Font validation failed. Fs-verity is not enabled: " + file);
            return null;
        }
        if (!validateFontFileName(file)) {
            Slog.w(TAG, "Font validation failed. Could not validate font file name: " + file);
            return null;
        }
        long revision = getFontRevision(file);
        if (revision == -1) {
            Slog.w(TAG, "Font validation failed. Could not read font revision: " + file);
            return null;
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

    Map<String, File> getFontFileMap() {
        Map<String, File> map = new HashMap<>();
        synchronized (UpdatableFontDir.this) {
            for (Map.Entry<String, FontFileInfo> entry : mFontFileInfoMap.entrySet()) {
                map.put(entry.getKey(), entry.getValue().getFile());
            }
        }
        return map;
    }
}
