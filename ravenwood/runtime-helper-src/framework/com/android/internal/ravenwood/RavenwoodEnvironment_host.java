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
package com.android.internal.ravenwood;

import com.android.ravenwood.common.JvmWorkaround;
import com.android.ravenwood.common.RavenwoodCommonUtils;

public class RavenwoodEnvironment_host {
    private RavenwoodEnvironment_host() {
    }

    /**
     * Called from {@link RavenwoodEnvironment#ensureRavenwoodInitialized()}.
     */
    public static void ensureRavenwoodInitialized() {
        // Initialization is now done by RavenwoodAwareTestRunner.
        // Should we remove it?
    }

    /**
     * Called from {@link RavenwoodEnvironment#getRavenwoodRuntimePath()}.
     */
    public static String getRavenwoodRuntimePath(RavenwoodEnvironment env) {
        return RavenwoodCommonUtils.getRavenwoodRuntimePath();
    }

    /**
     * Called from {@link RavenwoodEnvironment#fromAddress(long)}.
     */
    public static <T> T fromAddress(RavenwoodEnvironment env, long address) {
        return JvmWorkaround.getInstance().fromAddress(address);
    }
}
