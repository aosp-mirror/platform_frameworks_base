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

import android.annotation.Nullable;
import android.content.pm.PermissionInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedString;

/** @hide */
public class ParsedPermission extends ParsedComponent {

    @Nullable
    String backgroundPermission;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String group;
    int requestRes;
    int protectionLevel;
    boolean tree;
    @Nullable
    private ParsedPermissionGroup parsedPermissionGroup;

    @VisibleForTesting
    public ParsedPermission() {
    }

    public ParsedPermission(ParsedPermission other) {
        super(other);
        this.backgroundPermission = other.backgroundPermission;
        this.group = other.group;
        this.requestRes = other.requestRes;
        this.protectionLevel = other.protectionLevel;
        this.tree = other.tree;
        this.parsedPermissionGroup = other.parsedPermissionGroup;
    }

    public ParsedPermission(ParsedPermission other, PermissionInfo pendingPermissionInfo,
            String packageName, String name) {
        this(other);

        this.flags = pendingPermissionInfo.flags;
        this.descriptionRes = pendingPermissionInfo.descriptionRes;

        this.backgroundPermission = pendingPermissionInfo.backgroundPermission;
        this.group = pendingPermissionInfo.group;
        this.requestRes = pendingPermissionInfo.requestRes;
        this.protectionLevel = pendingPermissionInfo.protectionLevel;

        setName(name);
        setPackageName(packageName);
    }

    public ParsedPermission setGroup(String group) {
        this.group = TextUtils.safeIntern(group);
        return this;
    }

    public ParsedPermission setFlags(int flags) {
        this.flags = flags;
        return this;
    }

    public boolean isRuntime() {
        return getProtection() == PermissionInfo.PROTECTION_DANGEROUS;
    }

    public boolean isAppOp() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_APPOP) != 0;
    }

    @PermissionInfo.Protection
    public int getProtection() {
        return protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
    }

    public int getProtectionFlags() {
        return protectionLevel & ~PermissionInfo.PROTECTION_MASK_BASE;
    }

    public int calculateFootprint() {
        int size = getName().length();
        if (getNonLocalizedLabel() != null) {
            size += getNonLocalizedLabel().length();
        }
        return size;
    }

    public String toString() {
        return "Permission{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + getName() + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(this.backgroundPermission);
        dest.writeString(this.group);
        dest.writeInt(this.requestRes);
        dest.writeInt(this.protectionLevel);
        dest.writeBoolean(this.tree);
        dest.writeParcelable(this.parsedPermissionGroup, flags);
    }

    protected ParsedPermission(Parcel in) {
        super(in);
        // We use the boot classloader for all classes that we load.
        final ClassLoader boot = Object.class.getClassLoader();
        this.backgroundPermission = in.readString();
        this.group = in.readString();
        this.requestRes = in.readInt();
        this.protectionLevel = in.readInt();
        this.tree = in.readBoolean();
        this.parsedPermissionGroup = in.readParcelable(boot);
    }

    public static final Parcelable.Creator<ParsedPermission> CREATOR =
            new Parcelable.Creator<ParsedPermission>() {
                @Override
                public ParsedPermission createFromParcel(Parcel source) {
                    return new ParsedPermission(source);
                }

                @Override
                public ParsedPermission[] newArray(int size) {
                    return new ParsedPermission[size];
                }
            };

    @Nullable
    public String getBackgroundPermission() {
        return backgroundPermission;
    }

    @Nullable
    public String getGroup() {
        return group;
    }

    public int getRequestRes() {
        return requestRes;
    }

    public int getProtectionLevel() {
        return protectionLevel;
    }

    public boolean isTree() {
        return tree;
    }

    @Nullable
    public ParsedPermissionGroup getParsedPermissionGroup() {
        return parsedPermissionGroup;
    }

    public ParsedPermission setProtectionLevel(int value) {
        protectionLevel = value;
        return this;
    }

    public ParsedPermission setParsedPermissionGroup(@Nullable ParsedPermissionGroup value) {
        parsedPermissionGroup = value;
        return this;
    }
}
