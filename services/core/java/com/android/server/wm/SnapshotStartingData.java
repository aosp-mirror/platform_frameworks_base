/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import android.window.TaskSnapshot;

import com.android.server.policy.WindowManagerPolicy.StartingSurface;

/**
 * Represents starting data for snapshot starting windows.
 */
class SnapshotStartingData extends StartingData {

    private final WindowManagerService mService;
    private final TaskSnapshot mSnapshot;

    SnapshotStartingData(WindowManagerService service, TaskSnapshot snapshot, int typeParams) {
        super(service, typeParams);
        mService = service;
        mSnapshot = snapshot;
    }

    @Override
    StartingSurface createStartingSurface(ActivityRecord activity) {
        return mService.mStartingSurfaceController.createTaskSnapshotSurface(activity,
                mSnapshot);
    }

    @Override
    boolean needRevealAnimation() {
        return false;
    }

    @Override
    boolean hasImeSurface() {
        return mSnapshot.hasImeSurface();
    }
}
