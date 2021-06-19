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

package com.android.settingslib.enterprise;

import static java.util.Objects.requireNonNull;

import android.annotation.UserIdInt;

import com.android.internal.util.Preconditions;
import com.android.settingslib.RestrictedLockUtils.EnforcedAdmin;

/**
 * Base class for {@link ActionDisabledByAdminController} implementations.
 */
abstract class BaseActionDisabledByAdminController
        implements ActionDisabledByAdminController {

    protected @UserIdInt int mEnforcementAdminUserId;
    protected EnforcedAdmin mEnforcedAdmin;
    protected ActionDisabledLearnMoreButtonLauncher mLauncher;
    protected final DeviceAdminStringProvider mStringProvider;

    BaseActionDisabledByAdminController(DeviceAdminStringProvider stringProvider) {
        mStringProvider = stringProvider;
    }

    @Override
    public final void initialize(ActionDisabledLearnMoreButtonLauncher launcher) {
        mLauncher = requireNonNull(launcher, "launcher cannot be null");
    }

    @Override
    public final void updateEnforcedAdmin(EnforcedAdmin admin, int adminUserId) {
        assertInitialized();
        mEnforcementAdminUserId = adminUserId;
        mEnforcedAdmin = requireNonNull(admin, "admin cannot be null");
    }

    protected final void assertInitialized() {
        Preconditions.checkState(mLauncher != null, "must call initialize() first");
    }
}
