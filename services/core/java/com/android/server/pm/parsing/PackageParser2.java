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

import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.parsing.ParsingPackage;
import android.content.pm.parsing.ParsingPackageUtils;
import android.content.pm.parsing.ParsingUtils;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Slog;

import com.android.internal.compat.IPlatformCompat;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import java.io.File;

/**
 * The v2 of {@link PackageParser} for use when parsing is initiated in the server and must
 * contain state contained by the server.
 *
 * The {@link AutoCloseable} helps signal that this class contains resources that must be freed.
 * Although it is sufficient to release references to an instance of this class and let it get
 * collected automatically.
 */
public class PackageParser2 implements AutoCloseable {

    /**
     * For parsing inside the system server but outside of {@link PackageManagerService}.
     * Generally used for parsing information in an APK that hasn't been installed yet.
     *
     * This must be called inside the system process as it relies on {@link ServiceManager}.
     */
    @NonNull
    public static PackageParser2 forParsingFileWithDefaults() {
        IPlatformCompat platformCompat = IPlatformCompat.Stub.asInterface(
                ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
        return new PackageParser2(null /* separateProcesses */, false /* onlyCoreApps */,
                null /* displayMetrics */, null /* cacheDir */, new Callback() {
            @Override
            public boolean isChangeEnabled(long changeId, @NonNull ApplicationInfo appInfo) {
                try {
                    return platformCompat.isChangeEnabled(changeId, appInfo);
                } catch (Exception e) {
                    // This shouldn't happen, but assume enforcement if it does
                    Slog.wtf(ParsingUtils.TAG, "IPlatformCompat query failed", e);
                    return true;
                }
            }

            @Override
            public boolean hasFeature(String feature) {
                // Assume the device doesn't support anything. This will affect permission parsing
                // and will force <uses-permission/> declarations to include all requiredNotFeature
                // permissions and exclude all requiredFeature permissions. This mirrors the old
                // behavior.
                return false;
            }
        });
    }

    static final String TAG = "PackageParser2";

    private static final boolean LOG_PARSE_TIMINGS = Build.IS_DEBUGGABLE;
    private static final int LOG_PARSE_TIMINGS_THRESHOLD_MS = 100;

    private ThreadLocal<ApplicationInfo> mSharedAppInfo =
            ThreadLocal.withInitial(() -> {
                ApplicationInfo appInfo = new ApplicationInfo();
                appInfo.uid = -1; // Not a valid UID since the app will not be installed yet
                return appInfo;
            });

    private ThreadLocal<ParseTypeImpl> mSharedResult;

    @Nullable
    protected PackageCacher mCacher;

    private ParsingPackageUtils parsingUtils;

    /**
     * @param onlyCoreApps Flag indicating this parser should only consider apps with
     *                     {@code coreApp} manifest attribute to be valid apps. This is useful when
     *                     creating a minimalist boot environment.
     */
    public PackageParser2(String[] separateProcesses, boolean onlyCoreApps,
            DisplayMetrics displayMetrics, @Nullable File cacheDir, @NonNull Callback callback) {
        if (displayMetrics == null) {
            displayMetrics = new DisplayMetrics();
            displayMetrics.setToDefaults();
        }

        mCacher = cacheDir == null ? null : new PackageCacher(cacheDir);

        parsingUtils = new ParsingPackageUtils(onlyCoreApps, separateProcesses, displayMetrics,
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
        if (useCaches && mCacher != null) {
            ParsedPackage parsed = mCacher.getCachedResult(packageFile, flags);
            if (parsed != null) {
                return parsed;
            }
        }

        long parseTime = LOG_PARSE_TIMINGS ? SystemClock.uptimeMillis() : 0;
        ParseInput input = mSharedResult.get().reset();
        ParseResult<ParsingPackage> result = parsingUtils.parsePackage(input, packageFile, flags);
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

    public static abstract class Callback implements ParsingPackageUtils.Callback {

        @Override
        public final ParsingPackage startParsingPackage(@NonNull String packageName,
                @NonNull String baseCodePath, @NonNull String codePath,
                @NonNull TypedArray manifestArray, boolean isCoreApp) {
            return PackageImpl.forParsing(packageName, baseCodePath, codePath, manifestArray,
                    isCoreApp);
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
