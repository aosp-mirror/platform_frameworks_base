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
import android.annotation.Nullable;
import android.content.pm.PermissionInfo;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedString;
import com.android.internal.util.Parcelling.BuiltIn.ForStringSet;

import java.util.Locale;
import java.util.Set;

/** @hide */
public class ParsedPermission extends ParsedComponent {

    private static ForStringSet sForStringSet = Parcelling.Cache.getOrCreate(ForStringSet.class);

    @Nullable
    private String backgroundPermission;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    private String group;
    private int requestRes;
    private int protectionLevel;
    private boolean tree;
    @Nullable
    private ParsedPermissionGroup parsedPermissionGroup;
    @Nullable
    private Set<String> knownCerts;

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

    public ParsedPermission setBackgroundPermission(String backgroundPermission) {
        this.backgroundPermission = backgroundPermission;
        return this;
    }

    public ParsedPermission setGroup(String group) {
        this.group = TextUtils.safeIntern(group);
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

    public @Nullable Set<String> getKnownCerts() {
        return knownCerts;
    }

    protected void setKnownCert(String knownCert) {
        // Convert the provided digest to upper case for consistent Set membership
        // checks when verifying the signing certificate digests of requesting apps.
        this.knownCerts = Set.of(knownCert.toUpperCase(Locale.US));
    }

    protected void setKnownCerts(String[] knownCerts) {
        this.knownCerts = new ArraySet<>();
        for (String knownCert : knownCerts) {
            this.knownCerts.add(knownCert.toUpperCase(Locale.US));
        }
    }

    public int calculateFootprint() {
        int size = getName().length();
        if (getNonLocalizedLabel() != null) {
            size += getNonLocalizedLabel().length();
        }
        return size;
    }

    public ParsedPermission setKnownCerts(Set<String> knownCerts) {
        this.knownCerts = knownCerts;
        return this;
    }

    public ParsedPermission setRequestRes(int requestRes) {
        this.requestRes = requestRes;
        return this;
    }

    public ParsedPermission setTree(boolean tree) {
        this.tree = tree;
        return this;
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
        sForStringSet.parcel(knownCerts, dest, flags);
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
        this.knownCerts = sForStringSet.unparcel(in);
    }

    @NonNull
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
