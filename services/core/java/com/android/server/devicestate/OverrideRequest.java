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

package com.android.server.devicestate;

import android.hardware.devicestate.DeviceStateRequest;
import android.os.IBinder;

/**
 * A request to override the state managed by {@link DeviceStateManagerService}.
 *
 * @see OverrideRequestController
 */
final class OverrideRequest {
    private final IBinder mToken;
    private final int mPid;
    private final int mRequestedState;
    @DeviceStateRequest.RequestFlags
    private final int mFlags;

    OverrideRequest(IBinder token, int pid, int requestedState,
            @DeviceStateRequest.RequestFlags int flags) {
        mToken = token;
        mPid = pid;
        mRequestedState = requestedState;
        mFlags = flags;
    }

    IBinder getToken() {
        return mToken;
    }

    int getPid() {
        return mPid;
    }

    int getRequestedState() {
        return mRequestedState;
    }

    @DeviceStateRequest.RequestFlags
    int getFlags() {
        return mFlags;
    }
}
