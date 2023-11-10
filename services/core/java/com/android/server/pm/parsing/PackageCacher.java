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

package com.android.server.pm.parsing;

import android.annotation.NonNull;
import android.content.pm.PackageParserCacheHelper;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Parcel;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.ApexManager;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import libcore.io.IoUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public class PackageCacher {

    private static final String TAG = "PackageCacher";

    /**
     * Total number of packages that were read from the cache.  We use it only for logging.
     */
    public static final AtomicInteger sCachedPackageReadCount = new AtomicInteger();

    @NonNull
    private final File mCacheDir;

    public PackageCacher(@NonNull File cacheDir) {
        this.mCacheDir = cacheDir;
    }

    /**
     * Returns the cache key for a specified {@code packageFile} and {@code flags}.
     */
    private String getCacheKey(File packageFile, int flags) {
        StringBuilder sb = new StringBuilder(packageFile.getName());
        sb.append('-');
        sb.append(flags);
        sb.append('-');
        sb.append(packageFile.getAbsolutePath().hashCode());

        return sb.toString();
    }

    @VisibleForTesting
    protected ParsedPackage fromCacheEntry(byte[] bytes) {
        return fromCacheEntryStatic(bytes);
    }

    /** static version of {@link #fromCacheEntry} for unit tests. */
    @VisibleForTesting
    public static ParsedPackage fromCacheEntryStatic(byte[] bytes) {
        final Parcel p = Parcel.obtain();
        p.unmarshall(bytes, 0, bytes.length);
        p.setDataPosition(0);

        final PackageParserCacheHelper.ReadHelper helper =
                new PackageParserCacheHelper.ReadHelper(p);
        helper.startAndInstall();

        ParsedPackage pkg = new PackageImpl(p);

        p.recycle();

        sCachedPackageReadCount.incrementAndGet();

        return pkg;
    }

    @VisibleForTesting
    protected byte[] toCacheEntry(ParsedPackage pkg) {
        return toCacheEntryStatic(pkg);

    }

    /** static version of {@link #toCacheEntry} for unit tests. */
    @VisibleForTesting
    public static byte[] toCacheEntryStatic(ParsedPackage pkg) {
        final Parcel p = Parcel.obtain();
        final PackageParserCacheHelper.WriteHelper helper =
                new PackageParserCacheHelper.WriteHelper(p);

        ((PackageImpl) pkg).writeToParcel(p, 0 /* flags */);

        helper.finishAndUninstall();

        byte[] serialized = p.marshall();
        p.recycle();

        return serialized;
    }

    /**
     * Given a {@code packageFile} and a {@code cacheFile} returns whether the
     * cache file is up to date based on the mod-time of both files.
     */
    private static boolean isCacheUpToDate(File packageFile, File cacheFile) {
        try {
            // In case packageFile is located on one of /apex mount points it's mtime will always be
            // 0. Instead, we can use mtime of the APEX file backing the corresponding mount point.
            if (packageFile.toPath().startsWith(Environment.getApexDirectory().toPath())) {
                File backingApexFile = ApexManager.getInstance().getBackingApexFile(packageFile);
                if (backingApexFile == null) {
                    Slog.w(TAG,
                            "Failed to find APEX file backing " + packageFile.getAbsolutePath());
                } else {
                    packageFile = backingApexFile;
                }
            }
            // NOTE: We don't use the File.lastModified API because it has the very
            // non-ideal failure mode of returning 0 with no excepions thrown.
            // The nio2 Files API is a little better but is considerably more expensive.
            final StructStat pkg = Os.stat(packageFile.getAbsolutePath());
            final StructStat cache = Os.stat(cacheFile.getAbsolutePath());
            return pkg.st_mtime < cache.st_mtime;
        } catch (ErrnoException ee) {
            // The most common reason why stat fails is that a given cache file doesn't
            // exist. We ignore that here. It's easy to reason that it's safe to say the
            // cache isn't up to date if we see any sort of exception here.
            //
            // (1) Exception while stating the package file : This should never happen,
            // and if it does, we do a full package parse (which is likely to throw the
            // same exception).
            // (2) Exception while stating the cache file : If the file doesn't exist, the
            // cache is obviously out of date. If the file *does* exist, we can't read it.
            // We will attempt to delete and recreate it after parsing the package.
            if (ee.errno != OsConstants.ENOENT) {
                Slog.w("Error while stating package cache : ", ee);
            }

            return false;
        }
    }

    /**
     * Returns the cached parse result for {@code packageFile} for parse flags {@code flags},
     * or {@code null} if no cached result exists.
     */
    public ParsedPackage getCachedResult(File packageFile, int flags) {
        final String cacheKey = getCacheKey(packageFile, flags);
        final File cacheFile = new File(mCacheDir, cacheKey);

        try {
            // If the cache is not up to date, return null.
            if (!isCacheUpToDate(packageFile, cacheFile)) {
                return null;
            }

            final byte[] bytes = IoUtils.readFileAsByteArray(cacheFile.getAbsolutePath());
            ParsedPackage parsed = fromCacheEntry(bytes);
            if (!packageFile.getAbsolutePath().equals(parsed.getPath())) {
                // Don't use this cache if the path doesn't match
                return null;
            }
            return parsed;
        } catch (Throwable e) {
            Slog.w(TAG, "Error reading package cache: ", e);

            // If something went wrong while reading the cache entry, delete the cache file
            // so that we regenerate it the next time.
            cacheFile.delete();
            return null;
        }
    }

    /**
     * Caches the parse result for {@code packageFile} with flags {@code flags}.
     */
    public void cacheResult(File packageFile, int flags, ParsedPackage parsed) {
        try {
            final String cacheKey = getCacheKey(packageFile, flags);
            final File cacheFile = new File(mCacheDir, cacheKey);

            if (cacheFile.exists()) {
                if (!cacheFile.delete()) {
                    Slog.e(TAG, "Unable to delete cache file: " + cacheFile);
                }
            }

            final byte[] cacheEntry = toCacheEntry(parsed);

            if (cacheEntry == null) {
                return;
            }

            try (FileOutputStream fos = new FileOutputStream(cacheFile)) {
                fos.write(cacheEntry);
            } catch (IOException ioe) {
                Slog.w(TAG, "Error writing cache entry.", ioe);
                cacheFile.delete();
            }
        } catch (Throwable e) {
            Slog.w(TAG, "Error saving package cache.", e);
        }
    }

    /**
     * Delete the cache files for the given {@code packageFile}.
     */
    public void cleanCachedResult(@NonNull File packageFile) {
        final String packageName = packageFile.getName();
        final File[] files = FileUtils.listFilesOrEmpty(mCacheDir,
                (dir, name) -> name.startsWith(packageName));
        for (File file : files) {
            if (!file.delete()) {
                Slog.e(TAG, "Unable to clean cache file: " + file);
            }
        }
    }
}
