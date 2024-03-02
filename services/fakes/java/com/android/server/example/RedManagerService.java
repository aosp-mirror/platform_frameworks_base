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
import android.os.IBinder;
import android.os.RemoteException;
import android.ravenwood.example.BlueManager;
import android.ravenwood.example.RedManager;

import com.android.server.SystemService;

import java.util.List;

public class RedManagerService extends Binder {
    private IBinder mBlueService;

    public static class Lifecycle extends SystemService {
        private RedManagerService mService;

        public Lifecycle(Context context) {
            super(context, List.of(BlueManager.class));
        }

        @Override
        public void onStart() {
            mService = new RedManagerService();
            publishBinderService(RedManager.SERVICE_NAME, mService);
        }

        @Override
        public void onBootPhase(int phase) {
            if (phase == PHASE_SYSTEM_SERVICES_READY) {
                mService.mBlueService = getBinderService(BlueManager.SERVICE_NAME);
            }
        }
    }

    @Override
    public String getInterfaceDescriptor() {
        try {
            // Obtain the answer from dependency, but then augment it to prove that the answer
            // was channeled through us
            return mBlueService.getInterfaceDescriptor() + "+red";
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }
}
