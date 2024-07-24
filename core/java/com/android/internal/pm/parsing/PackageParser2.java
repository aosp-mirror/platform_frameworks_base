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

package com.android.internal.pm.parsing;

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.content.pm.parsing.PackageLite;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.SystemClock;
import android.permission.PermissionManager;
import android.util.DisplayMetrics;
import android.util.Slog;

import com.android.internal.pm.parsing.pkg.PackageImpl;
import com.android.internal.pm.parsing.pkg.ParsedPackage;
import com.android.internal.pm.pkg.parsing.ParsingPackage;
import com.android.internal.pm.pkg.parsing.ParsingPackageUtils;
import com.android.internal.pm.pkg.parsing.ParsingUtils;
import com.android.internal.util.ArrayUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The v2 of package parsing for use when parsing is initiated in the server and must
 * contain state contained by the server.
 *
 * The {@link AutoCloseable} helps signal that this class contains resources that must be freed.
 * Although it is sufficient to release references to an instance of this class and let it get
 * collected automatically.
 */
public class PackageParser2 implements AutoCloseable {

    private static final String TAG = ParsingUtils.TAG;

    private static final boolean LOG_PARSE_TIMINGS = Build.IS_DEBUGGABLE;
    private static final int LOG_PARSE_TIMINGS_THRESHOLD_MS = 100;

    private final ThreadLocal<ApplicationInfo> mSharedAppInfo =
            ThreadLocal.withInitial(() -> {
                ApplicationInfo appInfo = new ApplicationInfo();
                appInfo.uid = -1; // Not a valid UID since the app will not be installed yet
                return appInfo;
            });

    private final ThreadLocal<ParseTypeImpl> mSharedResult;

    @Nullable
    protected IPackageCacher mCacher;

    private final ParsingPackageUtils mParsingUtils;

    public PackageParser2(String[] separateProcesses, DisplayMetrics displayMetrics,
            @Nullable IPackageCacher cacher, @NonNull Callback callback) {
        if (displayMetrics == null) {
            displayMetrics = new DisplayMetrics();
            displayMetrics.setToDefaults();
        }

        List<PermissionManager.SplitPermissionInfo> splitPermissions = null;

        final Application application = ActivityThread.currentApplication();
        if (application != null) {
            final PermissionManager permissionManager =
                    application.getSystemService(PermissionManager.class);
            if (permissionManager != null) {
                splitPermissions = permissionManager.getSplitPermissions();
            }
        }
        if (splitPermissions == null) {
            splitPermissions = new ArrayList<>();
        }

        mCacher = cacher;

        mParsingUtils = new ParsingPackageUtils(separateProcesses, displayMetrics, splitPermissions,
                callback);

        ParseInput.Callback enforcementCallback = (changeId, packageName, targetSdkVersion) -> {
            ApplicationInfo appInfo = mSharedAppInfo.get();
            //noinspection ConstantConditions
            appInfo.packageName = packageName;
            appInfo.targetSdkVersion = targetSdkVersion;
            return callback.isChangeEnabled(changeId, appInfo);
        };

        mSharedResult = ThreadLocal.withInitial(() -> new ParseTypeImpl(enforcementCallback));
    }

    /**
     * TODO(b/135203078): Document new package parsing
     */
    @AnyThread
    public ParsedPackage parsePackage(File packageFile, int flags, boolean useCaches)
            throws PackageParserException {
        var files = packageFile.listFiles();
        // Apk directory is directly nested under the current directory
        if (ArrayUtils.size(files) == 1 && files[0].isDirectory()) {
            packageFile = files[0];
        }

        if (useCaches && mCacher != null) {
            ParsedPackage parsed = mCacher.getCachedResult(packageFile, flags);
            if (parsed != null) {
                return parsed;
            }
        }

        long parseTime = LOG_PARSE_TIMINGS ? SystemClock.uptimeMillis() : 0;
        ParseInput input = mSharedResult.get().reset();
        ParseResult<ParsingPackage> result = mParsingUtils.parsePackage(input, packageFile, flags);
        if (result.isError()) {
            throw new PackageParserException(result.getErrorCode(), result.getErrorMessage(),
                    result.getException());
        }

        ParsedPackage parsed = (ParsedPackage) result.getResult().hideAsParsed();

        long cacheTime = LOG_PARSE_TIMINGS ? SystemClock.uptimeMillis() : 0;
        if (mCacher != null) {
            mCacher.cacheResult(packageFile, flags, parsed);
        }
        if (LOG_PARSE_TIMINGS) {
            parseTime = cacheTime - parseTime;
            cacheTime = SystemClock.uptimeMillis() - cacheTime;
            if (parseTime + cacheTime > LOG_PARSE_TIMINGS_THRESHOLD_MS) {
                Slog.i(TAG, "Parse times for '" + packageFile + "': parse=" + parseTime
                        + "ms, update_cache=" + cacheTime + " ms");
            }
        }

        return parsed;
    }

    /**
     * Creates a ParsedPackage from PackageLite without any additional parsing or processing.
     * Most fields will get reasonable default values, corresponding to "deleted-keep-data".
     */
    @AnyThread
    public ParsedPackage parsePackageFromPackageLite(PackageLite packageLite, int flags)
            throws PackageParserException {
        ParseInput input = mSharedResult.get().reset();
        ParseResult<ParsingPackage> result = mParsingUtils.parsePackageFromPackageLite(input,
                packageLite, flags);
        if (result.isError()) {
            throw new PackageParserException(result.getErrorCode(), result.getErrorMessage(),
                    result.getException());
        }
        return result.getResult().hideAsParsed();
    }

    /**
     * Removes the cached value for the thread the parser was created on. It is assumed that
     * any threads created for parallel parsing will be created and released, so they don't
     * need an explicit close call.
     *
     * Realistically an instance should never be retained, so when the enclosing class is released,
     * the values will also be released, making this method unnecessary.
     */
    @Override
    public void close() {
        mSharedResult.remove();
        mSharedAppInfo.remove();
    }

    public abstract static class Callback implements ParsingPackageUtils.Callback {

        @Override
        public final ParsingPackage startParsingPackage(@NonNull String packageName,
                @NonNull String baseCodePath, @NonNull String codePath,
                @NonNull TypedArray manifestArray, boolean isCoreApp) {
            return PackageImpl.forParsing(packageName, baseCodePath, codePath, manifestArray,
                    isCoreApp, Callback.this);
        }

        /**
         * An indirection from {@link ParseInput.Callback#isChangeEnabled(long, String, int)},
         * allowing the {@link ApplicationInfo} objects to be cached in {@link #mSharedAppInfo}
         * and cleaned up with the parser instance, not the callback instance.
         *
         * @param appInfo will only have 3 fields filled in, {@link ApplicationInfo#packageName},
         * {@link ApplicationInfo#targetSdkVersion}, and {@link ApplicationInfo#uid}
         */
        public abstract boolean isChangeEnabled(long changeId, @NonNull ApplicationInfo appInfo);
    }
}
