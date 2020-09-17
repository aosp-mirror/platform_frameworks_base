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

package android.content.pm.parsing.component;

import static android.content.pm.parsing.component.ComponentParseUtils.flag;

import android.annotation.NonNull;
import android.content.pm.PackageParser;
import android.content.pm.PathPermission;
import android.content.pm.ProviderInfo;
import android.content.pm.parsing.ParsingPackage;
import android.content.pm.parsing.ParsingPackageUtils;
import android.content.pm.parsing.ParsingUtils;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.PatternMatcher;
import android.util.Slog;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.Objects;

/** @hide */
public class ParsedProviderUtils {

    private static final String TAG = ParsingPackageUtils.TAG;

    @NonNull
    public static ParseResult<ParsedProvider> parseProvider(String[] separateProcesses,
            ParsingPackage pkg, Resources res, XmlResourceParser parser, int flags,
            boolean useRoundIcon, ParseInput input)
            throws IOException, XmlPullParserException {
        String authority;
        boolean visibleToEphemeral;

        final int targetSdkVersion = pkg.getTargetSdkVersion();
        final String packageName = pkg.getPackageName();
        final ParsedProvider provider = new ParsedProvider();
        final String tag = parser.getName();

        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestProvider);
        try {
            ParseResult<ParsedProvider> result =
                    ParsedMainComponentUtils.parseMainComponent(provider, tag, separateProcesses,
                    pkg, sa, flags, useRoundIcon, input,
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
                    R.styleable.AndroidManifestProvider_splitName);
            if (result.isError()) {
                return result;
            }

            authority = sa.getNonConfigurationString(R.styleable.AndroidManifestProvider_authorities, 0);

            // For compatibility, applications targeting API level 16 or lower
            // should have their content providers exported by default, unless they
            // specify otherwise.
            provider.exported = sa.getBoolean(R.styleable.AndroidManifestProvider_exported,
                    targetSdkVersion < Build.VERSION_CODES.JELLY_BEAN_MR1);

            provider.syncable = sa.getBoolean(R.styleable.AndroidManifestProvider_syncable, false);

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

            provider.grantUriPermissions = sa.getBoolean(R.styleable.AndroidManifestProvider_grantUriPermissions, false);
            provider.forceUriPermissions = sa.getBoolean(R.styleable.AndroidManifestProvider_forceUriPermissions, false);
            provider.multiProcess = sa.getBoolean(R.styleable.AndroidManifestProvider_multiprocess, false);
            provider.initOrder = sa.getInt(R.styleable.AndroidManifestProvider_initOrder, 0);

            provider.flags |= flag(ProviderInfo.FLAG_SINGLE_USER, R.styleable.AndroidManifestProvider_singleUser, sa);

            visibleToEphemeral = sa.getBoolean(R.styleable.AndroidManifestProvider_visibleToInstantApps, false);
            if (visibleToEphemeral) {
                provider.flags |= ProviderInfo.FLAG_VISIBLE_TO_INSTANT_APP;
                pkg.setVisibleToInstantApps(true);
            }
        } finally {
            sa.recycle();
        }

        if (pkg.isCantSaveState()) {
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
            ParsedProvider provider, ParseInput input)
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
                    ParseResult<ParsedIntentInfo> intentResult = ParsedMainComponentUtils
                            .parseIntentFilter(provider, pkg, res, parser, visibleToEphemeral,
                                    true /*allowGlobs*/, false /*allowAutoVerify*/,
                                    false /*allowImplicitEphemeralVisibility*/,
                                    false /*failOnNoActions*/, input);
                    result = intentResult;
                    if (intentResult.isSuccess()) {
                        ParsedIntentInfo intent = intentResult.getResult();
                        provider.order = Math.max(intent.getOrder(), provider.order);
                        provider.addIntent(intent);
                    }
                    break;
                case "meta-data":
                    result = ParsedComponentUtils.addMetaData(provider, pkg, res, parser, input);
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
    private static ParseResult<ParsedProvider> parseGrantUriPermission(ParsedProvider provider,
            ParsingPackage pkg, Resources resources, XmlResourceParser parser, ParseInput input) {
        TypedArray sa = resources.obtainAttributes(parser,
                R.styleable.AndroidManifestGrantUriPermission);
        try {
            String name = parser.getName();
            // Pattern has priority over prefix over literal path
            PatternMatcher pa = null;
            String str = sa.getNonConfigurationString(
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
                            R.styleable.AndroidManifestGrantUriPermission_path, 0);
                    if (str != null) {
                        pa = new PatternMatcher(str, PatternMatcher.PATTERN_LITERAL);
                    }
                }
            }

            if (pa != null) {
                if (provider.uriPermissionPatterns == null) {
                    provider.uriPermissionPatterns = new PatternMatcher[1];
                    provider.uriPermissionPatterns[0] = pa;
                } else {
                    final int N = provider.uriPermissionPatterns.length;
                    PatternMatcher[] newp = new PatternMatcher[N + 1];
                    System.arraycopy(provider.uriPermissionPatterns, 0, newp, 0, N);
                    newp[N] = pa;
                    provider.uriPermissionPatterns = newp;
                }
                provider.grantUriPermissions = true;
            } else {
                if (PackageParser.RIGID_PARSER) {
                    return input.error("No path, pathPrefix, or pathPattern for <path-permission>");
                }

                Slog.w(TAG, "Unknown element under <path-permission>: " + name + " at "
                        + pkg.getBaseCodePath() + " " + parser.getPositionDescription());
            }

            return input.success(provider);
        } finally {
            sa.recycle();
        }
    }

    @NonNull
    private static ParseResult<ParsedProvider> parsePathPermission(ParsedProvider provider,
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
                if (PackageParser.RIGID_PARSER) {
                    return input.error(
                            "No readPermission or writePermission for <path-permission>");
                }
                Slog.w(TAG, "No readPermission or writePermission for <path-permission>: "
                        + name + " at " + pkg.getBaseCodePath() + " " + parser.getPositionDescription());
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
                        path = sa.getNonConfigurationString(R.styleable.AndroidManifestPathPermission_path, 0);
                        if (path != null) {
                            pa = new PathPermission(path, PatternMatcher.PATTERN_LITERAL,
                                    readPermission, writePermission);
                        }
                    }
                }
            }

            if (pa != null) {
                if (provider.pathPermissions == null) {
                    provider.pathPermissions = new PathPermission[1];
                    provider.pathPermissions[0] = pa;
                } else {
                    final int N = provider.pathPermissions.length;
                    PathPermission[] newp = new PathPermission[N + 1];
                    System.arraycopy(provider.pathPermissions, 0, newp, 0, N);
                    newp[N] = pa;
                    provider.pathPermissions = newp;
                }
            } else {
                if (PackageParser.RIGID_PARSER) {
                    return input.error(
                            "No path, pathPrefix, or pathPattern for <path-permission>");
                }

                Slog.w(TAG, "No path, pathPrefix, or pathPattern for <path-permission>: "
                        + name + " at " + pkg.getBaseCodePath()
                        + " "
                        + parser.getPositionDescription());
            }

            return input.success(provider);
        } finally {
            sa.recycle();
        }
    }
}
