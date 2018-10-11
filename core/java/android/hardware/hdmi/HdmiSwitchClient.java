/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.annotation.NonNull;
import android.os.RemoteException;
import android.util.Log;

import java.util.Collections;
import java.util.List;

/**
 * HdmiSwitchClient represents HDMI-CEC logical device of type Switch in the Android system which
 * acts as switch.
 *
 * <p>HdmiSwitchClient has a CEC device type of HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH,
 * but it is used by all Android TV devices that have multiple HDMI inputs,
 * even if it is not a "pure" swicth and has another device type like TV or Player.
 *
 * @hide
 * TODO(b/110094868): unhide and add @SystemApi for Q
 */
public class HdmiSwitchClient extends HdmiClient {

    private static final String TAG = "HdmiSwitchClient";

    /* package */ HdmiSwitchClient(IHdmiControlService service) {
        super(service);
    }

    private static IHdmiControlCallback getCallbackWrapper(final SelectCallback callback) {
        return new IHdmiControlCallback.Stub() {
            @Override
            public void onComplete(int result) {
                callback.onComplete(result);
            }
        };
    }

    /** @hide */
    // TODO(b/110094868): unhide for Q
    @Override
    public int getDeviceType() {
        return HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH;
    }

    /**
     * Selects a CEC logical device to be a new active source.
     *
     * @param logicalAddress logical address of the device to select
     * @param callback callback to get the result with
     * @throws {@link IllegalArgumentException} if the {@code callback} is null
     *
     * @hide
     * TODO(b/110094868): unhide and add @SystemApi for Q
     */
    public void deviceSelect(int logicalAddress, @NonNull SelectCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("callback must not be null.");
        }
        try {
            mService.deviceSelect(logicalAddress, getCallbackWrapper(callback));
        } catch (RemoteException e) {
            Log.e(TAG, "failed to select device: ", e);
        }
    }

    /**
     * Selects a HDMI port to be a new route path.
     *
     * @param portId HDMI port to select
     * @param callback callback to get the result with
     * @throws {@link IllegalArgumentException} if the {@code callback} is null
     *
     * @hide
     * TODO(b/110094868): unhide and add @SystemApi for Q
     */
    public void portSelect(int portId, @NonNull SelectCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("Callback must not be null");
        }
        try {
            mService.portSelect(portId, getCallbackWrapper(callback));
        } catch (RemoteException e) {
            Log.e(TAG, "failed to select port: ", e);
        }
    }

    /**
     * Returns all the CEC devices connected to the device.
     *
     * <p>This only applies to device with multiple HDMI inputs
     *
     * @return list of {@link HdmiDeviceInfo} for connected CEC devices. Empty list is returned if
     * there is none.
     *
     * @hide
     * TODO(b/110094868): unhide and add @SystemApi for Q
     */
    public List<HdmiDeviceInfo> getDeviceList() {
        try {
            return mService.getDeviceList();
        } catch (RemoteException e) {
            Log.e("TAG", "Failed to call getDeviceList():", e);
            return Collections.<HdmiDeviceInfo>emptyList();
        }
    }

    /**
     * Callback interface used to get the result of {@link #deviceSelect} or {@link #portSelect}.
     *
     * @hide
     * TODO(b/110094868): unhide and add @SystemApi for Q
     */
    public interface SelectCallback {

        /**
         * Called when the operation is finished.
         *
         * @param result the result value of {@link #deviceSelect} or {@link #portSelect}.
         */
        void onComplete(int result);
    }
}
