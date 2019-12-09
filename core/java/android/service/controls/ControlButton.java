/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.controls;

import android.annotation.NonNull;
import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * Button element for {@link ControlTemplate}.
 * @hide
 */
public class ControlButton implements Parcelable {

    private final boolean mChecked;
    private final @NonNull Icon mIcon;
    private final @NonNull CharSequence mContentDescription;

    /**
     * @param checked true if the button should be rendered as active.
     * @param icon icon to display in the button.
     * @param contentDescription content description for the button.
     */
    public ControlButton(boolean checked, @NonNull Icon icon,
            @NonNull CharSequence contentDescription) {
        Preconditions.checkNotNull(icon);
        Preconditions.checkNotNull(contentDescription);
        mChecked = checked;
        mIcon = icon;
        mContentDescription = contentDescription;
    }

    /**
     * Whether the button should be rendered in a checked state.
     */
    public boolean isChecked() {
        return mChecked;
    }

    /**
     * The icon for this button.
     */
    @NonNull
    public Icon getIcon() {
        return mIcon;
    }

    /**
     * The content description for this button.
     */
    @NonNull
    public CharSequence getContentDescription() {
        return mContentDescription;
    }


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte(mChecked ? (byte) 1 : (byte) 0);
        mIcon.writeToParcel(dest, flags);
        dest.writeCharSequence(mContentDescription);
    }

    ControlButton(Parcel in) {
        mChecked = in.readByte() != 0;
        mIcon = Icon.CREATOR.createFromParcel(in);
        mContentDescription = in.readCharSequence();
    }

    public static final Creator<ControlButton> CREATOR = new Creator<ControlButton>() {
        @Override
        public ControlButton createFromParcel(Parcel source) {
            return new ControlButton(source);
        }

        @Override
        public ControlButton[] newArray(int size) {
            return new ControlButton[size];
        }
    };
}
