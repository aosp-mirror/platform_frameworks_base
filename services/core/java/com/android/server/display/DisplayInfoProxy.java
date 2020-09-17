/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.display;

import android.annotation.Nullable;
import android.hardware.display.DisplayManagerGlobal;
import android.view.DisplayInfo;

/**
 * Class for wrapping access of DisplayInfo objects by LogicalDisplay so that we can appropriately
 * invalidate caches when they change.
 */
public class DisplayInfoProxy {
    private DisplayInfo mInfo;

    public DisplayInfoProxy(@Nullable DisplayInfo info) {
        mInfo = info;
    }

    /**
     * Set the current {@link DisplayInfo}.
     *
     * The also automatically invalidates the display info caches across the entire system.
     * @param info the new {@link DisplayInfo}.
     */
    public void set(@Nullable DisplayInfo info) {
        mInfo = info;
        DisplayManagerGlobal.invalidateLocalDisplayInfoCaches();
    }

    /**
     * Returns the current {@link DisplayInfo}.
     *
     * This info <b>must</b> be treated as immutable. Modifying the returned object is undefined
     * behavior that <b>will</b> result in inconsistent states across the system.
     *
     * @return the current {@link DisplayInfo}
     */
    @Nullable
    public DisplayInfo get() {
        return mInfo;
    }
}
