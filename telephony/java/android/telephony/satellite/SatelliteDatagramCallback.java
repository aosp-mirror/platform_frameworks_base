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
 * A callback class for listening to satellite datagrams.
 *
 * @hide
 */
public class SatelliteDatagramCallback {
    private final CallbackBinder mBinder = new CallbackBinder(this);

    private static class CallbackBinder extends ISatelliteDatagramCallback.Stub {
        private final SatelliteDatagramCallback mLocalCallback;
        private Executor mExecutor;

        private CallbackBinder(SatelliteDatagramCallback localCallback) {
            mLocalCallback = localCallback;
        }

        @Override
        public void onSatelliteDatagramReceived(long datagramId, SatelliteDatagram datagram,
                int pendingCount, ISatelliteDatagramReceiverAck callback) {
            final long callingIdentity = Binder.clearCallingIdentity();
            try {
                mExecutor.execute(() -> mLocalCallback.onSatelliteDatagramReceived(datagramId,
                        datagram, pendingCount, callback));
            } finally {
                restoreCallingIdentity(callingIdentity);
            }
        }

        private void setExecutor(Executor executor) {
            mExecutor = executor;
        }
    }

    /**
     * Called when there are incoming datagrams to be received.
     * @param datagramId An id that uniquely identifies incoming datagram.
     * @param datagram datagram to be received over satellite.
     * @param pendingCount Number of datagrams yet to be received by the app.
     * @param callback This callback will be used by datagram receiver app to send ack back to
     *                 Telephony.
     */
    public void onSatelliteDatagramReceived(long datagramId, SatelliteDatagram datagram,
            int pendingCount, ISatelliteDatagramReceiverAck callback) {
        // Base Implementation
    }

    /**@hide*/
    @NonNull
    public final ISatelliteDatagramCallback getBinder() {
        return mBinder;
    }

    /**@hide*/
    public void setExecutor(@NonNull Executor executor) {
        mBinder.setExecutor(executor);
    }
}
