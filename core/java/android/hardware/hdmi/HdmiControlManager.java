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
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;

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
@SystemService(Context.HDMI_CONTROL_SERVICE)
@RequiresFeature(PackageManager.FEATURE_HDMI_CEC)
public final class HdmiControlManager {
    private static final String TAG = "HdmiControlManager";

    @Nullable private final IHdmiControlService mService;

    /**
     * Broadcast Action: Display OSD message.
     * <p>Send when the service has a message to display on screen for events
     * that need user's attention such as ARC status change.
     * <p>Always contains the extra fields {@link #EXTRA_MESSAGE_ID}.
     * <p>Requires {@link android.Manifest.permission#HDMI_CEC} to receive.
     */
    @SdkConstant(SdkConstantType.BROADCAST_INTENT_ACTION)
    public static final String ACTION_OSD_MESSAGE = "android.hardware.hdmi.action.OSD_MESSAGE";

    // --- Messages for ACTION_OSD_MESSAGE ---
    /**
     * Message that ARC enabled device is connected to invalid port (non-ARC port).
     */
    public static final int OSD_MESSAGE_ARC_CONNECTED_INVALID_PORT = 1;

    /**
     * Message used by TV to receive volume status from Audio Receiver. It should check volume value
     * that is retrieved from extra value with the key {@link #EXTRA_MESSAGE_EXTRA_PARAM1}. If the
     * value is in range of [0,100], it is current volume of Audio Receiver. And there is another
     * value, {@link #AVR_VOLUME_MUTED}, which is used to inform volume mute.
     */
    public static final int OSD_MESSAGE_AVR_VOLUME_CHANGED = 2;

    /**
     * Used as an extra field in the intent {@link #ACTION_OSD_MESSAGE}. Contains the ID of
     * the message to display on screen.
     */
    public static final String EXTRA_MESSAGE_ID = "android.hardware.hdmi.extra.MESSAGE_ID";
    /**
     * Used as an extra field in the intent {@link #ACTION_OSD_MESSAGE}. Contains the extra value
     * of the message.
     */
    public static final String EXTRA_MESSAGE_EXTRA_PARAM1 =
            "android.hardware.hdmi.extra.MESSAGE_EXTRA_PARAM1";

    /**
     * Volume value for mute state.
     */
    public static final int AVR_VOLUME_MUTED = 101;

    public static final int POWER_STATUS_UNKNOWN = -1;
    public static final int POWER_STATUS_ON = 0;
    public static final int POWER_STATUS_STANDBY = 1;
    public static final int POWER_STATUS_TRANSIENT_TO_ON = 2;
    public static final int POWER_STATUS_TRANSIENT_TO_STANDBY = 3;

    public static final int RESULT_SUCCESS = 0;
    public static final int RESULT_TIMEOUT = 1;
    public static final int RESULT_SOURCE_NOT_AVAILABLE = 2;
    public static final int RESULT_TARGET_NOT_AVAILABLE = 3;

    @Deprecated public static final int RESULT_ALREADY_IN_PROGRESS = 4;
    public static final int RESULT_EXCEPTION = 5;
    public static final int RESULT_INCORRECT_MODE = 6;
    public static final int RESULT_COMMUNICATION_FAILED = 7;

    public static final int DEVICE_EVENT_ADD_DEVICE = 1;
    public static final int DEVICE_EVENT_REMOVE_DEVICE = 2;
    public static final int DEVICE_EVENT_UPDATE_DEVICE = 3;

    // --- One Touch Recording success result
    /** Recording currently selected source. Indicates the status of a recording. */
    public static final int ONE_TOUCH_RECORD_RECORDING_CURRENTLY_SELECTED_SOURCE = 0x01;
    /** Recording Digital Service. Indicates the status of a recording. */
    public static final int ONE_TOUCH_RECORD_RECORDING_DIGITAL_SERVICE = 0x02;
    /** Recording Analogue Service. Indicates the status of a recording. */
    public static final int ONE_TOUCH_RECORD_RECORDING_ANALOGUE_SERVICE = 0x03;
    /** Recording External input. Indicates the status of a recording. */
    public static final int ONE_TOUCH_RECORD_RECORDING_EXTERNAL_INPUT = 0x04;

