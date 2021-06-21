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

package com.android.server.policy;

import android.annotation.NonNull;
import android.content.Context;

import com.android.server.devicestate.DeviceStatePolicy;
import com.android.server.devicestate.DeviceStateProvider;

/**
 * Default empty implementation of {@link DeviceStatePolicy}.
 *
 * @see DeviceStateProviderImpl
 */
public final class DeviceStatePolicyImpl implements DeviceStatePolicy {
    private final Context mContext;
    private final DeviceStateProvider mProvider;

    public DeviceStatePolicyImpl(Context context) {
        mContext = context;
        mProvider = DeviceStateProviderImpl.create(mContext);
    }

    public DeviceStateProvider getDeviceStateProvider() {
        return mProvider;
    }

    @Override
    public void configureDeviceForState(int state, @NonNull Runnable onComplete) {
        onComplete.run();
    }
}
