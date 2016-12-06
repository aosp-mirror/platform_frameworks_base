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

import android.util.ArrayMap;
import android.util.AtomicFile;
import android.util.Log;

import com.android.internal.util.FastPrintWriter;
import com.android.internal.util.IndentingPrintWriter;

import libcore.io.IoUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

/**
 * A class that collects, serializes and deserializes compiler-related statistics on a
 * per-package per-code-path basis.
 *
 * Currently used to track compile times.
 */
class CompilerStats extends AbstractStatsBase<Void> {

    private final static String COMPILER_STATS_VERSION_HEADER = "PACKAGE_MANAGER__COMPILER_STATS__";
    private final static int COMPILER_STATS_VERSION = 1;

    /**
     * Class to collect all stats pertaining to one package.
     */
    static class PackageStats {

        private final String packageName;

        /**
         * This map stores compile-times for all code paths in the package. The value
         * is in milliseconds.
         */
        private final Map<String, Long> compileTimePerCodePath;

        /**
         * @param packageName
         */
        public PackageStats(String packageName) {
            this.packageName = packageName;
            // We expect at least one element in here, but let's make it minimal.
            compileTimePerCodePath = new ArrayMap<>(2);
        }

        public String getPackageName() {
            return packageName;
        }

        /**
         * Return the recorded compile time for a given code path. Returns
         * 0 if there is no recorded time.
         */
        public long getCompileTime(String codePath) {
            String storagePath = getStoredPathFromCodePath(codePath);
            synchronized (compileTimePerCodePath) {
                Long l = compileTimePerCodePath.get(storagePath);
                if (l == null) {
                    return 0;
                }
                return l;
            }
        }

        public void setCompileTime(String codePath, long compileTimeInMs) {
            String storagePath = getStoredPathFromCodePath(codePath);
            synchronized (compileTimePerCodePath) {
                if (compileTimeInMs <= 0) {
                    compileTimePerCodePath.remove(storagePath);
                } else {
                    compileTimePerCodePath.put(storagePath, compileTimeInMs);
                }
            }
        }

        private static String getStoredPathFromCodePath(String codePath) {
            int lastSlash = codePath.lastIndexOf(File.separatorChar);
            return codePath.substring(lastSlash + 1);
        }

        public void dump(IndentingPrintWriter ipw) {
            synchronized (compileTimePerCodePath) {
                if (compileTimePerCodePath.size() == 0) {
                    ipw.println("(No recorded stats)");
                } else {
                    for (Map.Entry<String, Long> e : compileTimePerCodePath.entrySet()) {
                        ipw.println(" " + e.getKey() + " - " + e.getValue());
                    }
                }
            }
        }
    }

    private final Map<String, PackageStats> packageStats;

    public CompilerStats() {
        super("package-cstats.list", "CompilerStats_DiskWriter", /* lock */ false);
        packageStats = new HashMap<>();
    }

    public PackageStats getPackageStats(String packageName) {
        synchronized (packageStats) {
            return packageStats.get(packageName);
        }
    }

    public void setPackageStats(String packageName, PackageStats stats) {
        synchronized (packageStats) {
            packageStats.put(packageName, stats);
        }
    }

    public PackageStats createPackageStats(String packageName) {
        synchronized (packageStats) {
            PackageStats newStats = new PackageStats(packageName);
            packageStats.put(packageName, newStats);
            return newStats;
        }
    }

    public PackageStats getOrCreatePackageStats(String packageName) {
        synchronized (packageStats) {
            PackageStats existingStats = packageStats.get(packageName);
            if (existingStats != null) {
                return existingStats;
            }

            return createPackageStats(packageName);
        }
    }

    public void deletePackageStats(String packageName) {
        synchronized (packageStats) {
            packageStats.remove(packageName);
        }
    }

    // I/O

    // The encoding is simple:
    //
    // 1) The first line is a line consisting of the version header and the version number.
    //
    // 2) The rest of the file is package data.
    // 2.1) A package is started by any line not starting with "-";
    // 2.2) Any line starting with "-" is code path data. The format is:
    //      '-'{code-path}':'{compile-time}

    public void write(Writer out) {
        @SuppressWarnings("resource")
        FastPrintWriter fpw = new FastPrintWriter(out);

        fpw.print(COMPILER_STATS_VERSION_HEADER);
        fpw.println(COMPILER_STATS_VERSION);

        synchronized (packageStats) {
            for (PackageStats pkg : packageStats.values()) {
                synchronized (pkg.compileTimePerCodePath) {
                    if (!pkg.compileTimePerCodePath.isEmpty()) {
                        fpw.println(pkg.getPackageName());

                        for (Map.Entry<String, Long> e : pkg.compileTimePerCodePath.entrySet()) {
                            fpw.println("-" + e.getKey() + ":" + e.getValue());
                        }
                    }
                }
            }
        }

        fpw.flush();
    }

    public boolean read(Reader r) {
        synchronized (packageStats) {
            // TODO: Could make this a final switch, then we wouldn't have to synchronize over
            //       the whole reading.
            packageStats.clear();

            try {
                BufferedReader in = new BufferedReader(r);

                // Read header, do version check.
                String versionLine = in.readLine();
                if (versionLine == null) {
                    throw new IllegalArgumentException("No version line found.");
                } else {
                    if (!versionLine.startsWith(COMPILER_STATS_VERSION_HEADER)) {
                        throw new IllegalArgumentException("Invalid version line: " + versionLine);
                    }
                    int version = Integer.parseInt(
                            versionLine.substring(COMPILER_STATS_VERSION_HEADER.length()));
                    if (version != COMPILER_STATS_VERSION) {
                        // TODO: Upgrade older formats? For now, just reject and regenerate.
                        throw new IllegalArgumentException("Unexpected version: " + version);
                    }
                }

                // For simpler code, we ignore any data lines before the first package. We
                // collect it in a fake package.
                PackageStats currentPackage = new PackageStats("fake package");

                String s = null;
                while ((s = in.readLine()) != null) {
                    if (s.startsWith("-")) {
                        int colonIndex = s.indexOf(':');
                        if (colonIndex == -1 || colonIndex == 1) {
                            throw new IllegalArgumentException("Could not parse data " + s);
                        }
                        String codePath = s.substring(1, colonIndex);
                        long time = Long.parseLong(s.substring(colonIndex + 1));
                        currentPackage.setCompileTime(codePath, time);
                    } else {
                        currentPackage = getOrCreatePackageStats(s);
                    }
                }
            } catch (Exception e) {
                Log.e(PackageManagerService.TAG, "Error parsing compiler stats", e);
                return false;
            }

            return true;
        }
    }

    void writeNow() {
        writeNow(null);
    }

    boolean maybeWriteAsync() {
        return maybeWriteAsync(null);
    }

    @Override
    protected void writeInternal(Void data) {
        AtomicFile file = getFile();
        FileOutputStream f = null;

        try {
            f = file.startWrite();
            OutputStreamWriter osw = new OutputStreamWriter(f);
            write(osw);
            osw.flush();
            file.finishWrite(f);
        } catch (IOException e) {
            if (f != null) {
                file.failWrite(f);
            }
            Log.e(PackageManagerService.TAG, "Failed to write compiler stats", e);
        }
    }

    void read() {
        read((Void)null);
    }

    @Override
    protected void readInternal(Void data) {
        AtomicFile file = getFile();
        BufferedReader in = null;
        try {
            in = new BufferedReader(new InputStreamReader(file.openRead()));
            read(in);
        } catch (FileNotFoundException expected) {
        } finally {
            IoUtils.closeQuietly(in);
        }
    }
}
