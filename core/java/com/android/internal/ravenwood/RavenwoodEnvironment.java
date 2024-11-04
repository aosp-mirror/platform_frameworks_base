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

import android.ravenwood.annotation.RavenwoodKeepWholeClass;
import android.ravenwood.annotation.RavenwoodRedirect;
import android.ravenwood.annotation.RavenwoodRedirectionClass;
import android.ravenwood.annotation.RavenwoodReplace;

/**
 * Class to interact with the Ravenwood environment.
 */
@RavenwoodKeepWholeClass
@RavenwoodRedirectionClass("RavenwoodEnvironment_host")
public final class RavenwoodEnvironment {
    public static final String TAG = "RavenwoodEnvironment";

    private static RavenwoodEnvironment sInstance = new RavenwoodEnvironment();

    private static RuntimeException notSupportedOnDevice() {
        return new UnsupportedOperationException("This method can only be used on Ravenwood");
    }

    /**
     * @return the singleton instance.
     */
    public static RavenwoodEnvironment getInstance() {
        return sInstance;
    }

    /**
     * USE IT SPARINGLY! Returns true if it's running on Ravenwood, hostside test environment.
     *
     * <p>Using this allows code to behave differently on a real device and on Ravenwood, but
     * generally speaking, that's a bad idea because we want the test target code to behave
     * differently.
     *
     * <p>This should be only used when different behavior is absolutely needed.
     *
     * <p>If someone needs it without having access to the SDK, the following hack would work too.
     * <code>System.getProperty("java.class.path").contains("ravenwood")</code>
     */
    @RavenwoodReplace
    public boolean isRunningOnRavenwood() {
        return false;
    }

    private boolean isRunningOnRavenwood$ravenwood() {
        return true;
    }

    /**
     * Get the object back from the address obtained from
     * {@link dalvik.system.VMRuntime#addressOf(Object)}.
     */
    @RavenwoodRedirect
    public <T> T fromAddress(long address) {
        throw notSupportedOnDevice();
    }

    /**
     * @return the "ravenwood-runtime" directory.
     */
    @RavenwoodRedirect
    public String getRavenwoodRuntimePath() {
        throw notSupportedOnDevice();
    }
}
