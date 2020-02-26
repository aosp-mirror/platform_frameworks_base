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
import android.annotation.Nullable;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.parsing.ParsingPackage;
import android.content.pm.parsing.ParsingPackageUtils;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.content.res.TypedArray;
import android.os.Build;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Slog;

import com.android.server.pm.parsing.pkg.PackageImpl;
import com.android.server.pm.parsing.pkg.ParsedPackage;

import java.io.File;

/**
 * The v2 of {@link PackageParser} for use when parsing is initiated in the server and must
 * contain state contained by the server.
 */
public class PackageParser2 {

    static final String TAG = "PackageParser2";

    private static final boolean LOG_PARSE_TIMINGS = Build.IS_DEBUGGABLE;
    private static final int LOG_PARSE_TIMINGS_THRESHOLD_MS = 100;

    private ThreadLocal<ParseTypeImpl> mSharedResult = ThreadLocal.withInitial(ParseTypeImpl::new);

    private final String[] mSeparateProcesses;
    private final boolean mOnlyCoreApps;
    private final DisplayMetrics mDisplayMetrics;

    @Nullable
    protected PackageCacher mCacher;

    private ParsingPackageUtils parsingUtils;

    /**
     * @param onlyCoreApps Flag indicating this parser should only consider apps with
     *                     {@code coreApp} manifest attribute to be valid apps. This is useful when
     *                     creating a minimalist boot environment.
     */
    public PackageParser2(String[] separateProcesses, boolean onlyCoreApps,
            DisplayMetrics displayMetrics, @Nullable File cacheDir, Callback callback) {
        mSeparateProcesses = separateProcesses;
        mOnlyCoreApps = onlyCoreApps;

        if (displayMetrics == null) {
            mDisplayMetrics = new DisplayMetrics();
            mDisplayMetrics.setToDefaults();
        } else {
            mDisplayMetrics = displayMetrics;
        }

        mCacher = cacheDir == null ? null : new PackageCacher(cacheDir);
        // TODO(b/135203078): Remove nullability of callback
        callback = callback != null ? callback : new Callback() {
            @Override
            public boolean hasFeature(String feature) {
                return false;
            }
        };

        parsingUtils = new ParsingPackageUtils(onlyCoreApps, separateProcesses, displayMetrics, callback);
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

    public static abstract class Callback implements ParsingPackageUtils.Callback {

        @Override
        public final ParsingPackage startParsingPackage(String packageName, String baseCodePath,
                String codePath, TypedArray manifestArray, boolean isCoreApp) {
            return PackageImpl.forParsing(packageName, baseCodePath, codePath, manifestArray,
                    isCoreApp);
        }
    }
}
