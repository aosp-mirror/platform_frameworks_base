/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.os.Process.PACKAGE_INFO_GID;
import static android.os.Process.SYSTEM_UID;

import android.content.pm.PackageManager;
import android.os.FileUtils;
import android.util.AtomicFile;
import android.util.Log;

import libcore.io.IoUtils;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

class PackageUsage extends AbstractStatsBase<Map<String, PackageSetting>> {

    private static final String USAGE_FILE_MAGIC = "PACKAGE_USAGE__VERSION_";
    private static final String USAGE_FILE_MAGIC_VERSION_1 = USAGE_FILE_MAGIC + "1";

    private boolean mIsHistoricalPackageUsageAvailable = true;

    PackageUsage() {
        super("package-usage.list", "PackageUsage_DiskWriter", /* lock */ true);
    }

    boolean isHistoricalPackageUsageAvailable() {
        return mIsHistoricalPackageUsageAvailable;
    }

    @Override
    protected void writeInternal(Map<String, PackageSetting> pkgSettings) {
        AtomicFile file = getFile();
        FileOutputStream f = null;
        try {
            f = file.startWrite();
            BufferedOutputStream out = new BufferedOutputStream(f);
            FileUtils.setPermissions(file.getBaseFile().getPath(),
                    0640, SYSTEM_UID, PACKAGE_INFO_GID);
            StringBuilder sb = new StringBuilder();

            sb.append(USAGE_FILE_MAGIC_VERSION_1);
            sb.append('\n');
            out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));

            for (PackageSetting pkgSetting : pkgSettings.values()) {
                if (pkgSetting.getPkgState().getLatestPackageUseTimeInMills() == 0L) {
                    continue;
                }
                sb.setLength(0);
                sb.append(pkgSetting.getPackageName());
                for (long usageTimeInMillis : pkgSetting.getPkgState()
                        .getLastPackageUsageTimeInMills()) {
                    sb.append(' ');
                    sb.append(usageTimeInMillis);
                }
                sb.append('\n');
                out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
            }
            out.flush();
            file.finishWrite(f);
        } catch (IOException e) {
            if (f != null) {
                file.failWrite(f);
            }
            Log.e(PackageManagerService.TAG, "Failed to write package usage times", e);
        }
    }

    @Override
    protected void readInternal(Map<String, PackageSetting> pkgSettings) {
        AtomicFile file = getFile();
        BufferedInputStream in = null;
        try {
            in = new BufferedInputStream(file.openRead());
            StringBuilder sb = new StringBuilder();

            String firstLine = readLine(in, sb);
            if (firstLine == null) {
                // Empty file. Do nothing.
            } else if (USAGE_FILE_MAGIC_VERSION_1.equals(firstLine)) {
                readVersion1LP(pkgSettings, in, sb);
            } else {
                readVersion0LP(pkgSettings, in, sb, firstLine);
            }
        } catch (FileNotFoundException expected) {
            mIsHistoricalPackageUsageAvailable = false;
        } catch (IOException e) {
            Log.w(PackageManagerService.TAG, "Failed to read package usage times", e);
        } finally {
            IoUtils.closeQuietly(in);
        }
    }

    private void readVersion0LP(Map<String, PackageSetting> pkgSettings, InputStream in,
            StringBuilder sb, String firstLine)
            throws IOException {
        // Initial version of the file had no version number and stored one
        // package-timestamp pair per line.
        // Note that the first line has already been read from the InputStream.
        for (String line = firstLine; line != null; line = readLine(in, sb)) {
            String[] tokens = line.split(" ");
            if (tokens.length != 2) {
                throw new IOException("Failed to parse " + line +
                        " as package-timestamp pair.");
            }

            String packageName = tokens[0];
            PackageSetting pkgSetting = pkgSettings.get(packageName);
            if (pkgSetting == null) {
                continue;
            }

            long timestamp = parseAsLong(tokens[1]);
            for (int reason = 0;
                    reason < PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT;
                    reason++) {
                pkgSetting.getPkgState().setLastPackageUsageTimeInMills(reason, timestamp);
            }
        }
    }

    private void readVersion1LP(Map<String, PackageSetting> pkgSettings, InputStream in,
            StringBuilder sb) throws IOException {
        // Version 1 of the file started with the corresponding version
        // number and then stored a package name and eight timestamps per line.
        String line;
        while ((line = readLine(in, sb)) != null) {
            String[] tokens = line.split(" ");
            if (tokens.length != PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT + 1) {
                throw new IOException("Failed to parse " + line + " as a timestamp array.");
            }

            String packageName = tokens[0];
            PackageSetting pkgSetting = pkgSettings.get(packageName);
            if (pkgSetting == null) {
                continue;
            }

            for (int reason = 0;
                    reason < PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT;
                    reason++) {
                pkgSetting.getPkgState().setLastPackageUsageTimeInMills(reason,
                        parseAsLong(tokens[reason + 1]));
            }
        }
    }

    private long parseAsLong(String token) throws IOException {
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException e) {
            throw new IOException("Failed to parse " + token + " as a long.", e);
        }
    }

    private String readLine(InputStream in, StringBuilder sb) throws IOException {
        return readToken(in, sb, '\n');
    }

    private String readToken(InputStream in, StringBuilder sb, char endOfToken)
            throws IOException {
        sb.setLength(0);
        while (true) {
            int ch = in.read();
            if (ch == -1) {
                if (sb.length() == 0) {
                    return null;
                }
                throw new IOException("Unexpected EOF");
            }
            if (ch == endOfToken) {
                return sb.toString();
            }
            sb.append((char)ch);
        }
    }
}
