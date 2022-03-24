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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Contains status label and icon displayed in the in-call UI.
 */
public final class StatusHints implements Parcelable {

    private final CharSequence mLabel;
    private final Icon mIcon;
    private final Bundle mExtras;

    /**
     * @hide
     */
    @SystemApi @Deprecated
    public StatusHints(ComponentName packageName, CharSequence label, int iconResId,
            Bundle extras) {
        this(label, iconResId == 0 ? null : Icon.createWithResource(packageName.getPackageName(),
            iconResId), extras);
    }

    public StatusHints(CharSequence label, Icon icon, Bundle extras) {
        mLabel = label;
        mIcon = icon;
        mExtras = extras;
    }

    /**
     * @return A package used to load the icon.
     *
     * @hide
     */
    @SystemApi @Deprecated
    public ComponentName getPackageName() {
        // Minimal compatibility shim for legacy apps' tests
        return new ComponentName("", "");
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
     *
     * @hide
     */
    @SystemApi @Deprecated
    public int getIconResId() {
        // Minimal compatibility shim for legacy apps' tests
        return 0;
    }

    /**
     * @return An icon displayed in the in-call UI.
     *
     * @hide
     */
    @SystemApi @Deprecated
    public Drawable getIcon(Context context) {
        return mIcon.loadDrawable(context);
    }

    /**
     * @return An icon depicting the status.
     */
    public Icon getIcon() {
        return mIcon;
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
        out.writeCharSequence(mLabel);
        out.writeParcelable(mIcon, 0);
        out.writeParcelable(mExtras, 0);
    }

    public static final @android.annotation.NonNull Creator<StatusHints> CREATOR
            = new Creator<StatusHints>() {
        public StatusHints createFromParcel(Parcel in) {
            return new StatusHints(in);
        }

        public StatusHints[] newArray(int size) {
            return new StatusHints[size];
        }
    };

    private StatusHints(Parcel in) {
        mLabel = in.readCharSequence();
        mIcon = in.readParcelable(getClass().getClassLoader(), android.graphics.drawable.Icon.class);
        mExtras = in.readParcelable(getClass().getClassLoader(), android.os.Bundle.class);
    }

    @Override
    public boolean equals(Object other) {
        if (other != null && other instanceof StatusHints) {
            StatusHints otherHints = (StatusHints) other;
            return Objects.equals(otherHints.getLabel(), getLabel()) &&
                    Objects.equals(otherHints.getIcon(), getIcon()) &&
                    Objects.equals(otherHints.getExtras(), getExtras());
        }
        return false;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mLabel) + Objects.hashCode(mIcon) + Objects.hashCode(mExtras);
    }
}
