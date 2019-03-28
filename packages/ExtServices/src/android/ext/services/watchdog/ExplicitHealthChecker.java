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

package android.ext.services.watchdog;

/**
 * A type of explicit health check that can be performed on a device, e.g network health check
 */
interface ExplicitHealthChecker {
    /**
     * Requests a checker to listen to explicit health checks for {@link #getPackageName}.
     *  {@link #isPending} will now return {@code true}.
     */
    void request();

    /**
     * Cancels a pending explicit health check request for {@link #getPackageName}.
     * {@link #isPending} will now return {@code false}.
     */
    void cancel();

    /**
     * Returns {@code true} if a request is pending, {@code false} otherwise.
     */
    boolean isPending();

    /**
     * Returns the package name this checker can make requests for.
     */
    String getPackageName();
}
