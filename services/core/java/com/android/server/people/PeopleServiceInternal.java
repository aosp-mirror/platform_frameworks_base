/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.people;

import android.annotation.NonNull;
import android.service.appprediction.IPredictionService;

/**
 * @hide Only for use within the system server.
 */
public abstract class PeopleServiceInternal extends IPredictionService.Stub {

    /**
     * The number conversation infos will be dynamic, based on the currently installed apps on the
     * device. All of which should be combined into a single blob to be backed up.
     */
    public abstract byte[] backupConversationInfos(@NonNull int userId);

    /**
     * Multiple conversation infos may exist in the restore payload, child classes are required to
     * manage the restoration based on how individual conversation infos were originally combined
     * during backup.
     */
    public abstract void restoreConversationInfos(@NonNull int userId, @NonNull String key,
            @NonNull byte[] payload);
}
