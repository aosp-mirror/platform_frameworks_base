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

package com.android.packageinstaller.v2.model.installstagedata

abstract class InstallStage {

    /**
     * @return the integer value representing current install stage.
     */
    abstract val stageCode: Int

    companion object {
        const val STAGE_DEFAULT = -1
        const val STAGE_ABORTED = 0
        const val STAGE_STAGING = 1
        const val STAGE_READY = 2
        const val STAGE_USER_ACTION_REQUIRED = 3
        const val STAGE_INSTALLING = 4
        const val STAGE_SUCCESS = 5
        const val STAGE_FAILED = 6
    }
}
