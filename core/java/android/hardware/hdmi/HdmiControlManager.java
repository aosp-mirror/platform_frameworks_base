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

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.RequiresPermission;
import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.annotation.StringDef;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.RemoteException;
import android.sysprop.HdmiProperties;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ConcurrentUtils;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * The {@link HdmiControlManager} class is used to send HDMI control messages
 * to attached CEC devices. It also allows to control the eARC feature.
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

    private static final int INVALID_PHYSICAL_ADDRESS = 0xFFFF;

    /**
     * A cache of the current device's physical address. When device's HDMI out port
     * is not connected to any device, it is set to {@link #INVALID_PHYSICAL_ADDRESS}.
     *
     * <p>Otherwise it is updated by the {@link ClientHotplugEventListener} registered
     * with {@link com.android.server.hdmi.HdmiControlService} by the
     * {@link #addHotplugEventListener(HotplugEventListener)} and the address is from
     * {@link com.android.server.hdmi.HdmiControlService#getPortInfo()}
     */
    @GuardedBy("mLock")
    private int mLocalPhysicalAddress = INVALID_PHYSICAL_ADDRESS;

    private void setLocalPhysicalAddress(int physicalAddress) {
        synchronized (mLock) {
            mLocalPhysicalAddress = physicalAddress;
        }
    }

    private int getLocalPhysicalAddress() {
        synchronized (mLock) {
            return mLocalPhysicalAddress;
        }
    }

    private final Object mLock = new Object();

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
     * Used as an extra field in the Set Menu Language intent. Contains the requested locale.
     * @hide
     */
    public static final String EXTRA_LOCALE = "android.hardware.hdmi.extra.LOCALE";

    /**
     * Volume value for mute state.
     */
    public static final int AVR_VOLUME_MUTED = 101;

    public static final int POWER_STATUS_UNKNOWN = -1;
    public static final int POWER_STATUS_ON = 0;
    public static final int POWER_STATUS_STANDBY = 1;
    public static final int POWER_STATUS_TRANSIENT_TO_ON = 2;
    public static final int POWER_STATUS_TRANSIENT_TO_STANDBY = 3;

    /** @removed mistakenly exposed previously */
    @IntDef ({
        RESULT_SUCCESS,
        RESULT_TIMEOUT,
        RESULT_SOURCE_NOT_AVAILABLE,
        RESULT_TARGET_NOT_AVAILABLE,
        RESULT_ALREADY_IN_PROGRESS,
        RESULT_EXCEPTION,
        RESULT_INCORRECT_MODE,
        RESULT_COMMUNICATION_FAILED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ControlCallbackResult {}

    /** Control operation is successfully handled by the framework. */
    public static final int RESULT_SUCCESS = 0;
    public static final int RESULT_TIMEOUT = 1;
    /** Source device that the application is using is not available. */
    public static final int RESULT_SOURCE_NOT_AVAILABLE = 2;
    /** Target device that the application is controlling is not available. */
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

    // -- Whether the HDMI CEC is enabled or disabled.
    /**
     * HDMI CEC enabled.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_HDMI_CEC_ENABLED
     */
    public static final int HDMI_CEC_CONTROL_ENABLED = 1;
    /**
     * HDMI CEC disabled.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_HDMI_CEC_ENABLED
     */
    public static final int HDMI_CEC_CONTROL_DISABLED = 0;
    /**
     * @hide
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_HDMI_CEC_ENABLED
     */
    @IntDef(prefix = { "HDMI_CEC_CONTROL_" }, value = {
            HDMI_CEC_CONTROL_ENABLED,
            HDMI_CEC_CONTROL_DISABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HdmiCecControl {}

    // -- Supported HDMI-CEC versions.
    /**
     * Version constant for HDMI-CEC v1.4b.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_HDMI_CEC_VERSION
     */
    public static final int HDMI_CEC_VERSION_1_4_B = 0x05;
    /**
     * Version constant for HDMI-CEC v2.0.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_HDMI_CEC_VERSION
     */
    public static final int HDMI_CEC_VERSION_2_0 = 0x06;
    /**
     * @see HdmiControlManager#CEC_SETTING_NAME_HDMI_CEC_VERSION
     * @hide
     */
    @IntDef(prefix = { "HDMI_CEC_VERSION_" }, value = {
            HDMI_CEC_VERSION_1_4_B,
            HDMI_CEC_VERSION_2_0
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface HdmiCecVersion {}

    // -- Whether the Routing Control feature is enabled or disabled.
    /**
     * Routing Control feature enabled.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_ROUTING_CONTROL
     */
    public static final int ROUTING_CONTROL_ENABLED = 1;
    /**
     * Routing Control feature disabled.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_ROUTING_CONTROL
     */
    public static final int ROUTING_CONTROL_DISABLED = 0;
    /**
     * @see HdmiControlManager#CEC_SETTING_NAME_ROUTING_CONTROL
     * @hide
     */
    @IntDef(prefix = { "ROUTING_CONTROL_" }, value = {
            ROUTING_CONTROL_ENABLED,
            ROUTING_CONTROL_DISABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RoutingControl {}

    // -- Whether the Soundbar mode feature is enabled or disabled.
    /**
     * Soundbar mode feature enabled.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_SOUNDBAR_MODE
     */
    public static final int SOUNDBAR_MODE_ENABLED = 1;
    /**
     * Soundbar mode feature disabled.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_SOUNDBAR_MODE
     */
    public static final int SOUNDBAR_MODE_DISABLED = 0;
    /**
     * @see HdmiControlManager#CEC_SETTING_NAME_SOUNDBAR_MODE
     * @hide
     */
    @IntDef(prefix = { "SOUNDBAR_MODE" }, value = {
            SOUNDBAR_MODE_ENABLED,
            SOUNDBAR_MODE_DISABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SoundbarMode {}

    // -- Scope of CEC power control messages sent by a playback device.
    /**
     * Send CEC power control messages to TV only:
     * Upon going to sleep, send {@code <Standby>} to TV only.
     * Upon waking up, attempt to turn on the TV via {@code <One Touch Play>} but do not turn on the
     * Audio system via {@code <System Audio Mode Request>}.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_POWER_CONTROL_MODE
     */
    public static final String POWER_CONTROL_MODE_TV = "to_tv";
    /**
     * Send CEC power control messages to TV and Audio System:
     * Upon going to sleep, send {@code <Standby>} to TV and Audio system.
     * Upon waking up, attempt to turn on the TV via {@code <One Touch Play>} and attempt to turn on
     * the Audio system via {@code <System Audio Mode Request>}.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_POWER_CONTROL_MODE
     */
    public static final String POWER_CONTROL_MODE_TV_AND_AUDIO_SYSTEM = "to_tv_and_audio_system";
    /**
     * Broadcast CEC power control messages to all devices in the network:
     * Upon going to sleep, send {@code <Standby>} to all devices in the network.
     * Upon waking up, attempt to turn on the TV via {@code <One Touch Play>} and attempt to turn on
     * the Audio system via {@code <System Audio Mode Request>}.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_POWER_CONTROL_MODE
     */
    public static final String POWER_CONTROL_MODE_BROADCAST = "broadcast";
    /**
     * Don't send any CEC power control messages:
     * Upon going to sleep, do not send any {@code <Standby>} message.
     * Upon waking up, do not turn on the TV via {@code <One Touch Play>} and do not turn on the
     * Audio system via {@code <System Audio Mode Request>}.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_POWER_CONTROL_MODE
     */
    public static final String POWER_CONTROL_MODE_NONE = "none";
    /**
     * @see HdmiControlManager#CEC_SETTING_NAME_POWER_CONTROL_MODE
     * @hide
     */
    @StringDef(prefix = { "POWER_CONTROL_MODE_" }, value = {
            POWER_CONTROL_MODE_TV,
            POWER_CONTROL_MODE_TV_AND_AUDIO_SYSTEM,
            POWER_CONTROL_MODE_BROADCAST,
            POWER_CONTROL_MODE_NONE
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PowerControlMode {}

    // -- Which power state action should be taken when Active Source is lost.
    /**
     * No action to be taken.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST
     */
    public static final String POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE = "none";
    /**
     * Go to standby immediately.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST
     */
    public static final String POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW = "standby_now";
    /**
     * @see HdmiControlManager#CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST
     * @hide
     */
    @StringDef(prefix = { "POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_" }, value = {
            POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_NONE,
            POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST_STANDBY_NOW
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ActiveSourceLostBehavior {}

    // -- Whether System Audio Control is enabled or disabled.
    /**
     * System Audio Control enabled.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL
     */
    public static final int SYSTEM_AUDIO_CONTROL_ENABLED = 1;
    /**
     * System Audio Control disabled.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL
     */
    public static final int SYSTEM_AUDIO_CONTROL_DISABLED = 0;
    /**
     * @see HdmiControlManager#CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL
     * @hide
     */
    @IntDef(prefix = { "SYSTEM_AUDIO_CONTROL_" }, value = {
            SYSTEM_AUDIO_CONTROL_ENABLED,
            SYSTEM_AUDIO_CONTROL_DISABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SystemAudioControl {}

    // -- Whether System Audio Mode muting is enabled or disabled.
    /**
     * System Audio Mode muting enabled.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING
     */
    public static final int SYSTEM_AUDIO_MODE_MUTING_ENABLED = 1;
    /**
     * System Audio Mode muting disabled.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING
     */
    public static final int SYSTEM_AUDIO_MODE_MUTING_DISABLED = 0;
    /**
     * @see HdmiControlManager#CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING
     * @hide
     */
    @IntDef(prefix = { "SYSTEM_AUDIO_MODE_MUTING_" }, value = {
            SYSTEM_AUDIO_MODE_MUTING_ENABLED,
            SYSTEM_AUDIO_MODE_MUTING_DISABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SystemAudioModeMuting {}

    // -- Whether the HDMI CEC volume control is enabled or disabled.
    /**
     * HDMI CEC enabled.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_VOLUME_CONTROL_MODE
     */
    public static final int VOLUME_CONTROL_ENABLED = 1;
    /**
     * HDMI CEC disabled.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_VOLUME_CONTROL_MODE
     */
    public static final int VOLUME_CONTROL_DISABLED = 0;
    /**
     * @see HdmiControlManager#CEC_SETTING_NAME_VOLUME_CONTROL_MODE
     * @hide
     */
    @IntDef(prefix = { "VOLUME_CONTROL_" }, value = {
            VOLUME_CONTROL_ENABLED,
            VOLUME_CONTROL_DISABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface VolumeControl {}

    // -- Whether TV Wake on One Touch Play is enabled or disabled.
    /**
     * TV Wake on One Touch Play enabled.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY
     */
    public static final int TV_WAKE_ON_ONE_TOUCH_PLAY_ENABLED = 1;
    /**
     * TV Wake on One Touch Play disabled.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY
     */
    public static final int TV_WAKE_ON_ONE_TOUCH_PLAY_DISABLED = 0;
    /**
     * @see HdmiControlManager#CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY
     * @hide
     */
    @IntDef(prefix = { "TV_WAKE_ON_ONE_TOUCH_PLAY_" }, value = {
            TV_WAKE_ON_ONE_TOUCH_PLAY_ENABLED,
            TV_WAKE_ON_ONE_TOUCH_PLAY_DISABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TvWakeOnOneTouchPlay {}

    // -- Whether TV should send &lt;Standby&gt; on sleep.
    /**
     * Sending &lt;Standby&gt; on sleep.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP
     */
    public static final int TV_SEND_STANDBY_ON_SLEEP_ENABLED = 1;
    /**
     * Not sending &lt;Standby&gt; on sleep.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP
     */
    public static final int TV_SEND_STANDBY_ON_SLEEP_DISABLED = 0;
    /**
     * @see HdmiControlManager#CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP
     * @hide
     */
    @IntDef(prefix = { "TV_SEND_STANDBY_ON_SLEEP_" }, value = {
            TV_SEND_STANDBY_ON_SLEEP_ENABLED,
            TV_SEND_STANDBY_ON_SLEEP_DISABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface TvSendStandbyOnSleep {}

    // -- Whether a playback device should act on an incoming {@code <Set Menu Language>} message.
    /**
     * Confirmation dialog should be shown upon receiving the CEC message.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_SET_MENU_LANGUAGE
     * @hide
     */
    public static final int SET_MENU_LANGUAGE_ENABLED = 1;
    /**
     * The message should be ignored.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_SET_MENU_LANGUAGE
     * @hide
     */
    public static final int SET_MENU_LANGUAGE_DISABLED = 0;
    /**
     * @see HdmiControlManager#CEC_SETTING_NAME_SET_MENU_LANGUAGE
     * @hide
     */
    @IntDef(prefix = { "SET_MENU_LANGUAGE_" }, value = {
            SET_MENU_LANGUAGE_ENABLED,
            SET_MENU_LANGUAGE_DISABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SetMenuLanguage {}

    // -- The RC profile of a TV panel.
    /**
     * RC profile none.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_TV
     * @hide
     */
    public static final int RC_PROFILE_TV_NONE = 0x0;
    /**
     * RC profile 1.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_TV
     * @hide
     */
    public static final int RC_PROFILE_TV_ONE = 0x2;
    /**
     * RC profile 2.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_TV
     * @hide
     */
    public static final int RC_PROFILE_TV_TWO = 0x6;
    /**
     * RC profile 3.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_TV
     * @hide
     */
    public static final int RC_PROFILE_TV_THREE = 0xA;
    /**
     * RC profile 4.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_TV
     * @hide
     */
    public static final int RC_PROFILE_TV_FOUR = 0xE;
    /**
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_TV
     * @hide
     */
    @IntDef(prefix = { "RC_PROFILE_TV_" }, value = {
            RC_PROFILE_TV_NONE,
            RC_PROFILE_TV_ONE,
            RC_PROFILE_TV_TWO,
            RC_PROFILE_TV_THREE,
            RC_PROFILE_TV_FOUR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RcProfileTv {}

    // -- RC profile parameter defining if a source handles a specific menu.
    /**
     * Handles the menu.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_ROOT_MENU
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_SETUP_MENU
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_TOP_MENU
     * @see HdmiControlManager#
     * CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU
     * @hide
     */
    public static final int RC_PROFILE_SOURCE_MENU_HANDLED = 1;
    /**
     * Doesn't handle the menu.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_ROOT_MENU
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_SETUP_MENU
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_TOP_MENU
     * @see HdmiControlManager#
     * CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU
     * @hide
     */
    public static final int RC_PROFILE_SOURCE_MENU_NOT_HANDLED = 0;
    /**
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_ROOT_MENU
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_SETUP_MENU
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU
     * @see HdmiControlManager#CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_TOP_MENU
     * @see HdmiControlManager#
     * CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU
     * @hide
     */
    @IntDef(prefix = { "RC_PROFILE_SOURCE_MENU_" }, value = {
            RC_PROFILE_SOURCE_MENU_HANDLED,
            RC_PROFILE_SOURCE_MENU_NOT_HANDLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RcProfileSourceHandlesMenu {}

    // -- Whether the Short Audio Descriptor (SAD) for a specific codec should be queried or not.
    /**
     * Query the SAD.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_LPCM
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_DD
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_MPEG1
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_MP3
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_MPEG2
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_AAC
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_DTS
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_ATRAC
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_ONEBITAUDIO
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_DDP
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_DTSHD
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_TRUEHD
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_DST
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_WMAPRO
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_MAX
     */
    public static final int QUERY_SAD_ENABLED = 1;
    /**
     * Don't query the SAD.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_LPCM
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_DD
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_MPEG1
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_MP3
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_MPEG2
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_AAC
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_DTS
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_ATRAC
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_ONEBITAUDIO
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_DDP
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_DTSHD
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_TRUEHD
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_DST
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_WMAPRO
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_MAX
     */
    public static final int QUERY_SAD_DISABLED = 0;
    /**
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_LPCM
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_DD
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_MPEG1
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_MP3
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_MPEG2
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_AAC
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_DTS
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_ATRAC
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_ONEBITAUDIO
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_DDP
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_DTSHD
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_TRUEHD
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_DST
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_WMAPRO
     * @see HdmiControlManager#CEC_SETTING_NAME_QUERY_SAD_MAX
     * @hide
     */
    @IntDef(prefix = { "QUERY_SAD_" }, value = {
            QUERY_SAD_ENABLED,
            QUERY_SAD_DISABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SadPresenceInQuery {}

    // -- Whether eARC is enabled or disabled.
    /**
     * eARC enabled.
     *
     * @see HdmiControlManager#SETTING_NAME_EARC_ENABLED
     */
    public static final int EARC_FEATURE_ENABLED = 1;
    /**
     * eARC disabled.
     *
     * @see HdmiControlManager#SETTING_NAME_EARC_ENABLED
     */
    public static final int EARC_FEATURE_DISABLED = 0;
    /**
     * @hide
     *
     * @see HdmiControlManager#SETTING_NAME_EARC_ENABLED
     */
    @IntDef(prefix = { "EARC_FEATURE" }, value = {
            EARC_FEATURE_ENABLED,
            EARC_FEATURE_DISABLED
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface EarcFeature {}

    // -- Settings available in the CEC Configuration.
    /**
     * Name of a setting deciding whether the CEC is enabled.
     *
     * @see HdmiControlManager#setHdmiCecEnabled(int)
     */
    public static final String CEC_SETTING_NAME_HDMI_CEC_ENABLED = "hdmi_cec_enabled";
    /**
     * Name of a setting controlling the version of HDMI-CEC used.
     *
     * @see HdmiControlManager#setHdmiCecVersion(int)
     */
    public static final String CEC_SETTING_NAME_HDMI_CEC_VERSION = "hdmi_cec_version";
    /**
     * Name of a setting deciding whether the Routing Control feature is enabled.
     *
     * @see HdmiControlManager#setRoutingControl(int)
     */
    public static final String CEC_SETTING_NAME_ROUTING_CONTROL = "routing_control";
    /**
     * Name of a setting deciding whether the Soundbar mode feature is enabled.
     * Before exposing this setting make sure the hardware supports it, otherwise, you may
     * experience multiple issues.
     *
     * @see HdmiControlManager#setSoundbarMode(int)
     */
    public static final String CEC_SETTING_NAME_SOUNDBAR_MODE = "soundbar_mode";
    /**
     * Name of a setting deciding on the power control mode.
     *
     * @see HdmiControlManager#setPowerControlMode(String)
     */
    public static final String CEC_SETTING_NAME_POWER_CONTROL_MODE = "power_control_mode";
    /**
     * Name of a setting deciding on power state action when losing Active Source.
     *
     * @see HdmiControlManager#setPowerStateChangeOnActiveSourceLost(String)
     */
    public static final String CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST =
            "power_state_change_on_active_source_lost";
    /**
     * Name of a setting deciding whether System Audio Control is enabled.
     *
     * @see HdmiControlManager#setSystemAudioControl(int)
     */
    public static final String CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL =
            "system_audio_control";
    /**
     * Name of a setting deciding whether System Audio Muting is allowed.
     *
     * @see HdmiControlManager#setSystemAudioModeMuting(int)
     */
    public static final String CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING =
            "system_audio_mode_muting";
    /**
     * Controls whether volume control commands via HDMI CEC are enabled.
     *
     * <p>Effects on different device types:
     * <table>
     *     <tr><th>HDMI CEC device type</th><th>0: disabled</th><th>1: enabled</th></tr>
     *     <tr>
     *         <td>TV (type: 0)</td>
     *         <td>Per CEC specification.</td>
     *         <td>TV changes system volume. TV no longer reacts to incoming volume changes
     *         via {@code <User Control Pressed>}. TV no longer handles {@code <Report Audio
     *         Status>}.</td>
     *     </tr>
     *     <tr>
     *         <td>Playback device (type: 4)</td>
     *         <td>Device sends volume commands to TV/Audio system via {@code <User Control
     *         Pressed>}</td>
     *         <td>Device does not send volume commands via {@code <User Control Pressed>}.</td>
     *     </tr>
     *     <tr>
     *         <td>Audio device (type: 5)</td>
     *         <td>Full "System Audio Control" capabilities.</td>
     *         <td>Audio device no longer reacts to incoming {@code <User Control Pressed>}
     *         volume commands. Audio device no longer reports volume changes via {@code
     *         <Report Audio Status>}.</td>
     *     </tr>
     * </table>
     *
     * <p> Due to the resulting behavior, usage on TV and Audio devices is discouraged.
     *
     * @see HdmiControlManager#setHdmiCecVolumeControlEnabled(int)
     */
    public static final String CEC_SETTING_NAME_VOLUME_CONTROL_MODE =
            "volume_control_enabled";
    /**
     * Name of a setting deciding whether the TV will automatically turn on upon reception
     * of the CEC command &lt;Text View On&gt; or &lt;Image View On&gt;.
     *
     * @see HdmiControlManager#setTvWakeOnOneTouchPlay(int)
     */
    public static final String CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY =
            "tv_wake_on_one_touch_play";
    /**
     * Name of a setting deciding whether the TV will also turn off other CEC devices
     * when it goes to standby mode.
     *
     * @see HdmiControlManager#setTvSendStandbyOnSleep(int)
     */
    public static final String CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP =
            "tv_send_standby_on_sleep";
    /**
     * Name of a setting deciding whether {@code <Set Menu Language>} message should be
     * handled by the framework or ignored.
     *
     * @hide
     */
    public static final String CEC_SETTING_NAME_SET_MENU_LANGUAGE = "set_menu_language";
    /**
     * Name of a setting representing the RC profile of a TV panel.
     *
     * @hide
     */
    public static final String CEC_SETTING_NAME_RC_PROFILE_TV =
            "rc_profile_tv";
    /**
     * Name of a setting representing the RC profile parameter defining if a source handles the root
     * menu.
     *
     * @hide
     */
    public static final String CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_ROOT_MENU =
            "rc_profile_source_handles_root_menu";
    /**
     * Name of a setting representing the RC profile parameter defining if a source handles the
     * setup menu.
     *
     * @hide
     */
    public static final String CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_SETUP_MENU =
            "rc_profile_source_handles_setup_menu";
    /**
     * Name of a setting representing the RC profile parameter defining if a source handles the
     * contents menu.
     *
     * @hide
     */
    public static final String CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU =
            "rc_profile_source_handles_contents_menu";
    /**
     * Name of a setting representing the RC profile parameter defining if a source handles the top
     * menu.
     *
     * @hide
     */
    public static final String CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_TOP_MENU =
            "rc_profile_source_handles_top_menu";
    /**
     * Name of a setting representing the RC profile parameter defining if a source handles the
     * media context sensitive menu.
     *
     * @hide
     */
    public static final String
            CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU =
            "rc_profile_source_handles_media_context_sensitive_menu";
    /**
     * Name of a setting representing whether the Short Audio Descriptor (SAD) for the LPCM codec
     * (0x1) should be queried or not.
     *
     * @see HdmiControlManager#setSadPresenceInQuery(String, int)
     */
    public static final String CEC_SETTING_NAME_QUERY_SAD_LPCM = "query_sad_lpcm";
    /**
     * Name of a setting representing whether the Short Audio Descriptor (SAD) for the DD codec
     * (0x2) should be queried or not.
     *
     * @see HdmiControlManager#setSadPresenceInQuery(String, int)
     */
    public static final String CEC_SETTING_NAME_QUERY_SAD_DD = "query_sad_dd";
    /**
     * Name of a setting representing whether the Short Audio Descriptor (SAD) for the MPEG1 codec
     * (0x3) should be queried or not.
     *
     * @see HdmiControlManager#setSadPresenceInQuery(String, int)
     */
    public static final String CEC_SETTING_NAME_QUERY_SAD_MPEG1 = "query_sad_mpeg1";
    /**
     * Name of a setting representing whether the Short Audio Descriptor (SAD) for the MP3 codec
     * (0x4) should be queried or not.
     *
     * @see HdmiControlManager#setSadPresenceInQuery(String, int)
     */
    public static final String CEC_SETTING_NAME_QUERY_SAD_MP3 = "query_sad_mp3";
    /**
     * Name of a setting representing whether the Short Audio Descriptor (SAD) for the MPEG2 codec
     * (0x5) should be queried or not.
     *
     * @see HdmiControlManager#setSadPresenceInQuery(String, int)
     */
    public static final String CEC_SETTING_NAME_QUERY_SAD_MPEG2 = "query_sad_mpeg2";
    /**
     * Name of a setting representing whether the Short Audio Descriptor (SAD) for the AAC codec
     * (0x6) should be queried or not.
     *
     * @see HdmiControlManager#setSadPresenceInQuery(String, int)
     */
    public static final String CEC_SETTING_NAME_QUERY_SAD_AAC = "query_sad_aac";
    /**
     * Name of a setting representing whether the Short Audio Descriptor (SAD) for the DTS codec
     * (0x7) should be queried or not.
     *
     * @see HdmiControlManager#setSadPresenceInQuery(String, int)
     */
    public static final String CEC_SETTING_NAME_QUERY_SAD_DTS = "query_sad_dts";
    /**
     * Name of a setting representing whether the Short Audio Descriptor (SAD) for the ATRAC codec
     * (0x8) should be queried or not.
     *
     * @see HdmiControlManager#setSadPresenceInQuery(String, int)
     */
    public static final String CEC_SETTING_NAME_QUERY_SAD_ATRAC = "query_sad_atrac";
    /**
     * Name of a setting representing whether the Short Audio Descriptor (SAD) for the ONEBITAUDIO
     * codec (0x9) should be queried or not.
     *
     * @see HdmiControlManager#setSadPresenceInQuery(String, int)
     */
    public static final String CEC_SETTING_NAME_QUERY_SAD_ONEBITAUDIO = "query_sad_onebitaudio";
    /**
     * Name of a setting representing whether the Short Audio Descriptor (SAD) for the DDP codec
     * (0xA) should be queried or not.
     *
     * @see HdmiControlManager#setSadPresenceInQuery(String, int)
     */
    public static final String CEC_SETTING_NAME_QUERY_SAD_DDP = "query_sad_ddp";
    /**
     * Name of a setting representing whether the Short Audio Descriptor (SAD) for the DTSHD codec
     * (0xB) should be queried or not.
     *
     * @see HdmiControlManager#setSadPresenceInQuery(String, int)
     */
    public static final String CEC_SETTING_NAME_QUERY_SAD_DTSHD = "query_sad_dtshd";
    /**
     * Name of a setting representing whether the Short Audio Descriptor (SAD) for the TRUEHD codec
     * (0xC) should be queried or not.
     *
     * @see HdmiControlManager#setSadPresenceInQuery(String, int)
     */
    public static final String CEC_SETTING_NAME_QUERY_SAD_TRUEHD = "query_sad_truehd";
    /**
     * Name of a setting representing whether the Short Audio Descriptor (SAD) for the DST codec
     * (0xD) should be queried or not.
     *
     * @see HdmiControlManager#setSadPresenceInQuery(String, int)
     */
    public static final String CEC_SETTING_NAME_QUERY_SAD_DST = "query_sad_dst";
    /**
     * Name of a setting representing whether the Short Audio Descriptor (SAD) for the WMAPRO codec
     * (0xE) should be queried or not.
     *
     * @see HdmiControlManager#setSadPresenceInQuery(String, int)
     */
    public static final String CEC_SETTING_NAME_QUERY_SAD_WMAPRO = "query_sad_wmapro";
    /**
     * Name of a setting representing whether the Short Audio Descriptor (SAD) for the MAX codec
     * (0xF) should be queried or not.
     *
     * @see HdmiControlManager#setSadPresenceInQuery(String, int)
     */
    public static final String CEC_SETTING_NAME_QUERY_SAD_MAX = "query_sad_max";
    /**
     * Name of a setting representing whether eARC is enabled or not.
     *
     * @see HdmiControlManager#setEarcEnabled(int)
     */
    public static final String SETTING_NAME_EARC_ENABLED = "earc_enabled";
    /**
     * @hide
     */
    // TODO(b/240379115): change names of CEC settings so that their prefix matches with the other
    // HDMI control settings.
    @StringDef(value = {
        CEC_SETTING_NAME_HDMI_CEC_ENABLED,
        CEC_SETTING_NAME_HDMI_CEC_VERSION,
        CEC_SETTING_NAME_ROUTING_CONTROL,
        CEC_SETTING_NAME_SOUNDBAR_MODE,
        CEC_SETTING_NAME_POWER_CONTROL_MODE,
        CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST,
        CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL,
        CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING,
        CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
        CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY,
        CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP,
        CEC_SETTING_NAME_SET_MENU_LANGUAGE,
        CEC_SETTING_NAME_RC_PROFILE_TV,
        CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_ROOT_MENU,
        CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_SETUP_MENU,
        CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU,
        CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_TOP_MENU,
        CEC_SETTING_NAME_RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU,
        CEC_SETTING_NAME_QUERY_SAD_LPCM,
        CEC_SETTING_NAME_QUERY_SAD_DD,
        CEC_SETTING_NAME_QUERY_SAD_MPEG1,
        CEC_SETTING_NAME_QUERY_SAD_MP3,
        CEC_SETTING_NAME_QUERY_SAD_MPEG2,
        CEC_SETTING_NAME_QUERY_SAD_AAC,
        CEC_SETTING_NAME_QUERY_SAD_DTS,
        CEC_SETTING_NAME_QUERY_SAD_ATRAC,
        CEC_SETTING_NAME_QUERY_SAD_ONEBITAUDIO,
        CEC_SETTING_NAME_QUERY_SAD_DDP,
        CEC_SETTING_NAME_QUERY_SAD_DTSHD,
        CEC_SETTING_NAME_QUERY_SAD_TRUEHD,
        CEC_SETTING_NAME_QUERY_SAD_DST,
        CEC_SETTING_NAME_QUERY_SAD_WMAPRO,
        CEC_SETTING_NAME_QUERY_SAD_MAX,
        SETTING_NAME_EARC_ENABLED,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SettingName {}

    /**
     * @hide
     */
    @StringDef(prefix = { "CEC_SETTING_NAME_QUERY_SAD_" }, value = {
            CEC_SETTING_NAME_QUERY_SAD_LPCM,
            CEC_SETTING_NAME_QUERY_SAD_DD,
            CEC_SETTING_NAME_QUERY_SAD_MPEG1,
            CEC_SETTING_NAME_QUERY_SAD_MP3,
            CEC_SETTING_NAME_QUERY_SAD_MPEG2,
            CEC_SETTING_NAME_QUERY_SAD_AAC,
            CEC_SETTING_NAME_QUERY_SAD_DTS,
            CEC_SETTING_NAME_QUERY_SAD_ATRAC,
            CEC_SETTING_NAME_QUERY_SAD_ONEBITAUDIO,
            CEC_SETTING_NAME_QUERY_SAD_DDP,
            CEC_SETTING_NAME_QUERY_SAD_DTSHD,
            CEC_SETTING_NAME_QUERY_SAD_TRUEHD,
            CEC_SETTING_NAME_QUERY_SAD_DST,
            CEC_SETTING_NAME_QUERY_SAD_WMAPRO,
            CEC_SETTING_NAME_QUERY_SAD_MAX,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface CecSettingSad {}

    // True if we have a logical device of type playback hosted in the system.
    private final boolean mHasPlaybackDevice;
    // True if we have a logical device of type TV hosted in the system.
    private final boolean mHasTvDevice;
    // True if we have a logical device of type audio system hosted in the system.
    private final boolean mHasAudioSystemDevice;
    // True if we have a logical device of type audio system hosted in the system.
    private final boolean mHasSwitchDevice;
    // True if it's a switch device.
    private final boolean mIsSwitchDevice;

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
        mHasAudioSystemDevice = hasDeviceType(types, HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
        mHasSwitchDevice = hasDeviceType(types, HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH);
        mIsSwitchDevice = HdmiProperties.is_switch().orElse(false);
        addHotplugEventListener(new ClientHotplugEventListener());
    }

    private final class ClientHotplugEventListener implements HotplugEventListener {

        @Override
        public void onReceived(HdmiHotplugEvent event) {
            List<HdmiPortInfo> ports = new ArrayList<>();
            try {
                ports = mService.getPortInfo();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            if (ports.isEmpty()) {
                Log.e(TAG, "Can't find port info, not updating connected status. "
                        + "Hotplug event:" + event);
                return;
            }
            // If the HDMI OUT port is plugged or unplugged, update the mLocalPhysicalAddress
            for (HdmiPortInfo port : ports) {
                if (port.getId() == event.getPort()) {
                    if (port.getType() == HdmiPortInfo.PORT_OUTPUT) {
                        setLocalPhysicalAddress(
                                event.isConnected()
                                        ? port.getAddress()
                                        : INVALID_PHYSICAL_ADDRESS);
                    }
                    break;
                }
            }
        }
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
     * See {@link HdmiDeviceInfo#DEVICE_AUDIO_SYSTEM}
     */
    @Nullable
    @SuppressLint("RequiresPermission")
    public HdmiClient getClient(int type) {
        if (mService == null) {
            return null;
        }
        switch (type) {
            case HdmiDeviceInfo.DEVICE_TV:
                return mHasTvDevice ? new HdmiTvClient(mService) : null;
            case HdmiDeviceInfo.DEVICE_PLAYBACK:
                return mHasPlaybackDevice ? new HdmiPlaybackClient(mService) : null;
            case HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM:
                try {
                    if ((mService.getCecSettingIntValue(CEC_SETTING_NAME_SOUNDBAR_MODE)
                            == SOUNDBAR_MODE_ENABLED && mHasPlaybackDevice)
                            || mHasAudioSystemDevice) {
                        return new HdmiAudioSystemClient(mService);
                    }
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
                return null;
            case HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH:
                return (mHasSwitchDevice || mIsSwitchDevice)
                    ? new HdmiSwitchClient(mService) : null;
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
    @SuppressLint("RequiresPermission")
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
    @SuppressLint("RequiresPermission")
    public HdmiTvClient getTvClient() {
        return (HdmiTvClient) getClient(HdmiDeviceInfo.DEVICE_TV);
    }

    /**
     * Gets an object that represents an HDMI-CEC logical device of type audio system on the system.
     *
     * <p>Used to send HDMI control messages to other devices like TV through HDMI bus. It is also
     * possible to communicate with other logical devices hosted in the same system if the system is
     * configured to host more than one type of HDMI-CEC logical devices.
     *
     * @return {@link HdmiAudioSystemClient} instance. {@code null} on failure.
     *
     * @hide
     */
    @Nullable
    @SuppressLint("RequiresPermission")
    public HdmiAudioSystemClient getAudioSystemClient() {
        return (HdmiAudioSystemClient) getClient(HdmiDeviceInfo.DEVICE_AUDIO_SYSTEM);
    }

    /**
     * Gets an object that represents an HDMI-CEC logical device of type switch on the system.
     *
     * <p>Used to send HDMI control messages to other devices (e.g. TVs) through HDMI bus.
     * It is also possible to communicate with other logical devices hosted in the same
     * system if the system is configured to host more than one type of HDMI-CEC logical device.
     *
     * @return {@link HdmiSwitchClient} instance. {@code null} on failure.
     */
    @Nullable
    @SuppressLint("RequiresPermission")
    public HdmiSwitchClient getSwitchClient() {
        return (HdmiSwitchClient) getClient(HdmiDeviceInfo.DEVICE_PURE_CEC_SWITCH);
    }

    /**
     * Get a snapshot of the real-time status of the devices on the CEC bus.
     *
     * <p>This only applies to devices with switch functionality, which are devices with one
     * or more than one HDMI inputs.
     *
     * @return a list of {@link HdmiDeviceInfo} of the connected CEC devices on the CEC bus. An
     * empty list will be returned if there is none.
     */
    @NonNull
    public List<HdmiDeviceInfo> getConnectedDevices() {
        try {
            return mService.getDeviceList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @removed
     * @deprecated Please use {@link #getConnectedDevices()} instead.
     */
    @Deprecated
    public List<HdmiDeviceInfo> getConnectedDevicesList() {
        try {
            return mService.getDeviceList();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the list of the HDMI ports configuration.
     *
     * <p>This returns an empty list when the current device does not have HDMI ports.
     *
     * @return a list of {@link HdmiPortInfo}
     */
    @NonNull
    public List<HdmiPortInfo> getPortInfo() {
        try {
            return mService.getPortInfo();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Power off the target device by sending CEC commands. Note that this device can't be the
     * current device itself.
     *
     * <p>The target device info can be obtained by calling {@link #getConnectedDevicesList()}.
     *
     * @param deviceInfo {@link HdmiDeviceInfo} of the device to be powered off.
     */
    public void powerOffDevice(@NonNull HdmiDeviceInfo deviceInfo) {
        Objects.requireNonNull(deviceInfo);
        try {
            mService.powerOffRemoteDevice(
                    deviceInfo.getLogicalAddress(), deviceInfo.getDevicePowerStatus());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @removed
     * @deprecated Please use {@link #powerOffDevice(deviceInfo)} instead.
     */
    @Deprecated
    public void powerOffRemoteDevice(@NonNull HdmiDeviceInfo deviceInfo) {
        Objects.requireNonNull(deviceInfo);
        try {
            mService.powerOffRemoteDevice(
                    deviceInfo.getLogicalAddress(), deviceInfo.getDevicePowerStatus());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Power on the target device by sending CEC commands. Note that this device can't be the
     * current device itself.
     *
     * <p>The target device info can be obtained by calling {@link #getConnectedDevicesList()}.
     *
     * @param deviceInfo {@link HdmiDeviceInfo} of the device to be powered on.
     *
     * @hide
     */
    public void powerOnDevice(HdmiDeviceInfo deviceInfo) {
        Objects.requireNonNull(deviceInfo);
        try {
            mService.powerOnRemoteDevice(
                    deviceInfo.getLogicalAddress(), deviceInfo.getDevicePowerStatus());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @removed
     * @deprecated Please use {@link #powerOnDevice(deviceInfo)} instead.
     */
    @Deprecated
    public void powerOnRemoteDevice(HdmiDeviceInfo deviceInfo) {
        Objects.requireNonNull(deviceInfo);
        try {
            mService.powerOnRemoteDevice(
                    deviceInfo.getLogicalAddress(), deviceInfo.getDevicePowerStatus());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Request the target device to be the new Active Source by sending CEC commands. Note that
     * this device can't be the current device itself.
     *
     * <p>The target device info can be obtained by calling {@link #getConnectedDevicesList()}.
     *
     * <p>If the target device responds to the command, the users should see the target device
     * streaming on their TVs.
     *
     * @param deviceInfo HdmiDeviceInfo of the target device
     */
    public void setActiveSource(@NonNull HdmiDeviceInfo deviceInfo) {
        Objects.requireNonNull(deviceInfo);
        try {
            mService.askRemoteDeviceToBecomeActiveSource(deviceInfo.getPhysicalAddress());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * @removed
     * @deprecated Please use {@link #setActiveSource(deviceInfo)} instead.
     */
    @Deprecated
    public void requestRemoteDeviceToBecomeActiveSource(@NonNull HdmiDeviceInfo deviceInfo) {
        Objects.requireNonNull(deviceInfo);
        try {
            mService.askRemoteDeviceToBecomeActiveSource(deviceInfo.getPhysicalAddress());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
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
     * For CEC source devices (OTT/STB/Audio system): toggle the power status of the HDMI-connected
     * display and follow the display's new power status.
     * For all other devices: no functionality.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void toggleAndFollowTvPower() {
        try {
            mService.toggleAndFollowTvPower();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Determines whether the HDMI CEC stack should handle KEYCODE_TV_POWER.
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public boolean shouldHandleTvPowerKey() {
        try {
            return mService.shouldHandleTvPowerKey();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Controls whether volume control commands via HDMI CEC are enabled.
     *
     * <p>When disabled:
     * <ul>
     *     <li>the device will not send any HDMI CEC audio messages
     *     <li>received HDMI CEC audio messages are responded to with {@code <Feature Abort>}
     * </ul>
     *
     * <p>Effects on different device types:
     * <table>
     *     <tr><th>HDMI CEC device type</th><th>enabled</th><th>disabled</th></tr>
     *     <tr>
     *         <td>TV (type: 0)</td>
     *         <td>Per CEC specification.</td>
     *         <td>TV changes system volume. TV no longer reacts to incoming volume changes via
     *         {@code <User Control Pressed>}. TV no longer handles {@code <Report Audio Status>}
     *         .</td>
     *     </tr>
     *     <tr>
     *         <td>Playback device (type: 4)</td>
     *         <td>Device sends volume commands to TV/Audio system via {@code <User Control
     *         Pressed>}</td><td>Device does not send volume commands via {@code <User Control
     *         Pressed>}.</td>
     *     </tr>
     *     <tr>
     *         <td>Audio device (type: 5)</td>
     *         <td>Full "System Audio Control" capabilities.</td>
     *         <td>Audio device no longer reacts to incoming {@code <User Control Pressed>}
     *         volume commands. Audio device no longer reports volume changes via {@code <Report
     *         Audio Status>}.</td>
     *     </tr>
     * </table>
     *
     * <p> Due to the resulting behavior, usage on TV and Audio devices is discouraged.
     *
     * @param hdmiCecVolumeControlEnabled target state of HDMI CEC volume control.
     * @see HdmiControlManager#CEC_SETTING_NAME_VOLUME_CONTROL_MODE
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void setHdmiCecVolumeControlEnabled(
            @VolumeControl int hdmiCecVolumeControlEnabled) {
        try {
            mService.setCecSettingIntValue(CEC_SETTING_NAME_VOLUME_CONTROL_MODE,
                    hdmiCecVolumeControlEnabled);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns whether volume changes via HDMI CEC are enabled.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_VOLUME_CONTROL_MODE
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    @VolumeControl
    public int getHdmiCecVolumeControlEnabled() {
        try {
            return mService.getCecSettingIntValue(CEC_SETTING_NAME_VOLUME_CONTROL_MODE);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets whether the system is in system audio mode.
     *
     * @hide
     */
    public boolean getSystemAudioMode() {
        try {
            return mService.getSystemAudioMode();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the physical address of the device.
     *
     * <p>Physical address needs to be automatically adjusted when devices are phyiscally or
     * electrically added or removed from the device tree. Please see HDMI Specification Version
     * 1.4b 8.7 Physical Address for more details on the address discovery proccess.
     */
    public int getPhysicalAddress() {
        return getLocalPhysicalAddress();
    }

    /**
     * Check if the target device is connected to the current device.
     *
     * <p>The API also returns true if the current device is the target.
     *
     * @param targetDevice {@link HdmiDeviceInfo} of the target device.
     * @return true if {@code targetDevice} is directly or indirectly
     * connected to the current device.
     */
    public boolean isDeviceConnected(@NonNull HdmiDeviceInfo targetDevice) {
        Objects.requireNonNull(targetDevice);
        int physicalAddress = getLocalPhysicalAddress();
        if (physicalAddress == INVALID_PHYSICAL_ADDRESS) {
            return false;
        }
        int targetPhysicalAddress = targetDevice.getPhysicalAddress();
        if (targetPhysicalAddress == INVALID_PHYSICAL_ADDRESS) {
            return false;
        }
        return HdmiUtils.getLocalPortFromPhysicalAddress(targetPhysicalAddress, physicalAddress)
            != HdmiUtils.TARGET_NOT_UNDER_LOCAL_DEVICE;
    }

    /**
     * @removed
     * @deprecated Please use {@link #isDeviceConnected(targetDevice)} instead.
     */
    @Deprecated
    public boolean isRemoteDeviceConnected(@NonNull HdmiDeviceInfo targetDevice) {
        Objects.requireNonNull(targetDevice);
        int physicalAddress = getLocalPhysicalAddress();
        if (physicalAddress == INVALID_PHYSICAL_ADDRESS) {
            return false;
        }
        int targetPhysicalAddress = targetDevice.getPhysicalAddress();
        if (targetPhysicalAddress == INVALID_PHYSICAL_ADDRESS) {
            return false;
        }
        return HdmiUtils.getLocalPortFromPhysicalAddress(targetPhysicalAddress, physicalAddress)
            != HdmiUtils.TARGET_NOT_UNDER_LOCAL_DEVICE;
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
     * Listener used to get HDMI Control (CEC) status (enabled/disabled) and the connected display
     * status.
     * @hide
     */
    public interface HdmiControlStatusChangeListener {
        /**
         * Called when HDMI Control (CEC) is enabled/disabled.
         *
         * @param isCecEnabled status of HDMI Control
         * {@link android.hardware.hdmi.HdmiControlManager#CEC_SETTING_NAME_HDMI_CEC_ENABLED}:
         * {@code HDMI_CEC_CONTROL_ENABLED} if enabled.
         * @param isCecAvailable status of CEC support of the connected display (the TV).
         * {@code true} if supported.
         *
         * Note: Value of isCecAvailable is only valid when isCecEnabled is true.
         **/
        void onStatusChange(@HdmiControlManager.HdmiCecControl int isCecEnabled,
                boolean isCecAvailable);
    }

    private final ArrayMap<HdmiControlStatusChangeListener, IHdmiControlStatusChangeListener>
            mHdmiControlStatusChangeListeners = new ArrayMap<>();

    /**
     * Listener used to get the status of the HDMI CEC volume control feature (enabled/disabled).
     * @hide
     */
    public interface HdmiCecVolumeControlFeatureListener {
        /**
         * Called when the HDMI Control (CEC) volume control feature is enabled/disabled.
         *
         * @param hdmiCecVolumeControl status of HDMI CEC volume control feature
         * @see {@link HdmiControlManager#setHdmiCecVolumeControlEnabled(int)} ()}
         **/
        void onHdmiCecVolumeControlFeature(@VolumeControl int hdmiCecVolumeControl);
    }

    private final ArrayMap<HdmiCecVolumeControlFeatureListener,
            IHdmiCecVolumeControlFeatureListener>
            mHdmiCecVolumeControlFeatureListeners = new ArrayMap<>();

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
     * Note that each invocation of the callback will be executed on an arbitrary
     * Binder thread. This means that all callback implementations must be
     * thread safe. To specify the execution thread, use
     * {@link addHotplugEventListener(Executor, HotplugEventListener)}.
     *
     * @param listener {@link HotplugEventListener} instance
     * @see HdmiControlManager#removeHotplugEventListener(HotplugEventListener)
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void addHotplugEventListener(HotplugEventListener listener) {
        addHotplugEventListener(ConcurrentUtils.DIRECT_EXECUTOR, listener);
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
    public void addHotplugEventListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull HotplugEventListener listener) {
        if (mService == null) {
            Log.e(TAG, "addHotplugEventListener: HdmiControlService is not available");
            return;
        }
        if (mHotplugEventListeners.containsKey(listener)) {
            Log.e(TAG, "listener is already registered");
            return;
        }
        IHdmiHotplugEventListener wrappedListener =
                getHotplugEventListenerWrapper(executor, listener);
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
            Log.e(TAG, "removeHotplugEventListener: HdmiControlService is not available");
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
            Executor executor, final HotplugEventListener listener) {
        return new IHdmiHotplugEventListener.Stub() {
            @Override
            public void onReceived(HdmiHotplugEvent event) {
                final long token = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> listener.onReceived(event));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        };
    }

    /**
     * Adds a listener to get informed of {@link HdmiControlStatusChange}.
     *
     * <p>To stop getting the notification,
     * use {@link #removeHdmiControlStatusChangeListener(HdmiControlStatusChangeListener)}.
     *
     * Note that each invocation of the callback will be executed on an arbitrary
     * Binder thread. This means that all callback implementations must be
     * thread safe. To specify the execution thread, use
     * {@link addHdmiControlStatusChangeListener(Executor, HdmiControlStatusChangeListener)}.
     *
     * @param listener {@link HdmiControlStatusChangeListener} instance
     * @see HdmiControlManager#removeHdmiControlStatusChangeListener(
     * HdmiControlStatusChangeListener)
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void addHdmiControlStatusChangeListener(HdmiControlStatusChangeListener listener) {
        addHdmiControlStatusChangeListener(ConcurrentUtils.DIRECT_EXECUTOR, listener);
    }

    /**
     * Adds a listener to get informed of {@link HdmiControlStatusChange}.
     *
     * <p>To stop getting the notification,
     * use {@link #removeHdmiControlStatusChangeListener(HdmiControlStatusChangeListener)}.
     *
     * @param listener {@link HdmiControlStatusChangeListener} instance
     * @see HdmiControlManager#removeHdmiControlStatusChangeListener(
     * HdmiControlStatusChangeListener)
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void addHdmiControlStatusChangeListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull HdmiControlStatusChangeListener listener) {
        if (mService == null) {
            Log.e(TAG, "addHdmiControlStatusChangeListener: HdmiControlService is not available");
            return;
        }
        if (mHdmiControlStatusChangeListeners.containsKey(listener)) {
            Log.e(TAG, "listener is already registered");
            return;
        }
        IHdmiControlStatusChangeListener wrappedListener =
                getHdmiControlStatusChangeListenerWrapper(executor, listener);
        mHdmiControlStatusChangeListeners.put(listener, wrappedListener);
        try {
            mService.addHdmiControlStatusChangeListener(wrappedListener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes a listener to stop getting informed of {@link HdmiControlStatusChange}.
     *
     * @param listener {@link HdmiControlStatusChangeListener} instance to be removed
     *
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void removeHdmiControlStatusChangeListener(HdmiControlStatusChangeListener listener) {
        if (mService == null) {
            Log.e(TAG,
                    "removeHdmiControlStatusChangeListener: HdmiControlService is not available");
            return;
        }
        IHdmiControlStatusChangeListener wrappedListener =
                mHdmiControlStatusChangeListeners.remove(listener);
        if (wrappedListener == null) {
            Log.e(TAG, "tried to remove not-registered listener");
            return;
        }
        try {
            mService.removeHdmiControlStatusChangeListener(wrappedListener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private IHdmiControlStatusChangeListener getHdmiControlStatusChangeListenerWrapper(
            Executor executor, final HdmiControlStatusChangeListener listener) {
        return new IHdmiControlStatusChangeListener.Stub() {
            @Override
            public void onStatusChange(@HdmiCecControl int isCecEnabled, boolean isCecAvailable) {
                final long token = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> listener.onStatusChange(isCecEnabled, isCecAvailable));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        };
    }

    /**
     * Adds a listener to get informed of changes to the state of the HDMI CEC volume control
     * feature.
     *
     * Upon adding a listener, the current state of the HDMI CEC volume control feature will be
     * sent immediately.
     *
     * <p>To stop getting the notification,
     * use {@link #removeHdmiCecVolumeControlFeatureListener(HdmiCecVolumeControlFeatureListener)}.
     *
     * @param listener {@link HdmiCecVolumeControlFeatureListener} instance
     * @hide
     * @see #removeHdmiCecVolumeControlFeatureListener(HdmiCecVolumeControlFeatureListener)
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void addHdmiCecVolumeControlFeatureListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull HdmiCecVolumeControlFeatureListener listener) {
        if (mService == null) {
            Log.e(TAG,
                    "addHdmiCecVolumeControlFeatureListener: HdmiControlService is not available");
            return;
        }
        if (mHdmiCecVolumeControlFeatureListeners.containsKey(listener)) {
            Log.e(TAG, "listener is already registered");
            return;
        }
        IHdmiCecVolumeControlFeatureListener wrappedListener =
                createHdmiCecVolumeControlFeatureListenerWrapper(executor, listener);
        mHdmiCecVolumeControlFeatureListeners.put(listener, wrappedListener);
        try {
            mService.addHdmiCecVolumeControlFeatureListener(wrappedListener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Removes a listener to stop getting informed of changes to the state of the HDMI CEC volume
     * control feature.
     *
     * @param listener {@link HdmiCecVolumeControlFeatureListener} instance to be removed
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void removeHdmiCecVolumeControlFeatureListener(
            HdmiCecVolumeControlFeatureListener listener) {
        if (mService == null) {
            Log.e(TAG,
                    "removeHdmiCecVolumeControlFeatureListener: HdmiControlService is not "
                            + "available");
            return;
        }
        IHdmiCecVolumeControlFeatureListener wrappedListener =
                mHdmiCecVolumeControlFeatureListeners.remove(listener);
        if (wrappedListener == null) {
            Log.e(TAG, "tried to remove not-registered listener");
            return;
        }
        try {
            mService.removeHdmiCecVolumeControlFeatureListener(wrappedListener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private IHdmiCecVolumeControlFeatureListener createHdmiCecVolumeControlFeatureListenerWrapper(
            Executor executor, final HdmiCecVolumeControlFeatureListener listener) {
        return new android.hardware.hdmi.IHdmiCecVolumeControlFeatureListener.Stub() {
            @Override
            public void onHdmiCecVolumeControlFeature(int enabled) {
                final long token = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> listener.onHdmiCecVolumeControlFeature(enabled));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        };
    }

    /**
     * Listener used to get setting change notification.
     */
    public interface CecSettingChangeListener {
        /**
         * Called when value of a setting changes.
         *
         * @param setting name of a CEC setting that changed
         */
        void onChange(@NonNull @SettingName String setting);
    }

    private final ArrayMap<String,
            ArrayMap<CecSettingChangeListener, IHdmiCecSettingChangeListener>>
                    mCecSettingChangeListeners = new ArrayMap<>();

    private void addCecSettingChangeListener(
            @NonNull @SettingName String setting,
            @NonNull @CallbackExecutor Executor executor,
            @NonNull CecSettingChangeListener listener) {
        if (mService == null) {
            Log.e(TAG, "addCecSettingChangeListener: HdmiControlService is not available");
            return;
        }
        if (mCecSettingChangeListeners.containsKey(setting)
                && mCecSettingChangeListeners.get(setting).containsKey(listener)) {
            Log.e(TAG, "listener is already registered");
            return;
        }
        IHdmiCecSettingChangeListener wrappedListener =
                getCecSettingChangeListenerWrapper(executor, listener);
        if (!mCecSettingChangeListeners.containsKey(setting)) {
            mCecSettingChangeListeners.put(setting, new ArrayMap<>());
        }
        mCecSettingChangeListeners.get(setting).put(listener, wrappedListener);
        try {
            mService.addCecSettingChangeListener(setting, wrappedListener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void removeCecSettingChangeListener(
            @NonNull @SettingName String setting,
            @NonNull CecSettingChangeListener listener) {
        if (mService == null) {
            Log.e(TAG, "removeCecSettingChangeListener: HdmiControlService is not available");
            return;
        }
        IHdmiCecSettingChangeListener wrappedListener =
                !mCecSettingChangeListeners.containsKey(setting) ? null :
                    mCecSettingChangeListeners.get(setting).remove(listener);
        if (wrappedListener == null) {
            Log.e(TAG, "tried to remove not-registered listener");
            return;
        }
        try {
            mService.removeCecSettingChangeListener(setting, wrappedListener);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private IHdmiCecSettingChangeListener getCecSettingChangeListenerWrapper(
            Executor executor, final CecSettingChangeListener listener) {
        return new IHdmiCecSettingChangeListener.Stub() {
            @Override
            public void onChange(String setting) {
                final long token = Binder.clearCallingIdentity();
                try {
                    executor.execute(() -> listener.onChange(setting));
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        };
    }

    /**
     * Get a set of user-modifiable HDMI control settings.
     * This applies to CEC settings and eARC settings.
     *
     * @return a set of user-modifiable settings.
     * @throws RuntimeException when the HdmiControlService is not available.
     */
    // TODO(b/240379115): rename this API to represent that this applies to all HDMI control
    // settings and not just CEC settings.
    @NonNull
    @SettingName
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public List<String> getUserCecSettings() {
        if (mService == null) {
            Log.e(TAG, "getUserCecSettings: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            return mService.getUserCecSettings();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get a set of allowed values for an HDMI control setting (string value-type).
     * This applies to CEC settings and eARC settings.
     *
     *
     * @param name name of the setting
     * @return a set of allowed values for a settings. {@code null} on failure.
     * @throws IllegalArgumentException when setting {@code name} does not exist.
     * @throws IllegalArgumentException when setting {@code name} value type is invalid.
     * @throws RuntimeException when the HdmiControlService is not available.
     */
    // TODO(b/240379115): rename this API to represent that this applies to all HDMI control
    // settings and not just CEC settings.
    @NonNull
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public List<String> getAllowedCecSettingStringValues(@NonNull @SettingName String name) {
        if (mService == null) {
            Log.e(TAG, "getAllowedCecSettingStringValues: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            return mService.getAllowedCecSettingStringValues(name);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get a set of allowed values for an HDMI control setting (int value-type).
     * This applies to CEC settings and eARC settings.
     *
     * @param name name of the setting
     * @return a set of allowed values for a settings. {@code null} on failure.
     * @throws IllegalArgumentException when setting {@code name} does not exist.
     * @throws IllegalArgumentException when setting {@code name} value type is invalid.
     * @throws RuntimeException when the HdmiControlService is not available.
     */
    // TODO(b/240379115): rename this API to represent that this applies to all HDMI control
    // settings and not just CEC settings.
    @NonNull
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public List<Integer> getAllowedCecSettingIntValues(@NonNull @SettingName String name) {
        if (mService == null) {
            Log.e(TAG, "getAllowedCecSettingIntValues: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            int[] allowedValues = mService.getAllowedCecSettingIntValues(name);
            return Arrays.stream(allowedValues).boxed().collect(Collectors.toList());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the global status of HDMI CEC.
     *
     * <p>This allows to enable/disable HDMI CEC on the device.
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void setHdmiCecEnabled(@NonNull @HdmiCecControl int value) {
        if (mService == null) {
            Log.e(TAG, "setHdmiCecEnabled: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            mService.setCecSettingIntValue(CEC_SETTING_NAME_HDMI_CEC_ENABLED, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the current global status of HDMI CEC.
     *
     * <p>Reflects whether HDMI CEC is currently enabled on the device.
     */
    @NonNull
    @HdmiCecControl
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public int getHdmiCecEnabled() {
        if (mService == null) {
            Log.e(TAG, "getHdmiCecEnabled: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            return mService.getCecSettingIntValue(CEC_SETTING_NAME_HDMI_CEC_ENABLED);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Add change listener for global status of HDMI CEC.
     *
     * <p>To stop getting the notification,
     * use {@link #removeHdmiCecEnabledChangeListener(CecSettingChangeListener)}.
     *
     * Note that each invocation of the callback will be executed on an arbitrary
     * Binder thread. This means that all callback implementations must be
     * thread safe. To specify the execution thread, use
     * {@link addHdmiCecEnabledChangeListener(Executor, CecSettingChangeListener)}.
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void addHdmiCecEnabledChangeListener(@NonNull CecSettingChangeListener listener) {
        addHdmiCecEnabledChangeListener(ConcurrentUtils.DIRECT_EXECUTOR, listener);
    }

    /**
     * Add change listener for global status of HDMI CEC.
     *
     * <p>To stop getting the notification,
     * use {@link #removeHdmiCecEnabledChangeListener(CecSettingChangeListener)}.
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void addHdmiCecEnabledChangeListener(
            @NonNull @CallbackExecutor Executor executor,
            @NonNull CecSettingChangeListener listener) {
        addCecSettingChangeListener(CEC_SETTING_NAME_HDMI_CEC_ENABLED, executor, listener);
    }

    /**
     * Remove change listener for global status of HDMI CEC.
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void removeHdmiCecEnabledChangeListener(
            @NonNull CecSettingChangeListener listener) {
        removeCecSettingChangeListener(CEC_SETTING_NAME_HDMI_CEC_ENABLED, listener);
    }

    /**
     * Set the version of the HDMI CEC specification currently used.
     *
     * <p>Allows to select either CEC 1.4b or 2.0 to be used by the device.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_HDMI_CEC_VERSION
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void setHdmiCecVersion(@NonNull @HdmiCecVersion int value) {
        if (mService == null) {
            Log.e(TAG, "setHdmiCecVersion: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            mService.setCecSettingIntValue(CEC_SETTING_NAME_HDMI_CEC_VERSION, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the version of the HDMI CEC specification currently used.
     *
     * <p>Reflects which CEC version 1.4b or 2.0 is currently used by the device.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_HDMI_CEC_VERSION
     */
    @NonNull
    @HdmiCecVersion
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public int getHdmiCecVersion() {
        if (mService == null) {
            Log.e(TAG, "getHdmiCecVersion: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            return mService.getCecSettingIntValue(CEC_SETTING_NAME_HDMI_CEC_VERSION);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the status of Routing Control feature.
     *
     * <p>This allows to enable/disable Routing Control on the device.
     * If enabled, the switch device will route to the correct input source on
     * receiving Routing Control related messages. If disabled, you can only
     * switch the input via controls on this device.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_ROUTING_CONTROL
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void setRoutingControl(@NonNull @RoutingControl int value) {
        if (mService == null) {
            Log.e(TAG, "setRoutingControl: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            mService.setCecSettingIntValue(CEC_SETTING_NAME_ROUTING_CONTROL, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the current status of Routing Control feature.
     *
     * <p>Reflects whether Routing Control is currently enabled on the device.
     * If enabled, the switch device will route to the correct input source on
     * receiving Routing Control related messages. If disabled, you can only
     * switch the input via controls on this device.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_ROUTING_CONTROL
     */
    @NonNull
    @RoutingControl
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public int getRoutingControl() {
        if (mService == null) {
            Log.e(TAG, "getRoutingControl: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            return mService.getCecSettingIntValue(CEC_SETTING_NAME_ROUTING_CONTROL);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the status of Soundbar mode feature.
     *
     * <p>This allows to enable/disable Soundbar mode on the playback device.
     * The setting's effect will be available on devices where the hardware supports this feature.
     * If enabled, an audio system local device will be allocated and try to establish an ARC
     * connection with the TV. If disabled, the ARC connection will be terminated and the audio
     * system local device will be removed from the network.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_SOUNDBAR_MODE
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void setSoundbarMode(@SoundbarMode int value) {
        if (mService == null) {
            Log.e(TAG, "setSoundbarMode: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            mService.setCecSettingIntValue(CEC_SETTING_NAME_SOUNDBAR_MODE, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the current status of Soundbar mode feature.
     *
     * <p>Reflects whether Soundbar mode is currently enabled on the playback device.
     * If enabled, an audio system local device will be allocated and try to establish an ARC
     * connection with the TV. If disabled, the ARC connection will be terminated and the audio
     * system local device will be removed from the network.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_SOUNDBAR_MODE
     */
    @SoundbarMode
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public int getSoundbarMode() {
        if (mService == null) {
            Log.e(TAG, "getSoundbarMode: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            return mService.getCecSettingIntValue(CEC_SETTING_NAME_SOUNDBAR_MODE);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the status of Power Control.
     *
     * <p>Specifies to which devices Power Control messages should be sent:
     * only to the TV, broadcast to all devices, no power control messages.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_POWER_CONTROL_MODE
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void setPowerControlMode(@NonNull @PowerControlMode String value) {
        if (mService == null) {
            Log.e(TAG, "setPowerControlMode: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            mService.setCecSettingStringValue(CEC_SETTING_NAME_POWER_CONTROL_MODE, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the status of Power Control.
     *
     * <p>Reflects to which devices Power Control messages should be sent:
     * only to the TV, broadcast to all devices, no power control messages.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_POWER_CONTROL_MODE
     */
    @NonNull
    @PowerControlMode
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public String getPowerControlMode() {
        if (mService == null) {
            Log.e(TAG, "getPowerControlMode: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            return mService.getCecSettingStringValue(CEC_SETTING_NAME_POWER_CONTROL_MODE);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the current power state behaviour when Active Source is lost.
     *
     * <p>Sets the action taken: do nothing or go to sleep immediately.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void setPowerStateChangeOnActiveSourceLost(
            @NonNull @ActiveSourceLostBehavior String value) {
        if (mService == null) {
            Log.e(TAG,
                    "setPowerStateChangeOnActiveSourceLost: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            mService.setCecSettingStringValue(
                    CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the current power state behaviour when Active Source is lost.
     *
     * <p>Reflects the action taken: do nothing or go to sleep immediately.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST
     */
    @NonNull
    @ActiveSourceLostBehavior
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public String getPowerStateChangeOnActiveSourceLost() {
        if (mService == null) {
            Log.e(TAG,
                    "getPowerStateChangeOnActiveSourceLost: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            return mService.getCecSettingStringValue(
                    CEC_SETTING_NAME_POWER_STATE_CHANGE_ON_ACTIVE_SOURCE_LOST);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the current status of System Audio Control.
     *
     * <p>Sets whether HDMI System Audio Control feature is enabled. If enabled,
     * TV or Audio System will try to turn on the System Audio Mode if there's a
     * connected CEC-enabled AV Receiver. Then an audio stream will be played on
     * the AVR instead of TV speaker or Audio System speakers. If disabled, the
     * System Audio Mode will never be activated.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void setSystemAudioControl(@NonNull @SystemAudioControl int value) {
        if (mService == null) {
            Log.e(TAG, "setSystemAudioControl: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            mService.setCecSettingIntValue(CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the current status of System Audio Control.
     *
     * <p>Reflects whether HDMI System Audio Control feature is enabled. If enabled,
     * TV or Audio System will try to turn on the System Audio Mode if there's a
     * connected CEC-enabled AV Receiver. Then an audio stream will be played on
     * the AVR instead of TV speaker or Audio System speakers. If disabled, the
     * System Audio Mode will never be activated.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL
     */
    @NonNull
    @SystemAudioControl
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public int getSystemAudioControl() {
        if (mService == null) {
            Log.e(TAG, "getSystemAudioControl: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            return mService.getCecSettingIntValue(CEC_SETTING_NAME_SYSTEM_AUDIO_CONTROL);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the current status of System Audio Mode muting.
     *
     * <p>Sets whether the device should be muted when System Audio Mode is turned off.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void setSystemAudioModeMuting(@NonNull @SystemAudioModeMuting int value) {
        if (mService == null) {
            Log.e(TAG, "setSystemAudioModeMuting: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            mService.setCecSettingIntValue(CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the current status of System Audio Mode muting.
     *
     * <p>Reflects whether the device should be muted when System Audio Mode is turned off.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING
     */
    @NonNull
    @SystemAudioModeMuting
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public int getSystemAudioModeMuting() {
        if (mService == null) {
            Log.e(TAG, "getSystemAudioModeMuting: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            return mService.getCecSettingIntValue(CEC_SETTING_NAME_SYSTEM_AUDIO_MODE_MUTING);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the current status of TV Wake on One Touch Play.
     *
     * <p>Sets whether the TV should wake up upon reception of &lt;Text View On&gt;
     * or &lt;Image View On&gt;.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void setTvWakeOnOneTouchPlay(@NonNull @TvWakeOnOneTouchPlay int value) {
        if (mService == null) {
            Log.e(TAG, "setTvWakeOnOneTouchPlay: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            mService.setCecSettingIntValue(CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the current status of TV Wake on One Touch Play.
     *
     * <p>Reflects whether the TV should wake up upon reception of &lt;Text View On&gt;
     * or &lt;Image View On&gt;.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY
     */
    @NonNull
    @TvWakeOnOneTouchPlay
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public int getTvWakeOnOneTouchPlay() {
        if (mService == null) {
            Log.e(TAG, "getTvWakeOnOneTouchPlay: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            return mService.getCecSettingIntValue(CEC_SETTING_NAME_TV_WAKE_ON_ONE_TOUCH_PLAY);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the current status of TV send &lt;Standby&gt; on Sleep.
     *
     * <p>Sets whether the device will also turn off other CEC devices
     * when it goes to standby mode.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void setTvSendStandbyOnSleep(@NonNull @TvSendStandbyOnSleep int value) {
        if (mService == null) {
            Log.e(TAG, "setTvSendStandbyOnSleep: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            mService.setCecSettingIntValue(CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the current status of TV send &lt;Standby&gt; on Sleep.
     *
     * <p>Reflects whether the device will also turn off other CEC devices
     * when it goes to standby mode.
     *
     * @see HdmiControlManager#CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP
     */
    @NonNull
    @TvSendStandbyOnSleep
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public int getTvSendStandbyOnSleep() {
        if (mService == null) {
            Log.e(TAG, "getTvSendStandbyOnSleep: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            return mService.getCecSettingIntValue(CEC_SETTING_NAME_TV_SEND_STANDBY_ON_SLEEP);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set presence of one Short Audio Descriptor (SAD) in the query.
     *
     * <p>Allows the caller to specify whether the SAD for a specific audio codec should be
     * present in the &lt;Request Short Audio Descriptor&gt; query. Each &lt;Request Short Audio
     * Descriptor&gt; message can carry at most 4 SADs at a time. This method allows the caller to
     * limit the amount of SADs queried and therefore limit the amount of CEC messages on the bus.
     *
     * <p>When an ARC connection is established, the TV sends a
     * &lt;Request Short Audio Descriptor&gt; query to the Audio System that it's connected to. If
     * an SAD is queried and the Audio System reports that it supports that SAD, the TV can send
     * audio in that format to be output on the Audio System via ARC.
     * If a codec is not queried, the TV doesn't know if the connected Audio System supports this
     * SAD and doesn't send audio in that format to the Audio System.
     *
     * @param setting SAD to set.
     * @param value Presence to set the SAD to.
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void setSadPresenceInQuery(@NonNull @CecSettingSad String setting,
            @SadPresenceInQuery int value) {
        if (mService == null) {
            Log.e(TAG, "setSadPresenceInQuery: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            mService.setCecSettingIntValue(setting, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set presence of multiple Short Audio Descriptors (SADs) in the query.
     *
     * <p>Allows the caller to specify whether the SADs for specific audio codecs should be present
     * in the &lt;Request Short Audio Descriptor&gt; query. For audio codecs that are not specified,
     * the SAD's presence remains at its previous value. Each &lt;Request Short Audio Descriptor&gt;
     * message can carry at most 4 SADs at a time. This method allows the caller to limit the amount
     * of SADs queried and therefore limit the amount of CEC messages on the bus.
     *
     * <p>When an ARC connection is established, the TV sends a
     * &lt;Request Short Audio Descriptor&gt; query to the Audio System that it's connected to. If
     * an SAD is queried and the Audio System reports that it supports that SAD, the TV can send
     * audio in that format to be output on the Audio System via ARC.
     * If a codec is not queried, the TV doesn't know if the connected Audio System supports this
     * SAD and doesn't send audio in that format to the Audio System.
     *
     *
     * @param settings SADs to set.
     * @param value Presence to set all specified SADs to.
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void setSadsPresenceInQuery(@NonNull @CecSettingSad List<String> settings,
            @SadPresenceInQuery int value) {
        if (mService == null) {
            Log.e(TAG, "setSadsPresenceInQuery: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            for (String sad : settings) {
                mService.setCecSettingIntValue(sad, value);
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get presence of one Short Audio Descriptor (SAD) in the query.
     *
     * <p>Reflects whether the SAD for a specific audio codec should be present in the
     * &lt;Request Short Audio Descriptor&gt; query.
     *
     * <p>When an ARC connection is established, the TV sends a
     * &lt;Request Short Audio Descriptor&gt; query to the Audio System that it's connected to. If
     * an SAD is queried and the Audio System reports that it supports that SAD, the TV can send
     * audio in that format to be output on the Audio System via ARC.
     * If a codec is not queried, the TV doesn't know if the connected Audio System supports this
     * SAD and doesn't send audio in that format to the Audio System.
     *
     * @param setting SAD to get.
     * @return Current presence of the specified SAD.
     */
    @SadPresenceInQuery
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public int getSadPresenceInQuery(@NonNull @CecSettingSad String setting) {
        if (mService == null) {
            Log.e(TAG, "getSadPresenceInQuery: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            return mService.getCecSettingIntValue(setting);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Set the global status of eARC.
     *
     * <p>This allows to enable/disable the eARC feature on the device. If the feature is enabled
     * and the hardware supports eARC as well, the device can attempt to establish an eARC
     * connection.
     */
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public void setEarcEnabled(@NonNull @EarcFeature int value) {
        if (mService == null) {
            Log.e(TAG, "setEarcEnabled: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            mService.setCecSettingIntValue(SETTING_NAME_EARC_ENABLED, value);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Get the current global status of eARC.
     *
     * <p>Reflects whether the eARC feature is currently enabled on the device.
     */
    @NonNull
    @EarcFeature
    @RequiresPermission(android.Manifest.permission.HDMI_CEC)
    public int getEarcEnabled() {
        if (mService == null) {
            Log.e(TAG, "getEarcEnabled: HdmiControlService is not available");
            throw new RuntimeException("HdmiControlService is not available");
        }
        try {
            return mService.getCecSettingIntValue(SETTING_NAME_EARC_ENABLED);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
