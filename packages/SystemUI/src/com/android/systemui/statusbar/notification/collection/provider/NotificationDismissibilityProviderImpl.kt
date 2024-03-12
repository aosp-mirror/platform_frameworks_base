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

package com.android.systemui.statusbar.notification.collection.provider

import androidx.annotation.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.util.asIndenting
import com.android.systemui.util.printCollection
import java.io.PrintWriter
import javax.inject.Inject

@SysUISingleton
class NotificationDismissibilityProviderImpl @Inject constructor(dumpManager: DumpManager) :
    NotificationDismissibilityProvider, Dumpable {

    init {
        dumpManager.registerNormalDumpable(TAG, this)
    }

    @VisibleForTesting
    @Volatile
    var nonDismissableEntryKeys = setOf<String>()
        private set

    override fun isDismissable(entry: NotificationEntry): Boolean {
        return entry.key !in nonDismissableEntryKeys
    }

    @Synchronized
    fun update(nonDismissableEntryKeys: Set<String>) {
        this.nonDismissableEntryKeys = nonDismissableEntryKeys.toSet()
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) =
        pw.asIndenting().run { printCollection("non-dismissible entries", nonDismissableEntryKeys) }

    companion object {
        private const val TAG = "NotificationDismissibilityProvider"
    }
}
