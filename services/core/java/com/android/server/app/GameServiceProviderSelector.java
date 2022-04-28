/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.app;

import android.annotation.Nullable;

import com.android.server.SystemService;

/**
 * Responsible for determining what the active Game Service provider should be.
 */
interface GameServiceProviderSelector {

    /**
     * Returns the {@link GameServiceConfiguration} associated with the selected Game
     * Service provider for the given user or {@code null} if none should be used.
     */
    @Nullable
    GameServiceConfiguration get(@Nullable SystemService.TargetUser user,
            @Nullable String packageNameOverride);
}
