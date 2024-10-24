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

package com.android.frameworks.coretests.bfscctestapp;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.frameworks.coretests.aidl.IBfsccTestAppCmdService;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class BfsccTestAppCmdService extends Service {
    private IBfsccTestAppCmdService.Stub mBinder = new IBfsccTestAppCmdService.Stub() {
        private final LinkedBlockingQueue<Integer> mNotifications =
                new LinkedBlockingQueue<>();

        @Override
        public void listenTo(IBinder binder) throws RemoteException {
            binder.addFrozenStateChangeCallback(
                    (IBinder who, int state) -> mNotifications.offer(state));
        }

        @Override
        public boolean[] waitAndConsumeNotifications() {
            List<Boolean> results = new ArrayList<>();
            try {
                Integer state = mNotifications.poll(5, TimeUnit.SECONDS);
                if (state != null) {
                    results.add(
                            state.intValue() == IBinder.FrozenStateChangeCallback.STATE_FROZEN);
                }
            } catch (InterruptedException e) {
                return null;
            }
            while (mNotifications.size() > 0) {
                results.add(mNotifications.poll().intValue()
                        == IBinder.FrozenStateChangeCallback.STATE_FROZEN);
            }
            boolean[] convertedResults = new boolean[results.size()];
            for (int i = 0; i < results.size(); i++) {
                convertedResults[i] = results.get(i).booleanValue();
            }
            return convertedResults;
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
