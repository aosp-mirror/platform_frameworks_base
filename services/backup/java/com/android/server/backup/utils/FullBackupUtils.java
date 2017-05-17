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
 * limitations under the License
 */

package com.android.server.backup.utils;

import static com.android.server.backup.RefactoredBackupManagerService.BACKUP_MANIFEST_VERSION;
import static com.android.server.backup.RefactoredBackupManagerService.TAG;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Slog;
import android.util.StringBuilderPrinter;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Low-level utility methods for full backup.
 */
public class FullBackupUtils {
    /**
     * Reads data from pipe and writes it to the stream in chunks of up to 32KB.
     *
     * @param inPipe - pipe to read the data from.
     * @param out - stream to write the data to.
     * @throws IOException - in case of an error.
     */
    public static void routeSocketDataToOutput(ParcelFileDescriptor inPipe, OutputStream out)
            throws IOException {
        // We do not take close() responsibility for the pipe FD
        FileInputStream raw = new FileInputStream(inPipe.getFileDescriptor());
        DataInputStream in = new DataInputStream(raw);

        byte[] buffer = new byte[32 * 1024];
        int chunkTotal;
        while ((chunkTotal = in.readInt()) > 0) {
            while (chunkTotal > 0) {
                int toRead = (chunkTotal > buffer.length) ? buffer.length : chunkTotal;
                int nRead = in.read(buffer, 0, toRead);
                if (nRead < 0) {
                    Slog.e(TAG, "Unexpectedly reached end of file while reading data");
                    throw new EOFException();
                }
                out.write(buffer, 0, nRead);
                chunkTotal -= nRead;
            }
        }
    }

    /**
     * Writes app manifest to the given manifest file.
     *
     * @param pkg - app package, which manifest to write.
     * @param packageManager - {@link PackageManager} instance.
     * @param manifestFile - target manifest file.
     * @param withApk - whether include apk or not.
     * @param withWidgets - whether to write widgets data.
     * @throws IOException - in case of an error.
     */
    // TODO: withWidgets is not used, decide whether it is needed.
    public static void writeAppManifest(PackageInfo pkg, PackageManager packageManager,
            File manifestFile, boolean withApk, boolean withWidgets) throws IOException {
        // Manifest format. All data are strings ending in LF:
        //     BACKUP_MANIFEST_VERSION, currently 1
        //
        // Version 1:
        //     package name
        //     package's versionCode
        //     platform versionCode
        //     getInstallerPackageName() for this package (maybe empty)
        //     boolean: "1" if archive includes .apk; any other string means not
        //     number of signatures == N
        // N*:    signature byte array in ascii format per Signature.toCharsString()
        StringBuilder builder = new StringBuilder(4096);
        StringBuilderPrinter printer = new StringBuilderPrinter(builder);

        printer.println(Integer.toString(BACKUP_MANIFEST_VERSION));
        printer.println(pkg.packageName);
        printer.println(Integer.toString(pkg.versionCode));
        printer.println(Integer.toString(Build.VERSION.SDK_INT));

        String installerName = packageManager.getInstallerPackageName(pkg.packageName);
        printer.println((installerName != null) ? installerName : "");

        printer.println(withApk ? "1" : "0");
        if (pkg.signatures == null) {
            printer.println("0");
        } else {
            printer.println(Integer.toString(pkg.signatures.length));
            for (Signature sig : pkg.signatures) {
                printer.println(sig.toCharsString());
            }
        }

        FileOutputStream outstream = new FileOutputStream(manifestFile);
        outstream.write(builder.toString().getBytes());
        outstream.close();

        // We want the manifest block in the archive stream to be idempotent:
        // each time we generate a backup stream for the app, we want the manifest
        // block to be identical.  The underlying tar mechanism sees it as a file,
        // though, and will propagate its mtime, causing the tar header to vary.
        // Avoid this problem by pinning the mtime to zero.
        manifestFile.setLastModified(0);
    }
}
