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

package com.android.packageinstaller.v2.model.installstagedata;

public abstract class InstallStage {

    public static final int STAGE_DEFAULT = -1;
    public static final int STAGE_ABORTED = 0;
    public static final int STAGE_STAGING = 1;
    public static final int STAGE_READY = 2;
    public static final int STAGE_USER_ACTION_REQUIRED = 3;
    public static final int STAGE_INSTALLING = 4;
    public static final int STAGE_SUCCESS = 5;
    public static final int STAGE_FAILED = 6;

    /**
     * @return the integer value representing current install stage.
     */
    public abstract int getStageCode();
}
