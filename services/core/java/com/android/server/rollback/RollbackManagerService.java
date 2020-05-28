/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.rollback;

import android.content.Context;

import com.android.server.LocalServices;
import com.android.server.SystemService;

/**
 * Service that manages APK level rollbacks. Publishes
 * Context.ROLLBACK_SERVICE.
 *
 * @hide
 */
public final class RollbackManagerService extends SystemService {

    private RollbackManagerServiceImpl mService;

    public RollbackManagerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mService = new RollbackManagerServiceImpl(getContext());
        publishBinderService(Context.ROLLBACK_SERVICE, mService);
        LocalServices.addService(RollbackManagerInternal.class, mService);
    }

    @Override
    public void onUserUnlocking(TargetUser user) {
        mService.onUnlockUser(user.getUserHandle().getIdentifier());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            mService.onBootCompleted();
        }
    }
}
