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

import android.app.AppOpsManager
import com.android.modules.utils.BinaryXmlPullParser
import com.android.modules.utils.BinaryXmlSerializer
import com.android.server.permission.access.AccessState
import com.android.server.permission.access.AccessUri
import com.android.server.permission.access.AppOpUri
import com.android.server.permission.access.SchemePolicy
import com.android.server.permission.access.UserState
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports

abstract class BaseAppOpPolicy(private val persistence: BaseAppOpPersistence) : SchemePolicy() {
    override fun getDecision(subject: AccessUri, `object`: AccessUri, state: AccessState): Int {
        `object` as AppOpUri
        return getModes(subject, state)
            .getWithDefault(`object`.appOpName, opToDefaultMode(`object`.appOpName))
    }

    override fun setDecision(
        subject: AccessUri,
        `object`: AccessUri,
        decision: Int,
        oldState: AccessState,
        newState: AccessState
    ) {
        `object` as AppOpUri
        val modes = getOrCreateModes(subject, newState)
        val oldMode = modes.putWithDefault(`object`.appOpName, decision,
            opToDefaultMode(`object`.appOpName))
        if (modes.isEmpty()) {
            removeModes(subject, newState)
        }
        if (oldMode != decision) {
            notifyOnDecisionChangedListeners(subject, `object`, oldMode, decision)
        }
    }

    abstract fun getModes(subject: AccessUri, state: AccessState): IndexedMap<String, Int>?

    abstract fun getOrCreateModes(subject: AccessUri, state: AccessState): IndexedMap<String, Int>

    abstract fun removeModes(subject: AccessUri, state: AccessState)

    // TODO need to check that [AppOpsManager.getSystemAlertWindowDefault] works; likely no issue
    //  since running in system process.
    private fun opToDefaultMode(appOpName: String) = AppOpsManager.opToDefaultMode(appOpName)

    override fun BinaryXmlPullParser.parseUserState(userId: Int, userState: UserState) {
        with(persistence) { this@parseUserState.parseUserState(userId, userState) }
    }

    override fun BinaryXmlSerializer.serializeUserState(userId: Int, userState: UserState) {
        with(persistence) { this@serializeUserState.serializeUserState(userId, userState) }
    }
}
