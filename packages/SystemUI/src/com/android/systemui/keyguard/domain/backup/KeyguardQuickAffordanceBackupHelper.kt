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
 *
 */

package com.android.systemui.keyguard.domain.backup

import android.app.backup.BackupDataInputStream
import android.app.backup.SharedPreferencesBackupHelper
import android.content.Context
import android.util.Log
import com.android.app.tracing.traceSection
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceSelectionManager
import com.android.systemui.settings.UserFileManagerImpl

/** Handles backup & restore for keyguard quick affordances. */
class KeyguardQuickAffordanceBackupHelper(
    private val context: Context,
    userId: Int,
) :
    SharedPreferencesBackupHelper(
        context,
        UserFileManagerImpl.createFile(
                userId = userId,
                fileName = KeyguardQuickAffordanceSelectionManager.FILE_NAME,
            )
            .getPath()
    ) {

    override fun restoreEntity(data: BackupDataInputStream?) {
        Log.d(TAG, "Starting restore for ${data?.key} for user ${context.userId}")
        traceSection("$TAG File restore: ${data?.key}") {
            super.restoreEntity(data)
        }
        Log.d(TAG, "Finished restore for ${data?.key} for user ${context.userId}")
    }

    companion object {
        private const val TAG = "KeyguardQuickAffordanceBackupHelper"
    }
}
