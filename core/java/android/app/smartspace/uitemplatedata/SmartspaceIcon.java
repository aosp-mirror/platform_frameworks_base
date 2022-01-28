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
import android.graphics.drawable.Icon;
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
public final class SmartspaceIcon implements Parcelable {

    @NonNull
    private final Icon mIcon;

    @Nullable
    private final CharSequence mContentDescription;

    SmartspaceIcon(@NonNull Parcel in) {
        mIcon = in.readTypedObject(Icon.CREATOR);
        mContentDescription = TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(in);
    }

    private SmartspaceIcon(@NonNull Icon icon, @Nullable CharSequence contentDescription) {
        mIcon = icon;
        mContentDescription = contentDescription;
    }

    @NonNull
    public Icon getIcon() {
        return mIcon;
    }

    @Nullable
    public CharSequence getContentDescription() {
        return mContentDescription;
    }

    @NonNull
    public static final Creator<SmartspaceIcon> CREATOR = new Creator<SmartspaceIcon>() {
        @Override
        public SmartspaceIcon createFromParcel(Parcel in) {
            return new SmartspaceIcon(in);
        }

        @Override
        public SmartspaceIcon[] newArray(int size) {
            return new SmartspaceIcon[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SmartspaceIcon)) return false;
        SmartspaceIcon that = (SmartspaceIcon) o;
        return mIcon.equals(that.mIcon) && SmartspaceUtils.isEqual(mContentDescription,
                that.mContentDescription);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIcon, mContentDescription);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel out, int flags) {
        out.writeTypedObject(mIcon, flags);
        TextUtils.writeToParcel(mContentDescription, out, flags);
    }

    @Override
    public String toString() {
        return "SmartspaceIcon{"
                + "mImage=" + mIcon
                + ", mContentDescription='" + mContentDescription + '\''
                + '}';
    }

    /**
     * A builder for {@link SmartspaceIcon} object.
     *
     * @hide
     */
    @SystemApi
    public static final class Builder {

        private Icon mIcon;
        private CharSequence mContentDescription;

        /**
         * A builder for {@link SmartspaceIcon}.
         *
         * @param icon the icon image of this smartspace icon.
         */
        public Builder(@NonNull Icon icon) {
            mIcon = Objects.requireNonNull(icon);
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
         * Builds a new SmartspaceIcon instance.
         */
        @NonNull
        public SmartspaceIcon build() {
            return new SmartspaceIcon(mIcon, mContentDescription);
        }
    }
}
