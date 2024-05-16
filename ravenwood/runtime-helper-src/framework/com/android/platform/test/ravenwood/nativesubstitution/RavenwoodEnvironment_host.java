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
package com.android.platform.test.ravenwood.nativesubstitution;

import android.platform.test.ravenwood.RavenwoodSystemProperties;
import android.util.Log;

import com.android.internal.ravenwood.RavenwoodEnvironment;
import com.android.ravenwood.common.RavenwoodCommonUtils;

public class RavenwoodEnvironment_host {
    private static final String TAG = RavenwoodEnvironment.TAG;

    private static final Object sInitializeLock = new Object();

    // @GuardedBy("sInitializeLock")
    private static boolean sInitialized;

    private RavenwoodEnvironment_host() {
    }

    /**
     * Called from {@link RavenwoodEnvironment#ensureRavenwoodInitialized()}.
     */
    public static void ensureRavenwoodInitializedInternal() {
        synchronized (sInitializeLock) {
            if (sInitialized) {
                return;
            }
            Log.i(TAG, "Initializing Ravenwood environment");

            // Set the default values.
            var sysProps = RavenwoodSystemProperties.DEFAULT_VALUES;

            // We have a method that does it in RavenwoodRuleImpl, but we can't use that class
            // here, So just inline it.
            SystemProperties_host.initializeIfNeeded(
                    sysProps.getValues(),
                    sysProps.getKeyReadablePredicate(),
                    sysProps.getKeyWritablePredicate());

            sInitialized = true;
        }
    }
}