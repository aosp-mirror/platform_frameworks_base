/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.content.pm;

import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

/**
 * Information you can retrieve about a particular security permission
 * known to the system.  This corresponds to information collected from the
 * AndroidManifest.xml's &lt;permission&gt; tags.
 */
public class PermissionInfo extends PackageItemInfo implements Parcelable {
    /**
     * A normal application value for {@link #protectionLevel}, corresponding
     * to the <code>normal</code> value of
     * {@link android.R.attr#protectionLevel}.
     */
    public static final int PROTECTION_NORMAL = 0;

    /**
     * Dangerous value for {@link #protectionLevel}, corresponding
     * to the <code>dangerous</code> value of
     * {@link android.R.attr#protectionLevel}.
     */
    public static final int PROTECTION_DANGEROUS = 1;

    /**
     * System-level value for {@link #protectionLevel}, corresponding
     * to the <code>signature</code> value of
     * {@link android.R.attr#protectionLevel}.
     */
    public static final int PROTECTION_SIGNATURE = 2;

    /**
     * @deprecated Use {@link #PROTECTION_SIGNATURE}|{@link #PROTECTION_FLAG_PRIVILEGED}
     * instead.
     */
    @Deprecated
    public static final int PROTECTION_SIGNATURE_OR_SYSTEM = 3;

    /**
     * Additional flag for {@link #protectionLevel}, corresponding
     * to the <code>privileged</code> value of
     * {@link android.R.attr#protectionLevel}.
     */
    public static final int PROTECTION_FLAG_PRIVILEGED = 0x10;

    /**
     * @deprecated Old name for {@link #PROTECTION_FLAG_PRIVILEGED}, which
     * is now very confusing because it only applies to privileged apps, not all
     * apps on the system image.
     */
    @Deprecated
    public static final int PROTECTION_FLAG_SYSTEM = 0x10;

    /**
     * Additional flag for {@link #protectionLevel}, corresponding
     * to the <code>development</code> value of
     * {@link android.R.attr#protectionLevel}.
     */
    public static final int PROTECTION_FLAG_DEVELOPMENT = 0x20;

    /**
     * Additional flag for {@link #protectionLevel}, corresponding
     * to the <code>appop</code> value of
     * {@link android.R.attr#protectionLevel}.
     */
    public static final int PROTECTION_FLAG_APPOP = 0x40;

    /**
     * Additional flag for {@link #protectionLevel}, corresponding
     * to the <code>pre23</code> value of
     * {@link android.R.attr#protectionLevel}.
     */
    public static final int PROTECTION_FLAG_PRE23 = 0x80;

    /**
     * Additional flag for {@link #protectionLevel}, corresponding
     * to the <code>installer</code> value of
     * {@link android.R.attr#protectionLevel}.
     */
    public static final int PROTECTION_FLAG_INSTALLER = 0x100;

    /**
     * Additional flag for {@link #protectionLevel}, corresponding
     * to the <code>verifier</code> value of
     * {@link android.R.attr#protectionLevel}.
     */
    public static final int PROTECTION_FLAG_VERIFIER = 0x200;

    /**
     * Additional flag for {@link #protectionLevel}, corresponding
     * to the <code>preinstalled</code> value of
     * {@link android.R.attr#protectionLevel}.
     */
    public static final int PROTECTION_FLAG_PREINSTALLED = 0x400;

    /**
     * Mask for {@link #protectionLevel}: the basic protection type.
     */
    public static final int PROTECTION_MASK_BASE = 0xf;

    /**
     * Mask for {@link #protectionLevel}: additional flag bits.
     */
    public static final int PROTECTION_MASK_FLAGS = 0xff0;

    /**
     * The level of access this permission is protecting, as per
     * {@link android.R.attr#protectionLevel}.  Values may be
     * {@link #PROTECTION_NORMAL}, {@link #PROTECTION_DANGEROUS}, or
     * {@link #PROTECTION_SIGNATURE}.  May also include the additional
     * flags {@link #PROTECTION_FLAG_SYSTEM} or {@link #PROTECTION_FLAG_DEVELOPMENT}
     * (which only make sense in combination with the base
     * {@link #PROTECTION_SIGNATURE}.
     */
    public int protectionLevel;

    /**
     * The group this permission is a part of, as per
     * {@link android.R.attr#permissionGroup}.
     */
    public String group;

    /**
     * Flag for {@link #flags}, corresponding to <code>costsMoney</code>
     * value of {@link android.R.attr#permissionFlags}.
     */
    public static final int FLAG_COSTS_MONEY = 1<<0;

