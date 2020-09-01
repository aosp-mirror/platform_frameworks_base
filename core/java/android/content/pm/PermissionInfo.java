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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

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

    /** @hide */
    @IntDef(flag = false, prefix = { "PROTECTION_" }, value = {
            PROTECTION_NORMAL,
            PROTECTION_DANGEROUS,
            PROTECTION_SIGNATURE,
            PROTECTION_SIGNATURE_OR_SYSTEM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Protection {}

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
     * Additional flag for {@link #protectionLevel}, corresponding
     * to the <code>setup</code> value of
     * {@link android.R.attr#protectionLevel}.
     */
    public static final int PROTECTION_FLAG_SETUP = 0x800;

    /**
     * Additional flag for {@link #protectionLevel}, corresponding
     * to the <code>instant</code> value of
     * {@link android.R.attr#protectionLevel}.
     */
    public static final int PROTECTION_FLAG_INSTANT = 0x1000;

    /**
     * Additional flag for {@link #protectionLevel}, corresponding
     * to the <code>runtime</code> value of
     * {@link android.R.attr#protectionLevel}.
     */
    public static final int PROTECTION_FLAG_RUNTIME_ONLY = 0x2000;

    /**
     * Additional flag for {@link #protectionLevel}, corresponding
     * to the <code>oem</code> value of
     * {@link android.R.attr#protectionLevel}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int PROTECTION_FLAG_OEM = 0x4000;

    /**
     * Additional flag for {${link #protectionLevel}, corresponding
     * to the <code>vendorPrivileged</code> value of
     * {@link android.R.attr#protectionLevel}.
     *
     * @hide
     */
    @TestApi
    public static final int PROTECTION_FLAG_VENDOR_PRIVILEGED = 0x8000;

    /**
     * Additional flag for {@link #protectionLevel}, corresponding
     * to the <code>text_classifier</code> value of
     * {@link android.R.attr#protectionLevel}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int PROTECTION_FLAG_SYSTEM_TEXT_CLASSIFIER = 0x10000;

    /**
     * Additional flag for {${link #protectionLevel}, corresponding
     * to the <code>wellbeing</code> value of
     * {@link android.R.attr#protectionLevel}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int PROTECTION_FLAG_WELLBEING = 0x20000;

    /**
     * Additional flag for {@link #protectionLevel}, corresponding to the
     * {@code documenter} value of {@link android.R.attr#protectionLevel}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int PROTECTION_FLAG_DOCUMENTER = 0x40000;

    /**
     * Additional flag for {@link #protectionLevel}, corresponding to the
     * {@code configurator} value of {@link android.R.attr#protectionLevel}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int PROTECTION_FLAG_CONFIGURATOR = 0x80000;

    /**
     * Additional flag for {${link #protectionLevel}, corresponding
     * to the <code>incident_report_approver</code> value of
     * {@link android.R.attr#protectionLevel}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int PROTECTION_FLAG_INCIDENT_REPORT_APPROVER = 0x100000;

    /**
     * Additional flag for {@link #protectionLevel}, corresponding
     * to the <code>app_predictor</code> value of
     * {@link android.R.attr#protectionLevel}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int PROTECTION_FLAG_APP_PREDICTOR = 0x200000;

    /**
     * Additional flag for {@link #protectionLevel}, corresponding
     * to the <code>companion</code> value of
     * {@link android.R.attr#protectionLevel}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int PROTECTION_FLAG_COMPANION = 0x800000;

    /**
     * Additional flag for {@link #protectionLevel}, corresponding
     * to the <code>retailDemo</code> value of
     * {@link android.R.attr#protectionLevel}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int PROTECTION_FLAG_RETAIL_DEMO = 0x1000000;

    /** @hide */
    @IntDef(flag = true, prefix = { "PROTECTION_FLAG_" }, value = {
            PROTECTION_FLAG_PRIVILEGED,
            PROTECTION_FLAG_SYSTEM,
            PROTECTION_FLAG_DEVELOPMENT,
            PROTECTION_FLAG_APPOP,
            PROTECTION_FLAG_PRE23,
            PROTECTION_FLAG_INSTALLER,
            PROTECTION_FLAG_VERIFIER,
            PROTECTION_FLAG_PREINSTALLED,
            PROTECTION_FLAG_SETUP,
            PROTECTION_FLAG_INSTANT,
            PROTECTION_FLAG_RUNTIME_ONLY,
            PROTECTION_FLAG_OEM,
            PROTECTION_FLAG_VENDOR_PRIVILEGED,
            PROTECTION_FLAG_SYSTEM_TEXT_CLASSIFIER,
            PROTECTION_FLAG_WELLBEING,
            PROTECTION_FLAG_DOCUMENTER,
            PROTECTION_FLAG_CONFIGURATOR,
            PROTECTION_FLAG_INCIDENT_REPORT_APPROVER,
            PROTECTION_FLAG_APP_PREDICTOR,
            PROTECTION_FLAG_COMPANION,
            PROTECTION_FLAG_RETAIL_DEMO,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProtectionFlags {}

    /**
     * Mask for {@link #protectionLevel}: the basic protection type.
     *
     * @deprecated Use #getProtection() instead.
     */
    @Deprecated
    public static final int PROTECTION_MASK_BASE = 0xf;

    /**
     * Mask for {@link #protectionLevel}: additional flag bits.
     *
     * @deprecated Use #getProtectionFlags() instead.
     */
    @Deprecated
    public static final int PROTECTION_MASK_FLAGS = 0xfff0;

    /**
     * The level of access this permission is protecting, as per
     * {@link android.R.attr#protectionLevel}. Consists of
     * a base permission type and zero or more flags. Use the following functions
     * to extract them.
     *
     * <pre>
     * int basePermissionType = permissionInfo.getProtection();
     * int permissionFlags = permissionInfo.getProtectionFlags();
     * </pre>
     *
     * <p></p>Base permission types are {@link #PROTECTION_NORMAL},
     * {@link #PROTECTION_DANGEROUS}, {@link #PROTECTION_SIGNATURE}
     * and the deprecated {@link #PROTECTION_SIGNATURE_OR_SYSTEM}.
     * Flags are listed under {@link android.R.attr#protectionLevel}.
     *
     * @deprecated Use #getProtection() and #getProtectionFlags() instead.
     */
    @Deprecated
    public int protectionLevel;

    /**
     * The group this permission is a part of, as per
     * {@link android.R.attr#permissionGroup}.
     */
    public @Nullable String group;

    /**
     * Flag for {@link #flags}, corresponding to <code>costsMoney</code>
     * value of {@link android.R.attr#permissionFlags}.
     */
    public static final int FLAG_COSTS_MONEY = 1<<0;

    /**
     * Flag for {@link #flags}, corresponding to <code>removed</code>
     * value of {@link android.R.attr#permissionFlags}.
     * @hide
     */
    @TestApi
    @SystemApi
    public static final int FLAG_REMOVED = 1<<1;

    /**
     * Flag for {@link #flags}, corresponding to <code>hardRestricted</code>
     * value of {@link android.R.attr#permissionFlags}.
     *
     * <p> This permission is restricted by the platform and it would be
     * grantable only to apps that meet special criteria per platform
     * policy.
     */
    public static final int FLAG_HARD_RESTRICTED = 1<<2;

    /**
     * Flag for {@link #flags}, corresponding to <code>softRestricted</code>
     * value of {@link android.R.attr#permissionFlags}.
     *
     * <p>This permission is restricted by the platform and it would be
     * grantable in its full form to apps that meet special criteria
     * per platform policy. Otherwise, a weaker form of the permission
     * would be granted. The weak grant depends on the permission.
     */
    public static final int FLAG_SOFT_RESTRICTED = 1<<3;

    /**
     * Flag for {@link #flags}, corresponding to <code>immutablyRestricted</code>
     * value of {@link android.R.attr#permissionFlags}.
     *
     * <p>This permission is restricted immutably which means that its
     * restriction state may be specified only on the first install of
     * the app and will stay in this initial whitelist state until
     * the app is uninstalled.
     */
    public static final int FLAG_IMMUTABLY_RESTRICTED = 1<<4;

    /**
     * Flag for {@link #flags}, indicating that this permission has been
     * installed into the system's globally defined permissions.
     */
    public static final int FLAG_INSTALLED = 1<<30;

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_COSTS_MONEY,
            FLAG_REMOVED,
            FLAG_HARD_RESTRICTED,
            FLAG_SOFT_RESTRICTED,
            FLAG_IMMUTABLY_RESTRICTED,
            FLAG_INSTALLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Flags {}

    /**
     * Additional flags about this permission as given by
     * {@link android.R.attr#permissionFlags}.
     */
    public @Flags int flags;

    /**
     * A string resource identifier (in the package's resources) of this
     * permission's description.  From the "description" attribute or,
     * if not set, 0.
     */
    public @StringRes int descriptionRes;

    /**
     * A string resource identifier (in the package's resources) used to request the permissions.
     * From the "request" attribute or, if not set, 0.
     *
     * @hide
     */
    @SystemApi
    public @StringRes int requestRes;

    /**
     * Some permissions only grant access while the app is in foreground. Some of these permissions
     * allow to add background capabilities by adding another permission.
     *
     * If this is such a permission, this is the name of the permission adding the background
     * access.
     *
     * From the "backgroundPermission" attribute or, if not set null
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public final @Nullable String backgroundPermission;

    /**
     * The description string provided in the AndroidManifest file, if any.  You
     * probably don't want to use this, since it will be null if the description
     * is in a resource.  You probably want
     * {@link PermissionInfo#loadDescription} instead.
     */
    public @Nullable CharSequence nonLocalizedDescription;

    /** @hide */
    public static int fixProtectionLevel(int level) {
        if (level == PROTECTION_SIGNATURE_OR_SYSTEM) {
            level = PROTECTION_SIGNATURE | PROTECTION_FLAG_PRIVILEGED;
        }
        if ((level & PROTECTION_FLAG_VENDOR_PRIVILEGED) != 0
                && (level & PROTECTION_FLAG_PRIVILEGED) == 0) {
            // 'vendorPrivileged' must be 'privileged'. If not,
            // drop the vendorPrivileged.
            level = level & ~PROTECTION_FLAG_VENDOR_PRIVILEGED;
        }
        return level;
    }

    /** @hide */
    @UnsupportedAppUsage
    public static @NonNull String protectionToString(int level) {
        String protLevel = "????";
        switch (level & PROTECTION_MASK_BASE) {
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
        if ((level & PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0) {
            protLevel += "|privileged";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0) {
            protLevel += "|development";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_APPOP) != 0) {
            protLevel += "|appop";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_PRE23) != 0) {
            protLevel += "|pre23";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_INSTALLER) != 0) {
            protLevel += "|installer";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_VERIFIER) != 0) {
            protLevel += "|verifier";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_PREINSTALLED) != 0) {
            protLevel += "|preinstalled";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_SETUP) != 0) {
            protLevel += "|setup";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_INSTANT) != 0) {
            protLevel += "|instant";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_RUNTIME_ONLY) != 0) {
            protLevel += "|runtime";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_OEM) != 0) {
            protLevel += "|oem";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_VENDOR_PRIVILEGED) != 0) {
            protLevel += "|vendorPrivileged";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_SYSTEM_TEXT_CLASSIFIER) != 0) {
            protLevel += "|textClassifier";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_WELLBEING) != 0) {
            protLevel += "|wellbeing";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_DOCUMENTER) != 0) {
            protLevel += "|documenter";
        }
        if ((level & PROTECTION_FLAG_CONFIGURATOR) != 0) {
            protLevel += "|configurator";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_INCIDENT_REPORT_APPROVER) != 0) {
            protLevel += "|incidentReportApprover";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_APP_PREDICTOR) != 0) {
            protLevel += "|appPredictor";
        }
        if ((level & PermissionInfo.PROTECTION_FLAG_RETAIL_DEMO) != 0) {
            protLevel += "|retailDemo";
        }
        return protLevel;
    }

    /**
     * @hide
     */
    public PermissionInfo(@Nullable String backgroundPermission) {
        this.backgroundPermission = backgroundPermission;
    }

    /**
     * @deprecated Should only be created by the system.
     */
    @Deprecated
    public PermissionInfo() {
        this((String) null);
    }

    /**
     * @deprecated Should only be created by the system.
     */
    @Deprecated
    public PermissionInfo(@NonNull PermissionInfo orig) {
        super(orig);
        protectionLevel = orig.protectionLevel;
        flags = orig.flags;
        group = orig.group;
        backgroundPermission = orig.backgroundPermission;
        descriptionRes = orig.descriptionRes;
        requestRes = orig.requestRes;
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
    public @Nullable CharSequence loadDescription(@NonNull PackageManager pm) {
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

    /**
     * Return the base permission type.
     */
    @Protection
    public int getProtection() {
        return protectionLevel & PROTECTION_MASK_BASE;
    }

    /**
     * Return the additional flags in {@link #protectionLevel}.
     */
    @ProtectionFlags
    public int getProtectionFlags() {
        return protectionLevel & ~PROTECTION_MASK_BASE;
    }

    @Override
    public String toString() {
        return "PermissionInfo{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + name + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        super.writeToParcel(dest, parcelableFlags);
        dest.writeInt(protectionLevel);
        dest.writeInt(flags);
        dest.writeString8(group);
        dest.writeString8(backgroundPermission);
        dest.writeInt(descriptionRes);
        dest.writeInt(requestRes);
        TextUtils.writeToParcel(nonLocalizedDescription, dest, parcelableFlags);
    }

    /** @hide */
    public int calculateFootprint() {
        int size = name.length();
        if (nonLocalizedLabel != null) {
            size += nonLocalizedLabel.length();
        }
        if (nonLocalizedDescription != null) {
            size += nonLocalizedDescription.length();
        }
        return size;
    }

    /** @hide */
    public boolean isHardRestricted() {
        return (flags & PermissionInfo.FLAG_HARD_RESTRICTED) != 0;
    }

    /** @hide */
    public boolean isSoftRestricted() {
        return (flags & PermissionInfo.FLAG_SOFT_RESTRICTED) != 0;
    }

    /** @hide */
    public boolean isRestricted() {
        return isHardRestricted() || isSoftRestricted();
    }

    /** @hide */
    public boolean isAppOp() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_APPOP) != 0;
    }

    /** @hide */
    public boolean isRuntime() {
        return getProtection() == PROTECTION_DANGEROUS;
    }

    public static final @NonNull Creator<PermissionInfo> CREATOR =
        new Creator<PermissionInfo>() {
        @Override
        public PermissionInfo createFromParcel(Parcel source) {
            return new PermissionInfo(source);
        }
        @Override
        public PermissionInfo[] newArray(int size) {
            return new PermissionInfo[size];
        }
    };

    private PermissionInfo(Parcel source) {
        super(source);
        protectionLevel = source.readInt();
        flags = source.readInt();
        group = source.readString8();
        backgroundPermission = source.readString8();
        descriptionRes = source.readInt();
        requestRes = source.readInt();
        nonLocalizedDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
    }
}