    // --- One Touch Record failure result
    /** No recording – unable to record Digital Service. No suitable tuner. */
    public static final int ONE_TOUCH_RECORD_UNABLE_DIGITAL_SERVICE = 0x05;
    /** No recording – unable to record Analogue Service. No suitable tuner. */
    public static final int ONE_TOUCH_RECORD_UNABLE_ANALOGUE_SERVICE = 0x06;
    /**
     * No recording – unable to select required service. as suitable tuner, but the requested
     * parameters are invalid or out of range for that tuner.
     */
    public static final int ONE_TOUCH_RECORD_UNABLE_SELECTED_SERVICE = 0x07;
    /** No recording – invalid External plug number */
    public static final int ONE_TOUCH_RECORD_INVALID_EXTERNAL_PLUG_NUMBER = 0x09;
    /** No recording – invalid External Physical Address */
    public static final int ONE_TOUCH_RECORD_INVALID_EXTERNAL_PHYSICAL_ADDRESS = 0x0A;
    /** No recording – CA system not supported */
    public static final int ONE_TOUCH_RECORD_UNSUPPORTED_CA = 0x0B;
    /** No Recording – No or Insufficient CA Entitlements” */
    public static final int ONE_TOUCH_RECORD_NO_OR_INSUFFICIENT_CA_ENTITLEMENTS = 0x0C;
    /** No recording – Not allowed to copy source. Source is “copy never”. */
    public static final int ONE_TOUCH_RECORD_DISALLOW_TO_COPY = 0x0D;
    /** No recording – No further copies allowed */
    public static final int ONE_TOUCH_RECORD_DISALLOW_TO_FUTHER_COPIES = 0x0E;
    /** No recording – No media */
    public static final int ONE_TOUCH_RECORD_NO_MEDIA = 0x10;
    /** No recording – playing */
    public static final int ONE_TOUCH_RECORD_PLAYING = 0x11;
    /** No recording – already recording */
    public static final int ONE_TOUCH_RECORD_ALREADY_RECORDING = 0x12;
    /** No recording – media protected */
    public static final int ONE_TOUCH_RECORD_MEDIA_PROTECTED = 0x13;
    /** No recording – no source signal */
    public static final int ONE_TOUCH_RECORD_NO_SOURCE_SIGNAL = 0x14;
    /** No recording – media problem */
    public static final int ONE_TOUCH_RECORD_MEDIA_PROBLEM = 0x15;
    /** No recording – not enough space available */
    public static final int ONE_TOUCH_RECORD_NOT_ENOUGH_SPACE = 0x16;
    /** No recording – Parental Lock On */
    public static final int ONE_TOUCH_RECORD_PARENT_LOCK_ON = 0x17;
    /** Recording terminated normally */
    public static final int ONE_TOUCH_RECORD_RECORDING_TERMINATED_NORMALLY = 0x1A;
    /** Recording has already terminated */
    public static final int ONE_TOUCH_RECORD_RECORDING_ALREADY_TERMINATED = 0x1B;
    /** No recording – other reason */
    public static final int ONE_TOUCH_RECORD_OTHER_REASON = 0x1F;
    // From here extra message for recording that is not mentioned in CEC spec
    /** No recording. Previous recording request in progress. */
    public static final int ONE_TOUCH_RECORD_PREVIOUS_RECORDING_IN_PROGRESS = 0x30;
    /** No recording. Please check recorder and connection. */
    public static final int ONE_TOUCH_RECORD_CHECK_RECORDER_CONNECTION = 0x31;
    /** Cannot record currently displayed source. */
    public static final int ONE_TOUCH_RECORD_FAIL_TO_RECORD_DISPLAYED_SCREEN = 0x32;
    /** CEC is disabled. */
    public static final int ONE_TOUCH_RECORD_CEC_DISABLED = 0x33;

    // --- Types for timer recording
    /** Timer recording type for digital service source. */
    public static final int TIMER_RECORDING_TYPE_DIGITAL = 1;
    /** Timer recording type for analogue service source. */
    public static final int TIMER_RECORDING_TYPE_ANALOGUE = 2;
    /** Timer recording type for external source. */
    public static final int TIMER_RECORDING_TYPE_EXTERNAL = 3;

