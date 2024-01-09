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
 */

package com.android.server.biometrics.sensors;

import android.annotation.NonNull;

/**
 * Interface to get the appropriate start and stop user clients.
 *
 * @param <T> Hal instance for starting the user.
 * @param <U> Session associated with the current user id.
 */
public interface UserSwitchProvider<T, U> {
    @NonNull
    StartUserClient<T, U> getStartUserClient(int newUserId);
    @NonNull
    StopUserClient<U> getStopUserClient(int userId);
}
