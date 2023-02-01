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

package com.android.server.hdmi;

import android.annotation.IntDef;
import android.annotation.StringDef;
import android.hardware.hdmi.HdmiDeviceInfo;
import android.hardware.tv.hdmi.connection.HpdSignal;
import android.hardware.tv.hdmi.earc.IEArcStatus;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Defines constants related to HDMI-CEC protocol internal implementation. If a constant will be
 * used in the public api, it should be located in {@link android.hardware.hdmi.HdmiControlManager}.
 */
final class Constants {

    /** Logical address for TV */
    public static final int ADDR_TV = 0;

    /** Logical address for recorder 1 */
    public static final int ADDR_RECORDER_1 = 1;

    /** Logical address for recorder 2 */
    public static final int ADDR_RECORDER_2 = 2;

    /** Logical address for tuner 1 */
    public static final int ADDR_TUNER_1 = 3;

    /** Logical address for playback 1 */
    public static final int ADDR_PLAYBACK_1 = 4;

    /** Logical address for audio system */
    public static final int ADDR_AUDIO_SYSTEM = 5;

    /** Logical address for tuner 2 */
    public static final int ADDR_TUNER_2 = 6;

    /** Logical address for tuner 3 */
    public static final int ADDR_TUNER_3 = 7;

    /** Logical address for playback 2 */
    public static final int ADDR_PLAYBACK_2 = 8;

    /** Logical address for recorder 3 */
    public static final int ADDR_RECORDER_3 = 9;

    /** Logical address for tuner 4 */
    public static final int ADDR_TUNER_4 = 10;

    /** Logical address for playback 3 */
    public static final int ADDR_PLAYBACK_3 = 11;

    /** Logical address reserved for future usage */
    public static final int ADDR_BACKUP_1 = 12;

    /** Logical address reserved for future usage */
    public static final int ADDR_BACKUP_2 = 13;

    /** Logical address for TV other than the one assigned with {@link #ADDR_TV} */
    public static final int ADDR_SPECIFIC_USE = 14;

    /** Logical address for devices to which address cannot be allocated */
    public static final int ADDR_UNREGISTERED = 15;

    /** Logical address used in the destination address field for broadcast messages */
    public static final int ADDR_BROADCAST = 15;

    /** Logical address used to indicate it is not initialized or invalid. */
    public static final int ADDR_INVALID = HdmiDeviceInfo.ADDR_INVALID;