    // --- Timer Status Data
    /** [Timer Status Data/Media Info] - Media present and not protected. */
    public static final int TIMER_STATUS_MEDIA_INFO_PRESENT_NOT_PROTECTED = 0x0;
    /** [Timer Status Data/Media Info] - Media present, but protected. */
    public static final int TIMER_STATUS_MEDIA_INFO_PRESENT_PROTECTED = 0x1;
    /** [Timer Status Data/Media Info] - Media not present. */
    public static final int TIMER_STATUS_MEDIA_INFO_NOT_PRESENT = 0x2;

    /** [Timer Status Data/Programmed Info] - Enough space available for recording. */
    public static final int TIMER_STATUS_PROGRAMMED_INFO_ENOUGH_SPACE = 0x8;
    /** [Timer Status Data/Programmed Info] - Not enough space available for recording. */
    public static final int TIMER_STATUS_PROGRAMMED_INFO_NOT_ENOUGH_SPACE = 0x9;
    /** [Timer Status Data/Programmed Info] - Might not enough space available for recording. */
    public static final int TIMER_STATUS_PROGRAMMED_INFO_MIGHT_NOT_ENOUGH_SPACE = 0xB;
    /** [Timer Status Data/Programmed Info] - No media info available. */
    public static final int TIMER_STATUS_PROGRAMMED_INFO_NO_MEDIA_INFO = 0xA;

    /** [Timer Status Data/Not Programmed Error Info] - No free timer available. */
    public static final int TIMER_STATUS_NOT_PROGRAMMED_NO_FREE_TIME = 0x1;
    /** [Timer Status Data/Not Programmed Error Info] - Date out of range. */
    public static final int TIMER_STATUS_NOT_PROGRAMMED_DATE_OUT_OF_RANGE = 0x2;
    /** [Timer Status Data/Not Programmed Error Info] - Recording Sequence error. */
    public static final int TIMER_STATUS_NOT_PROGRAMMED_INVALID_SEQUENCE = 0x3;
    /** [Timer Status Data/Not Programmed Error Info] - Invalid External Plug Number. */
    public static final int TIMER_STATUS_NOT_PROGRAMMED_INVALID_EXTERNAL_PLUG_NUMBER = 0x4;
    /** [Timer Status Data/Not Programmed Error Info] - Invalid External Physical Address. */
    public static final int TIMER_STATUS_NOT_PROGRAMMED_INVALID_EXTERNAL_PHYSICAL_NUMBER = 0x5;
    /** [Timer Status Data/Not Programmed Error Info] - CA system not supported. */
    public static final int TIMER_STATUS_NOT_PROGRAMMED_CA_NOT_SUPPORTED = 0x6;
    /** [Timer Status Data/Not Programmed Error Info] - No or insufficient CA Entitlements. */
    public static final int TIMER_STATUS_NOT_PROGRAMMED_NO_CA_ENTITLEMENTS = 0x7;
    /** [Timer Status Data/Not Programmed Error Info] - Does not support resolution. */
    public static final int TIMER_STATUS_NOT_PROGRAMMED_UNSUPPORTED_RESOLUTION = 0x8;
    /** [Timer Status Data/Not Programmed Error Info] - Parental Lock On. */
    public static final int TIMER_STATUS_NOT_PROGRAMMED_PARENTAL_LOCK_ON= 0x9;
    /** [Timer Status Data/Not Programmed Error Info] - Clock Failure. */
    public static final int TIMER_STATUS_NOT_PROGRAMMED_CLOCK_FAILURE = 0xA;
    /** [Timer Status Data/Not Programmed Error Info] - Duplicate: already programmed. */
    public static final int TIMER_STATUS_NOT_PROGRAMMED_DUPLICATED = 0xE;

