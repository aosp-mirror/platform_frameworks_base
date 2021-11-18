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
import android.annotation.SystemApi;

/**
 * Contains necessary information to create user using
 * {@link UserManager#createUser(NewUserRequest)}.
 *
 * @hide
 */
@SystemApi
public final class NewUserRequest {
    @Nullable
    private final String mName;
    private final boolean mAdmin;
    private final boolean mEphemeral;
    @NonNull
    private final String mUserType;

    private NewUserRequest(Builder builder) {
        mName = builder.mName;
        mAdmin = builder.mAdmin;
        mEphemeral = builder.mEphemeral;
        mUserType = builder.mUserType;
    }

    /**
     * Gets the user name.
     */
    @Nullable
    public String getName() {
        return mName;
    }

    /**
     * Is user Ephemenral?
     *
     * <p> Ephemeral user will be removed after leaving the foreground.
     */
    public boolean isEphemeral() {
        return mEphemeral;
    }

    /**
     * Is user Admin?
     *
     * <p> Admin user is with administrative privileges and such user can create and
     * delete users.
     */
    public boolean isAdmin() {
        return mAdmin;
    }

    /**
     * Gets user type.
     *
     * <p> Supported types are {@link UserManager.USER_TYPE_FULL_SECONDARY} and
     * {@link USER_TYPE_FULL_GUEST}
     */
    @NonNull
    public String getUserType() {
        return mUserType;
    }

    @Override
    public String toString() {
        return String.format(
                "NewUserRequest- UserName:%s, userType:%s, IsAdmin:%s, IsEphemeral:%s.", mName,
                mUserType, mAdmin, mEphemeral);
    }

    /**
     * Builder for building {@link NewUserRequest}
     */
    public static final class Builder {

        private String mName;
        private boolean mAdmin;
        private boolean mEphemeral;
        private String mUserType = UserManager.USER_TYPE_FULL_SECONDARY;

        /**
         * Sets user name.
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
         */
        @NonNull
        public Builder setEphemeral() {
            mEphemeral = true;
            return this;
        }

        /**
         * Sets user type.
         * <p>
         * Supported types are {@link UserManager.USER_TYPE_FULL_SECONDARY} and
         * {@link UserManager.USER_TYPE_FULL_GUEST}. Default value is
         * {@link UserManager.USER_TYPE_FULL_SECONDARY}.
         */
        @NonNull
        public Builder setUserType(@NonNull String type) {
            mUserType = type;
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
            // Conditions which can't be true simultaneously
            // A guest user can't be admin user
            if (mAdmin && mUserType == UserManager.USER_TYPE_FULL_GUEST) {
                throw new IllegalStateException("A guest user can't be admin.");
            }

            // check for only supported user types
            if (mUserType != UserManager.USER_TYPE_FULL_SECONDARY
                    && mUserType != UserManager.USER_TYPE_FULL_GUEST) {
                throw new IllegalStateException("Unsupported user type: " + mUserType);
            }
        }
    }
}
