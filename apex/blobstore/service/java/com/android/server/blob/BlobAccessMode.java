/*
 * Copyright 2020 The Android Open Source Project
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
package com.android.server.blob;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.app.blob.XmlTags.ATTR_CERTIFICATE;
import static android.app.blob.XmlTags.ATTR_PACKAGE;
import static android.app.blob.XmlTags.ATTR_TYPE;
import static android.app.blob.XmlTags.ATTR_VALUE;
import static android.app.blob.XmlTags.TAG_ALLOWED_PACKAGE;
import static android.app.blob.XmlTags.TAG_ALLOWED_PERMISSION;

import static com.android.server.blob.BlobStoreConfig.TAG;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.util.ArraySet;
import android.util.Base64;
import android.util.DebugUtils;
import android.util.Slog;

import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Objects;

/**
 * Class for representing how a blob can be shared.
 *
 * Note that this class is not thread-safe, callers need to take care of synchronizing access.
 */
class BlobAccessMode {
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, value = {
            ACCESS_TYPE_PRIVATE,
            ACCESS_TYPE_PUBLIC,
            ACCESS_TYPE_SAME_SIGNATURE,
            ACCESS_TYPE_ALLOWLIST,
            ACCESS_TYPE_LOCATION_PERMISSION,
    })
    @interface AccessType {}
    public static final int ACCESS_TYPE_PRIVATE = 1 << 0;
    public static final int ACCESS_TYPE_PUBLIC = 1 << 1;
    public static final int ACCESS_TYPE_SAME_SIGNATURE = 1 << 2;
    public static final int ACCESS_TYPE_ALLOWLIST = 1 << 3;
    public static final int ACCESS_TYPE_LOCATION_PERMISSION = 1 << 4;

    private int mAccessType = ACCESS_TYPE_PRIVATE;

    private final ArraySet<PackageIdentifier> mAllowedPackages = new ArraySet<>();
    private final ArraySet<String> mAllowedPermissions = new ArraySet<>();

    void allow(BlobAccessMode other) {
        if ((other.mAccessType & ACCESS_TYPE_ALLOWLIST) != 0) {
            mAllowedPackages.addAll(other.mAllowedPackages);
        }
        if ((other.mAccessType & ACCESS_TYPE_LOCATION_PERMISSION) != 0) {
            mAllowedPermissions.addAll(other.mAllowedPermissions);
        }
        mAccessType |= other.mAccessType;
    }

    void allowPublicAccess() {
        mAccessType |= ACCESS_TYPE_PUBLIC;
    }

    void allowSameSignatureAccess() {
        mAccessType |= ACCESS_TYPE_SAME_SIGNATURE;
    }

    void allowPackageAccess(@NonNull String packageName, @NonNull byte[] certificate) {
        mAccessType |= ACCESS_TYPE_ALLOWLIST;
        mAllowedPackages.add(PackageIdentifier.create(packageName, certificate));
    }

    void allowPackagesWithLocationPermission(@NonNull String permissionName) {
        mAccessType |= ACCESS_TYPE_LOCATION_PERMISSION;
        mAllowedPermissions.add(permissionName);
    }

    boolean isPublicAccessAllowed() {
        return (mAccessType & ACCESS_TYPE_PUBLIC) != 0;
    }

    boolean isSameSignatureAccessAllowed() {
        return (mAccessType & ACCESS_TYPE_SAME_SIGNATURE) != 0;
    }

    boolean isPackageAccessAllowed(@NonNull String packageName, @NonNull byte[] certificate) {
        if ((mAccessType & ACCESS_TYPE_ALLOWLIST) == 0) {
            return false;
        }
        return mAllowedPackages.contains(PackageIdentifier.create(packageName, certificate));
    }

    boolean arePackagesWithLocationPermissionAllowed(@NonNull String permissionName) {
        if ((mAccessType & ACCESS_TYPE_LOCATION_PERMISSION) == 0) {
            return false;
        }
        return mAllowedPermissions.contains(permissionName);
    }

    boolean isAccessAllowedForCaller(Context context, @NonNull String callingPackage,
            @NonNull String committerPackage, int callingUid, @Nullable String attributionTag) {
        if ((mAccessType & ACCESS_TYPE_PUBLIC) != 0) {
            return true;
        }

        final PackageManager pm = context.getPackageManager();
        if ((mAccessType & ACCESS_TYPE_SAME_SIGNATURE) != 0) {
            if (pm.checkSignatures(committerPackage, callingPackage)
                    == PackageManager.SIGNATURE_MATCH) {
                return true;
            }
        }

        if ((mAccessType & ACCESS_TYPE_ALLOWLIST) != 0) {
            for (int i = 0; i < mAllowedPackages.size(); ++i) {
                final PackageIdentifier packageIdentifier = mAllowedPackages.valueAt(i);
                if (packageIdentifier.packageName.equals(callingPackage)
                        && pm.hasSigningCertificate(callingPackage, packageIdentifier.certificate,
                                PackageManager.CERT_INPUT_SHA256)) {
                    return true;
                }
            }
        }

        if ((mAccessType & ACCESS_TYPE_LOCATION_PERMISSION) != 0) {
            final AppOpsManager appOpsManager = context.getSystemService(AppOpsManager.class);
            for (int i = 0; i < mAllowedPermissions.size(); ++i) {
                final String permission = mAllowedPermissions.valueAt(i);
                if (PermissionManager.checkPackageNamePermission(permission, callingPackage,
                        UserHandle.getUserId(callingUid)) != PackageManager.PERMISSION_GRANTED) {
                    continue;
                }
                // TODO: Add appropriate message
                if (appOpsManager.noteOpNoThrow(getAppOp(permission), callingUid, callingPackage,
                        attributionTag, null /* message */) == AppOpsManager.MODE_ALLOWED) {
                    return true;
                }
            }
        }

        return false;
    }

    private static String getAppOp(String permission) {
        switch (permission) {
            case ACCESS_FINE_LOCATION:
                return AppOpsManager.OPSTR_FINE_LOCATION;
            case ACCESS_COARSE_LOCATION:
                return AppOpsManager.OPSTR_COARSE_LOCATION;
            default:
                Slog.w(TAG, "Unknown permission found: " + permission);
                return null;
        }
    }

    int getAccessType() {
        return mAccessType;
    }

    int getAllowedPackagesCount() {
        return mAllowedPackages.size();
    }

    void dump(IndentingPrintWriter fout) {
        fout.println("accessType: " + DebugUtils.flagsToString(
                BlobAccessMode.class, "ACCESS_TYPE_", mAccessType));
        fout.print("Explicitly allowed pkgs:");
        if (mAllowedPackages.isEmpty()) {
            fout.println(" (Empty)");
        } else {
            fout.increaseIndent();
            for (int i = 0, count = mAllowedPackages.size(); i < count; ++i) {
                fout.println(mAllowedPackages.valueAt(i).toString());
            }
            fout.decreaseIndent();
        }
        fout.print("Allowed permissions:");
        if (mAllowedPermissions.isEmpty()) {
            fout.println(" (Empty)");
        } else {
            fout.increaseIndent();
            for (int i = 0, count = mAllowedPermissions.size(); i < count; ++i) {
                fout.println(mAllowedPermissions.valueAt(i).toString());
            }
            fout.decreaseIndent();
        }
    }

    void writeToXml(@NonNull XmlSerializer out) throws IOException {
        XmlUtils.writeIntAttribute(out, ATTR_TYPE, mAccessType);
        for (int i = 0, count = mAllowedPackages.size(); i < count; ++i) {
            out.startTag(null, TAG_ALLOWED_PACKAGE);
            final PackageIdentifier packageIdentifier = mAllowedPackages.valueAt(i);
            XmlUtils.writeStringAttribute(out, ATTR_PACKAGE, packageIdentifier.packageName);
            XmlUtils.writeByteArrayAttribute(out, ATTR_CERTIFICATE, packageIdentifier.certificate);
            out.endTag(null, TAG_ALLOWED_PACKAGE);
        }
        for (int i = 0, count = mAllowedPermissions.size(); i < count; ++i) {
            out.startTag(null, TAG_ALLOWED_PERMISSION);
            final String permission = mAllowedPermissions.valueAt(i);
            XmlUtils.writeStringAttribute(out, ATTR_VALUE, permission);
            out.endTag(null, TAG_ALLOWED_PERMISSION);
        }
    }

    @NonNull
    static BlobAccessMode createFromXml(@NonNull XmlPullParser in)
            throws IOException, XmlPullParserException {
        final BlobAccessMode blobAccessMode = new BlobAccessMode();

        final int accessType = XmlUtils.readIntAttribute(in, ATTR_TYPE);
        blobAccessMode.mAccessType = accessType;

        final int depth = in.getDepth();
        while (XmlUtils.nextElementWithin(in, depth)) {
            if (TAG_ALLOWED_PACKAGE.equals(in.getName())) {
                final String packageName = XmlUtils.readStringAttribute(in, ATTR_PACKAGE);
                final byte[] certificate = XmlUtils.readByteArrayAttribute(in, ATTR_CERTIFICATE);
                blobAccessMode.allowPackageAccess(packageName, certificate);
            }
            if (TAG_ALLOWED_PERMISSION.equals(in.getName())) {
                final String permission = XmlUtils.readStringAttribute(in, ATTR_VALUE);
                blobAccessMode.allowPackagesWithLocationPermission(permission);
            }
        }
        return blobAccessMode;
    }

    private static final class PackageIdentifier {
        public final String packageName;
        public final byte[] certificate;

        private PackageIdentifier(@NonNull String packageName, @NonNull byte[] certificate) {
            this.packageName = packageName;
            this.certificate = certificate;
        }

        public static PackageIdentifier create(@NonNull String packageName,
                @NonNull byte[] certificate) {
            return new PackageIdentifier(packageName, certificate);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || !(obj instanceof PackageIdentifier)) {
                return false;
            }
            final PackageIdentifier other = (PackageIdentifier) obj;
            return this.packageName.equals(other.packageName)
                    && Arrays.equals(this.certificate, other.certificate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(packageName, Arrays.hashCode(certificate));
        }

        @Override
        public String toString() {
            return "[" + packageName + ", "
                    + Base64.encodeToString(certificate, Base64.NO_WRAP) + "]";
        }
    }
}
