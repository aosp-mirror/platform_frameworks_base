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
package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.content.pm.UserInfo;
import android.graphics.Bitmap;
import android.text.TextUtils;

/**
 * Contains necessary information to create user using
 * {@link UserManager#createUser(NewUserRequest)}.
 *
 * @hide
 */
@SystemApi
@SuppressLint("PackageLayering")
public final class NewUserRequest {
    @Nullable
    private final String mName;
    private final boolean mAdmin;
    private final boolean mEphemeral;
    @NonNull
    private final String mUserType;
    private final Bitmap mUserIcon;
    private final String mAccountName;
    private final String mAccountType;
    private final PersistableBundle mAccountOptions;

    private NewUserRequest(Builder builder) {
        mName = builder.mName;
        mAdmin = builder.mAdmin;
        mEphemeral = builder.mEphemeral;
        mUserType = builder.mUserType;
        mUserIcon = builder.mUserIcon;
        mAccountName = builder.mAccountName;
        mAccountType = builder.mAccountType;
        mAccountOptions = builder.mAccountOptions;
    }

    /**
     * Returns the name of the user.
     */
    @Nullable
    public String getName() {
        return mName;
    }

    /**
     * Returns whether the user is ephemeral.
     *
     * <p> Ephemeral user will be removed after leaving the foreground.
     */
    public boolean isEphemeral() {
        return mEphemeral;
    }

    /**
     * Returns whether the user is an admin.
     *
     * <p> Admin user is with administrative privileges and such user can create and
     * delete users.
     */
    public boolean isAdmin() {
        return mAdmin;
    }

    /**
     * Returns the calculated flags for user creation.
     */
    int getFlags() {
        int flags = 0;
        if (isAdmin()) flags |= UserInfo.FLAG_ADMIN;
        if (isEphemeral()) flags |= UserInfo.FLAG_EPHEMERAL;
        return flags;
    }

    /**
     * Returns the user type.
     *
     * <p> Default value is {@link UserManager#USER_TYPE_FULL_SECONDARY}
     */
    @NonNull
    public String getUserType() {
        return mUserType;
    }

    /**
     * Returns the user icon.
     */
    @Nullable
    public Bitmap getUserIcon() {
        return mUserIcon;
    }

    /**
     * Returns the account name.
     */
    @Nullable
    public String getAccountName() {
        return mAccountName;
    }

    /**
     * Returns the account type.
     */
    @Nullable
    public String getAccountType() {
        return mAccountType;
    }

    /**
     * Returns the account options.
     */
    @SuppressLint("NullableCollection")
    @Nullable
    public PersistableBundle getAccountOptions() {
        return mAccountOptions;
    }

    @Override
    public String toString() {
        return "NewUserRequest{"
                + "mName='" + mName + '\''
                + ", mAdmin=" + mAdmin
                + ", mEphemeral=" + mEphemeral
                + ", mUserType='" + mUserType + '\''
                + ", mAccountName='" + mAccountName + '\''
                + ", mAccountType='" + mAccountType + '\''
                + ", mAccountOptions=" + mAccountOptions
                + '}';
    }

    /**
     * Builder for building {@link NewUserRequest}
     */
    @SuppressLint("PackageLayering")
    public static final class Builder {

        private String mName;
        private boolean mAdmin;
        private boolean mEphemeral;
        private String mUserType = UserManager.USER_TYPE_FULL_SECONDARY;
        private Bitmap mUserIcon;
        private String mAccountName;
        private String mAccountType;
        private PersistableBundle mAccountOptions;

        /**
         * Sets user name.
         *
         * @return This object for method chaining.
         */
        @NonNull
        public Builder setName(@Nullable String name) {
            mName = name;
            return this;
        }

        /**
         * Sets user as admin.
         *
         * <p> Admin user is with administrative privileges and such user can create
         * and delete users.
         *
         * @return This object for method chaining.
         */
        @NonNull
        public Builder setAdmin() {
            mAdmin = true;
            return this;
        }

        /**
         * Sets user as ephemeral.
         *
         * <p> Ephemeral user will be removed after leaving the foreground.
         *
         * @return This object for method chaining.
         */
        @NonNull
        public Builder setEphemeral() {
            mEphemeral = true;
            return this;
        }

        /**
         * Sets user type.
         *
         * <p> Default value is {link UserManager#USER_TYPE_FULL_SECONDARY}.
         *
         * @return This object for method chaining.
         */
        @NonNull
        public Builder setUserType(@NonNull String type) {
            mUserType = type;
            return this;
        }

        /**
         * Sets user icon.
         *
         * @return This object for method chaining.
         */
        @NonNull
        public Builder setUserIcon(@Nullable Bitmap userIcon) {
            mUserIcon = userIcon;
            return this;
        }

        /**
         * Sets account name that will be used by the setup wizard to initialize the user.
         *
         * @see android.accounts.Account
         * @return This object for method chaining.
         */
        @NonNull
        public Builder setAccountName(@Nullable String accountName) {
            mAccountName = accountName;
            return this;
        }

        /**
         * Sets account type for the account to be created. This is required if the account name
         * is not null. This will be used by the setup wizard to initialize the user.
         *
         * @see android.accounts.Account
         * @return This object for method chaining.
         */
        @NonNull
        public Builder setAccountType(@Nullable String accountType) {
            mAccountType = accountType;
            return this;
        }

        /**
         * Sets account options that can contain account-specific extra information
         * to be used by setup wizard to initialize the account for the user.
         *
         * @return This object for method chaining.
         */
        @NonNull
        public Builder setAccountOptions(@Nullable PersistableBundle accountOptions) {
            mAccountOptions = accountOptions;
            return this;
        }

        /**
         * Builds {@link NewUserRequest}
         *
         * @throws IllegalStateException if builder is configured with incompatible properties and
         * it is not possible to create such user. For example - a guest admin user.
         */
        @NonNull
        public NewUserRequest build() {
            checkIfPropertiesAreCompatible();
            return new NewUserRequest(this);
        }

        private void checkIfPropertiesAreCompatible() {
            if (mUserType == null) {
                throw new IllegalStateException("Usertype cannot be null");
            }

            // Admin user can only be USER_TYPE_FULL_SECONDARY
            if (mAdmin && !mUserType.equals(UserManager.USER_TYPE_FULL_SECONDARY)) {
                throw new IllegalStateException("Admin user can't be of type: " + mUserType);
            }

            if (TextUtils.isEmpty(mAccountName) != TextUtils.isEmpty(mAccountType)) {
                throw new IllegalStateException(
                        "Account name and account type should be provided together.");
            }
        }
    }
}
