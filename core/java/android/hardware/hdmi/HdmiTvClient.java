/*
 * Copyright (C) 2014 The Android Open Source Project
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
package android.hardware.hdmi;

import android.annotation.SystemApi;
import android.os.RemoteException;
import android.util.Log;

/**
 * HdmiTvClient represents HDMI-CEC logical device of type TV in the Android system
 * which acts as TV/Display. It provides with methods that manage, interact with other
 * devices on the CEC bus.
 *
 * @hide
 */
@SystemApi
public final class HdmiTvClient {
    private static final String TAG = "HdmiTvClient";

    private final IHdmiControlService mService;

    HdmiTvClient(IHdmiControlService service) {
        mService = service;
    }

    // Factory method for HdmiTvClient.
    // Declared package-private. Accessed by HdmiControlManager only.
    static HdmiTvClient create(IHdmiControlService service) {
        return new HdmiTvClient(service);
    }

    /**
     * Callback interface used to get the result of {@link #deviceSelect}.
     */
    public interface SelectCallback {
        /**
         * Called when the operation is finished.
         *
         * @param result the result value of {@link #deviceSelect}
         */
        void onComplete(int result);
    }

    /**
     * Select a CEC logical device to be a new active source.
     *
     * @param logicalAddress
     * @param callback
     */
    public void deviceSelect(int logicalAddress, SelectCallback callback) {
        // TODO: Replace SelectCallback with PartialResult.
        try {
            mService.deviceSelect(logicalAddress, getCallbackWrapper(callback));
        } catch (RemoteException e) {
            Log.e(TAG, "failed to select device: ", e);
        }
    }

    private static IHdmiControlCallback getCallbackWrapper(final SelectCallback callback) {
        return new IHdmiControlCallback.Stub() {
            @Override
            public void onComplete(int result) {
                callback.onComplete(result);
            }
        };
    }
}
