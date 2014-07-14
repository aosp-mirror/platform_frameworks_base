/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.telecomm;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.IOException;
import java.io.ObjectStreamException;
import java.io.Serializable;
import java.util.MissingResourceException;

/**
 * Provides user interface description information for a {@code PhoneAccount}.
 */
public class PhoneAccountMetadata implements Parcelable {
    private PhoneAccount mAccount;
    private int mIconResId;
    private String mLabel;
    private String mShortDescription;

    public PhoneAccountMetadata(
            PhoneAccount account,
            int iconResId,
            String label,
            String shortDescription) {
        mAccount = account;
        mIconResId = iconResId;
        mLabel = label;
        mShortDescription = shortDescription;
    }

    /**
     * The {@code PhoneAccount} to which this metadata pertains.
     *
     * @return A {@code PhoneAccount}.
     */
    public PhoneAccount getAccount() {
        return mAccount;
    }

    /**
     * A short string label describing a {@code PhoneAccount}.
     *
     * @return A label for this {@code PhoneAccount}.
     */
    public String getLabel() {
        return mLabel;
    }

    /**
     * A short paragraph describing a {@code PhoneAccount}.
     *
     * @return A description for this {@code PhoneAccount}.
     */
    public String getShortDescription() {
        return mShortDescription;
    }

    /**
     * An icon to represent this {@code PhoneAccount} in a user interface.
     *
     * @return An icon for this {@code PhoneAccount}.
     */
    public Drawable getIcon(Context context) {
        return getIcon(context, mIconResId);
    }

    private Drawable getIcon(Context context, int resId) {
        Context packageContext;
        try {
            packageContext = context.createPackageContext(
                    mAccount.getComponentName().getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(this, "Cannot find package %s", mAccount.getComponentName().getPackageName());
            return null;
        }
        try {
            return packageContext.getResources().getDrawable(resId);
        } catch (MissingResourceException e) {
            Log.e(this, e, "Cannot find icon %d in package %s",
                    resId, mAccount.getComponentName().getPackageName());
            return null;
        }
    }

    //
    // Parcelable implementation
    //

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(mAccount, 0);
        out.writeInt(mIconResId);
        out.writeString(mLabel);
        out.writeString(mShortDescription);
    }

    public static final Creator<PhoneAccountMetadata> CREATOR
            = new Creator<PhoneAccountMetadata>() {
        @Override
        public PhoneAccountMetadata createFromParcel(Parcel in) {
            return new PhoneAccountMetadata(in);
        }

        @Override
        public PhoneAccountMetadata[] newArray(int size) {
            return new PhoneAccountMetadata[size];
        }
    };

    private PhoneAccountMetadata(Parcel in) {
        mAccount = in.readParcelable(getClass().getClassLoader());
        mIconResId = in.readInt();
        mLabel = in.readString();
        mShortDescription = in.readString();
    }
}
