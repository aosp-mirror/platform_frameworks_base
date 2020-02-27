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
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.os.CancellationSignal;
import android.service.appprediction.IPredictionService;

/**
 * @hide Only for use within the system server.
 */
public abstract class PeopleServiceInternal extends IPredictionService.Stub {

    /**
     * Prunes the data for the specified user. Called by {@link
     * com.android.server.people.data.DataMaintenanceService} when the device is idle.
     */
    public abstract void pruneDataForUser(@UserIdInt int userId,
            @NonNull CancellationSignal signal);

    /**
     * Returns a backup payload that contains conversation infos. The number conversation infos will
     * be dynamic, based on the currently installed apps on the device. All of which should be
     * combined into a single blob to be backed up.
     */
    @Nullable
    public abstract byte[] getBackupPayload(@UserIdInt int userId);

    /**
     * Restores conversation infos stored in payload blob. Multiple conversation infos may exist in
     * the restore payload, child classes are required to manage the restoration based on how
     * individual conversation infos were originally combined during backup.
     */
    public abstract void restore(@UserIdInt int userId, @NonNull byte[] payload);
}
