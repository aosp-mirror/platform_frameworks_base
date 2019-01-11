/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.os;

import android.content.Context;

import com.android.server.SystemService;

/**
 * Service that provides a privileged API to capture and consume bugreports.
 *
 * @hide
 */
public class BugreportManagerService extends SystemService {
    private static final String TAG = "BugreportManagerService";

    private BugreportManagerServiceImpl mService;

    public BugreportManagerService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mService = new BugreportManagerServiceImpl(getContext());
        // TODO(b/111441001): Needs sepolicy to be submitted first.
        // publishBinderService(Context.BUGREPORT_SERVICE, mService);
    }
}
