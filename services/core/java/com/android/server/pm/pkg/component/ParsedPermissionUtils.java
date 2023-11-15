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

package com.android.server.pm.pkg.component;

import static com.android.server.pm.pkg.parsing.ParsingUtils.NOT_SET;

import android.annotation.NonNull;
import android.content.pm.PermissionInfo;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.pm.pkg.component.ParsedPermission;
import com.android.internal.pm.pkg.component.ParsedPermissionGroup;
import com.android.server.pm.pkg.parsing.ParsingPackage;
import com.android.server.pm.pkg.parsing.ParsingUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * @hide
 */
public class ParsedPermissionUtils {

    private static final String TAG = ParsingUtils.TAG;

    @NonNull
    public static ParseResult<ParsedPermission> parsePermission(ParsingPackage pkg, Resources res,
            XmlResourceParser parser, boolean useRoundIcon, ParseInput input)
            throws IOException, XmlPullParserException {
        String packageName = pkg.getPackageName();
        ParsedPermissionImpl permission = new ParsedPermissionImpl();
        String tag = "<" + parser.getName() + ">";
        ParseResult<ParsedPermissionImpl> result;

        try (TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestPermission)) {
            result = ParsedComponentUtils.parseComponent(
                    permission, tag, pkg, sa, useRoundIcon, input,
                    R.styleable.AndroidManifestPermission_banner,
                    R.styleable.AndroidManifestPermission_description,
                    R.styleable.AndroidManifestPermission_icon,
                    R.styleable.AndroidManifestPermission_label,
                    R.styleable.AndroidManifestPermission_logo,
                    R.styleable.AndroidManifestPermission_name,
                    R.styleable.AndroidManifestPermission_roundIcon);
            if (result.isError()) {
                return input.error(result);
            }

            int maxSdkVersion = sa.getInt(R.styleable.AndroidManifestPermission_maxSdkVersion, -1);
            if ((maxSdkVersion != -1) && (maxSdkVersion < Build.VERSION.SDK_INT)) {
                return input.success(null);
            }

            if (sa.hasValue(
                    R.styleable.AndroidManifestPermission_backgroundPermission)) {
                if ("android".equals(packageName)) {
                    permission.setBackgroundPermission(sa.getNonResourceString(
                            R.styleable.AndroidManifestPermission_backgroundPermission));
                } else {
                    Slog.w(TAG, packageName + " defines a background permission. Only the "
                            + "'android' package can do that.");
                }
            }

            // Note: don't allow this value to be a reference to a resource
            // that may change.
            permission.setGroup(sa.getNonResourceString(
                    R.styleable.AndroidManifestPermission_permissionGroup))
                    .setRequestRes(sa.getResourceId(
                            R.styleable.AndroidManifestPermission_request, 0))
                    .setProtectionLevel(sa.getInt(
                            R.styleable.AndroidManifestPermission_protectionLevel,
                            PermissionInfo.PROTECTION_NORMAL))
                    .setFlags(sa.getInt(
                            R.styleable.AndroidManifestPermission_permissionFlags, 0));

            final int knownCertsResource = sa.getResourceId(
                    R.styleable.AndroidManifestPermission_knownCerts, 0);
            if (knownCertsResource != 0) {
                // The knownCerts attribute supports both a string array resource as well as a
                // string resource for the case where the permission should only be granted to a
                // single known signer.
                final String resourceType = res.getResourceTypeName(knownCertsResource);
                if (resourceType.equals("array")) {
                    final String[] knownCerts = res.getStringArray(knownCertsResource);
                    if (knownCerts != null) {
                        permission.setKnownCerts(knownCerts);
                    }
                } else {
                    final String knownCert = res.getString(knownCertsResource);
                    if (knownCert != null) {
                        permission.setKnownCert(knownCert);
                    }
                }
                if (permission.getKnownCerts().isEmpty()) {
                    Slog.w(TAG, packageName + " defines a knownSigner permission but"
                            + " the provided knownCerts resource is null");
                }
            } else {
                // If the knownCerts resource ID is null check if the app specified a string
                // value for the attribute representing a single trusted signer.
                final String knownCert = sa.getString(
                        R.styleable.AndroidManifestPermission_knownCerts);
                if (knownCert != null) {
                    permission.setKnownCert(knownCert);
                }
            }

            // For now only platform runtime permissions can be restricted
            if (!isRuntime(permission) || !"android".equals(permission.getPackageName())) {
                permission.setFlags(permission.getFlags() & ~PermissionInfo.FLAG_HARD_RESTRICTED);
                permission.setFlags(permission.getFlags() & ~PermissionInfo.FLAG_SOFT_RESTRICTED);
            } else {
                // The platform does not get to specify conflicting permissions
                if ((permission.getFlags() & PermissionInfo.FLAG_HARD_RESTRICTED) != 0
                        && (permission.getFlags() & PermissionInfo.FLAG_SOFT_RESTRICTED) != 0) {
                    throw new IllegalStateException("Permission cannot be both soft and hard"
                            + " restricted: " + permission.getName());
                }
            }
        }