    /** Logical address used to indicate the source comes from internal device. */
    public static final int ADDR_INTERNAL = HdmiDeviceInfo.ADDR_INTERNAL;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            MESSAGE_FEATURE_ABORT,
            MESSAGE_IMAGE_VIEW_ON,
            MESSAGE_TUNER_STEP_INCREMENT,
            MESSAGE_TUNER_STEP_DECREMENT,
            MESSAGE_TUNER_DEVICE_STATUS,
            MESSAGE_GIVE_TUNER_DEVICE_STATUS,
            MESSAGE_RECORD_ON,
            MESSAGE_RECORD_STATUS,
            MESSAGE_RECORD_OFF,
            MESSAGE_TEXT_VIEW_ON,
            MESSAGE_RECORD_TV_SCREEN,
            MESSAGE_GIVE_DECK_STATUS,
            MESSAGE_DECK_STATUS,
            MESSAGE_SET_MENU_LANGUAGE,
            MESSAGE_CLEAR_ANALOG_TIMER,
            MESSAGE_SET_ANALOG_TIMER,
            MESSAGE_TIMER_STATUS,
            MESSAGE_STANDBY,
            MESSAGE_PLAY,
            MESSAGE_DECK_CONTROL,
            MESSAGE_TIMER_CLEARED_STATUS,
            MESSAGE_USER_CONTROL_PRESSED,
            MESSAGE_USER_CONTROL_RELEASED,
            MESSAGE_GIVE_OSD_NAME,
            MESSAGE_SET_OSD_NAME,
            MESSAGE_SET_OSD_STRING,
            MESSAGE_SET_TIMER_PROGRAM_TITLE,
            MESSAGE_SYSTEM_AUDIO_MODE_REQUEST,
            MESSAGE_GIVE_AUDIO_STATUS,
            MESSAGE_SET_SYSTEM_AUDIO_MODE,
            MESSAGE_SET_AUDIO_VOLUME_LEVEL,
            MESSAGE_REPORT_AUDIO_STATUS,
            MESSAGE_GIVE_SYSTEM_AUDIO_MODE_STATUS,
            MESSAGE_SYSTEM_AUDIO_MODE_STATUS,
            MESSAGE_ROUTING_CHANGE,
            MESSAGE_ROUTING_INFORMATION,
            MESSAGE_ACTIVE_SOURCE,
            MESSAGE_GIVE_PHYSICAL_ADDRESS,
            MESSAGE_REPORT_PHYSICAL_ADDRESS,
            MESSAGE_REQUEST_ACTIVE_SOURCE,
            MESSAGE_SET_STREAM_PATH,
            MESSAGE_DEVICE_VENDOR_ID,
            MESSAGE_VENDOR_COMMAND,
            MESSAGE_VENDOR_REMOTE_BUTTON_DOWN,
            MESSAGE_VENDOR_REMOTE_BUTTON_UP,
            MESSAGE_GIVE_DEVICE_VENDOR_ID,
            MESSAGE_MENU_REQUEST,
            MESSAGE_MENU_STATUS,
            MESSAGE_GIVE_DEVICE_POWER_STATUS,
            MESSAGE_REPORT_POWER_STATUS,
            MESSAGE_GET_MENU_LANGUAGE,
            MESSAGE_SELECT_ANALOG_SERVICE,
            MESSAGE_SELECT_DIGITAL_SERVICE,
            MESSAGE_SET_DIGITAL_TIMER,
            MESSAGE_CLEAR_DIGITAL_TIMER,
            MESSAGE_SET_AUDIO_RATE,
            MESSAGE_INACTIVE_SOURCE,
            MESSAGE_CEC_VERSION,
            MESSAGE_GET_CEC_VERSION,
            MESSAGE_VENDOR_COMMAND_WITH_ID,
            MESSAGE_CLEAR_EXTERNAL_TIMER,
            MESSAGE_SET_EXTERNAL_TIMER,
            MESSAGE_REPORT_SHORT_AUDIO_DESCRIPTOR,
            MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR,
            MESSAGE_GIVE_FEATURES,
            MESSAGE_REPORT_FEATURES,
            MESSAGE_REQUEST_CURRENT_LATENCY,
            MESSAGE_REPORT_CURRENT_LATENCY,
            MESSAGE_INITIATE_ARC,
            MESSAGE_REPORT_ARC_INITIATED,
            MESSAGE_REPORT_ARC_TERMINATED,
            MESSAGE_REQUEST_ARC_INITIATION,
            MESSAGE_REQUEST_ARC_TERMINATION,
            MESSAGE_TERMINATE_ARC,
            MESSAGE_CDC_MESSAGE,
            MESSAGE_ABORT,
    })
    public @interface FeatureOpcode {
    }

    static final int MESSAGE_FEATURE_ABORT = 0x00;
    static final int MESSAGE_IMAGE_VIEW_ON = 0x04;
    static final int MESSAGE_TUNER_STEP_INCREMENT = 0x05;
    static final int MESSAGE_TUNER_STEP_DECREMENT = 0x06;
    static final int MESSAGE_TUNER_DEVICE_STATUS = 0x07;
    static final int MESSAGE_GIVE_TUNER_DEVICE_STATUS = 0x08;
    static final int MESSAGE_RECORD_ON = 0x09;
    static final int MESSAGE_RECORD_STATUS = 0x0A;
    static final int MESSAGE_RECORD_OFF = 0x0B;
    static final int MESSAGE_TEXT_VIEW_ON = 0x0D;
    static final int MESSAGE_RECORD_TV_SCREEN = 0x0F;
    static final int MESSAGE_GIVE_DECK_STATUS = 0x1A;
    static final int MESSAGE_DECK_STATUS = 0x1B;
    static final int MESSAGE_SET_MENU_LANGUAGE = 0x32;
    static final int MESSAGE_CLEAR_ANALOG_TIMER = 0x33;
    static final int MESSAGE_SET_ANALOG_TIMER = 0x34;
    static final int MESSAGE_TIMER_STATUS = 0x35;
    static final int MESSAGE_STANDBY = 0x36;
    static final int MESSAGE_PLAY = 0x41;
    static final int MESSAGE_DECK_CONTROL = 0x42;
    static final int MESSAGE_TIMER_CLEARED_STATUS = 0x043;
    static final int MESSAGE_USER_CONTROL_PRESSED = 0x44;
    static final int MESSAGE_USER_CONTROL_RELEASED = 0x45;
    static final int MESSAGE_GIVE_OSD_NAME = 0x46;
    static final int MESSAGE_SET_OSD_NAME = 0x47;
    static final int MESSAGE_SET_OSD_STRING = 0x64;
    static final int MESSAGE_SET_TIMER_PROGRAM_TITLE = 0x67;
    static final int MESSAGE_SYSTEM_AUDIO_MODE_REQUEST = 0x70;
    static final int MESSAGE_GIVE_AUDIO_STATUS = 0x71;
    static final int MESSAGE_SET_SYSTEM_AUDIO_MODE = 0x72;
    static final int MESSAGE_SET_AUDIO_VOLUME_LEVEL = 0x73;
    static final int MESSAGE_REPORT_AUDIO_STATUS = 0x7A;
    static final int MESSAGE_GIVE_SYSTEM_AUDIO_MODE_STATUS = 0x7D;
    static final int MESSAGE_SYSTEM_AUDIO_MODE_STATUS = 0x7E;
    static final int MESSAGE_ROUTING_CHANGE = 0x80;
    static final int MESSAGE_ROUTING_INFORMATION = 0x81;
    static final int MESSAGE_ACTIVE_SOURCE = 0x82;
    static final int MESSAGE_GIVE_PHYSICAL_ADDRESS = 0x83;
    static final int MESSAGE_REPORT_PHYSICAL_ADDRESS = 0x84;
    static final int MESSAGE_REQUEST_ACTIVE_SOURCE = 0x85;
    static final int MESSAGE_SET_STREAM_PATH = 0x86;
    static final int MESSAGE_DEVICE_VENDOR_ID = 0x87;
    static final int MESSAGE_VENDOR_COMMAND = 0x89;
    static final int MESSAGE_VENDOR_REMOTE_BUTTON_DOWN = 0x8A;
    static final int MESSAGE_VENDOR_REMOTE_BUTTON_UP = 0x8B;
    static final int MESSAGE_GIVE_DEVICE_VENDOR_ID = 0x8C;
    static final int MESSAGE_MENU_REQUEST = 0x8D;
    static final int MESSAGE_MENU_STATUS = 0x8E;
    static final int MESSAGE_GIVE_DEVICE_POWER_STATUS = 0x8F;
    static final int MESSAGE_REPORT_POWER_STATUS = 0x90;
    static final int MESSAGE_GET_MENU_LANGUAGE = 0x91;
    static final int MESSAGE_SELECT_ANALOG_SERVICE = 0x92;
    static final int MESSAGE_SELECT_DIGITAL_SERVICE = 0x93;
    static final int MESSAGE_SET_DIGITAL_TIMER = 0x97;
    static final int MESSAGE_CLEAR_DIGITAL_TIMER = 0x99;
    static final int MESSAGE_SET_AUDIO_RATE = 0x9A;
    static final int MESSAGE_INACTIVE_SOURCE = 0x9D;
    static final int MESSAGE_CEC_VERSION = 0x9E;
    static final int MESSAGE_GET_CEC_VERSION = 0x9F;
    static final int MESSAGE_VENDOR_COMMAND_WITH_ID = 0xA0;
    static final int MESSAGE_CLEAR_EXTERNAL_TIMER = 0xA1;
    static final int MESSAGE_SET_EXTERNAL_TIMER = 0xA2;
    static final int MESSAGE_REPORT_SHORT_AUDIO_DESCRIPTOR = 0xA3;
    static final int MESSAGE_REQUEST_SHORT_AUDIO_DESCRIPTOR = 0xA4;
    static final int MESSAGE_GIVE_FEATURES = 0xA5;
    static final int MESSAGE_REPORT_FEATURES = 0xA6;
    static final int MESSAGE_REQUEST_CURRENT_LATENCY = 0xA7;
    static final int MESSAGE_REPORT_CURRENT_LATENCY = 0xA8;
    static final int MESSAGE_INITIATE_ARC = 0xC0;
    static final int MESSAGE_REPORT_ARC_INITIATED = 0xC1;
    static final int MESSAGE_REPORT_ARC_TERMINATED = 0xC2;
    static final int MESSAGE_REQUEST_ARC_INITIATION = 0xC3;
    static final int MESSAGE_REQUEST_ARC_TERMINATION = 0xC4;
    static final int MESSAGE_TERMINATE_ARC = 0xC5;
    static final int MESSAGE_CDC_MESSAGE = 0xF8;
    static final int MESSAGE_ABORT = 0xFF;

    static final int VENDOR_ID_UNKNOWN = HdmiDeviceInfo.VENDOR_ID_UNKNOWN;

    static final int TRUE = 1;
    static final int FALSE = 0;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            NOT_HANDLED,
            HANDLED,
            ABORT_UNRECOGNIZED_OPCODE,
            ABORT_NOT_IN_CORRECT_MODE,
            ABORT_CANNOT_PROVIDE_SOURCE,
            ABORT_INVALID_OPERAND,
            ABORT_REFUSED,
            ABORT_UNABLE_TO_DETERMINE,
    })
    public @interface HandleMessageResult {}

    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        ABORT_UNRECOGNIZED_OPCODE,
        ABORT_NOT_IN_CORRECT_MODE,
        ABORT_CANNOT_PROVIDE_SOURCE,
        ABORT_INVALID_OPERAND,
        ABORT_REFUSED,
        ABORT_UNABLE_TO_DETERMINE,
    })
    public @interface AbortReason {}

    // Indicates that a message was not handled, but could be handled by another local device.
    // If no local devices handle the message, we send <Feature Abort>[Unrecognized Opcode].
    static final int NOT_HANDLED = -2;
    // Indicates that a message has been handled successfully; no feature abort needed.
    static final int HANDLED = -1;
    // Constants related to operands of HDMI CEC commands.
    // Refer to CEC Table 29 in HDMI Spec v1.4b.
    // [Abort Reason]
    static final int ABORT_UNRECOGNIZED_OPCODE = 0;
    static final int ABORT_NOT_IN_CORRECT_MODE = 1;
    static final int ABORT_CANNOT_PROVIDE_SOURCE = 2;
    static final int ABORT_INVALID_OPERAND = 3;
    static final int ABORT_REFUSED = 4;
    static final int ABORT_UNABLE_TO_DETERMINE = 5;

    // [Audio Status]
    static final int SYSTEM_AUDIO_STATUS_OFF = 0;
    static final int SYSTEM_AUDIO_STATUS_ON = 1;

    // [Menu State]
    static final int MENU_STATE_ACTIVATED = 0;
    static final int MENU_STATE_DEACTIVATED = 1;

    // Audio Format Codes
    // Refer to CEA Standard (CEA-861-D), Table 37 Audio Format Codes.
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
        AUDIO_CODEC_NONE,
        AUDIO_CODEC_LPCM,
        AUDIO_CODEC_DD,
        AUDIO_CODEC_MPEG1,
        AUDIO_CODEC_MP3,
        AUDIO_CODEC_MPEG2,
        AUDIO_CODEC_AAC,
        AUDIO_CODEC_DTS,
        AUDIO_CODEC_ATRAC,
        AUDIO_CODEC_ONEBITAUDIO,
        AUDIO_CODEC_DDP,
        AUDIO_CODEC_DTSHD,
        AUDIO_CODEC_TRUEHD,
        AUDIO_CODEC_DST,
        AUDIO_CODEC_WMAPRO,
        AUDIO_CODEC_MAX,
    })
    public @interface AudioCodec {}

    static final int AUDIO_CODEC_NONE = 0x0;
    static final int AUDIO_CODEC_LPCM = 0x1; // Support LPCMs
    static final int AUDIO_CODEC_DD = 0x2; // Support DD
    static final int AUDIO_CODEC_MPEG1 = 0x3; // Support MPEG1
    static final int AUDIO_CODEC_MP3 = 0x4; // Support MP3
    static final int AUDIO_CODEC_MPEG2 = 0x5; // Support MPEG2
    static final int AUDIO_CODEC_AAC = 0x6; // Support AAC
    static final int AUDIO_CODEC_DTS = 0x7; // Support DTS
    static final int AUDIO_CODEC_ATRAC = 0x8; // Support ATRAC
    static final int AUDIO_CODEC_ONEBITAUDIO = 0x9; // Support One-Bit Audio
    static final int AUDIO_CODEC_DDP = 0xA; // Support DDP
    static final int AUDIO_CODEC_DTSHD = 0xB; // Support DTSHD
    static final int AUDIO_CODEC_TRUEHD = 0xC; // Support MLP/TRUE-HD
    static final int AUDIO_CODEC_DST = 0xD; // Support DST
    static final int AUDIO_CODEC_WMAPRO = 0xE; // Support WMA-Pro
    static final int AUDIO_CODEC_MAX = 0xF;

    static final int AUDIO_FORMAT_MASK = 0b0111_1000;

    @StringDef({
        AUDIO_DEVICE_ARC_IN,
        AUDIO_DEVICE_SPDIF,
    })
    public @interface AudioDevice {}

    static final String AUDIO_DEVICE_ARC_IN = "ARC_IN";
    static final String AUDIO_DEVICE_SPDIF = "SPDIF";

    // Bit mask used to get the routing path of the top level device.
    // When &'d with the path 1.2.2.0 (0x1220), for instance, gives 1.0.0.0.
    static final int ROUTING_PATH_TOP_MASK = 0xF000;
    static final int ROUTING_PATH_TOP_SHIFT = 12;

    static final int INVALID_PORT_ID = HdmiDeviceInfo.PORT_INVALID;
    static final int INVALID_PHYSICAL_ADDRESS = HdmiDeviceInfo.PATH_INVALID;
    static final int PATH_INTERNAL = HdmiDeviceInfo.PATH_INTERNAL;

    // The relationship from one path (physical address) to another.
    @IntDef({
            PATH_RELATIONSHIP_UNKNOWN,
            PATH_RELATIONSHIP_DIFFERENT_BRANCH,
            PATH_RELATIONSHIP_ANCESTOR,
            PATH_RELATIONSHIP_DESCENDANT,
            PATH_RELATIONSHIP_SIBLING,
            PATH_RELATIONSHIP_SAME
    })
    @interface PathRelationship {}

    // One or both of the paths is invalid
    static final int PATH_RELATIONSHIP_UNKNOWN = 0;
    // None of the relationships below holds
    static final int PATH_RELATIONSHIP_DIFFERENT_BRANCH = 1;
    // A path is either the TV, or between the TV and another path
    static final int PATH_RELATIONSHIP_ANCESTOR = 2;
    // A path is located somewhere below another path
    static final int PATH_RELATIONSHIP_DESCENDANT = 3;
    // A path has the same parent as another path
    static final int PATH_RELATIONSHIP_SIBLING = 4;
    // A path is equal to another path
    static final int PATH_RELATIONSHIP_SAME = 5;

    // Strategy for device polling.
    // Should use "OR(|) operation of POLL_STRATEGY_XXX and POLL_ITERATION_XXX.
    static final int POLL_STRATEGY_MASK = 0x3; // first and second bit.
    static final int POLL_STRATEGY_REMOTES_DEVICES = 0x1;
    static final int POLL_STRATEGY_SYSTEM_AUDIO = 0x2;

    static final int POLL_ITERATION_STRATEGY_MASK = 0x30000; // first and second bit.
    static final int POLL_ITERATION_IN_ORDER = 0x10000;
    static final int POLL_ITERATION_REVERSE_ORDER = 0x20000;

    static final int UNKNOWN_VOLUME = -1;

    // This constant is used in two operands in the CEC spec.
    //
    // CEC 1.4: [Audio Volume Status] (part of [Audio Status]) - operand for <Report Audio Status>
    // Indicates that the current audio volume status is unknown.
    //
    // CEC 2.1a: [Audio Volume Level] - operand for <Set Audio Volume Level>
    // Part of the Absolute Volume Control feature. Indicates that no change shall be made to the
    // volume level of the recipient. This allows <Set Audio Volume Level> to be sent to determine
    // whether the recipient supports Absolute Volume Control.
    static final int AUDIO_VOLUME_STATUS_UNKNOWN = 0x7F;

    // States of property PROPERTY_SYSTEM_AUDIO_CONTROL_ON_POWER_ON
    // to decide if turn on the system audio control when power on the device
    @IntDef({
        ALWAYS_SYSTEM_AUDIO_CONTROL_ON_POWER_ON,
        USE_LAST_STATE_SYSTEM_AUDIO_CONTROL_ON_POWER_ON,
        NEVER_SYSTEM_AUDIO_CONTROL_ON_POWER_ON
    })
    @interface SystemAudioControlOnPowerOn {}

    static final int ALWAYS_SYSTEM_AUDIO_CONTROL_ON_POWER_ON = 0;
    static final int USE_LAST_STATE_SYSTEM_AUDIO_CONTROL_ON_POWER_ON = 1;
    static final int NEVER_SYSTEM_AUDIO_CONTROL_ON_POWER_ON = 2;

    // Port id to record local active port for Routing Control features
    // They are used to map to corresponding Inputs
    // Current interface is only implemented for specific device.
    // Developers can add more port number and map them to corresponding inputs on demand.
    @IntDef({
        CEC_SWITCH_HOME,
        CEC_SWITCH_HDMI1,
        CEC_SWITCH_HDMI2,
        CEC_SWITCH_HDMI3,
        CEC_SWITCH_HDMI4,
        CEC_SWITCH_HDMI5,
        CEC_SWITCH_HDMI6,
        CEC_SWITCH_HDMI7,
        CEC_SWITCH_HDMI8,
        CEC_SWITCH_ARC,
        CEC_SWITCH_BLUETOOTH,
        CEC_SWITCH_OPTICAL,
        CEC_SWITCH_AUX
    })
    @interface LocalActivePort {}
    static final int CEC_SWITCH_HOME = 0;
    static final int CEC_SWITCH_HDMI1 = 1;
    static final int CEC_SWITCH_HDMI2 = 2;
    static final int CEC_SWITCH_HDMI3 = 3;
    static final int CEC_SWITCH_HDMI4 = 4;
    static final int CEC_SWITCH_HDMI5 = 5;
    static final int CEC_SWITCH_HDMI6 = 6;
    static final int CEC_SWITCH_HDMI7 = 7;
    static final int CEC_SWITCH_HDMI8 = 8;
    static final int CEC_SWITCH_ARC = 17;
    static final int CEC_SWITCH_BLUETOOTH = 18;
    static final int CEC_SWITCH_OPTICAL = 19;
    static final int CEC_SWITCH_AUX = 20;
    static final int CEC_SWITCH_PORT_MAX = 21;

    static final String PROPERTY_PREFERRED_ADDRESS_AUDIO_SYSTEM =
            "persist.sys.hdmi.addr.audiosystem";
    static final String PROPERTY_PREFERRED_ADDRESS_PLAYBACK = "persist.sys.hdmi.addr.playback";
    static final String PROPERTY_PREFERRED_ADDRESS_TV = "persist.sys.hdmi.addr.tv";

    // Set to false to allow playback device to go to suspend mode even
    // when it's an active source. True by default.
    static final String PROPERTY_KEEP_AWAKE = "persist.sys.hdmi.keep_awake";

    // TODO(UI): Set this from UI to decide if turn on System Audio Mode when power on the device
    /**
     * Property to decide if turn on the system audio control when power on the device.
     *
     * <p>Default is always turn on. State must be a valid {@link SystemAudioControlOnPowerOn} int.
     */
    static final String PROPERTY_SYSTEM_AUDIO_CONTROL_ON_POWER_ON =
            "persist.sys.hdmi.system_audio_control_on_power_on";

    /**
     * Property to record last state of system audio control before device powered off.
     * <p>When {@link #PROPERTY_SYSTEM_AUDIO_CONTROL_ON_POWER_ON} is set to
     * {@link #USE_LAST_STATE_SYSTEM_AUDIO_CONTROL_ON_POWER_ON}, restoring this state on power on.
     * <p>State must be true or false. Default true.
     */
    static final String PROPERTY_LAST_SYSTEM_AUDIO_CONTROL =
            "persist.sys.hdmi.last_system_audio_control";

    /**
     * Property to indicate if device supports ARC or not
     * <p>Default is true.
     */
    static final String PROPERTY_ARC_SUPPORT =
            "persist.sys.hdmi.property_arc_support";

    /**
     * Property to save the audio port to switch to when system audio control is on.
     * <P>Audio system should switch to this port when cec active source is not its child in the tree
     * or is not itself.
     *
     * <p>Default is ARC port.
     */
    static final String PROPERTY_SYSTEM_AUDIO_MODE_AUDIO_PORT =
            "persist.sys.hdmi.property_sytem_audio_mode_audio_port";

    /**
     * Property to strip local audio of amplifier and use local speaker
     * when TV does not support system audio mode.
     *
     * <p>This property applies to device with both audio system/playback types.
     * <p>True means using local speaker when TV does not support system audio.
     * <p>False means passing audio to TV. Default is true.
     */
    static final String PROPERTY_STRIP_AUDIO_TV_NO_SYSTEM_AUDIO =
        "persist.sys.hdmi.property_strip_audio_tv_no_system_audio";

    static final int RECORDING_TYPE_DIGITAL_RF = 1;
    static final int RECORDING_TYPE_ANALOGUE_RF = 2;
    static final int RECORDING_TYPE_EXTERNAL_PHYSICAL_ADDRESS = 3;
    static final int RECORDING_TYPE_OWN_SOURCE = 4;

    // Definitions used for setOption(). These should be in sync with the definition
    // in hardware/libhardware/include/hardware/mhl.h.

    // If set to disabled, TV does not switch ports when mobile device is connected.
    static final int OPTION_MHL_INPUT_SWITCHING = 101;

    // If set to enabled, TV disables power charging for mobile device.
    static final int OPTION_MHL_POWER_CHARGE = 102;

    // If set to disabled, all MHL commands are discarded.
    static final int OPTION_MHL_ENABLE = 103;

    // If set to disabled, system service yields control of MHL to sub-microcontroller.
    // If enabled, it takes the control back.
    static final int OPTION_MHL_SERVICE_CONTROL = 104;

    static final int DISABLED = 0;
    static final int ENABLED = 1;

    static final int ALL_DEVICE_TYPES_TV = 7;
    static final int ALL_DEVICE_TYPES_RECORDER = 6;
    static final int ALL_DEVICE_TYPES_TUNER = 5;
    static final int ALL_DEVICE_TYPES_PLAYBACK = 4;
    static final int ALL_DEVICE_TYPES_AUDIO_SYSTEM = 3;
    static final int ALL_DEVICE_TYPES_SWITCH = 2;
    @IntDef({
            ALL_DEVICE_TYPES_TV,
            ALL_DEVICE_TYPES_RECORDER,
            ALL_DEVICE_TYPES_TUNER,
            ALL_DEVICE_TYPES_PLAYBACK,
            ALL_DEVICE_TYPES_AUDIO_SYSTEM,
            ALL_DEVICE_TYPES_SWITCH
    })
    @interface DeviceType {}

    static final int DEVICE_FEATURE_TV_SUPPORTS_RECORD_TV_SCREEN = 6;
    static final int DEVICE_FEATURE_TV_SUPPORTS_SET_OSD_STRING = 5;
    static final int DEVICE_FEATURE_SUPPORTS_DECK_CONTROL = 4;
    static final int DEVICE_FEATURE_SUPPORTS_SET_AUDIO_RATE = 3;
    static final int DEVICE_FEATURE_SINK_SUPPORTS_ARC_TX = 2;
    static final int DEVICE_FEATURE_SOURCE_SUPPORTS_ARC_RX = 1;
    @IntDef({
            DEVICE_FEATURE_TV_SUPPORTS_RECORD_TV_SCREEN,
            DEVICE_FEATURE_TV_SUPPORTS_SET_OSD_STRING,
            DEVICE_FEATURE_SUPPORTS_DECK_CONTROL,
            DEVICE_FEATURE_SUPPORTS_SET_AUDIO_RATE,
            DEVICE_FEATURE_SINK_SUPPORTS_ARC_TX,
            DEVICE_FEATURE_SOURCE_SUPPORTS_ARC_RX
    })
    @interface DeviceFeature {}

    static final int RC_PROFILE_TV = 0;
    static final int RC_PROFILE_SOURCE = 1;
    @IntDef({
            RC_PROFILE_TV,
            RC_PROFILE_SOURCE
    })
    @interface RcProfile {}

    static final int RC_PROFILE_TV_NONE = 0x0;
    static final int RC_PROFILE_TV_ONE = 0x2;
    static final int RC_PROFILE_TV_TWO = 0x6;
    static final int RC_PROFILE_TV_THREE = 0xA;
    static final int RC_PROFILE_TV_FOUR = 0xE;
    @IntDef({
            RC_PROFILE_TV_NONE,
            RC_PROFILE_TV_ONE,
            RC_PROFILE_TV_TWO,
            RC_PROFILE_TV_THREE,
            RC_PROFILE_TV_FOUR
    })
    @interface RcProfileTv {}

    static final int RC_PROFILE_SOURCE_HANDLES_ROOT_MENU = 4;
    static final int RC_PROFILE_SOURCE_HANDLES_SETUP_MENU = 3;
    static final int RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU = 2;
    static final int RC_PROFILE_SOURCE_HANDLES_TOP_MENU = 1;
    static final int RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU = 0;
    @IntDef({
            RC_PROFILE_SOURCE_HANDLES_ROOT_MENU,
            RC_PROFILE_SOURCE_HANDLES_SETUP_MENU,
            RC_PROFILE_SOURCE_HANDLES_CONTENTS_MENU,
            RC_PROFILE_SOURCE_HANDLES_TOP_MENU,
            RC_PROFILE_SOURCE_HANDLES_MEDIA_CONTEXT_SENSITIVE_MENU
    })
    @interface RcProfileSource {}

    static final int HDMI_EARC_STATUS_IDLE = IEArcStatus.STATUS_IDLE; // IDLE1
    static final int HDMI_EARC_STATUS_EARC_PENDING =
            IEArcStatus.STATUS_EARC_PENDING; // DISC1 and DISC2
    static final int HDMI_EARC_STATUS_ARC_PENDING = IEArcStatus.STATUS_ARC_PENDING; // IDLE2 for ARC
    static final int HDMI_EARC_STATUS_EARC_CONNECTED =
            IEArcStatus.STATUS_EARC_CONNECTED; // eARC connected

    @IntDef({
            HDMI_EARC_STATUS_IDLE,
            HDMI_EARC_STATUS_EARC_PENDING,
            HDMI_EARC_STATUS_ARC_PENDING,
            HDMI_EARC_STATUS_EARC_CONNECTED
            })
    @interface EarcStatus {}

    static final int HDMI_HPD_TYPE_PHYSICAL =
            HpdSignal.HDMI_HPD_PHYSICAL; // Default. Physical hotplug signal.
    static final int HDMI_HPD_TYPE_STATUS_BIT =
            HpdSignal.HDMI_HPD_STATUS_BIT; // HDMI_HPD status bit.

    @IntDef({
            HDMI_HPD_TYPE_PHYSICAL,
            HDMI_HPD_TYPE_STATUS_BIT
    })
    @interface HpdSignalType {}

    static final String DEVICE_CONFIG_FEATURE_FLAG_SOUNDBAR_MODE = "soundbar_mode";
    static final String DEVICE_CONFIG_FEATURE_FLAG_ENABLE_EARC_TX = "enable_earc_tx";
    @StringDef({
            DEVICE_CONFIG_FEATURE_FLAG_SOUNDBAR_MODE,
            DEVICE_CONFIG_FEATURE_FLAG_ENABLE_EARC_TX
    })
    @interface FeatureFlag {}

    private Constants() {
        /* cannot be instantiated */
    }
}
