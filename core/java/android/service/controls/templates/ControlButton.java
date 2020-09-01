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

package android.service.controls.templates;

import android.annotation.NonNull;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * Button element for {@link ControlTemplate}.
 */
public final class ControlButton implements Parcelable {

    private final boolean mChecked;
    private final @NonNull CharSequence mActionDescription;

    /**
     * @param checked true if the button should be rendered as active.
     * @param actionDescription action description for the button.
     */
    public ControlButton(boolean checked,
            @NonNull CharSequence actionDescription) {
        Preconditions.checkNotNull(actionDescription);
        mChecked = checked;
        mActionDescription = actionDescription;
    }

    /**
     * Whether the button should be rendered in a checked state.
     */
    public boolean isChecked() {
        return mChecked;
    }

    /**
     * The content description for this button.
     */
    @NonNull
    public CharSequence getActionDescription() {
        return mActionDescription;
    }


    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    @NonNull
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeByte(mChecked ? (byte) 1 : (byte) 0);
        dest.writeCharSequence(mActionDescription);
    }

    ControlButton(Parcel in) {
        mChecked = in.readByte() != 0;
        mActionDescription = in.readCharSequence();
    }

    public static final @NonNull Creator<ControlButton> CREATOR = new Creator<ControlButton>() {
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
