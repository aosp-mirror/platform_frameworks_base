/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.internal.pm.pkg.component;

import static com.android.internal.pm.pkg.component.ComponentParseUtils.flag;
import static com.android.internal.pm.pkg.parsing.ParsingPackageUtils.RIGID_PARSER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.IntentFilter;
import android.content.pm.PathPermission;
import android.content.pm.ProviderInfo;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.PatternMatcher;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.pm.pkg.parsing.ParsingPackage;
import com.android.internal.pm.pkg.parsing.ParsingUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Objects;

/** @hide */
public class ParsedProviderUtils {

    private static final String TAG = ParsingUtils.TAG;

    @NonNull
    public static ParseResult<ParsedProvider> parseProvider(String[] separateProcesses,
            ParsingPackage pkg, Resources res, XmlResourceParser parser, int flags,
            boolean useRoundIcon, @Nullable String defaultSplitName, @NonNull ParseInput input)
            throws IOException, XmlPullParserException {
        String authority;
        boolean visibleToEphemeral;

        final int targetSdkVersion = pkg.getTargetSdkVersion();
        final String packageName = pkg.getPackageName();
        final ParsedProviderImpl provider = new ParsedProviderImpl();
        final String tag = parser.getName();

        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestProvider);
        try {
            ParseResult<ParsedProviderImpl> result =
                    ParsedMainComponentUtils.parseMainComponent(provider, tag, separateProcesses,
                            pkg, sa, flags, useRoundIcon, defaultSplitName, input,
                            R.styleable.AndroidManifestProvider_banner,
                            R.styleable.AndroidManifestProvider_description,
                            R.styleable.AndroidManifestProvider_directBootAware,
                            R.styleable.AndroidManifestProvider_enabled,
                            R.styleable.AndroidManifestProvider_icon,
                            R.styleable.AndroidManifestProvider_label,
                            R.styleable.AndroidManifestProvider_logo,
                            R.styleable.AndroidManifestProvider_name,
                            R.styleable.AndroidManifestProvider_process,
                            R.styleable.AndroidManifestProvider_roundIcon,
                            R.styleable.AndroidManifestProvider_splitName,
                            R.styleable.AndroidManifestProvider_attributionTags);
            if (result.isError()) {
                return input.error(result);
            }

            authority = sa.getNonConfigurationString(R.styleable.AndroidManifestProvider_authorities, 0);

            // For compatibility, applications targeting API level 16 or lower
            // should have their content providers exported by default, unless they
            // specify otherwise.
            provider.setSyncable(sa.getBoolean(
                    R.styleable.AndroidManifestProvider_syncable, false))
                    .setExported(sa.getBoolean(R.styleable.AndroidManifestProvider_exported,
                            targetSdkVersion < Build.VERSION_CODES.JELLY_BEAN_MR1));

            String permission = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestProvider_permission, 0);
            String readPermission = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestProvider_readPermission, 0);
            if (readPermission == null) {
                readPermission = permission;
            }
            if (readPermission == null) {
                provider.setReadPermission(pkg.getPermission());
            } else {
                provider.setReadPermission(readPermission);
            }
            String writePermission = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestProvider_writePermission, 0);
            if (writePermission == null) {
                writePermission = permission;
            }
            if (writePermission == null) {
                provider.setWritePermission(pkg.getPermission());
            } else {
                provider.setWritePermission(writePermission);
            }

            provider.setGrantUriPermissions(
                    sa.getBoolean(R.styleable.AndroidManifestProvider_grantUriPermissions, false))
                    .setForceUriPermissions(
                            sa.getBoolean(R.styleable.AndroidManifestProvider_forceUriPermissions,
                                    false))
                    .setMultiProcess(
                            sa.getBoolean(R.styleable.AndroidManifestProvider_multiprocess, false))
                    .setInitOrder(sa.getInt(R.styleable.AndroidManifestProvider_initOrder, 0))
                    .setFlags(provider.getFlags() | flag(ProviderInfo.FLAG_SINGLE_USER,
                            R.styleable.AndroidManifestProvider_singleUser, sa));

            visibleToEphemeral = sa.getBoolean(
                    R.styleable.AndroidManifestProvider_visibleToInstantApps, false);
            if (visibleToEphemeral) {
                provider.setFlags(provider.getFlags() | ProviderInfo.FLAG_VISIBLE_TO_INSTANT_APP);
                pkg.setVisibleToInstantApps(true);
            }
        } finally {
            sa.recycle();
        }

        if (pkg.isSaveStateDisallowed()) {
            // A heavy-weight application can not have providers in its main process
            if (Objects.equals(provider.getProcessName(), packageName)) {
                return input.error("Heavy-weight applications can not have providers"
                        + " in main process");
            }
        }

        if (authority == null) {
            return input.error("<provider> does not include authorities attribute");
        }
        if (authority.length() <= 0) {
            return input.error("<provider> has empty authorities attribute");
        }
        provider.setAuthority(authority);

        return parseProviderTags(pkg, tag, res, parser, visibleToEphemeral, provider, input);
    }

    @NonNull
    private static ParseResult<ParsedProvider> parseProviderTags(ParsingPackage pkg, String tag,
            Resources res, XmlResourceParser parser, boolean visibleToEphemeral,
            ParsedProviderImpl provider, ParseInput input)
            throws XmlPullParserException, IOException {
        final int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > depth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            String name = parser.getName();
            final ParseResult result;
            switch (name) {
                case "intent-filter":
                    ParseResult<ParsedIntentInfoImpl> intentResult = ParsedMainComponentUtils
                            .parseIntentFilter(provider, pkg, res, parser, visibleToEphemeral,
                                    true /*allowGlobs*/, false /*allowAutoVerify*/,
                                    false /*allowImplicitEphemeralVisibility*/,
                                    false /*failOnNoActions*/, input);
                    result = intentResult;
                    if (intentResult.isSuccess()) {
                        ParsedIntentInfoImpl intent = intentResult.getResult();
                        IntentFilter intentFilter = intent.getIntentFilter();
                        provider.setOrder(Math.max(intentFilter.getOrder(), provider.getOrder()));
                        provider.addIntent(intent);
                    }
                    break;
                case "meta-data":
                    result = ParsedComponentUtils.addMetaData(provider, pkg, res, parser, input);
                    break;
                case "property":
                    result = ParsedComponentUtils.addProperty(provider, pkg, res, parser, input);
                    break;
                case "grant-uri-permission": {
                    result = parseGrantUriPermission(provider, pkg, res, parser, input);
                    break;
                }
                case "path-permission": {
                    result = parsePathPermission(provider, pkg, res, parser, input);
                    break;
                }
                default:
                    result = ParsingUtils.unknownTag(tag, pkg, parser, input);
                    break;
            }

            if (result.isError()) {
                return input.error(result);
            }
        }

        return input.success(provider);
    }

    @NonNull
    private static ParseResult<ParsedProvider> parseGrantUriPermission(ParsedProviderImpl provider,
            ParsingPackage pkg, Resources resources, XmlResourceParser parser, ParseInput input) {
        TypedArray sa = resources.obtainAttributes(parser,
                R.styleable.AndroidManifestGrantUriPermission);
        try {
            String name = parser.getName();
            // Pattern has priority over pre/suffix over literal path
            PatternMatcher pa = null;
            String str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestGrantUriPermission_pathAdvancedPattern, 0);
            if (str != null) {
                pa = new PatternMatcher(str, PatternMatcher.PATTERN_ADVANCED_GLOB);
            } else {
                str = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestGrantUriPermission_pathPattern, 0);
                if (str != null) {
                    pa = new PatternMatcher(str, PatternMatcher.PATTERN_SIMPLE_GLOB);
                } else {
                    str = sa.getNonConfigurationString(
                            R.styleable.AndroidManifestGrantUriPermission_pathPrefix, 0);
                    if (str != null) {
                        pa = new PatternMatcher(str, PatternMatcher.PATTERN_PREFIX);
                    } else {
                        str = sa.getNonConfigurationString(
                                R.styleable.AndroidManifestGrantUriPermission_pathSuffix, 0);
                        if (str != null) {
                            pa = new PatternMatcher(str, PatternMatcher.PATTERN_SUFFIX);
                        } else {
                            str = sa.getNonConfigurationString(
                                    R.styleable.AndroidManifestGrantUriPermission_path, 0);
                            if (str != null) {
                                pa = new PatternMatcher(str, PatternMatcher.PATTERN_LITERAL);
                            }
                        }
                    }
                }
            }

            if (pa != null) {
                provider.addUriPermissionPattern(pa);
                provider.setGrantUriPermissions(true);
            } else {
                if (RIGID_PARSER) {
                    return input.error("No path, pathPrefix, or pathPattern for <path-permission>");
                }

                Slog.w(TAG, "Unknown element under <path-permission>: " + name + " at "
                        + pkg.getBaseApkPath() + " " + parser.getPositionDescription());
            }

            return input.success(provider);
        } finally {
            sa.recycle();
        }
    }

    @NonNull
    private static ParseResult<ParsedProvider> parsePathPermission(ParsedProviderImpl provider,
            ParsingPackage pkg, Resources resources, XmlResourceParser parser, ParseInput input) {
        TypedArray sa = resources.obtainAttributes(parser,
                R.styleable.AndroidManifestPathPermission);
        try {
            String name = parser.getName();

            String permission = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestPathPermission_permission, 0);
            String readPermission = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestPathPermission_readPermission, 0);
            if (readPermission == null) {
                readPermission = permission;
            }
            String writePermission = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestPathPermission_writePermission, 0);
            if (writePermission == null) {
                writePermission = permission;
            }

            boolean havePerm = false;
            if (readPermission != null) {
                readPermission = readPermission.intern();
                havePerm = true;
            }
            if (writePermission != null) {
                writePermission = writePermission.intern();
                havePerm = true;
            }

            if (!havePerm) {
                if (RIGID_PARSER) {
                    return input.error(
                            "No readPermission or writePermission for <path-permission>");
                }
                Slog.w(TAG, "No readPermission or writePermission for <path-permission>: "
                        + name + " at " + pkg.getBaseApkPath() + " "
                        + parser.getPositionDescription());
                return input.success(provider);
            }

            // Advanced has priority over simply over prefix over literal
            PathPermission pa = null;
            String path = sa.getNonConfigurationString(R.styleable.AndroidManifestPathPermission_pathAdvancedPattern, 0);
            if (path != null) {
                pa = new PathPermission(path, PatternMatcher.PATTERN_ADVANCED_GLOB, readPermission,
                        writePermission);
            } else {
                path = sa.getNonConfigurationString(R.styleable.AndroidManifestPathPermission_pathPattern, 0);
                if (path != null) {
                    pa = new PathPermission(path, PatternMatcher.PATTERN_SIMPLE_GLOB,
                            readPermission, writePermission);
                } else {
                    path = sa.getNonConfigurationString(
                            R.styleable.AndroidManifestPathPermission_pathPrefix, 0);
                    if (path != null) {
                        pa = new PathPermission(path, PatternMatcher.PATTERN_PREFIX, readPermission,
                                writePermission);
                    } else {
                        path = sa.getNonConfigurationString(
                                R.styleable.AndroidManifestPathPermission_pathSuffix, 0);
                        if (path != null) {
                            pa = new PathPermission(path, PatternMatcher.PATTERN_SUFFIX,
                                    readPermission, writePermission);
                        } else {
                            path = sa.getNonConfigurationString(
                                    R.styleable.AndroidManifestPathPermission_path, 0);
                            if (path != null) {
                                pa = new PathPermission(path, PatternMatcher.PATTERN_LITERAL,
                                        readPermission, writePermission);
                            }
                        }
                    }
                }
            }

            if (pa != null) {
                provider.addPathPermission(pa);
            } else {
                if (RIGID_PARSER) {
                    return input.error(
                            "No path, pathPrefix, or pathPattern for <path-permission>");
                }

                Slog.w(TAG, "No path, pathPrefix, or pathPattern for <path-permission>: "
                        + name + " at " + pkg.getBaseApkPath()
                        + " "
                        + parser.getPositionDescription());
            }

            return input.success(provider);
        } finally {
            sa.recycle();
        }
    }
}
