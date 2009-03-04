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
     * System-level value for {@link #protectionLevel}, corresponding
     * to the <code>signatureOrSystem</code> value of
     * {@link android.R.attr#protectionLevel}.
     */
    public static final int PROTECTION_SIGNATURE_OR_SYSTEM = 3;

    /**
     * The group this permission is a part of, as per
     * {@link android.R.attr#permissionGroup}.
     */
    public String group;
    
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

    /**
     * The level of access this permission is protecting, as per
     * {@link android.R.attr#protectionLevel}.  Values may be
     * {@link #PROTECTION_NORMAL}, {@link #PROTECTION_DANGEROUS}, or
     * {@link #PROTECTION_SIGNATURE}.
     */
    public int protectionLevel;

    public PermissionInfo() {
    }

    public PermissionInfo(PermissionInfo orig) {
        super(orig);
        group = orig.group;
        descriptionRes = orig.descriptionRes;
        protectionLevel = orig.protectionLevel;
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
        dest.writeString(group);
        dest.writeInt(descriptionRes);
        dest.writeInt(protectionLevel);
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
        group = source.readString();
        descriptionRes = source.readInt();
        protectionLevel = source.readInt();
        nonLocalizedDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
    }
}
