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

import android.annotation.Nullable;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SystemApi;
import android.os.RemoteException;

/**
 * The {@link HdmiControlManager} class is used to send HDMI control messages
 * to attached CEC devices.
 *
 * <p>Provides various HDMI client instances that represent HDMI-CEC logical devices
 * hosted in the system. {@link #getTvClient()}, for instance will return an
 * {@link HdmiTvClient} object if the system is configured to host one. Android system
 * can host more than one logical CEC devices. If multiple types are configured they
 * all work as if they were independent logical devices running in the system.
 *
 * @hide
 */
@SystemApi
public final class HdmiControlManager {
    @Nullable private final IHdmiControlService mService;

    /**
     * Broadcast Action: Display OSD message.
     * <p>Send when the service has a message to display on screen for events
     * that need user's attention such as ARC status change.
     * <p>Always contains the extra fields {@link #EXTRA_MESSAGE}.
     * <p>Requires {@link android.Manifest.permission#HDMI_CEC} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_OSD_MESSAGE = "android.hardware.hdmi.action.OSD_MESSAGE";

    /**
     * Used as an extra field in the intent {@link #ACTION_OSD_MESSAGE}. Contains the ID of
     * the message to display on screen.
     */
    public static final String EXTRA_MESSAGE_ID = "android.hardware.hdmi.extra.MESSAGE_ID";

    public static final int POWER_STATUS_UNKNOWN = -1;
    public static final int POWER_STATUS_ON = 0;
    public static final int POWER_STATUS_STANDBY = 1;
    public static final int POWER_STATUS_TRANSIENT_TO_ON = 2;
    public static final int POWER_STATUS_TRANSIENT_TO_STANDBY = 3;

    public static final int RESULT_SUCCESS = 0;
    public static final int RESULT_TIMEOUT = 1;
    public static final int RESULT_SOURCE_NOT_AVAILABLE = 2;
    public static final int RESULT_TARGET_NOT_AVAILABLE = 3;
    public static final int RESULT_ALREADY_IN_PROGRESS = 4;
    public static final int RESULT_EXCEPTION = 5;
    public static final int RESULT_INCORRECT_MODE = 6;
    public static final int RESULT_COMMUNICATION_FAILED = 7;

    // -- Message ids for display osd.

    /** Place holder for recording status message. Indicates the status of a recording. */
    public static final int MESSAGE_RECORDING_STATUS_MESSAGE_START = 0x100;
    /** Recording currently selected source. Indicates the status of a recording. */
    public static final int MESSAGE_RECORDING_CURRENTLY_SELECTED_SOURCE = 0x101;
    /** Recording Digital Service. Indicates the status of a recording. */
    public static final int MESSAGE_RECORDING_DIGITAL_SERVICE = 0x102;
    /** Recording Analogue Service. Indicates the status of a recording. */
    public static final int MESSAGE_RECORDING_ANALOGUE_SERVICE = 0x103;
    /** Recording External input. Indicates the status of a recording. */
    public static final int MESSAGE_RECORDING_EXTERNAL_INPUT = 0x104;
    /** No recording – unable to record Digital Service. No suitable tuner. */
    public static final int MESSAGE_NO_RECORDNIG_UNABLE_DIGITAL_SERVICE = 0x105;
    /** No recording – unable to record Analogue Service. No suitable tuner. */
    public static final int MESSAGE_NO_RECORDNIG_UNABLE_ANALOGUE_SERVICE = 0x106;
    /**
     * No recording – unable to select required service. as suitable tuner, but the requested
     * parameters are invalid or out of range for that tuner.
     */
    public static final int MESSAGE_NO_RECORDNIG_UNABLE_SELECTED_SERVICE = 0x107;
    /** No recording – invalid External plug number */
    public static final int MESSAGE_NO_RECORDNIG_INVALID_EXTERNAL_PLUG_NUMBER = 0x109;
    /** No recording – invalid External Physical Address */
    public static final int MESSAGE_NO_RECORDNIG_INVALID_EXTERNAL_PHYSICAL_ADDRESS = 0x10A;
    /** No recording – CA system not supported */
    public static final int MESSAGE_NO_RECORDNIG_UNSUPPORTED_CA = 0x10B;
    /** No Recording – No or Insufficient CA Entitlements” */
    public static final int MESSAGE_NO_RECORDNIG_NO_OR_INSUFFICIENT_CA_ENTITLEMENTS = 0x10C;
    /** No recording – Not allowed to copy source. Source is “copy never”. */
    public static final int MESSAGE_NO_RECORDNIG_DISALLOW_TO_COPY = 0x10D;
    /** No recording – No further copies allowed */
    public static final int MESSAGE_NO_RECORDNIG_DISALLOW_TO_FUTHER_COPIES = 0x10E;
    /** No recording – No media */
    public static final int MESSAGE_NO_RECORDNIG_NO_MEDIA = 0x110;
    /** No recording – playing */
    public static final int MESSAGE_NO_RECORDNIG_PLAYING = 0x111;
    /** No recording – already recording */
    public static final int MESSAGE_NO_RECORDNIG_ALREADY_RECORDING = 0x112;
    /** No recording – media protected */
    public static final int MESSAGE_NO_RECORDNIG_MEDIA_PROTECTED = 0x113;
    /** No recording – no source signal */
    public static final int MESSAGE_NO_RECORDNIG_NO_SOURCE_SIGNAL = 0x114;
    /** No recording – media problem */
    public static final int MESSAGE_NO_RECORDNIG_MEDIA_PROBLEM = 0x115;
    /** No recording – not enough space available */
    public static final int MESSAGE_NO_RECORDNIG_NOT_ENOUGH_SPACE = 0x116;
    /** No recording – Parental Lock On */
    public static final int MESSAGE_NO_RECORDNIG_PARENT_LOCK_ON = 0x117;
    /** Recording terminated normally */
    public static final int MESSAGE_RECORDING_TERMINATED_NORMALLY = 0x11A;
    /** Recording has already terminated */
    public static final int MESSAGE_RECORDING_ALREADY_TERMINATED = 0x11B;
    /** No recording – other reason */
    public static final int MESSAGE_NO_RECORDNIG_OTHER_REASON = 0x11F;
    // From here extra message for recording that is not mentioned in CEC spec
    /** No recording. Previous recording request in progress. */
    public static final int MESSAGE_NO_RECORDING_PREVIOUS_RECORDING_IN_PROGRESS = 0x130;
    /** No recording. Please check recorder and connection. */
    public static final int MESSAGE_NO_RECORDING_CHECK_RECORDER_CONNECTION = 0x131;
    /** Cannot record currently displayed source. */
    public static final int MESSAGE_NO_RECORDING_FAIL_TO_RECORD_DISPLAYED_SCREEN = 0x132;

