/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.render

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.LogLevel
import com.android.systemui.log.dagger.NotificationLog
import java.lang.RuntimeException
import javax.inject.Inject

class ShadeViewDifferLogger @Inject constructor(
    @NotificationLog private val buffer: LogBuffer
) {
    fun logDetachingChild(
        key: String,
        isTransfer: Boolean,
        isParentRemoved: Boolean,
        oldParent: String?,
        newParent: String?
    ) {
        buffer.log(TAG, LogLevel.DEBUG, {
            str1 = key
            bool1 = isTransfer
            bool2 = isParentRemoved
            str2 = oldParent
            str3 = newParent
        }, {
            "Detach $str1 isTransfer=$bool1 isParentRemoved=$bool2 oldParent=$str2 newParent=$str3"
        })
    }

    fun logAttachingChild(key: String, parent: String) {
        buffer.log(TAG, LogLevel.DEBUG, {
            str1 = key
            str2 = parent
        }, {
            "Attaching view $str1 to $str2"
        })
    }

    fun logMovingChild(key: String, parent: String, toIndex: Int) {
        buffer.log(TAG, LogLevel.DEBUG, {
            str1 = key
            str2 = parent
            int1 = toIndex
        }, {
            "Moving child view $str1 in $str2 to index $int1"
        })
    }

    fun logDuplicateNodeInTree(node: NodeSpec, ex: RuntimeException) {
        buffer.log(TAG, LogLevel.ERROR, {
            str1 = ex.toString()
            str2 = treeSpecToStr(node)
        }, {
            "$str1 when mapping tree: $str2"
        })
    }
}

private const val TAG = "NotifViewManager"