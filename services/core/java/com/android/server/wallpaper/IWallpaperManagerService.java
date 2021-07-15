/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.wallpaper;

import android.app.IWallpaperManager;
import android.os.IBinder;

/**
 * Extended IWallpaperManager which can receive SystemService's lifetime events.
 */
interface IWallpaperManagerService extends IWallpaperManager, IBinder {
    /**
     * @see com.android.server.SystemService#onBootPhase(int)
     */
    void onBootPhase(int phase);

    /**
     * @see com.android.server.SystemService#onUserUnlocking
     */
    void onUnlockUser(final int userId);
}