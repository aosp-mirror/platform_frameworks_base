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
import android.hardware.hdmi.HdmiControlManager.VendorCommandListener;
import android.hardware.hdmi.IHdmiVendorCommandListener;
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
public final class HdmiTvClient extends HdmiClient {
    private static final String TAG = "HdmiTvClient";

    // Definitions used for setOption(). These should be in sync with the definition
    // in hardware/libhardware/include/hardware/{hdmi_cec.h,mhl.h}.

    /**
     * TV gets turned on by incoming &lt;Text/Image View On&gt;. {@code ENABLED} by default.
     * If set to {@code DISABLED}, TV won't turn on automatically.
     */
    public static final int OPTION_CEC_AUTO_WAKEUP = 1;

    /**
     * If set to {@code DISABLED}, all CEC commands are discarded.
     *
     * <p> This option is for internal use only, not supposed to be used by other components.
     * @hide
     */
    public static final int OPTION_CEC_ENABLE = 2;

    /**
     * If set to {@code DISABLED}, system service yields control of CEC to sub-microcontroller.
     * If {@code ENABLED}, it take the control back.
     *
     * <p> This option is for internal use only, not supposed to be used by other components.
     * @hide
     */
    public static final int OPTION_CEC_SERVICE_CONTROL = 3;

    /**
     * Put other devices to standby when TV goes to standby. {@code ENABLED} by default.
     * If set to {@code DISABLED}, TV doesn't send &lt;Standby&gt; to other devices.
     */
    public static final int OPTION_CEC_AUTO_DEVICE_OFF = 4;

    /** If set to {@code DISABLED}, TV does not switch ports when mobile device is connected. */
    public static final int OPTION_MHL_INPUT_SWITCHING = 101;

    /** If set to {@code ENABLED}, TV disables power charging for mobile device. */
    public static final int OPTION_MHL_POWER_CHARGE = 102;

    /**
     * If set to {@code DISABLED}, all MHL commands are discarded.
     *
     * <p> This option is for internal use only, not supposed to be used by other components.
     * @hide
     */
    public static final int OPTION_MHL_ENABLE = 103;

    public static final int DISABLED = 0;
    public static final int ENABLED = 1;

    HdmiTvClient(IHdmiControlService service) {
        super(service);
    }

    // Factory method for HdmiTvClient.
    // Declared package-private. Accessed by HdmiControlManager only.
    static HdmiTvClient create(IHdmiControlService service) {
        return new HdmiTvClient(service);
    }

    public int getDeviceType() {
        return HdmiCecDeviceInfo.DEVICE_TV;
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

    /**
     * Set system audio volume
     *
     * @param oldIndex current volume index
     * @param newIndex volume index to be set
     * @param maxIndex maximum volume index
     */
    public void setSystemAudioVolume(int oldIndex, int newIndex, int maxIndex) {
        try {
            mService.setSystemAudioVolume(oldIndex, newIndex, maxIndex);
        } catch (RemoteException e) {
            Log.e(TAG, "failed to set volume: ", e);
        }
    }

    /**
     * Set system audio mute status
     *
     * @param mute {@code true} if muted; otherwise, {@code false}
     */
    public void setSystemAudioMute(boolean mute) {
        try {
            mService.setSystemAudioMute(mute);
        } catch (RemoteException e) {
            Log.e(TAG, "failed to set mute: ", e);
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
