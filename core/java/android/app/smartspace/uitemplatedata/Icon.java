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

package android.app.smartspace.uitemplatedata;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.smartspace.SmartspaceUtils;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import java.util.Objects;

/**
 * Holds the information for a Smartspace-card icon. Including the icon image itself, and an
 * optional content description as the icon's accessibility description.
 *
 * @hide
 */
@SystemApi
public final class Icon implements Parcelable {

    @NonNull
    private final android.graphics.drawable.Icon mIcon;

    @Nullable
    private final CharSequence mContentDescription;

    private final boolean mShouldTint;

    Icon(@NonNull Parcel in) {
        mIcon = in.readTypedObject(android.graphics.drawable.Icon.CREATOR);
        mContentDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
        mShouldTint = in.readBoolean();
    }

    private Icon(@NonNull android.graphics.drawable.Icon icon,
            @Nullable CharSequence contentDescription,
            boolean shouldTint) {
        mIcon = icon;
        mContentDescription = contentDescription;
        mShouldTint = shouldTint;
    }

    /** Returns the icon image. */
    @NonNull
    public android.graphics.drawable.Icon getIcon() {
        return mIcon;
    }

    /** Returns the content description of the icon image. */
    @Nullable
    public CharSequence getContentDescription() {
        return mContentDescription;
    }

    /**
     * Return shouldTint value, which means whether should tint the icon with the system's theme
     * color. The default value is true.
     */
    public boolean shouldTint() {
        return mShouldTint;
    }

    @NonNull
    public static final Creator<Icon> CREATOR = new Creator<Icon>() {
        @Override
        public Icon createFromParcel(Parcel in) {
            return new Icon(in);
        }

        @Override
        public Icon[] newArray(int size) {
            return new Icon[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Icon)) return false;
        Icon that = (Icon) o;
        return mIcon.toString().equals(that.mIcon.toString()) && SmartspaceUtils.isEqual(
                mContentDescription,
                that.mContentDescription) && mShouldTint == that.mShouldTint;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIcon.toString(), mContentDescription, mShouldTint);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeTypedObject(mIcon, flags);
        TextUtils.writeToParcel(mContentDescription, out, flags);
        out.writeBoolean(mShouldTint);
    }

    @Override
    public String toString() {
        return "SmartspaceIcon{"
                + "mIcon=" + mIcon
                + ", mContentDescription=" + mContentDescription
                + ", mShouldTint=" + mShouldTint
                + '}';
    }

    /**
     * A builder for {@link Icon} object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {

        private android.graphics.drawable.Icon mIcon;
        private CharSequence mContentDescription;
        private boolean mShouldTint;

        /**
         * A builder for {@link Icon}, which sets shouldTint to true by default.
         *
         * @param icon the icon image of this {@link Icon} instance.
         */
        public Builder(@NonNull android.graphics.drawable.Icon icon) {
            mIcon = Objects.requireNonNull(icon);
            mShouldTint = true;
        }

        /**
         * Sets the icon's content description.
         */
        @NonNull
        public Builder setContentDescription(@NonNull CharSequence contentDescription) {
            mContentDescription = contentDescription;
            return this;
        }

        /**
         * Sets should tint icon with the system's theme color.
         */
        @NonNull
        public Builder setShouldTint(boolean shouldTint) {
            mShouldTint = shouldTint;
            return this;
        }

        /**
         * Builds a new SmartspaceIcon instance.
         */
        @NonNull
        public Icon build() {
            mIcon.convertToAshmem();
            return new Icon(mIcon, mContentDescription, mShouldTint);
        }
    }
}
