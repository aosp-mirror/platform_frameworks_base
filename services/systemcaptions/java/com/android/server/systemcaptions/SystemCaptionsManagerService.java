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

package com.android.server.systemcaptions;

import android.annotation.NonNull;
import android.annotation.UserIdInt;
import android.content.Context;

import com.android.server.infra.AbstractMasterSystemService;
import com.android.server.infra.FrameworkResourcesServiceNameResolver;

/** A system service to bind to a remote system captions manager service. */
public final class SystemCaptionsManagerService extends
        AbstractMasterSystemService<SystemCaptionsManagerService,
                SystemCaptionsManagerPerUserService> {

    public SystemCaptionsManagerService(@NonNull Context context) {
        super(context,
                new FrameworkResourcesServiceNameResolver(
                        context,
                        com.android.internal.R.string.config_defaultSystemCaptionsManagerService),
                /*disallowProperty=*/ null,
                /*packageUpdatePolicy=*/ PACKAGE_UPDATE_POLICY_REFRESH_EAGER);
    }

    @Override
    public void onStart() {
        // Do nothing. This service does not publish any local or system services.
    }

    @Override
    protected SystemCaptionsManagerPerUserService newServiceLocked(
            @UserIdInt int resolvedUserId, boolean disabled) {
        SystemCaptionsManagerPerUserService perUserService =
                new SystemCaptionsManagerPerUserService(this, mLock, disabled, resolvedUserId);
        perUserService.initializeLocked();
        return perUserService;
    }

    @Override
    protected void onServiceRemoved(
            SystemCaptionsManagerPerUserService service, @UserIdInt int userId) {
        synchronized (mLock) {
            service.destroyLocked();
        }
    }
}