        permission.setProtectionLevel(
                PermissionInfo.fixProtectionLevel(permission.getProtectionLevel()));

        final int otherProtectionFlags = getProtectionFlags(permission)
                & ~(PermissionInfo.PROTECTION_FLAG_APPOP | PermissionInfo.PROTECTION_FLAG_INSTANT
                | PermissionInfo.PROTECTION_FLAG_RUNTIME_ONLY);
        if (otherProtectionFlags != 0
                && getProtection(permission) != PermissionInfo.PROTECTION_SIGNATURE
                && getProtection(permission) != PermissionInfo.PROTECTION_INTERNAL) {
            return input.error("<permission> protectionLevel specifies a non-instant, non-appop,"
                    + " non-runtimeOnly flag but is not based on signature or internal type");
        }

        result = ComponentParseUtils.parseAllMetaData(pkg, res, parser, tag, permission, input);
        if (result.isError()) {
            return input.error(result);
        }

        return input.success(result.getResult());
    }

    @NonNull
    public static ParseResult<ParsedPermission> parsePermissionTree(ParsingPackage pkg, Resources res,
            XmlResourceParser parser, boolean useRoundIcon, ParseInput input)
            throws IOException, XmlPullParserException {
        ParsedPermissionImpl permission = new ParsedPermissionImpl();
        String tag = "<" + parser.getName() + ">";
        ParseResult<ParsedPermissionImpl> result;

        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestPermissionTree);
        try {
            result = ParsedComponentUtils.parseComponent(
                    permission, tag, pkg, sa, useRoundIcon, input,
                    R.styleable.AndroidManifestPermissionTree_banner,
                    NOT_SET /*descriptionAttr*/,
                    R.styleable.AndroidManifestPermissionTree_icon,
                    R.styleable.AndroidManifestPermissionTree_label,
                    R.styleable.AndroidManifestPermissionTree_logo,
                    R.styleable.AndroidManifestPermissionTree_name,
                    R.styleable.AndroidManifestPermissionTree_roundIcon);
            if (result.isError()) {
                return input.error(result);
            }
        } finally {
            sa.recycle();
        }

        int index = permission.getName().indexOf('.');
        if (index > 0) {
            index = permission.getName().indexOf('.', index + 1);
        }
        if (index < 0) {
            return input.error("<permission-tree> name has less than three segments: "
                    + permission.getName());
        }

        permission.setProtectionLevel(PermissionInfo.PROTECTION_NORMAL)
                .setTree(true);

        result = ComponentParseUtils.parseAllMetaData(pkg, res, parser, tag, permission, input);
        if (result.isError()) {
            return input.error(result);
        }

        return input.success(result.getResult());
    }

    @NonNull
    public static ParseResult<ParsedPermissionGroup> parsePermissionGroup(ParsingPackage pkg,
            Resources res, XmlResourceParser parser, boolean useRoundIcon, ParseInput input)
            throws IOException, XmlPullParserException {
        ParsedPermissionGroupImpl
                permissionGroup = new ParsedPermissionGroupImpl();
        String tag = "<" + parser.getName() + ">";

        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestPermissionGroup);
        try {
            ParseResult<ParsedPermissionGroupImpl> result = ParsedComponentUtils.parseComponent(
                    permissionGroup, tag, pkg, sa, useRoundIcon, input,
                    R.styleable.AndroidManifestPermissionGroup_banner,
                    R.styleable.AndroidManifestPermissionGroup_description,
                    R.styleable.AndroidManifestPermissionGroup_icon,
                    R.styleable.AndroidManifestPermissionGroup_label,
                    R.styleable.AndroidManifestPermissionGroup_logo,
                    R.styleable.AndroidManifestPermissionGroup_name,
                    R.styleable.AndroidManifestPermissionGroup_roundIcon);
            if (result.isError()) {
                return input.error(result);
            }

            // @formatter:off
            permissionGroup.setRequestDetailRes(sa.getResourceId(R.styleable.AndroidManifestPermissionGroup_requestDetail, 0))
                    .setBackgroundRequestRes(sa.getResourceId(R.styleable.AndroidManifestPermissionGroup_backgroundRequest, 0))
                    .setBackgroundRequestDetailRes(sa.getResourceId(R.styleable.AndroidManifestPermissionGroup_backgroundRequestDetail, 0))
                    .setRequestRes(sa.getResourceId(R.styleable.AndroidManifestPermissionGroup_request, 0))
                    .setPriority(sa.getInt(R.styleable.AndroidManifestPermissionGroup_priority, 0))
                    .setFlags(sa.getInt(R.styleable.AndroidManifestPermissionGroup_permissionGroupFlags,0));
            // @formatter:on
        } finally {
            sa.recycle();
        }

        ParseResult<ParsedPermissionGroupImpl> result = ComponentParseUtils.parseAllMetaData(pkg,
                res, parser, tag, permissionGroup, input);
        if (result.isError()) {
            return input.error(result);
        }

        return input.success(result.getResult());
    }

    public static boolean isRuntime(@NonNull ParsedPermission permission) {
        return getProtection(permission) == PermissionInfo.PROTECTION_DANGEROUS;
    }

    public static boolean isAppOp(@NonNull ParsedPermission permission) {
        return (permission.getProtectionLevel() & PermissionInfo.PROTECTION_FLAG_APPOP) != 0;
    }

    @PermissionInfo.Protection
    public static int getProtection(@NonNull ParsedPermission permission) {
        return permission.getProtectionLevel() & PermissionInfo.PROTECTION_MASK_BASE;
    }

    public static int getProtectionFlags(@NonNull ParsedPermission permission) {
        return permission.getProtectionLevel() & ~PermissionInfo.PROTECTION_MASK_BASE;
    }

    public static int calculateFootprint(@NonNull ParsedPermission permission) {
        int size = permission.getName().length();
        CharSequence nonLocalizedLabel = permission.getNonLocalizedLabel();
        if (nonLocalizedLabel != null) {
            size += nonLocalizedLabel.length();
        }
        return size;
    }

    /**
     * Determines if a duplicate permission is malformed .i.e. defines different protection level
     * or group.
     */
    private static boolean isMalformedDuplicate(ParsedPermission p1, ParsedPermission p2) {
        // Since a permission tree is also added as a permission with normal protection
        // level, we need to skip if the parsedPermission is a permission tree.
        if (p1 == null || p2 == null || p1.isTree() || p2.isTree()) {
            return false;
        }

        if (p1.getProtectionLevel() != p2.getProtectionLevel()) {
            return true;
        }
        if (!Objects.equals(p1.getGroup(), p2.getGroup())) {
            return true;
        }

        return false;
    }

    /**
     * @return {@code true} if the package declares malformed duplicate permissions.
     */
    public static boolean declareDuplicatePermission(@NonNull ParsingPackage pkg) {
        final List<ParsedPermission> permissions = pkg.getPermissions();
        final int size = permissions.size();
        if (size > 0) {
            final ArrayMap<String, ParsedPermission> checkDuplicatePerm = new ArrayMap<>(size);
            for (int i = 0; i < size; i++) {
                final ParsedPermission parsedPermission = permissions.get(i);
                final String name = parsedPermission.getName();
                final ParsedPermission perm = checkDuplicatePerm.get(name);
                if (isMalformedDuplicate(parsedPermission, perm)) {
                    // Fix for b/213323615
                    EventLog.writeEvent(0x534e4554, "213323615");
                    return true;
                }
                checkDuplicatePerm.put(name, parsedPermission);
            }
        }
        return false;
    }
}
