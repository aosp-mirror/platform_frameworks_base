/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.android.internal.annotations.VisibleForTesting.Visibility.PRIVATE;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.pm.parsing.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageStateInternal;

@VisibleForTesting(visibility = PRIVATE)
interface FeatureConfig {

    /** Called when the system is ready and components can be queried. */
    void onSystemReady();

    /** @return true if we should filter apps at all. */
    boolean isGloballyEnabled();

    /** @return true if the feature is enabled for the given package. */
    boolean packageIsEnabled(AndroidPackage pkg);

    /** @return true if debug logging is enabled for the given package. */
    boolean isLoggingEnabled(int appId);

    /**
     * Turns on logging for the given appId
     *
     * @param enable true if logging should be enabled, false if disabled.
     */
    void enableLogging(int appId, boolean enable);

    /**
     * Initializes the package enablement state for the given package. This gives opportunity
     * to do any expensive operations ahead of the actual checks.
     *
     * @param removed true if adding, false if removing
     */
    void updatePackageState(PackageStateInternal setting, boolean removed);

    FeatureConfig snapshot();
}
