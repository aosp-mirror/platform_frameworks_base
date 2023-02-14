/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.telephony.satellite;

import android.annotation.NonNull;
import android.os.Binder;

import java.util.concurrent.Executor;

/**
 * A callback class for monitoring satellite provision state change events.
 *
 * @hide
 */
public class SatelliteProvisionStateCallback {
    private final CallbackBinder mBinder = new CallbackBinder(this);

    private static class CallbackBinder extends ISatelliteProvisionStateCallback.Stub {
        private final SatelliteProvisionStateCallback mLocalCallback;
        private Executor mExecutor;

        private CallbackBinder(SatelliteProvisionStateCallback localCallback) {
            mLocalCallback = localCallback;
        }

        @Override
        public void onSatelliteProvisionStateChanged(boolean provisioned) {
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() ->
                        mLocalCallback.onSatelliteProvisionStateChanged(provisioned));
            } finally {
                restoreCallingIdentity(callingIdentity);
            }
        }

        private void setExecutor(Executor executor) {
            mExecutor = executor;
        }
    }

    /**
     * Called when satellite provision state changes.
     *
     * @param provisioned The new provision state. {@code true} means satellite is provisioned
     *                    {@code false} means satellite is not provisioned.
     */
    public void onSatelliteProvisionStateChanged(boolean provisioned) {
        // Base Implementation
    }

    /**@hide*/
    @NonNull
    public final ISatelliteProvisionStateCallback getBinder() {
        return mBinder;
    }

    /**@hide*/
    public void setExecutor(@NonNull Executor executor) {
        mBinder.setExecutor(executor);
    }
}
