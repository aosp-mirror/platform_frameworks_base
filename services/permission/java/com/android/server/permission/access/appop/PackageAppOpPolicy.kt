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

package com.android.server.permission.access.appop

import com.android.server.permission.access.AccessUri
import com.android.server.permission.access.AppOpUri
import com.android.server.permission.access.GetStateScope
import com.android.server.permission.access.MutateStateScope
import com.android.server.permission.access.PackageUri
import com.android.server.permission.access.UserState
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.external.PackageState

class PackageAppOpPolicy : BaseAppOpPolicy(PackageAppOpPersistence()) {
    override val subjectScheme: String
        get() = PackageUri.SCHEME

    override val objectScheme: String
        get() = AppOpUri.SCHEME

    override fun GetStateScope.getModes(subject: AccessUri): IndexedMap<String, Int>? {
        subject as PackageUri
        return state.userStates[subject.userId]?.packageAppOpModes?.get(subject.packageName)
    }

    override fun MutateStateScope.getOrCreateModes(subject: AccessUri): IndexedMap<String, Int> {
        subject as PackageUri
        return newState.userStates.getOrPut(subject.userId) { UserState() }
            .packageAppOpModes.getOrPut(subject.packageName) { IndexedMap() }
    }

    override fun MutateStateScope.removeModes(subject: AccessUri) {
        subject as PackageUri
        newState.userStates[subject.userId]?.packageAppOpModes?.remove(subject.packageName)
    }

    override fun MutateStateScope.onPackageRemoved(packageState: PackageState) {
        newState.userStates.forEachIndexed { _, _, userState ->
            userState.packageAppOpModes -= packageState.packageName
        }
    }
}
