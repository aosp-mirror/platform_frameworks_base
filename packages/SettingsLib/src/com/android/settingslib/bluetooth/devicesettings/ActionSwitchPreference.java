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

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Objects;

/**
 * A data class representing an action/switch preference. The preference could be one of the four
 * following forms: 1. Texted row with action to jump to another page 2. Texted row without action
 * 3. Texted row with action and switch 4. Texted row with switch
 */
public class ActionSwitchPreference extends DeviceSettingPreference implements Parcelable {
    private final String mTitle;
    private final String mSummary;
    private final Bitmap mIcon;
    private final Intent mIntent;
    private final boolean mHasSwitch;
    private final boolean mChecked;
    private final boolean mIsAllowedChangingState;
    private final Bundle mExtras;

    ActionSwitchPreference(
            String title,
            @Nullable String summary,
            @Nullable Bitmap icon,
            @Nullable Intent intent,
            boolean hasSwitch,
            boolean checked,
            boolean allowChangingState,
            @NonNull Bundle extras) {
        super(DeviceSettingType.DEVICE_SETTING_TYPE_ACTION_SWITCH);
        validate(title);
        mTitle = title;
        mSummary = summary;
        mIcon = icon;
        mIntent = intent;
        mHasSwitch = hasSwitch;
        mChecked = checked;
        mIsAllowedChangingState = allowChangingState;
        mExtras = extras;
    }

    private static void validate(String title) {
        if (Objects.isNull(title)) {
            throw new IllegalArgumentException("Title must be set");
        }
    }

    /**
     * Reads an {@link ActionSwitchPreference} instance from {@link Parcel}
     * @param in The parcel to read from
     * @return The instance read
     */
    @NonNull
    public static ActionSwitchPreference readFromParcel(@NonNull Parcel in) {
        String title = in.readString();
        String summary = in.readString();
        Bitmap icon = in.readParcelable(Bitmap.class.getClassLoader());
        Intent intent = in.readParcelable(Intent.class.getClassLoader());
        boolean hasSwitch = in.readBoolean();
        boolean checked = in.readBoolean();
        boolean allowChangingState = in.readBoolean();
        Bundle extras = in.readBundle(Bundle.class.getClassLoader());
        return new ActionSwitchPreference(
                title, summary, icon, intent, hasSwitch, checked, allowChangingState, extras);
    }

    public static final Creator<ActionSwitchPreference> CREATOR =
            new Creator<>() {
                @Override
                @NonNull
                public ActionSwitchPreference createFromParcel(@NonNull Parcel in) {
                    in.readInt();
                    return readFromParcel(in);
                }

                @Override
                @NonNull
                public ActionSwitchPreference[] newArray(int size) {
                    return new ActionSwitchPreference[size];
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
        dest.writeString(mSummary);
        dest.writeParcelable(mIcon, flags);
        dest.writeParcelable(mIntent, flags);
        dest.writeBoolean(mHasSwitch);
        dest.writeBoolean(mChecked);
        dest.writeBoolean(mIsAllowedChangingState);
        dest.writeBundle(mExtras);
    }

    /** Builder class for {@link ActionSwitchPreference}. */
    public static final class Builder {
        private String mTitle;
        private String mSummary;
        private Bitmap mIcon;
        private Intent mIntent;
        private boolean mHasSwitch;
        private boolean mChecked;
        private boolean mIsAllowedChangingState;
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
         * Sets the summary of the preference, optional.
         *
         * @param summary The preference summary.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setSummary(@Nullable String summary) {
            mSummary = summary;
            return this;
        }

        /**
         * Sets the icon to be displayed on the left of the preference, optional.
         *
         * @param icon The icon.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setIcon(@Nullable Bitmap icon) {
            mIcon = icon;
            return this;
        }

        /**
         * Sets the Intent to launch when the preference is clicked, optional.
         *
         * @param intent The Intent.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setIntent(@Nullable Intent intent) {
            mIntent = intent;
            return this;
        }

        /**
         * Sets whether the preference will contain a switch.
         *
         * @param hasSwitch Whether the preference contains a switch.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setHasSwitch(boolean hasSwitch) {
            mHasSwitch = hasSwitch;
            return this;
        }

        /**
         * Sets the state of the preference.
         *
         * @param checked Whether the switch is checked.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setChecked(boolean checked) {
            mChecked = checked;
            return this;
        }

        /**
         * Sets whether state can be changed by user.
         *
         * @param allowChangingState Whether user is allowed to change state.
         * @return Returns the Builder object.
         */
        @NonNull
        public Builder setAllowedChangingState(boolean allowChangingState) {
            mIsAllowedChangingState = allowChangingState;
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
         * Builds the {@link ActionSwitchPreference} object.
         *
         * @return Returns the built {@link ActionSwitchPreference} object.
         */
        @NonNull
        public ActionSwitchPreference build() {
            return new ActionSwitchPreference(
                    mTitle,
                    mSummary,
                    mIcon,
                    mIntent,
                    mHasSwitch,
                    mChecked,
                    mIsAllowedChangingState,
                    mExtras);
        }
    }

    /**
     * Gets the title of the preference.
     *
     * @return Returns the title of the preference.
     */
    @NonNull
    public String getTitle() {
        return mTitle;
    }

    /**
     * Gets the summary of the preference.
     *
     * @return Returns the summary of the preference.
     */
    @Nullable
    public String getSummary() {
        return mSummary;
    }

    /**
     * Gets the icon of the preference.
     *
     * @return Returns the icon of the preference.
     */
    @Nullable
    public Bitmap getIcon() {
        return mIcon;
    }

    /**
     * Gets the Intent to launch when the preference is clicked.
     *
     * @return Returns the intent to launch.
     */
    @Nullable
    public Intent getIntent() {
        return mIntent;
    }

    /**
     * Whether the preference contains a switch.
     *
     * @return Whether the preference contains a switch.
     */
    public boolean hasSwitch() {
        return mHasSwitch;
    }

    /**
     * Whether the switch is checked.
     *
     * @return Whether the switch is checked.
     */
    public boolean getChecked() {
        return mChecked;
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
     * Gets the extras bundle.
     *
     * @return The extra bundle.
     */
    @NonNull
    public Bundle getExtras() {
        return mExtras;
    }
}
