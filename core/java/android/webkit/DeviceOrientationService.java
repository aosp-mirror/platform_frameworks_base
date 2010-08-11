/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.webkit;

import android.os.Handler;
import android.webkit.DeviceOrientationManager;
import java.lang.Runnable;


final class DeviceOrientationService {
    private DeviceOrientationManager mManager;
    private boolean mIsRunning;
    private Handler mHandler;

    public DeviceOrientationService(DeviceOrientationManager manager) {
        mManager = manager;
        assert(mManager != null);
     }

    public void start() {
        mIsRunning = true;
        registerForSensors();
    }

    public void stop() {
        mIsRunning = false;
        unregisterFromSensors();
    }

    public void suspend() {
        if (mIsRunning) {
            unregisterFromSensors();
        }
    }

    public void resume() {
        if (mIsRunning) {
            registerForSensors();
        }
    }

    private void sendErrorEvent() {
        assert WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName());
        if (mHandler == null) {
            mHandler = new Handler();
        }
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                assert WebViewCore.THREAD_NAME.equals(Thread.currentThread().getName());
                if (mIsRunning) {
                    mManager.onOrientationChange(null, null, null);
                }
            }
        });
    }

    private void registerForSensors() {
        // Send the error event for now.
        // FIXME: Implement.
        sendErrorEvent();
    }

    private void unregisterFromSensors() {
        // FIXME: Implement.
    }
}
