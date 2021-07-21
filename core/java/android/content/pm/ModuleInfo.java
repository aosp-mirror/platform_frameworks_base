/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Information you can retrieve about a particular system
 * module.
 */
public final class ModuleInfo implements Parcelable {

     // NOTE: When adding new data members be sure to update the copy-constructor, Parcel
     // constructor, and writeToParcel.

    /** Public name of this module. */
    private CharSequence mName;

    /** The package name of this module. */
    private String mPackageName;

    /**
     * The name of the APEX this module is distributed as, or null if it is not distributed via
     * APEX.
     */
    @Nullable private String mApexModuleName;

    /** Whether or not this module is hidden from the user. */
    private boolean mHidden;

    // TODO: Decide whether we need an additional metadata bundle to support out of band
    // updates to ModuleInfo.
    //
    // private Bundle mMetadata;

    /** @hide */
    public ModuleInfo() {
    }

    /** @hide */
    public ModuleInfo(ModuleInfo orig) {
        mName = orig.mName;
        mPackageName = orig.mPackageName;
        mHidden = orig.mHidden;
        mApexModuleName = orig.mApexModuleName;
    }

    /** @hide Sets the public name of this module. */
    public ModuleInfo setName(CharSequence name) {
        mName = name;
        return this;
    }

    /** Gets the public name of this module. */
    public @Nullable CharSequence getName() {
        return mName;
    }

    /** @hide Sets the package name of this module. */
    public ModuleInfo setPackageName(String packageName) {
        mPackageName = packageName;
        return this;
    }

    /** Gets the package name of this module. */
    public @Nullable String getPackageName() {
        return mPackageName;
    }

    /** @hide Sets whether or not this package is hidden. */
    public ModuleInfo setHidden(boolean hidden) {
        mHidden = hidden;
        return this;
    }

    /** Gets whether or not this package is hidden. */
    public boolean isHidden() {
        return mHidden;
    }

    /** @hide Sets the apex module name. */
    public ModuleInfo setApexModuleName(@Nullable String apexModuleName) {
        mApexModuleName = apexModuleName;
        return this;
    }

    /** @hide Gets the apex module name. */
    public @Nullable String getApexModuleName() {
        return mApexModuleName;
    }

    /** Returns a string representation of this object. */
    public String toString() {
        return "ModuleInfo{"
            + Integer.toHexString(System.identityHashCode(this))
            + " " + mName + "}";
    }

    /** Describes the kinds of special objects contained in this object. */
    public int describeContents() {
        return 0;
    }

    @Override
    public int hashCode() {
        int hashCode = 0;
        hashCode = 31 * hashCode + Objects.hashCode(mName);
        hashCode = 31 * hashCode + Objects.hashCode(mPackageName);
        hashCode = 31 * hashCode + Objects.hashCode(mApexModuleName);
        hashCode = 31 * hashCode + Boolean.hashCode(mHidden);
        return hashCode;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (!(obj instanceof ModuleInfo)) {
            return false;
        }
        final ModuleInfo other = (ModuleInfo) obj;
        return Objects.equals(mName, other.mName)
                && Objects.equals(mPackageName, other.mPackageName)
                && Objects.equals(mApexModuleName, other.mApexModuleName)
                && mHidden == other.mHidden;
    }

    /** Flattens this object into the given {@link Parcel}. */
    public void writeToParcel(Parcel dest, int parcelableFlags) {
        dest.writeCharSequence(mName);
        dest.writeString(mPackageName);
        dest.writeBoolean(mHidden);
        dest.writeString(mApexModuleName);
    }

    private ModuleInfo(Parcel source) {
        mName = source.readCharSequence();
        mPackageName = source.readString();
        mHidden = source.readBoolean();
        mApexModuleName = source.readString();
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ModuleInfo> CREATOR =
            new Parcelable.Creator<ModuleInfo>() {
        public ModuleInfo createFromParcel(Parcel source) {
            return new ModuleInfo(source);
        }
        public ModuleInfo[] newArray(int size) {
            return new ModuleInfo[size];
        }
    };
}
