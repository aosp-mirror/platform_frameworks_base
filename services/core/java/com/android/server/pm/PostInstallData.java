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

package com.android.server.pm;

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * Record-keeping of restore-after-install operations that are currently in flight
 * between the Package Manager and the Backup Manager
 */
public final class PostInstallData {
    @Nullable
    public final InstallArgs args;
    @NonNull
    public final PackageInstalledInfo res;
    @Nullable
    public final Runnable mPostInstallRunnable;

    PostInstallData(@Nullable InstallArgs args, @NonNull PackageInstalledInfo res,
            @Nullable Runnable postInstallRunnable) {
        this.args = args;
        this.res = res;
        mPostInstallRunnable = postInstallRunnable;
    }
}
