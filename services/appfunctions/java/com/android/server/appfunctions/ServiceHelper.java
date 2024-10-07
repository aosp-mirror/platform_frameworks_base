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
import android.content.Intent;
import android.os.UserHandle;

import com.android.internal.annotations.VisibleForTesting;

/** Helper interface for AppFunctionService. */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public interface ServiceHelper {
    /**
     * Resolves the AppFunctionService for the target package.
     *
     * @param targetPackageName The package name of the target.
     * @param targetUser The user which the caller is requesting to execute as.
     * @return The intent to bind to the target service.
     */
    Intent resolveAppFunctionService(
            @NonNull String targetPackageName, @NonNull UserHandle targetUser);
}
