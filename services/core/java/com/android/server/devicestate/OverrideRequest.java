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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.hardware.devicestate.DeviceStateRequest;
import android.os.IBinder;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * A request to override the state managed by {@link DeviceStateManagerService}.
 *
 * @see OverrideRequestController
 */
final class OverrideRequest {
    private final IBinder mToken;
    private final int mPid;
    private final int mUid;
    @NonNull
    private final DeviceState mRequestedState;
    @DeviceStateRequest.RequestFlags
    private final int mFlags;
    @OverrideRequestType
    private final int mRequestType;

    /**
     * Denotes that the request is meant to override the emulated state of the device. This will
     * not change the base (physical) state of the device.
     *
     * This request type should be used if you are looking to emulate a device state for a feature
     * but want the system to be aware of the physical state of the device.
     */
    public static final int OVERRIDE_REQUEST_TYPE_EMULATED_STATE = 0;

    /**
     * Denotes that the request is meant to override the base (physical) state of the device.
     * Overriding the base state may not change the emulated state of the device if there is also an
     * override request active for that property.
     *
     * This request type should only be used for testing, where you want to simulate the physical
     * state of the device changing.
     */
    public static final int OVERRIDE_REQUEST_TYPE_BASE_STATE = 1;

    /**
     * Flags for signifying the type of {@link OverrideRequest}.
     *
     * @hide
     */
    @IntDef(flag = true, prefix = { "REQUEST_TYPE_" }, value = {
            OVERRIDE_REQUEST_TYPE_BASE_STATE,
            OVERRIDE_REQUEST_TYPE_EMULATED_STATE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface OverrideRequestType {}

    OverrideRequest(IBinder token, int pid, int uid, @NonNull DeviceState requestedState,
            @DeviceStateRequest.RequestFlags int flags, @OverrideRequestType int requestType) {
        mToken = token;
        mPid = pid;
        mUid = uid;
        mRequestedState = requestedState;
        mFlags = flags;
        mRequestType = requestType;
    }

    IBinder getToken() {
        return mToken;
    }

    int getPid() {
        return mPid;
    }

    int getUid() {
        return mUid;
    }

    @NonNull
    DeviceState getRequestedDeviceState() {
        return mRequestedState;
    }

    int getRequestedStateIdentifier() {
        return mRequestedState.getIdentifier();
    }

    @DeviceStateRequest.RequestFlags
    int getFlags() {
        return mFlags;
    }

    @OverrideRequestType
    int getRequestType() {
        return mRequestType;
    }
}
