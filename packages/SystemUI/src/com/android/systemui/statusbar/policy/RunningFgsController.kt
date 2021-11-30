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
package com.android.systemui.statusbar.policy

/**
 * Interface for tracking packages with running foreground services and demoting foreground status
 */
interface RunningFgsController : CallbackController<RunningFgsController.Callback> {

    /**
     * @return A list of [UserPackageTime]s which have running foreground service(s)
     */
    fun getPackagesWithFgs(): List<UserPackageTime>

    /**
     * Stops all foreground services running as a package
     * @param userId the userId the package is running under
     * @param packageName the packageName
     */
    fun stopFgs(userId: Int, packageName: String)

    /**
     * Returns when the list of packages with foreground services changes
     */
    interface Callback {
        /**
         * The thing that
         * @param packages the list of packages
         */
        fun onFgsPackagesChanged(packages: List<UserPackageTime>)
    }

    /**
     * A triplet <user, packageName, timeMillis> where each element is a package running
     * under a user that has had at least one foreground service running since timeMillis.
     * Time should be derived from [SystemClock.elapsedRealtime].
     */
    data class UserPackageTime(val userId: Int, val packageName: String, val startTimeMillis: Long)
}