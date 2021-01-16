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
import java.util.Map;

final class UpdatableFontDir {

    private static final String TAG = "UpdatableFontDir";
    private static final String RANDOM_DIR_PREFIX = "~~";

    /** Interface to mock font file access in tests. */
    interface FontFileParser {
        long getVersion(File file) throws IOException;
    }

    /** Data class to hold font file path and version. */
    static final class FontFileInfo {
        final File mFile;
        final long mVersion;

        FontFileInfo(File file, long version) {
            mFile = file;
            mVersion = version;
        }
    }

    /**
     * Root directory for storing updated font files. Each font file is stored in a unique random
     * dir. The font file path would be {@code mFilesDir/~~{randomStr}/{fontFileName}}.
     */
    private final File mFilesDir;
    private final FontFileParser mParser;
    @GuardedBy("UpdatableFontDir.this")
    private final Map<String, FontFileInfo> mFontFileInfoMap = new HashMap<>();

    UpdatableFontDir(File filesDir, FontFileParser parser) {
        mFilesDir = filesDir;
        mParser = parser;
        loadFontFileMap();
    }

    private void loadFontFileMap() {
        synchronized (UpdatableFontDir.this) {
            mFontFileInfoMap.clear();
            File[] dirs = mFilesDir.listFiles();
            if (dirs == null) return;
            for (File dir : dirs) {
                if (!dir.getName().startsWith(RANDOM_DIR_PREFIX)) continue;
                File[] files = dir.listFiles();
                if (files == null || files.length != 1) continue;
                addFileToMapLocked(files[0], true);
            }
        }
    }

    void installFontFile(String name, FileDescriptor fd) throws IOException {
        // TODO: Validate name.
        synchronized (UpdatableFontDir.this) {
            // TODO: proper error handling
            File newDir = getRandomDir(mFilesDir);
            if (!newDir.mkdir()) {
                throw new IOException("Failed to create a new dir");
            }
            File newFontFile = new File(newDir, name);
            try (FileOutputStream out = new FileOutputStream(newFontFile)) {
                FileUtils.copy(fd, out.getFD());
            }
            addFileToMapLocked(newFontFile, false);
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

    private void addFileToMapLocked(File file, boolean deleteOldFile) {
        final long version;
        try {
            version = mParser.getVersion(file);
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read font file", e);
            return;
        }
        if (version == -1) {
            Slog.e(TAG, "Invalid font file");
            return;
        }
        FontFileInfo info = mFontFileInfoMap.get(file.getName());
        if (info == null) {
            // TODO: check version of font in /system/fonts and /product/fonts
            mFontFileInfoMap.put(file.getName(), new FontFileInfo(file, version));
        } else if (info.mVersion < version) {
            if (deleteOldFile) {
                FileUtils.deleteContentsAndDir(info.mFile.getParentFile());
            }
            mFontFileInfoMap.put(file.getName(), new FontFileInfo(file, version));
        }
    }

    Map<String, File> getFontFileMap() {
        Map<String, File> map = new HashMap<>();
        synchronized (UpdatableFontDir.this) {
            for (Map.Entry<String, FontFileInfo> entry : mFontFileInfoMap.entrySet()) {
                map.put(entry.getKey(), entry.getValue().mFile);
            }
        }
        return map;
    }
}