    /** Timer recording type for digital service source. */
    public static final int TIMER_RECORDING_TYPE_DIGITAL = 1;
    /** Timer recording type for analogue service source. */
    public static final int TIMER_RECORDING_TYPE_ANALOGUE = 2;
    /** Timer recording type for external source. */
    public static final int TIMER_RECORDING_TYPE_EXTERNAL = 3;

    // True if we have a logical device of type playback hosted in the system.
    private final boolean mHasPlaybackDevice;
    // True if we have a logical device of type TV hosted in the system.
    private final boolean mHasTvDevice;

    /**
     * @hide - hide this constructor because it has a parameter of type
     * IHdmiControlService, which is a system private class. The right way
     * to create an instance of this class is using the factory
     * Context.getSystemService.
     */
    public HdmiControlManager(IHdmiControlService service) {
        mService = service;
        int[] types = null;
        if (mService != null) {
            try {
                types = mService.getSupportedTypes();
            } catch (RemoteException e) {
                // Do nothing.
            }
        }
        mHasTvDevice = hasDeviceType(types, HdmiCecDeviceInfo.DEVICE_TV);
        mHasPlaybackDevice = hasDeviceType(types, HdmiCecDeviceInfo.DEVICE_PLAYBACK);
    }

    private static boolean hasDeviceType(int[] types, int type) {
        if (types == null) {
            return false;
        }
        for (int t : types) {
            if (t == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets an object that represents a HDMI-CEC logical device of type playback on the system.
     *
     * <p>Used to send HDMI control messages to other devices like TV or audio amplifier through
     * HDMI bus. It is also possible to communicate with other logical devices hosted in the same
     * system if the system is configured to host more than one type of HDMI-CEC logical devices.
     *
     * @return {@link HdmiPlaybackClient} instance. {@code null} on failure.
     */
    @Nullable
    public HdmiPlaybackClient getPlaybackClient() {
        if (mService == null || !mHasPlaybackDevice) {
            return null;
        }
        return new HdmiPlaybackClient(mService);
    }

    /**
     * Gets an object that represents a HDMI-CEC logical device of type TV on the system.
     *
     * <p>Used to send HDMI control messages to other devices and manage them through
     * HDMI bus. It is also possible to communicate with other logical devices hosted in the same
     * system if the system is configured to host more than one type of HDMI-CEC logical devices.
     *
     * @return {@link HdmiTvClient} instance. {@code null} on failure.
     */
    @Nullable
    public HdmiTvClient getTvClient() {
        if (mService == null || !mHasTvDevice) {
                return null;
        }
        return new HdmiTvClient(mService);
    }

    /**
     * Listener used to get hotplug event from HDMI port.
     */
    public interface HotplugEventListener {
        void onReceived(HdmiHotplugEvent event);
    }

    /**
     * Listener used to get vendor-specific commands.
     */
    public interface VendorCommandListener {
        /**
         * Called when a vendor command is received.
         *
         * @param srcAddress source logical address
         * @param params vendor-specific parameters
         * @param hasVendorId {@code true} if the command is &lt;Vendor Command
         *        With ID&gt;. The first 3 bytes of params is vendor id.
         */
        void onReceived(int srcAddress, byte[] params, boolean hasVendorId);
    }

    /**
     * Adds a listener to get informed of {@link HdmiHotplugEvent}.
     *
     * <p>To stop getting the notification,
     * use {@link #removeHotplugEventListener(HotplugEventListener)}.
     *
     * @param listener {@link HotplugEventListener} instance
     * @see HdmiControlManager#removeHotplugEventListener(HotplugEventListener)
     */
    public void addHotplugEventListener(HotplugEventListener listener) {
        if (mService == null) {
            return;
        }
        try {
            mService.addHotplugEventListener(getHotplugEventListenerWrapper(listener));
        } catch (RemoteException e) {
            // Do nothing.
        }
    }

    /**
     * Removes a listener to stop getting informed of {@link HdmiHotplugEvent}.
     *
     * @param listener {@link HotplugEventListener} instance to be removed
     */
    public void removeHotplugEventListener(HotplugEventListener listener) {
        if (mService == null) {
            return;
        }
        try {
            mService.removeHotplugEventListener(getHotplugEventListenerWrapper(listener));
        } catch (RemoteException e) {
            // Do nothing.
        }
    }

    private IHdmiHotplugEventListener getHotplugEventListenerWrapper(
            final HotplugEventListener listener) {
        return new IHdmiHotplugEventListener.Stub() {
            public void onReceived(HdmiHotplugEvent event) {
                listener.onReceived(event);;
            }
        };
    }
}
