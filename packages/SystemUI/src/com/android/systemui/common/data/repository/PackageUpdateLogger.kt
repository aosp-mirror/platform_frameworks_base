/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.common.data.repository

import android.os.UserHandle
import com.android.systemui.common.shared.model.PackageChangeModel
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.PackageChangeRepoLog
import javax.inject.Inject

private fun getChangeString(model: PackageChangeModel) =
    when (model) {
        is PackageChangeModel.Installed -> "installed"
        is PackageChangeModel.Uninstalled -> "uninstalled"
        is PackageChangeModel.UpdateStarted -> "started updating"
        is PackageChangeModel.UpdateFinished -> "finished updating"
        is PackageChangeModel.Changed -> "changed"
        is PackageChangeModel.Empty -> throw IllegalStateException("Unexpected empty value: $model")
    }

/** A debug logger for [PackageChangeRepository]. */
@SysUISingleton
class PackageUpdateLogger @Inject constructor(@PackageChangeRepoLog private val buffer: LogBuffer) {

    fun logChange(model: PackageChangeModel) {
        buffer.log(
            TAG,
            LogLevel.DEBUG,
            {
                str1 = model.packageName
                str2 = getChangeString(model)
                int1 = model.packageUid
            },
            {
                val user = UserHandle.getUserHandleForUid(int1)
                "Package $str1 ($int1) $str2 on user $user"
            }
        )
    }
}

private const val TAG = "PackageChangeRepoLog"
