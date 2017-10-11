/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.server.wm;

import android.view.WindowManagerPolicy.StartingSurface;

/**
 * Represents the model about how a starting window should be constructed.
 */
public abstract class StartingData {

    protected final WindowManagerService mService;

    protected StartingData(WindowManagerService service) {
        mService = service;
    }

    /**
     * Creates the actual starting window surface. DO NOT HOLD THE WINDOW MANAGER LOCK WHEN CALLING
     * THIS METHOD.
     *
     * @param atoken the app to add the starting window to
     * @return a class implementing {@link StartingSurface} for easy removal with
     *         {@link StartingSurface#remove}
     */
    abstract StartingSurface createStartingSurface(AppWindowToken atoken);
}