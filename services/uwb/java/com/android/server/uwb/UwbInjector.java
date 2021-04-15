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

import android.annotation.NonNull;
import android.content.Context;
import android.os.IBinder;
import android.os.ServiceManager;
import android.uwb.IUwbAdapter;


/**
 * To be used for dependency injection (especially helps mocking static dependencies).
 */
public class UwbInjector {
    private static final String TAG = "UwbInjector";

    private static final String VENDOR_SERVICE_NAME = "uwb_vendor";

    private final Context mContext;

    public UwbInjector(@NonNull Context context) {
        mContext = context;
    }

    /**
     * @return Returns the vendor service handle.
     */
    public IUwbAdapter getVendorService() {
        IBinder b = ServiceManager.getService(VENDOR_SERVICE_NAME);
        if (b == null) return null;
        return IUwbAdapter.Stub.asInterface(b);
    }
}
