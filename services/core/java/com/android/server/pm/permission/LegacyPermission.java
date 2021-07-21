/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.server.pm.permission;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PermissionInfo;
import android.util.Log;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;

import libcore.util.EmptyArray;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Legacy permission definition.
 */
//@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
public final class LegacyPermission {
    /**
     * The permission is defined in a manifest.
     */
    public static final int TYPE_MANIFEST = 0;

    /**
     * The permission is defined in a system config.
     */
    public static final int TYPE_CONFIG = 1;

    /**
     * The permission is defined dynamically.
     */
    public static final int TYPE_DYNAMIC = 2;

    /**
     * @hide
     */
    @IntDef({
            TYPE_MANIFEST,
            TYPE_CONFIG,
            TYPE_DYNAMIC,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionType {}

    private static final String ATTR_NAME = "name";
    private static final String ATTR_PACKAGE = "package";
    private static final String TAG_ITEM = "item";

    @NonNull
    private final PermissionInfo mPermissionInfo;
    @PermissionType
    private final int mType;
    private final int mUid;
    @NonNull
    private final int[] mGids;

    /**
     * Create a new instance of this class.
     *
     * @param permissionInfo the {@link PermissionInfo} for the permission
     * @param type the type of the permission
     * @param uid the UID defining the permission
     * @param gids the GIDs associated with the permission
     */
    public LegacyPermission(@NonNull PermissionInfo permissionInfo, @PermissionType int type,
            int uid, @NonNull int[] gids) {
        mPermissionInfo = permissionInfo;
        mType = type;
        mUid = uid;
        mGids = gids;
    }

    private LegacyPermission(@NonNull String name, @NonNull String packageName,
            @PermissionType int type) {
        mPermissionInfo = new PermissionInfo();
        mPermissionInfo.name = name;
        mPermissionInfo.packageName = packageName;
        // Default to most conservative protection level.
        mPermissionInfo.protectionLevel = PermissionInfo.PROTECTION_SIGNATURE;
        mType = type;
        mUid = 0;
        mGids = EmptyArray.INT;
    }

    /**
     * Get the {@link PermissionInfo} for this mission.
     *
     * @return the {@link PermissionInfo}
     */
    @NonNull
    public PermissionInfo getPermissionInfo() {
        return mPermissionInfo;
    }

    /**
     * Get the type of this mission.
     *
     * @return the type
     */
    public int getType() {
        return mType;
    }

    /**
     * @hide
     */
    public static boolean read(@NonNull Map<String, LegacyPermission> out,
            @NonNull TypedXmlPullParser parser) {
        final String tagName = parser.getName();
        if (!tagName.equals(TAG_ITEM)) {
            return false;
        }
        final String name = parser.getAttributeValue(null, ATTR_NAME);
        final String packageName = parser.getAttributeValue(null, ATTR_PACKAGE);
        final String ptype = parser.getAttributeValue(null, "type");
        if (name == null || packageName == null) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: permissions has" + " no name at "
                            + parser.getPositionDescription());
            return false;
        }
        final boolean dynamic = "dynamic".equals(ptype);
        LegacyPermission bp = out.get(name);
        // If the permission is builtin, do not clobber it.
        if (bp == null || bp.mType != TYPE_CONFIG) {
            bp = new LegacyPermission(name.intern(), packageName,
                    dynamic ? TYPE_DYNAMIC : TYPE_MANIFEST);
        }
        bp.mPermissionInfo.protectionLevel = readInt(parser, null, "protection",
                PermissionInfo.PROTECTION_NORMAL);
        bp.mPermissionInfo.protectionLevel = PermissionInfo.fixProtectionLevel(
                bp.mPermissionInfo.protectionLevel);
        if (dynamic) {
            bp.mPermissionInfo.icon = readInt(parser, null, "icon", 0);
            bp.mPermissionInfo.nonLocalizedLabel = parser.getAttributeValue(null, "label");
        }
        out.put(bp.mPermissionInfo.name, bp);
        return true;
    }

    private static int readInt(@NonNull TypedXmlPullParser parser, @Nullable String namespace,
            @NonNull String name, int defaultValue) {
        return parser.getAttributeInt(namespace, name, defaultValue);
    }

    /**
     * @hide
     */
    public void write(@NonNull TypedXmlSerializer serializer) throws IOException {
        if (mPermissionInfo.packageName == null) {
            return;
        }
        serializer.startTag(null, TAG_ITEM);
        serializer.attribute(null, ATTR_NAME, mPermissionInfo.name);
        serializer.attribute(null, ATTR_PACKAGE, mPermissionInfo.packageName);
        if (mPermissionInfo.protectionLevel != PermissionInfo.PROTECTION_NORMAL) {
            serializer.attributeInt(null, "protection", mPermissionInfo.protectionLevel);
        }
        if (mType == TYPE_DYNAMIC) {
            serializer.attribute(null, "type", "dynamic");
            if (mPermissionInfo.icon != 0) {
                serializer.attributeInt(null, "icon", mPermissionInfo.icon);
            }
            if (mPermissionInfo.nonLocalizedLabel != null) {
                serializer.attribute(null, "label", mPermissionInfo.nonLocalizedLabel.toString());
            }
        }
        serializer.endTag(null, TAG_ITEM);
    }

    /**
     * @hide
     */
    public boolean dump(@NonNull PrintWriter pw, @NonNull String packageName,
            @NonNull Set<String> permissionNames, boolean readEnforced, boolean printedSomething,
            @NonNull DumpState dumpState) {
        if (packageName != null && !packageName.equals(mPermissionInfo.packageName)) {
            return false;
        }
        if (permissionNames != null && !permissionNames.contains(mPermissionInfo.name)) {
            return false;
        }
        if (!printedSomething) {
            if (dumpState.onTitlePrinted()) {
                pw.println();
            }
            pw.println("Permissions:");
        }
        pw.print("  Permission ["); pw.print(mPermissionInfo.name); pw.print("] (");
        pw.print(Integer.toHexString(System.identityHashCode(this)));
        pw.println("):");
        pw.print("    sourcePackage="); pw.println(mPermissionInfo.packageName);
        pw.print("    uid="); pw.print(mUid);
        pw.print(" gids="); pw.print(Arrays.toString(mGids));
        pw.print(" type="); pw.print(mType);
        pw.print(" prot=");
        pw.println(PermissionInfo.protectionToString(mPermissionInfo.protectionLevel));
        if (mPermissionInfo != null) {
            pw.print("    perm="); pw.println(mPermissionInfo);
            if ((mPermissionInfo.flags & PermissionInfo.FLAG_INSTALLED) == 0
                    || (mPermissionInfo.flags & PermissionInfo.FLAG_REMOVED) != 0) {
                pw.print("    flags=0x"); pw.println(Integer.toHexString(mPermissionInfo.flags));
            }
        }
        if (Objects.equals(mPermissionInfo.name,
                android.Manifest.permission.READ_EXTERNAL_STORAGE)) {
            pw.print("    enforced=");
            pw.println(readEnforced);
        }
        return true;
    }
}
