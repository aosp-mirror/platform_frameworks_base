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

package android.telecom;

import android.annotation.SystemApi;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.MissingResourceException;
import java.util.Objects;

/**
 * Contains status label and icon displayed in the in-call UI.
 * @hide
 */
@SystemApi
public final class StatusHints implements Parcelable {

    private final ComponentName mPackageName;
    private final CharSequence mLabel;
    private final int mIconResId;
    private final Bundle mExtras;

    public StatusHints(ComponentName packageName, CharSequence label, int iconResId,
            Bundle extras) {
        mPackageName = packageName;
        mLabel = label;
        mIconResId = iconResId;
        mExtras = extras;
    }

    /**
     * @return A package used to load the icon.
     */
    public ComponentName getPackageName() {
        return mPackageName;
    }

    /**
     * @return The label displayed in the in-call UI.
     */
    public CharSequence getLabel() {
        return mLabel;
    }

    /**
     * The icon resource ID for the icon to show.
     *
     * @return A resource ID.
     */
    public int getIconResId() {
        return mIconResId;
    }

    /**
     * @return An icon displayed in the in-call UI.
     */
    public Drawable getIcon(Context context) {
        return getIcon(context, mIconResId);
    }

    /**
     * @return Extra data used to display status.
     */
    public Bundle getExtras() {
        return mExtras;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(mPackageName, flags);
        out.writeCharSequence(mLabel);
        out.writeInt(mIconResId);
        out.writeParcelable(mExtras, 0);
    }

    public static final Creator<StatusHints> CREATOR
            = new Creator<StatusHints>() {
        public StatusHints createFromParcel(Parcel in) {
            return new StatusHints(in);
        }

        public StatusHints[] newArray(int size) {
            return new StatusHints[size];
        }
    };

    private StatusHints(Parcel in) {
        mPackageName = in.readParcelable(getClass().getClassLoader());
        mLabel = in.readCharSequence();
        mIconResId = in.readInt();
        mExtras = in.readParcelable(getClass().getClassLoader());
    }

    private Drawable getIcon(Context context, int resId) {
        Context packageContext;
        try {
            packageContext = context.createPackageContext(mPackageName.getPackageName(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(this, e, "Cannot find package %s", mPackageName.getPackageName());
            return null;
        }
        try {
            return packageContext.getDrawable(resId);
        } catch (MissingResourceException e) {
            Log.e(this, e, "Cannot find icon %d in package %s",
                    resId, mPackageName.getPackageName());
            return null;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (other != null && other instanceof StatusHints) {
            StatusHints otherHints = (StatusHints) other;
            return Objects.equals(otherHints.getPackageName(), getPackageName()) &&
                    Objects.equals(otherHints.getLabel(), getLabel()) &&
                    otherHints.getIconResId() == getIconResId() &&
                    Objects.equals(otherHints.getExtras(), getExtras());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mPackageName) + Objects.hashCode(mLabel) + mIconResId +
                Objects.hashCode(mExtras);
    }
}
