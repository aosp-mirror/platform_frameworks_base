/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.content.pm;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.os.Bundle;
import android.os.Flags;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;
import android.os.UserManager;

/**
 * The LauncherUserInfo object holds information about an Android user that is required to display
 * the Launcher related UI elements specific to the user (like badges).
 */
@FlaggedApi(Flags.FLAG_ALLOW_PRIVATE_PROFILE)
public final class LauncherUserInfo implements Parcelable {

    /**
     * A boolean extra indicating whether the private space entrypoint should be hidden when locked.
     *
     * @see #getUserConfig
     */
    @FlaggedApi(android.multiuser.Flags.FLAG_ADD_LAUNCHER_USER_CONFIG)
    public static final String PRIVATE_SPACE_ENTRYPOINT_HIDDEN =
            "private_space_entrypoint_hidden";

    private final String mUserType;

    // Serial number for the user, should be same as in the {@link UserInfo} object.
    private final int mUserSerialNumber;

    // Additional configs for the user, e.g., whether to hide the private space entrypoint when
    // locked.
    private final Bundle mUserConfig;


    /**
     * Returns type of the user as defined in {@link UserManager}. e.g.,
     * {@link UserManager.USER_TYPE_PROFILE_MANAGED} or {@link UserManager.USER_TYPE_PROFILE_ClONE}
     * or {@link UserManager.USER_TYPE_PROFILE_PRIVATE}
     *
     * @return the userType for the user whose LauncherUserInfo this is
     */
    @FlaggedApi(Flags.FLAG_ALLOW_PRIVATE_PROFILE)
    @NonNull
    public String getUserType() {
        return mUserType;
    }

    /**
     * Returns additional configs for this launcher user
     *
     * @see #PRIVATE_SPACE_ENTRYPOINT_HIDDEN
     */
    @FlaggedApi(android.multiuser.Flags.FLAG_ADD_LAUNCHER_USER_CONFIG)
    @NonNull
    public Bundle getUserConfig() {
        return mUserConfig;
    }

    /**
     * Returns serial number of user as returned by
     * {@link UserManager#getSerialNumberForUser(UserHandle)}
     *
     * @return the serial number associated with the user
     */
    @FlaggedApi(Flags.FLAG_ALLOW_PRIVATE_PROFILE)
    public int getUserSerialNumber() {
        return mUserSerialNumber;
    }

    private LauncherUserInfo(@NonNull Parcel in) {
        mUserType = in.readString16NoHelper();
        mUserSerialNumber = in.readInt();
        mUserConfig = in.readBundle(Bundle.class.getClassLoader());
    }

    @Override
    @FlaggedApi(Flags.FLAG_ALLOW_PRIVATE_PROFILE)
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString16NoHelper(mUserType);
        dest.writeInt(mUserSerialNumber);
        dest.writeBundle(mUserConfig);
    }

    @Override
    @FlaggedApi(Flags.FLAG_ALLOW_PRIVATE_PROFILE)
    public int describeContents() {
        return 0;
    }

    @FlaggedApi(Flags.FLAG_ALLOW_PRIVATE_PROFILE)
    public static final @android.annotation.NonNull Creator<LauncherUserInfo> CREATOR =
            new Creator<LauncherUserInfo>() {
                @Override
                public LauncherUserInfo createFromParcel(Parcel in) {
                    return new LauncherUserInfo(in);
                }

                @Override
                public LauncherUserInfo[] newArray(int size) {
                    return new LauncherUserInfo[size];
                }
            };

    /**
     * @hide
     */
    public static final class Builder {
        private final String mUserType;

        private final int mUserSerialNumber;
        private final Bundle mUserConfig;


        @FlaggedApi(android.multiuser.Flags.FLAG_ADD_LAUNCHER_USER_CONFIG)
        public Builder(@NonNull String userType, int userSerialNumber, @NonNull Bundle config) {
            this.mUserType = userType;
            this.mUserSerialNumber = userSerialNumber;
            this.mUserConfig = config;
        }

        public Builder(@NonNull String userType, int userSerialNumber) {
            this.mUserType = userType;
            this.mUserSerialNumber = userSerialNumber;
            this.mUserConfig = new Bundle();
        }

        /**
         * Builds the LauncherUserInfo object
         */
        @NonNull
        public LauncherUserInfo build() {
            return new LauncherUserInfo(this.mUserType, this.mUserSerialNumber, this.mUserConfig);
        }

    } // End builder

    private LauncherUserInfo(@NonNull String userType, int userSerialNumber,
            @NonNull Bundle config) {
        this.mUserType = userType;
        this.mUserSerialNumber = userSerialNumber;
        this.mUserConfig = config;
    }
}
