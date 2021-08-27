/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.content.pm.parsing.ParsingPackageImpl.sForInternedString;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.content.pm.PackageInfo;
import android.os.Parcel;
import android.os.Parcelable;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A {@link android.R.styleable#AndroidManifestUsesPermission
 * &lt;uses-permission&gt;} tag parsed from the manifest.
 *
 * @hide
 */
public class ParsedUsesPermission implements Parcelable {
    /** Name of the permission requested */
    public @NonNull String name;

    /** Set of flags that should apply to this permission request. */
    public @UsesPermissionFlags int usesPermissionFlags;

    /**
     * Strong assertion by a developer that they will never use this permission
     * to derive the physical location of the device, regardless of
     * ACCESS_FINE_LOCATION and/or ACCESS_COARSE_LOCATION being granted.
     */
    public static final int FLAG_NEVER_FOR_LOCATION =
            PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION;

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_NEVER_FOR_LOCATION
    })
    public @interface UsesPermissionFlags {}

    public ParsedUsesPermission(@NonNull String name,
            @UsesPermissionFlags int usesPermissionFlags) {
        this.name = name.intern();
        this.usesPermissionFlags = usesPermissionFlags;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        sForInternedString.parcel(this.name, dest, flags);
        dest.writeInt(usesPermissionFlags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    protected ParsedUsesPermission(@NonNull Parcel in) {
        this.name = sForInternedString.unparcel(in);
        this.usesPermissionFlags = in.readInt();
    }

    public static final @NonNull Parcelable.Creator<ParsedUsesPermission> CREATOR
            = new Parcelable.Creator<ParsedUsesPermission>() {
        @Override
        public ParsedUsesPermission[] newArray(int size) {
            return new ParsedUsesPermission[size];
        }

        @Override
        public ParsedUsesPermission createFromParcel(@NonNull Parcel in) {
            return new ParsedUsesPermission(in);
        }
    };
}
