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
package com.android.server.pm;

import android.util.Slog;

import com.android.internal.util.ArrayUtils;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Consumer;

public class ShortcutDumpFiles {
    private static final String TAG = ShortcutService.TAG;
    private static final boolean DEBUG = ShortcutService.DEBUG;
    private final ShortcutService mService;

    public ShortcutDumpFiles(ShortcutService service) {
        mService = service;
    }

    public boolean save(String filename, Consumer<PrintWriter> dumper) {
        try {
            final File directory = mService.getDumpPath();
            directory.mkdirs();
            if (!directory.exists()) {
                Slog.e(TAG, "Failed to create directory: " + directory);
                return false;
            }

            final File path = new File(directory, filename);

            if (DEBUG) {
                Slog.d(TAG, "Dumping to " + path);
            }

            try (PrintWriter pw = new PrintWriter(new BufferedOutputStream(
                    new FileOutputStream(path)))) {
                dumper.accept(pw);
            }
            return true;
        } catch (RuntimeException|IOException e) {
            Slog.w(TAG, "Failed to create dump file: " + filename, e);
            return false;
        }
    }

    public boolean save(String filename, byte[] utf8bytes) {
        return save(filename, pw -> pw.println(StandardCharsets.UTF_8.decode(
                ByteBuffer.wrap(utf8bytes)).toString()));
    }

    public void dumpAll(PrintWriter pw) {
        try {
            final File directory = mService.getDumpPath();
            final File[] files = directory.listFiles(f -> f.isFile());
            if (!directory.exists() || ArrayUtils.isEmpty(files)) {
                pw.print("  No dump files found.");
                return;
            }
            Arrays.sort(files, Comparator.comparing(f -> f.getName()));

            for (File path : files) {
                pw.print("*** Dumping: ");
                pw.println(path.getName());

                pw.print("mtime: ");
                pw.println(ShortcutService.formatTime(path.lastModified()));

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new FileInputStream(path)))) {
                    String line = null;
                    while ((line = reader.readLine()) != null) {
                        pw.println(line);
                    }
                }
            }
        } catch (RuntimeException|IOException e) {
            Slog.w(TAG, "Failed to print dump files", e);
        }
    }
}
