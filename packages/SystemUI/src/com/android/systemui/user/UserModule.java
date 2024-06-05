/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.user;

import android.os.UserHandle;

import com.android.settingslib.users.CreateUserDialogController;
import com.android.settingslib.users.EditUserInfoController;
import com.android.systemui.user.data.repository.UserRepositoryModule;
import com.android.systemui.user.ui.dialog.UserDialogModule;

import dagger.Module;
import dagger.Provides;

/**
 * Dagger module for User related classes.
 */
@Module(
        includes = {
                UserDialogModule.class,
                UserRepositoryModule.class,
        }
)
public abstract class UserModule {

    private static final String FILE_PROVIDER_AUTHORITY = "com.android.systemui.fileprovider";

    @Provides
    public static EditUserInfoController provideEditUserInfoController() {
        return new EditUserInfoController(FILE_PROVIDER_AUTHORITY);
    }

    /** Provides {@link CreateUserDialogController} */
    @Provides
    public static CreateUserDialogController provideCreateUserDialogController() {
        return new CreateUserDialogController(FILE_PROVIDER_AUTHORITY);
    }

    /**
     * Provides the {@link UserHandle} for the user associated with this System UI process.
     *
     * <p>Note that this is static and unchanging for the life-time of the process we are running
     * in. It can be <i>different</i> from the user that is the currently-selected user, which may
     * be associated with a different System UI process.
     *
     * <p>For example, the System UI process which creates all the windows and renders UI is always
     * the one associated with the primary user on the device. However, if the user is switched to
     * another, non-primary user (for example user "X"), then a secondary System UI process will be
     * spawned. While the original primary user process continues to be the only one rendering UI,
     * the new system UI process may be used for things like file or content access.
     */
    @Provides
    public static UserHandle provideUserHandle() {
        return new UserHandle(UserHandle.myUserId());
    }
}