    /**
     * Flag for {@link #flags}, corresponding to <code>hidden</code>
     * value of {@link android.R.attr#permissionFlags}.
     * @hide
     */
    public static final int FLAG_HIDDEN = 1<<1;

    /**
     * Flag for {@link #flags}, indicating that this permission has been
     * installed into the system's globally defined permissions.
     */
    public static final int FLAG_INSTALLED = 1<<30;

    /**
     * Additional flags about this permission as given by
     * {@link android.R.attr#permissionFlags}.
     */
    public int flags;

    /**
     * A string resource identifier (in the package's resources) of this
     * permission's description.  From the "description" attribute or,
     * if not set, 0.
     */
    public int descriptionRes;

    /**
     * The description string provided in the AndroidManifest file, if any.  You
     * probably don't want to use this, since it will be null if the description
     * is in a resource.  You probably want
     * {@link PermissionInfo#loadDescription} instead.
     */
    public CharSequence nonLocalizedDescription;

    /** @hide */
    public static int fixProtectionLevel(int level) {
        if (level == PROTECTION_SIGNATURE_OR_SYSTEM) {
            level = PROTECTION_SIGNATURE | PROTECTION_FLAG_PRIVILEGED;
        }
        return level;
    }

    /** @hide */
    public static String protectionToString(int level) {
        String protLevel = "????";
        switch (level&PROTECTION_MASK_BASE) {
            case PermissionInfo.PROTECTION_DANGEROUS:
                protLevel = "dangerous";
                break;
            case PermissionInfo.PROTECTION_NORMAL:
                protLevel = "normal";
                break;
            case PermissionInfo.PROTECTION_SIGNATURE:
                protLevel = "signature";
                break;
            case PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM:
                protLevel = "signatureOrSystem";
                break;
        }
        if ((level&PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0) {
            protLevel += "|privileged";
        }
        if ((level&PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0) {
            protLevel += "|development";
        }
        if ((level&PermissionInfo.PROTECTION_FLAG_APPOP) != 0) {
            protLevel += "|appop";
        }
        if ((level&PermissionInfo.PROTECTION_FLAG_PRE23) != 0) {
            protLevel += "|pre23";
        }
        if ((level&PermissionInfo.PROTECTION_FLAG_INSTALLER) != 0) {
            protLevel += "|installer";
        }
        if ((level&PermissionInfo.PROTECTION_FLAG_VERIFIER) != 0) {
            protLevel += "|verifier";
        }
        if ((level&PermissionInfo.PROTECTION_FLAG_PREINSTALLED) != 0) {
            protLevel += "|preinstalled";
        }
        return protLevel;
    }

    public PermissionInfo() {
    }

    public PermissionInfo(PermissionInfo orig) {
        super(orig);
        protectionLevel = orig.protectionLevel;
        flags = orig.flags;
        group = orig.group;
        descriptionRes = orig.descriptionRes;
        nonLocalizedDescription = orig.nonLocalizedDescription;
    }

    /**
     * Retrieve the textual description of this permission.  This
     * will call back on the given PackageManager to load the description from
     * the application.
     *
     * @param pm A PackageManager from which the label can be loaded; usually
     * the PackageManager from which you originally retrieved this item.
     *
     * @return Returns a CharSequence containing the permission's description.
     * If there is no description, null is returned.
     */
    public CharSequence loadDescription(PackageManager pm) {
        if (nonLocalizedDescription != null) {
            return nonLocalizedDescription;
        }
        if (descriptionRes != 0) {
            CharSequence label = pm.getText(packageName, descriptionRes, null);
            if (label != null) {
                return label;
            }
        }
        return null;
    }

    public String toString() {
        return "PermissionInfo{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + name + "}";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        super.writeToParcel(dest, parcelableFlags);
        dest.writeInt(protectionLevel);
        dest.writeInt(flags);
        dest.writeString(group);
        dest.writeInt(descriptionRes);
        TextUtils.writeToParcel(nonLocalizedDescription, dest, parcelableFlags);
    }

    public static final Creator<PermissionInfo> CREATOR =
        new Creator<PermissionInfo>() {
        public PermissionInfo createFromParcel(Parcel source) {
            return new PermissionInfo(source);
        }
        public PermissionInfo[] newArray(int size) {
            return new PermissionInfo[size];
        }
    };

    private PermissionInfo(Parcel source) {
        super(source);
        protectionLevel = source.readInt();
        flags = source.readInt();
        group = source.readString();
        descriptionRes = source.readInt();
        nonLocalizedDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
    }
}
