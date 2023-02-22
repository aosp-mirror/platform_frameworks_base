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
 * A callback class for monitoring satellite modem state change events.
 *
 * @hide
 */
public class SatelliteStateCallback {
    private final CallbackBinder mBinder = new CallbackBinder(this);

    private static class CallbackBinder extends ISatelliteStateCallback.Stub {
        private final SatelliteStateCallback mLocalCallback;
        private Executor mExecutor;

        private CallbackBinder(SatelliteStateCallback localCallback) {
            mLocalCallback = localCallback;
        }

        @Override
        public void onSatelliteModemStateChanged(@SatelliteManager.SatelliteModemState int state) {
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() ->
                        mLocalCallback.onSatelliteModemStateChanged(state));
            } finally {
                restoreCallingIdentity(callingIdentity);
            }
        }

        @Override
        public void onPendingDatagramCount(int count) {
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() ->
                        mLocalCallback.onPendingDatagramCount(count));
            } finally {
                restoreCallingIdentity(callingIdentity);
            }
        }

        private void setExecutor(Executor executor) {
            mExecutor = executor;
        }
    }

    /**
     * Called when satellite modem state changes.
     * @param state The new satellite modem state.
     */
    public void onSatelliteModemStateChanged(@SatelliteManager.SatelliteModemState int state) {
        // Base Implementation
    }

    /**
     * Called when there are pending datagrams to be received from satellite.
     * @param count Pending datagram count.
     */
    public void onPendingDatagramCount(int count) {
        // Base Implementation
    }

    //TODO: Add an API for datagram transfer state update here.

    /**@hide*/
    @NonNull
    public final ISatelliteStateCallback getBinder() {
        return mBinder;
    }

    /**@hide*/
    public void setExecutor(@NonNull Executor executor) {
        mBinder.setExecutor(executor);
    }
}
