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

import static android.content.res.Resources.ID_NULL;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Information you can retrieve about a particular security permission
 * group known to the system.  This corresponds to information collected from the
 * AndroidManifest.xml's &lt;permission-group&gt; tags.
 */
public class PermissionGroupInfo extends PackageItemInfo implements Parcelable {
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
     * A string resource identifier (in the package's resources) used as subtitle when requesting
     * only access while in the foreground.
     *
     * From the "requestDetail" attribute or, if not set, {@link
     * android.content.res.Resources#ID_NULL}.
     *
     * @hide
     */
    @SystemApi
    public final @StringRes int requestDetailResourceId;

    /**
     * A string resource identifier (in the package's resources) used when requesting background
     * access. Also used when requesting both foreground and background access.
     *
     * From the "backgroundRequest" attribute or, if not set, {@link
     * android.content.res.Resources#ID_NULL}.
     *
     * @hide
     */
    @SystemApi
    public final @StringRes int backgroundRequestResourceId;

    /**
     * A string resource identifier (in the package's resources) used as subtitle when requesting
     * background access.
     *
     * From the "backgroundRequestDetail" attribute or, if not set, {@link
     * android.content.res.Resources#ID_NULL}.
     *
     * @hide
     */
    @SystemApi
    public final @StringRes int backgroundRequestDetailResourceId;

    /**
     * The description string provided in the AndroidManifest file, if any.  You
     * probably don't want to use this, since it will be null if the description
     * is in a resource.  You probably want
     * {@link PermissionInfo#loadDescription} instead.
     */
    public @Nullable CharSequence nonLocalizedDescription;

    /**
     * Flag for {@link #flags}, corresponding to <code>personalInfo</code>
     * value of {@link android.R.attr#permissionGroupFlags}.
     */
    public static final int FLAG_PERSONAL_INFO = 1<<0;

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_PERSONAL_INFO,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Flags {}

    /**
     * Additional flags about this group as given by
     * {@link android.R.attr#permissionGroupFlags}.
     */
    public @Flags int flags;

    /**
     * Prioritization of this group, for visually sorting with other groups.
     */
    public int priority;

    /**
     * @hide
     */
    public PermissionGroupInfo(@StringRes int requestDetailResourceId,
            @StringRes int backgroundRequestResourceId,
            @StringRes int backgroundRequestDetailResourceId) {
        this.requestDetailResourceId = requestDetailResourceId;
        this.backgroundRequestResourceId = backgroundRequestResourceId;
        this.backgroundRequestDetailResourceId = backgroundRequestDetailResourceId;
    }

    /**
     * @deprecated Should only be created by the system.
     */
    @Deprecated
    public PermissionGroupInfo() {
        this(ID_NULL, ID_NULL, ID_NULL);
    }

    /**
     * @deprecated Should only be created by the system.
     */
    @Deprecated
    public PermissionGroupInfo(@NonNull PermissionGroupInfo orig) {
        super(orig);
        descriptionRes = orig.descriptionRes;
        requestRes = orig.requestRes;
        requestDetailResourceId = orig.requestDetailResourceId;
        backgroundRequestResourceId = orig.backgroundRequestResourceId;
        backgroundRequestDetailResourceId = orig.backgroundRequestDetailResourceId;
        nonLocalizedDescription = orig.nonLocalizedDescription;
        flags = orig.flags;
        priority = orig.priority;
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

    public String toString() {
        return "PermissionGroupInfo{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + name + " flgs=0x" + Integer.toHexString(flags) + "}";
    }

    public int describeContents() {
        return 0;
    }

    public void writeToParcel(Parcel dest, int parcelableFlags) {
        super.writeToParcel(dest, parcelableFlags);
        dest.writeInt(descriptionRes);
        dest.writeInt(requestRes);
        dest.writeInt(requestDetailResourceId);
        dest.writeInt(backgroundRequestResourceId);
        dest.writeInt(backgroundRequestDetailResourceId);
        TextUtils.writeToParcel(nonLocalizedDescription, dest, parcelableFlags);
        dest.writeInt(flags);
        dest.writeInt(priority);
    }

    public static final @NonNull Creator<PermissionGroupInfo> CREATOR =
            new Creator<PermissionGroupInfo>() {
        public PermissionGroupInfo createFromParcel(Parcel source) {
            return new PermissionGroupInfo(source);
        }
        public PermissionGroupInfo[] newArray(int size) {
            return new PermissionGroupInfo[size];
        }
    };

    private PermissionGroupInfo(Parcel source) {
        super(source);
        descriptionRes = source.readInt();
        requestRes = source.readInt();
        requestDetailResourceId = source.readInt();
        backgroundRequestResourceId = source.readInt();
        backgroundRequestDetailResourceId = source.readInt();
        nonLocalizedDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(source);
        flags = source.readInt();
        priority = source.readInt();
    }
}
