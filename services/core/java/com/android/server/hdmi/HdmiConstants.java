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

/**
 * Defines constants related to HDMI-CEC protocol internal implementation.
 * If a constant will be used in the public api, it should be located in
 * {@link android.hardware.hdmi.HdmiCec}.
 */
final class HdmiConstants {

    // Constants related to operands of HDMI CEC commands.
    // Refer to CEC Table 29 in HDMI Spec v1.4b.
    // [Abort Reason]
    static final int ABORT_UNRECOGNIZED_MODE = 0;
    static final int ABORT_NOT_IN_CORRECT_MODE = 1;
    static final int ABORT_CANNOT_PROVIDE_SOURCE = 2;
    static final int ABORT_INVALID_OPERAND = 3;
    static final int ABORT_REFUSED = 4;
    static final int ABORT_UNABLE_TO_DETERMINE = 5;

    // [Audio Status]
    static final int SYSTEM_AUDIO_STATUS_OFF = 0;
    static final int SYSTEM_AUDIO_STATUS_ON = 1;

    // Constants related to UI Command Codes.
    // Refer to CEC Table 30 in HDMI Spec v1.4b.
    static final int UI_COMMAND_POWER = 0x40;
    static final int UI_COMMAND_MUTE = 0x43;
    static final int UI_COMMAND_MUTE_FUNCTION = 0x65;
    static final int UI_COMMAND_RESTORE_VOLUME_FUNCTION = 0x66;
    static final int UI_COMMAND_POWER_ON_FUNCTION = 0x6D;

    // Bit mask used to get the routing path of the top level device.
    // When &'d with the path 1.2.2.0 (0x1220), for instance, gives 1.0.0.0.
    static final int ROUTING_PATH_TOP_MASK = 0xF000;

    // Flags used for setOption to CEC HAL.
    /**
     * When set to false, HAL does not wake up the system upon receiving
     * <Image View On> or <Text View On>. Used when user changes the TV
     * settings to disable the auto TV on functionality.
     * True by default.
     */
    static final int FLAG_HDMI_OPTION_WAKEUP = 1;
    /**
     * When set to false, all the CEC commands are discarded. Used when
     * user changes the TV settings to disable CEC functionality.
     * True by default.
     */
    static final int FLAG_HDMI_OPTION_ENABLE_CEC = 2;
    /**
     * Setting this flag to false means Android system will stop handling
     * CEC service and yield the control over to the microprocessor that is
     * powered on through the standby mode. When set to true, the system
     * will gain the control over, hence telling the microprocessor to stop
     * handling the cec commands. This is called when system goes
     * in and out of standby mode to notify the microprocessor that it should
     * start/stop handling CEC commands on behalf of the system.
     * False by default.
     */
    static final int FLAG_HDMI_OPTION_SYSTEM_CEC_CONTROL = 3;

    private HdmiConstants() { /* cannot be instantiated */ }
}
