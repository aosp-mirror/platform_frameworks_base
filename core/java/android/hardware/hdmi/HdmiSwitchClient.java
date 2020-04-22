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

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.hardware.hdmi.HdmiControlManager.ControlCallbackResult;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * An {@code HdmiSwitchClient} represents a HDMI-CEC switch device.
 *
 * <p>HdmiSwitchClient has a CEC device type of HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH, but it is
 * used by all Android devices that have multiple HDMI inputs, even if it is not a "pure" swicth
 * and has another device type like TV or Player.
 *
 * @hide
 */
@SystemApi
public class HdmiSwitchClient extends HdmiClient {

    private static final String TAG = "HdmiSwitchClient";

    /* package */ HdmiSwitchClient(IHdmiControlService service) {
        super(service);
    }

    private static IHdmiControlCallback getCallbackWrapper(final OnSelectListener listener) {
        return new IHdmiControlCallback.Stub() {
            @Override
            public void onComplete(int result) {
                listener.onSelect(result);
            }
        };
    }

    @Override
    public int getDeviceType() {
        return HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH;
    }

    /**
     * Selects a CEC logical device to be a new active source.
     *
     * @param logicalAddress logical address of the device to select
     * @param listener listener to get the result with
     *
     * @hide
     */
    public void selectDevice(int logicalAddress, @NonNull OnSelectListener listener) {
        Objects.requireNonNull(listener);
        try {
            mService.deviceSelect(logicalAddress, getCallbackWrapper(listener));
        } catch (RemoteException e) {
            Log.e(TAG, "failed to select device: ", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Selects a HDMI port to be a new route path.
     *
     * @param portId HDMI port to select
     * @see {@link android.media.tv.TvInputHardwareInfo#getHdmiPortId()}
     *     to get portId of a specific TV Input.
     * @param listener listener to get the result with
     *
     * @hide
     */
    @SystemApi
    public void selectPort(int portId, @NonNull OnSelectListener listener) {
        Objects.requireNonNull(listener);
        try {
            mService.portSelect(portId, getCallbackWrapper(listener));
        } catch (RemoteException e) {
            Log.e(TAG, "failed to select port: ", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Selects a CEC logical device to be a new active source.
     *
     * @param logicalAddress logical address of the device to select
     * @param listener       listener to get the result with
     * @hide
     */
    public void selectDevice(
            int logicalAddress,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnSelectListener listener) {
        Objects.requireNonNull(listener);
        try {
            mService.deviceSelect(logicalAddress,
                    new IHdmiControlCallback.Stub() {
                            @Override
                            public void onComplete(int result) {
                                Binder.withCleanCallingIdentity(
                                        () -> executor.execute(() -> listener.onSelect(result)));
                            }
                    }
            );
        } catch (RemoteException e) {
            Log.e(TAG, "failed to select device: ", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Selects a HDMI port to be a new route path.
     *
     * @param portId   HDMI port to select
     * @param listener listener to get the result with
     * @hide
     */
    @SystemApi
    public void selectPort(
            int portId,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull OnSelectListener listener) {
        Objects.requireNonNull(listener);
        try {
            mService.portSelect(portId,
                    new IHdmiControlCallback.Stub() {
                            @Override
                            public void onComplete(int result) {
                                Binder.withCleanCallingIdentity(
                                        () -> executor.execute(() -> listener.onSelect(result)));
                            }
                    }
            );
        } catch (RemoteException e) {
            Log.e(TAG, "failed to select port: ", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns all the CEC devices connected to the device.
     *
     * <p>This only applies to device with multiple HDMI inputs
     *
     * @return list of {@link HdmiDeviceInfo} for connected CEC devices. Empty list is returned if
     *     there is none.
     *
     * @hide
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
     * Get the list of the HDMI input port configuration.
     *
     * <p>This returns an empty list when the current device does not have HDMI input.
     *
     * @return a list of {@link HdmiPortInfo}
     *
     * @hide
     */
    @NonNull
    @SystemApi
    public List<HdmiPortInfo> getPortInfo() {
        try {
            return mService.getPortInfo();
        } catch (RemoteException e) {
            Log.e("TAG", "Failed to call getPortInfo():", e);
            return Collections.<HdmiPortInfo>emptyList();
        }
    }

    /**
     * Listener interface used to get the result of {@link #deviceSelect} or {@link #portSelect}.
     *
     * @hide
     */
    @SystemApi
    public interface OnSelectListener {

        /**
         * Called when the operation is finished.
         *
         * @param result callback result.
         * @see {@link ControlCallbackResult}
         */
        void onSelect(@ControlCallbackResult int result);
    }
}
