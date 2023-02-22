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
 * A callback class for monitoring satellite position update and datagram transfer state change
 * events.
 *
 * @hide
 */
public class SatellitePositionUpdateCallback {
    private final CallbackBinder mBinder = new CallbackBinder(this);

    private static class CallbackBinder extends ISatellitePositionUpdateCallback.Stub {
        private final SatellitePositionUpdateCallback mLocalCallback;
        private Executor mExecutor;

        private CallbackBinder(SatellitePositionUpdateCallback localCallback) {
            mLocalCallback = localCallback;
        }

        @Override
        public void onSatellitePositionChanged(@NonNull PointingInfo pointingInfo) {
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() ->
                        mLocalCallback.onSatellitePositionChanged(pointingInfo));
            } finally {
                restoreCallingIdentity(callingIdentity);
            }
        }

        @Override
        public void onDatagramTransferStateChanged(
                @SatelliteManager.SatelliteDatagramTransferState int state, int sendPendingCount,
                int receivePendingCount, @SatelliteManager.SatelliteError int errorCode) {
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() ->
                        mLocalCallback.onDatagramTransferStateChanged(
                                state, sendPendingCount, receivePendingCount, errorCode));
            } finally {
                restoreCallingIdentity(callingIdentity);
            }
        }

        private void setExecutor(Executor executor) {
            mExecutor = executor;
        }
    }

    /**
     * Called when the satellite position changed.
     *
     * @param pointingInfo The pointing info containing the satellite location.
     */
    public void onSatellitePositionChanged(@NonNull PointingInfo pointingInfo) {
        // Base Implementation
    }

    /**
     * Called when satellite datagram transfer state changed.
     *
     * @param state The new datagram transfer state.
     * @param sendPendingCount The number of datagrams that are currently being sent.
     * @param receivePendingCount The number of datagrams that are currently being received.
     * @param errorCode If datagram transfer failed, the reason for failure.
     */
    public void onDatagramTransferStateChanged(
            @SatelliteManager.SatelliteDatagramTransferState int state, int sendPendingCount,
            int receivePendingCount, @SatelliteManager.SatelliteError int errorCode) {
        // Base Implementation
    }

    /**@hide*/
    @NonNull
    public final ISatellitePositionUpdateCallback getBinder() {
        return mBinder;
    }

    /**@hide*/
    public void setExecutor(@NonNull Executor executor) {
        mBinder.setExecutor(executor);
    }
}
