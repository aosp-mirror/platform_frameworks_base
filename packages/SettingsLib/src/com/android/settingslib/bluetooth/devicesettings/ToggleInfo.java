/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib.bluetooth.devicesettings;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.Objects;

/** A data class representing a toggle in {@link MultiTogglePreference}. */
public class ToggleInfo implements Parcelable {
    private final String mLabel;
    private final Bitmap mIcon;
    private final Bundle mExtras;

    ToggleInfo(@NonNull String label, @NonNull Bitmap icon, @NonNull Bundle extras) {
        validate(label, icon);
        mLabel = label;
        mIcon = icon;
        mExtras = extras;
    }

    private static void validate(String label, Bitmap icon) {
        if (Objects.isNull(label)) {
            throw new IllegalArgumentException("Label must be set");
        }
        if (Objects.isNull(icon)) {
            throw new IllegalArgumentException("Icon must be set");
        }
    }

    /** Read a {@link ToggleInfo} instance from {@link Parcel}. */
    @NonNull
    public static ToggleInfo readFromParcel(@NonNull Parcel in) {
        String label = in.readString();
        Bitmap icon = in.readParcelable(Bitmap.class.getClassLoader());
        Bundle extras = in.readBundle(Bundle.class.getClassLoader());
        return new ToggleInfo(label, icon, extras);
    }

    public static final Creator<ToggleInfo> CREATOR =
            new Creator<>() {
                @Override
                @NonNull
                public ToggleInfo createFromParcel(@NonNull Parcel in) {
                    return readFromParcel(in);
                }

                @Override
                @NonNull
                public ToggleInfo[] newArray(int size) {
                    return new ToggleInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(mLabel);
        dest.writeParcelable(mIcon, flags);
        dest.writeBundle(mExtras);
    }

    /** Builder class for {@link ToggleInfo}. */
    public static final class Builder {
        private Bitmap mIcon;
        private String mLabel;
        private Bundle mExtras = Bundle.EMPTY;

        /**
         * Sets the label of the toggle.
         *
         * @param label The label of the toggle.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setLabel(@NonNull String label) {
            mLabel = label;
            return this;
        }

        /**
         * Sets the icon of the toggle.
         *
         * @param icon The icon of the toggle.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setIcon(@NonNull Bitmap icon) {
            mIcon = icon;
            return this;
        }

        /**
         * Sets the extras bundle.
         *
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setExtras(@NonNull Bundle extras) {
            mExtras = extras;
            return this;
        }

        /**
         * Builds the {@link ToggleInfo} object.
         *
         * @return Returns the built {@link ToggleInfo} object.
         */
        @NonNull
        public ToggleInfo build() {
            return new ToggleInfo(mLabel, mIcon, mExtras);
        }
    }

    /**
     * Gets the label of the toggle.
     *
     * @return the label to be shown under the toggle
     */
    @NonNull
    public String getLabel() {
        return mLabel;
    }

    /**
     * Gets the icon of the toggle.
     *
     * @return the icon in toggle
     */
    @NonNull
    public Bitmap getIcon() {
        return mIcon;
    }

    /**
     * Gets the extras Bundle.
     *
     * @return Returns a Bundle object.
     */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }
}
