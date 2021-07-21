/*
 * Copyright (C) 2009 The Android Open Source Project
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

package android.accounts;

import android.annotation.Nullable;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * A {@link Parcelable} value type that contains information about an account authenticator.
 */
public class AuthenticatorDescription implements Parcelable {
    /** The string that uniquely identifies an authenticator */
    final public String type;

    /** A resource id of a label for the authenticator that is suitable for displaying */
    final public int labelId;

    /** A resource id of a icon for the authenticator */
    final public int iconId;

    /** A resource id of a smaller icon for the authenticator */
    final public int smallIconId;

    /**
     * A resource id for a hierarchy of PreferenceScreen to be added to the settings page for the
     * account. See {@link AbstractAccountAuthenticator} for an example.
     */
    final public int accountPreferencesId;

    /** The package name that can be used to lookup the resources from above. */
    final public String packageName;

    /** Authenticator handles its own token caching and permission screen */
    final public boolean customTokens;

    /** A constructor for a full AuthenticatorDescription */
    public AuthenticatorDescription(String type, String packageName, int labelId, int iconId,
            int smallIconId, int prefId, boolean customTokens) {
        if (type == null) throw new IllegalArgumentException("type cannot be null");
        if (packageName == null) throw new IllegalArgumentException("packageName cannot be null");
        this.type = type;
        this.packageName = packageName;
        this.labelId = labelId;
        this.iconId = iconId;
        this.smallIconId = smallIconId;
        this.accountPreferencesId = prefId;
        this.customTokens = customTokens;
    }

    public AuthenticatorDescription(String type, String packageName, int labelId, int iconId,
            int smallIconId, int prefId) {
        this(type, packageName, labelId, iconId, smallIconId, prefId, false);
    }

    /**
     * A factory method for creating an AuthenticatorDescription that can be used as a key
     * to identify the authenticator by its type.
     */

    public static AuthenticatorDescription newKey(String type) {
        if (type == null) throw new IllegalArgumentException("type cannot be null");
        return new AuthenticatorDescription(type);
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private AuthenticatorDescription(String type) {
        this.type = type;
        this.packageName = null;
        this.labelId = 0;
        this.iconId = 0;
        this.smallIconId = 0;
        this.accountPreferencesId = 0;
        this.customTokens = false;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private AuthenticatorDescription(Parcel source) {
        this.type = source.readString();
        this.packageName = source.readString();
        this.labelId = source.readInt();
        this.iconId = source.readInt();
        this.smallIconId = source.readInt();
        this.accountPreferencesId = source.readInt();
        this.customTokens = source.readByte() == 1;
    }

    /** @inheritDoc */
    public int describeContents() {
        return 0;
    }

    /** Returns the hashcode of the type only. */
    public int hashCode() {
        return type.hashCode();
    }

    /** Compares the type only, suitable for key comparisons. */
    public boolean equals(@Nullable Object o) {
        if (o == this) return true;
        if (!(o instanceof AuthenticatorDescription)) return false;
        final AuthenticatorDescription other = (AuthenticatorDescription) o;
        return type.equals(other.type);
    }

    public String toString() {
        return "AuthenticatorDescription {type=" + type + "}";
    }

    /** @inheritDoc */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(type);
        dest.writeString(packageName);
        dest.writeInt(labelId);
        dest.writeInt(iconId);
        dest.writeInt(smallIconId);
        dest.writeInt(accountPreferencesId);
        dest.writeByte((byte) (customTokens ? 1 : 0));
    }

    /** Used to create the object from a parcel. */
    public static final @android.annotation.NonNull Creator<AuthenticatorDescription> CREATOR =
            new Creator<AuthenticatorDescription>() {
        /** @inheritDoc */
        public AuthenticatorDescription createFromParcel(Parcel source) {
            return new AuthenticatorDescription(source);
        }

        /** @inheritDoc */
        public AuthenticatorDescription[] newArray(int size) {
            return new AuthenticatorDescription[size];
        }
    };
}
