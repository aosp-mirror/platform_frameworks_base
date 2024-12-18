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
 *
 */

package com.android.systemui.communal.domain.backup

import android.app.backup.SharedPreferencesBackupHelper
import android.content.Context
import com.android.systemui.communal.data.repository.CommunalPrefsRepositoryImpl.Companion.FILE_NAME
import com.android.systemui.settings.UserFileManagerImpl

/** Helper to backup & restore the shared preferences in glanceable hub for the current user. */
class CommunalPrefsBackupHelper(
    context: Context,
    userId: Int,
) :
    SharedPreferencesBackupHelper(
        context,
        UserFileManagerImpl.createFile(
                userId = userId,
                fileName = FILE_NAME,
            )
            .path
    )
