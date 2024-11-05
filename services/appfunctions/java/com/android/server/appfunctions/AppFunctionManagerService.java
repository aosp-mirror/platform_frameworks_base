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

package com.android.server.appfunctions;

import android.annotation.NonNull;
import android.app.appfunctions.AppFunctionManagerConfiguration;
import android.content.Context;

import com.android.server.SystemService;

/** Service that manages app functions. */
public class AppFunctionManagerService extends SystemService {
    private final AppFunctionManagerServiceImpl mServiceImpl;

    public AppFunctionManagerService(Context context) {
        super(context);
        mServiceImpl = new AppFunctionManagerServiceImpl(context);
    }

    @Override
    public void onStart() {
        if (AppFunctionManagerConfiguration.isSupported(getContext())) {
            publishBinderService(Context.APP_FUNCTION_SERVICE, mServiceImpl);
        }
    }

    @Override
    public void onUserUnlocked(@NonNull TargetUser user) {
        mServiceImpl.onUserUnlocked(user);
    }

    @Override
    public void onUserStopping(@NonNull TargetUser user) {
        mServiceImpl.onUserStopping(user);
    }
}
