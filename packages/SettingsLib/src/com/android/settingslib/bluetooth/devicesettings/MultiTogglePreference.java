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

import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A data class representing a multi-toggle preference. */
public class MultiTogglePreference extends DeviceSettingPreference implements Parcelable {
    private final String mTitle;
    private final ImmutableList<ToggleInfo> mToggleInfos;
    private final int mState;
    private final boolean mIsActive;
    private final boolean mIsAllowedChangingState;
    private final Bundle mExtras;

    MultiTogglePreference(
            @NonNull String title,
            List<ToggleInfo> toggleInfos,
            int state,
            boolean isActive,
            boolean allowChangingState,
            Bundle extras) {
        super(DeviceSettingType.DEVICE_SETTING_TYPE_MULTI_TOGGLE);
        validate(title, state);
        mTitle = title;
        mToggleInfos = ImmutableList.copyOf(toggleInfos);
        mState = state;
        mIsActive = isActive;
        mIsAllowedChangingState = allowChangingState;
        mExtras = extras;
    }

    private static void validate(String title, int state) {
        if (Objects.isNull(title)) {
            throw new IllegalArgumentException("Title must be set");
        }
        if (state < 0) {
            throw new IllegalArgumentException("State must be a non-negative integer");
        }
    }

    /** Read a {@link MultiTogglePreference} from {@link Parcel}. */
    @NonNull
    public static MultiTogglePreference readFromParcel(@NonNull Parcel in) {
        String title = in.readString();
        List<ToggleInfo> toggleInfos = new ArrayList<>();
        in.readTypedList(toggleInfos, ToggleInfo.CREATOR);
        int state = in.readInt();
        boolean isActive = in.readBoolean();
        boolean allowChangingState = in.readBoolean();
        Bundle extras = in.readBundle(Bundle.class.getClassLoader());
        return new MultiTogglePreference(
                title, toggleInfos, state, isActive, allowChangingState, extras);
    }

    public static final Creator<MultiTogglePreference> CREATOR =
            new Creator<>() {
                @Override
                @NonNull
                public MultiTogglePreference createFromParcel(@NonNull Parcel in) {
                    in.readInt();
                    return readFromParcel(in);
                }

                @Override
                @NonNull
                public MultiTogglePreference[] newArray(int size) {
                    return new MultiTogglePreference[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString(mTitle);
        dest.writeTypedList(mToggleInfos, flags);
        dest.writeInt(mState);
        dest.writeBoolean(mIsActive);
        dest.writeBoolean(mIsAllowedChangingState);
        dest.writeBundle(mExtras);
    }

    /** Builder class for {@link MultiTogglePreference}. */
    public static final class Builder {
        private String mTitle;
        private ImmutableList.Builder<ToggleInfo> mToggleInfos = new ImmutableList.Builder<>();
        private int mState;
        private boolean mIsActive;
        private boolean mAllowChangingState;
        private Bundle mExtras = Bundle.EMPTY;

        /**
         * Sets the title of the preference.
         *
         * @param title The title of the preference.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setTitle(@NonNull String title) {
            mTitle = title;
            return this;
        }

        /**
         * Adds a toggle in the preference.
         *
         * @param toggleInfo The toggle to add.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder addToggleInfo(@NonNull ToggleInfo toggleInfo) {
            mToggleInfos.add(toggleInfo);
            return this;
        }

        /**
         * Sets the state of the preference.
         *
         * @param state The index of the enabled toggle.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setState(int state) {
            mState = state;
            return this;
        }

        /**
         * Sets whether the current state is considered as an "active" state. If it's set to true,
         * the toggle will be highlighted in UI.
         *
         * @param isActive The active state.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setIsActive(boolean isActive) {
            mIsActive = isActive;
            return this;
        }

        /**
         * Sets whether state can be changed by user.
         *
         * @param allowChangingState Whether user is allowed to change state.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setAllowChangingState(boolean allowChangingState) {
            mAllowChangingState = allowChangingState;
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
        public MultiTogglePreference build() {
            return new MultiTogglePreference(
                    mTitle, mToggleInfos.build(), mState, mIsActive, mAllowChangingState, mExtras);
        }
    }

    /**
     * Gets the title of the preference.
     *
     * @return The title.
     */
    @NonNull
    public String getTitle() {
        return mTitle;
    }

    /**
     * Gets the state of the {@link MultiTogglePreference}.
     *
     * @return Returns the index of the enabled toggle.
     */
    public int getState() {
        return mState;
    }

    /**
     * Whether the current state is considered as an active state. If it's set to true, the toggle
     * will be highlighted in UI.
     *
     * @return Returns the active state.
     */
    public boolean isActive() {
        return mIsActive;
    }

    /**
     * Gets the toggle list in the preference.
     *
     * @return the toggle list.
     */
    @NonNull
    public List<ToggleInfo> getToggleInfos() {
        return mToggleInfos;
    }

    /**
     * Gets whether the state can be changed by user.
     *
     * @return Whether the state can be changed by user.
     */
    public boolean isAllowedChangingState() {
        return mIsAllowedChangingState;
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
