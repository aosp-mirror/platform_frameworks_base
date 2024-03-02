/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.example;

import android.content.Context;
import android.os.Binder;
import android.ravenwood.example.BlueManager;

import com.android.server.SystemService;

public class BlueManagerService extends Binder {
    public static class Lifecycle extends SystemService {
        private BlueManagerService mService;

        public Lifecycle(Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            mService = new BlueManagerService();
            publishBinderService(BlueManager.SERVICE_NAME, mService);
        }
    }

    @Override
    public String getInterfaceDescriptor() {
        return "blue";
    }
}
