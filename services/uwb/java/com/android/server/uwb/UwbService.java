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

package com.android.server.uwb;

import android.content.Context;
import android.util.Log;

import com.android.server.SystemService;

/**
 * Uwb System service.
 */
public class UwbService extends SystemService {
    private static final String TAG = "UwbService";

    private final UwbServiceImpl mImpl;

    public UwbService(Context context) {
        super(context);
        mImpl = new UwbServiceImpl(context, new UwbInjector(context));
    }

    @Override
    public void onStart() {
        Log.i(TAG, "Registering " + Context.UWB_SERVICE);
        publishBinderService(Context.UWB_SERVICE, mImpl);
    }
}