    // --- Extra result value for timer recording.
    /** No extra error. */
    public static final int TIMER_RECORDING_RESULT_EXTRA_NO_ERROR = 0x00;
    /** No timer recording - check recorder and connection. */
    public static final int TIMER_RECORDING_RESULT_EXTRA_CHECK_RECORDER_CONNECTION = 0x01;
    /** No timer recording - cannot record selected source. */
    public static final int TIMER_RECORDING_RESULT_EXTRA_FAIL_TO_RECORD_SELECTED_SOURCE = 0x02;
    /** CEC is disabled. */
    public static final int TIMER_RECORDING_RESULT_EXTRA_CEC_DISABLED = 0x03;

    // -- Timer cleared status data code used for result of onClearTimerRecordingResult.
    /** Timer not cleared – recording. */
    public static final int CLEAR_TIMER_STATUS_TIMER_NOT_CLEARED_RECORDING = 0x00;
    /** Timer not cleared – no matching. */
    public static final int CLEAR_TIMER_STATUS_TIMER_NOT_CLEARED_NO_MATCHING = 0x01;
    /** Timer not cleared – no info available. */
    public static final int CLEAR_TIMER_STATUS_TIMER_NOT_CLEARED_NO_INFO_AVAILABLE = 0x02;
    /** Timer cleared. */
    public static final int CLEAR_TIMER_STATUS_TIMER_CLEARED = 0x80;
    /** Clear timer error - check recorder and connection. */
    public static final int CLEAR_TIMER_STATUS_CHECK_RECORDER_CONNECTION = 0xA0;
    /** Clear timer error - cannot clear timer for selected source. */
    public static final int CLEAR_TIMER_STATUS_FAIL_TO_CLEAR_SELECTED_SOURCE = 0xA1;
    /** Clear timer error - CEC is disabled. */
    public static final int CLEAR_TIMER_STATUS_CEC_DISABLE = 0xA2;

    /** The HdmiControlService is started. */
    public static final int CONTROL_STATE_CHANGED_REASON_START = 0;
    /** The state of HdmiControlService is changed by changing of settings. */
    public static final int CONTROL_STATE_CHANGED_REASON_SETTING = 1;
    /** The HdmiControlService is enabled to wake up. */
    public static final int CONTROL_STATE_CHANGED_REASON_WAKEUP = 2;
    /** The HdmiControlService will be disabled to standby. */
    public static final int CONTROL_STATE_CHANGED_REASON_STANDBY = 3;

    // True if we have a logical device of type playback hosted in the system.
    private final boolean mHasPlaybackDevice;
    // True if we have a logical device of type TV hosted in the system.
    private final boolean mHasTvDevice;

