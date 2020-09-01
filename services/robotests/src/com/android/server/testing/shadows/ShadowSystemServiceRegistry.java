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

package com.android.server.testing.shadows;

import android.app.SystemServiceRegistry;
import android.app.job.JobSchedulerFrameworkInitializer;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

/**
 * Shadow of {@link SystemServiceRegistry}
 *
 * <p>JobSchedulerFrameworkInitializer contains a static initializer registering JobScheduler as a
 * system service. We need to make sure the initializer is run before the tests that use
 * JobScheduler. And we're putting this on the static initializer of SystemServiceRegistry since
 * other services are registered here.
 */
@Implements(className = "android.app.SystemServiceRegistry")
public class ShadowSystemServiceRegistry {
    @Implementation
    protected static void __staticInitializer__() {
        // Make sure the static init in the real class is still executed.
        ReflectionHelpers.callStaticMethod(SystemServiceRegistry.class, "__staticInitializer__");
        try {
            Class.forName(JobSchedulerFrameworkInitializer.class.getCanonicalName());
        } catch (ClassNotFoundException e) {
            // Rethrowing as an unchecked exception because checked exceptions are not allowed in
            // static blocks.
            throw new ExceptionInInitializerError(e);
        }
    }
}
