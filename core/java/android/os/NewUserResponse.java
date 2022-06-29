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

import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;

/**
 * Contains the response of the call {@link UserManager#createUser(NewUserRequest)}.
 *
 * @hide
 */
@SystemApi
public final class NewUserResponse {

    private final @Nullable UserHandle mUser;
    private final @UserManager.UserOperationResult int mOperationResult;

    /**
     * @hide
     */
    @TestApi
    public NewUserResponse(@Nullable UserHandle user,
            @UserManager.UserOperationResult int operationResult) {
        mUser = user;
        mOperationResult = operationResult;
    }

    /**
     * Is user creation successful?
     */
    public boolean isSuccessful() {
        return mUser != null;
    }

    // TODO(b/199446283): If UserHandle.NULL is systemAPI, that can be returned here instead of null
    /**
     * Gets the created user handle.
     */
    public @Nullable UserHandle getUser() {
        return mUser;
    }

    /**
     * Gets operation results.
     */
    public @UserManager.UserOperationResult int getOperationResult() {
        return mOperationResult;
    }
}