    /**
     * {@hide} - hide this constructor because it has a parameter of type IHdmiControlService,
     * which is a system private class. The right way to create an instance of this class is
     * using the factory Context.getSystemService.
     */
    public HdmiControlManager(IHdmiControlService service) {
        mService = service;
        int[] types = null;
        if (mService != null) {
            try {
                types = mService.getSupportedTypes();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
        mHasTvDevice = hasDeviceType(types, HdmiDeviceInfo.DEVICE_TV);
        mHasPlaybackDevice = hasDeviceType(types, HdmiDeviceInfo.DEVICE_PLAYBACK);
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
     * Gets an object that represents an HDMI-CEC logical device of a specified type.
     *
     * @param type CEC device type
     * @return {@link HdmiClient} instance. {@code null} on failure.
     * See {@link HdmiDeviceInfo#DEVICE_PLAYBACK}
     * See {@link HdmiDeviceInfo#DEVICE_TV}
     */
    @Nullable
    @SuppressLint("Doclava125")
    public HdmiClient getClient(int type) {
        if (mService == null) {
            return null;
        }
        switch (type) {
            case HdmiDeviceInfo.DEVICE_TV:
                return mHasTvDevice ? new HdmiTvClient(mService) : null;
            case HdmiDeviceInfo.DEVICE_PLAYBACK:
                return mHasPlaybackDevice ? new HdmiPlaybackClient(mService) : null;
            default:
                return null;
        }
    }

    /**
     * Gets an object that represents an HDMI-CEC logical device of type playback on the system.
     *
     * <p>Used to send HDMI control messages to other devices like TV or audio amplifier through
     * HDMI bus. It is also possible to communicate with other logical devices hosted in the same
     * system if the system is configured to host more than one type of HDMI-CEC logical devices.
     *
     * @return {@link HdmiPlaybackClient} instance. {@code null} on failure.
     */
    @Nullable
    @SuppressLint("Doclava125")
    public HdmiPlaybackClient getPlaybackClient() {
        return (HdmiPlaybackClient) getClient(HdmiDeviceInfo.DEVICE_PLAYBACK);
    }

    /**
     * Gets an object that represents an HDMI-CEC logical device of type TV on the system.
     *
     * <p>Used to send HDMI control messages to other devices and manage them through
     * HDMI bus. It is also possible to communicate with other logical devices hosted in the same
     * system if the system is configured to host more than one type of HDMI-CEC logical devices.
     *
     * @return {@link HdmiTvClient} instance. {@code null} on failure.
     */
    @Nullable
    @SuppressLint("Doclava125")
    public HdmiTvClient getTvClient() {
        return (HdmiTvClient) getClient(HdmiDeviceInfo.DEVICE_TV);
    }

    /**
     * Controls standby mode of the system. It will also try to turn on/off the connected devices if
     * necessary.
     *
     * @param isStandbyModeOn target status of the system's standby mode
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void setStandbyMode(boolean isStandbyModeOn) {
        try {
            mService.setStandbyMode(isStandbyModeOn);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Listener used to get hotplug event from HDMI port.
     */
    public interface HotplugEventListener {
        void onReceived(HdmiHotplugEvent event);
    }

    private final ArrayMap<HotplugEventListener, IHdmiHotplugEventListener>
            mHotplugEventListeners = new ArrayMap<>();

    /**
     * Listener used to get vendor-specific commands.
     */
    public interface VendorCommandListener {
        /**
         * Called when a vendor command is received.
         *
         * @param srcAddress source logical address
         * @param destAddress destination logical address
         * @param params vendor-specific parameters
         * @param hasVendorId {@code true} if the command is &lt;Vendor Command
         *        With ID&gt;. The first 3 bytes of params is vendor id.
         */
        void onReceived(int srcAddress, int destAddress, byte[] params, boolean hasVendorId);

        /**
         * The callback is called:
         * <ul>
         *     <li> before HdmiControlService is disabled.
         *     <li> after HdmiControlService is enabled and the local address is assigned.
         * </ul>
         * The client shouldn't hold the thread too long since this is a blocking call.
         *
         * @param enabled {@code true} if HdmiControlService is enabled.
         * @param reason the reason code why the state of HdmiControlService is changed.
         * @see #CONTROL_STATE_CHANGED_REASON_START
         * @see #CONTROL_STATE_CHANGED_REASON_SETTING
         * @see #CONTROL_STATE_CHANGED_REASON_WAKEUP
         * @see #CONTROL_STATE_CHANGED_REASON_STANDBY
         */
        void onControlStateChanged(boolean enabled, int reason);
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
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void addHotplugEventListener(HotplugEventListener listener) {
        if (mService == null) {
            Log.e(TAG, "HdmiControlService is not available");
            return;
        }
        if (mHotplugEventListeners.containsKey(listener)) {
            Log.e(TAG, "listener is already registered");
            return;
        }
        IHdmiHotplugEventListener wrappedListener = getHotplugEventListenerWrapper(listener);
        mHotplugEventListeners.put(listener, wrappedListener);
        try {
            mService.addHotplugEventListener(wrappedListener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes a listener to stop getting informed of {@link HdmiHotplugEvent}.
     *
     * @param listener {@link HotplugEventListener} instance to be removed
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void removeHotplugEventListener(HotplugEventListener listener) {
        if (mService == null) {
            Log.e(TAG, "HdmiControlService is not available");
            return;
        }
        IHdmiHotplugEventListener wrappedListener = mHotplugEventListeners.remove(listener);
        if (wrappedListener == null) {
            Log.e(TAG, "tried to remove not-registered listener");
            return;
        }
        try {
            mService.removeHotplugEventListener(wrappedListener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private IHdmiHotplugEventListener getHotplugEventListenerWrapper(
            final HotplugEventListener listener) {
        return new IHdmiHotplugEventListener.Stub() {
            @Override
            public void onReceived(HdmiHotplugEvent event) {
                listener.onReceived(event);;
            }
        };
    }
}
