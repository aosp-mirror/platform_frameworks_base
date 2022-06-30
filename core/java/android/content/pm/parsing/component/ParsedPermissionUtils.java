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

import android.annotation.NonNull;
import android.content.pm.PermissionInfo;
import android.content.pm.parsing.ParsingPackage;
import android.content.pm.parsing.ParsingUtils;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.Slog;

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** @hide */
public class ParsedPermissionUtils {

    private static final String TAG = ParsingUtils.TAG;

    @NonNull
    public static ParseResult<ParsedPermission> parsePermission(ParsingPackage pkg, Resources res,
            XmlResourceParser parser, boolean useRoundIcon, ParseInput input)
            throws IOException, XmlPullParserException {
        String packageName = pkg.getPackageName();
        ParsedPermission
                permission = new ParsedPermission();
        String tag = "<" + parser.getName() + ">";
        final ParseResult<ParsedPermission> result;

        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestPermission);
        try {
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
                return result;
            }

            if (sa.hasValue(
                    R.styleable.AndroidManifestPermission_backgroundPermission)) {
                if ("android".equals(packageName)) {
                    permission.backgroundPermission = sa.getNonResourceString(
                            R.styleable
                                    .AndroidManifestPermission_backgroundPermission);
                } else {
                    Slog.w(TAG, packageName + " defines a background permission. Only the "
                            + "'android' package can do that.");
                }
            }

            // Note: don't allow this value to be a reference to a resource
            // that may change.
            permission.setGroup(sa.getNonResourceString(
                    R.styleable.AndroidManifestPermission_permissionGroup));

            permission.requestRes = sa.getResourceId(
                    R.styleable.AndroidManifestPermission_request, 0);

            permission.protectionLevel = sa.getInt(
                    R.styleable.AndroidManifestPermission_protectionLevel,
                    PermissionInfo.PROTECTION_NORMAL);

            permission.flags = sa.getInt(
                    R.styleable.AndroidManifestPermission_permissionFlags, 0);

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
                if (permission.knownCerts == null) {
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
            if (!permission.isRuntime() || !"android".equals(permission.getPackageName())) {
                permission.flags &= ~PermissionInfo.FLAG_HARD_RESTRICTED;
                permission.flags &= ~PermissionInfo.FLAG_SOFT_RESTRICTED;
            } else {
                // The platform does not get to specify conflicting permissions
                if ((permission.flags & PermissionInfo.FLAG_HARD_RESTRICTED) != 0
                        && (permission.flags & PermissionInfo.FLAG_SOFT_RESTRICTED) != 0) {
                    throw new IllegalStateException("Permission cannot be both soft and hard"
                            + " restricted: " + permission.getName());
                }
            }
        } finally {
            sa.recycle();
        }

        permission.protectionLevel = PermissionInfo.fixProtectionLevel(permission.protectionLevel);

        final int otherProtectionFlags = permission.getProtectionFlags()
                & ~(PermissionInfo.PROTECTION_FLAG_APPOP | PermissionInfo.PROTECTION_FLAG_INSTANT
                | PermissionInfo.PROTECTION_FLAG_RUNTIME_ONLY);
        if (otherProtectionFlags != 0
                && permission.getProtection() != PermissionInfo.PROTECTION_SIGNATURE
                && permission.getProtection() != PermissionInfo.PROTECTION_INTERNAL) {
            return input.error("<permission> protectionLevel specifies a non-instant, non-appop,"
                    + " non-runtimeOnly flag but is not based on signature or internal type");
        }

        return ComponentParseUtils.parseAllMetaData(pkg, res, parser, tag, permission, input);
    }

    @NonNull
    public static ParseResult<ParsedPermission> parsePermissionTree(ParsingPackage pkg, Resources res,
            XmlResourceParser parser, boolean useRoundIcon, ParseInput input)
            throws IOException, XmlPullParserException {
        ParsedPermission permission = new ParsedPermission();
        String tag = "<" + parser.getName() + ">";
        final ParseResult<ParsedPermission> result;

        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestPermissionTree);
        try {
            result = ParsedComponentUtils.parseComponent(
                    permission, tag, pkg, sa, useRoundIcon, input,
                    R.styleable.AndroidManifestPermissionTree_banner,
                    null /*descriptionAttr*/,
                    R.styleable.AndroidManifestPermissionTree_icon,
                    R.styleable.AndroidManifestPermissionTree_label,
                    R.styleable.AndroidManifestPermissionTree_logo,
                    R.styleable.AndroidManifestPermissionTree_name,
                    R.styleable.AndroidManifestPermissionTree_roundIcon);
            if (result.isError()) {
                return result;
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

        permission.protectionLevel = PermissionInfo.PROTECTION_NORMAL;
        permission.tree = true;

        return ComponentParseUtils.parseAllMetaData(pkg, res, parser, tag, permission,
                input);
    }

    @NonNull
    public static ParseResult<ParsedPermissionGroup> parsePermissionGroup(ParsingPackage pkg,
            Resources res, XmlResourceParser parser, boolean useRoundIcon, ParseInput input)
            throws IOException, XmlPullParserException {
        ParsedPermissionGroup
                permissionGroup = new ParsedPermissionGroup();
        String tag = "<" + parser.getName() + ">";

        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestPermissionGroup);
        try {
            ParseResult<ParsedPermissionGroup> result = ParsedComponentUtils.parseComponent(
                    permissionGroup, tag, pkg, sa, useRoundIcon, input,
                    R.styleable.AndroidManifestPermissionGroup_banner,
                    R.styleable.AndroidManifestPermissionGroup_description,
                    R.styleable.AndroidManifestPermissionGroup_icon,
                    R.styleable.AndroidManifestPermissionGroup_label,
                    R.styleable.AndroidManifestPermissionGroup_logo,
                    R.styleable.AndroidManifestPermissionGroup_name,
                    R.styleable.AndroidManifestPermissionGroup_roundIcon);
            if (result.isError()) {
                return result;
            }

            // @formatter:off
            permissionGroup.requestDetailResourceId = sa.getResourceId(R.styleable.AndroidManifestPermissionGroup_requestDetail, 0);
            permissionGroup.backgroundRequestResourceId = sa.getResourceId(R.styleable.AndroidManifestPermissionGroup_backgroundRequest, 0);
            permissionGroup.backgroundRequestDetailResourceId = sa.getResourceId(R.styleable.AndroidManifestPermissionGroup_backgroundRequestDetail, 0);
            permissionGroup.requestRes = sa.getResourceId(R.styleable.AndroidManifestPermissionGroup_request, 0);
            permissionGroup.flags = sa.getInt(R.styleable.AndroidManifestPermissionGroup_permissionGroupFlags,0);
            permissionGroup.priority = sa.getInt(R.styleable.AndroidManifestPermissionGroup_priority, 0);
            // @formatter:on
        } finally {
            sa.recycle();
        }

        return ComponentParseUtils.parseAllMetaData(pkg, res, parser, tag, permissionGroup,
                input);
    }

    /**
     * Determines if a duplicate permission is malformed .i.e. defines different protection level
     * or group
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
     *
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
