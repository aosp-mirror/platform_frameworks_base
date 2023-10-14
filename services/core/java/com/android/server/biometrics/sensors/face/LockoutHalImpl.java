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

package com.android.server.biometrics.sensors.face;

import com.android.server.biometrics.sensors.LockoutTracker;

/**
 * Implementation for storing {@link android.hardware.biometrics.face.V1_0} lockout state.
 * This implementation notifies the framework of the current user's lockout state whenever
 * the user changes.
 */
public class LockoutHalImpl implements LockoutTracker {
    private @LockoutMode int mCurrentUserLockoutMode;

    @Override
    public int getLockoutModeForUser(int userId) {
        return mCurrentUserLockoutMode;
    }

    @Override
    public void setLockoutModeForUser(int userId, @LockoutMode int mode) {
        setCurrentUserLockoutMode(mode);
    }

    public void setCurrentUserLockoutMode(@LockoutMode int lockoutMode) {
        mCurrentUserLockoutMode = lockoutMode;
    }
}
