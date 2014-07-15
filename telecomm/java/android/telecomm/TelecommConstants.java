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

package android.telecomm;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.TelephonyManager;

/**
 * Defines constants for use with the Telecomm system.
 */
public final class TelecommConstants {
    /**
     * <p>Activity action: Starts the UI for handing an incoming call. This intent starts the
     * in-call UI by notifying the Telecomm system that an incoming call exists for a specific call
     * service (see {@link android.telecomm.ConnectionService}). Telecomm reads the Intent extras
     * to find and bind to the appropriate {@link android.telecomm.ConnectionService} which
     * Telecomm will ultimately use to control and get information about the call.</p>
     *
     * <p>Input: get*Extra field {@link #EXTRA_PHONE_ACCOUNT} contains the component name of the
     * {@link android.telecomm.ConnectionService} that Telecomm should bind to. Telecomm will then
     * ask the connection service for more information about the call prior to showing any UI.
     *
     * TODO(santoscordon): Needs permissions.
     * TODO(santoscordon): Consider moving this into a simple method call on a system service.
     */
    public static final String ACTION_INCOMING_CALL = "android.intent.action.INCOMING_CALL";

    /**
     * The service action used to bind to {@link ConnectionService} implementations.
     */
    public static final String ACTION_CONNECTION_SERVICE = ConnectionService.class.getName();

    /**
     * The {@link Intent} action used to configure a {@link ConnectionService}.
     */
    public static final String ACTION_CONNECTION_SERVICE_CONFIGURE =
            "android.intent.action.CONNECTION_SERVICE_CONFIGURE";

    /**
     * Optional extra for {@link Intent#ACTION_CALL} containing a boolean that determines whether
     * the speakerphone should be automatically turned on for an outgoing call.
     */
    public static final String EXTRA_START_CALL_WITH_SPEAKERPHONE =
            "android.intent.extra.START_CALL_WITH_SPEAKERPHONE";

    /**
     * Optional extra for {@link Intent#ACTION_CALL} containing an integer that determines the
     * desired video state for an outgoing call.
     * Valid options: {@link VideoCallProfile#VIDEO_STATE_AUDIO_ONLY},
     * {@link VideoCallProfile#VIDEO_STATE_BIDIRECTIONAL},
     * {@link VideoCallProfile#VIDEO_STATE_RX_ENABLED},
     * {@link VideoCallProfile#VIDEO_STATE_TX_ENABLED}.
     */
    public static final String EXTRA_START_CALL_WITH_VIDEO_STATE =
            "android.intent.extra.START_CALL_WITH_VIDEO_STATE";

    /**
     * The extra used with an {@link android.content.Intent#ACTION_CALL},
     * {@link #ACTION_INCOMING_CALL}, {@link android.content.Intent#ACTION_DIAL} {@code Intent} to
     * specify a {@link PhoneAccount} to use when making the call.
     *
     * <p class="note">
     * Retrieve with
     * {@link android.content.Intent#getParcelableExtra(String)}.
     */
    public static final String EXTRA_PHONE_ACCOUNT = "android.intent.extra.PHONE_ACCOUNT";

    /**
     * Optional extra for {@link #ACTION_INCOMING_CALL} containing a {@link Bundle} which contains
     * metadata about the call. This {@link Bundle} will be returned to the
     * {@link ConnectionService}.
     */
    public static final String EXTRA_INCOMING_CALL_EXTRAS =
            "android.intent.extra.INCOMING_CALL_EXTRAS";

    /**
     * Optional extra for {@link TelephonyManager#ACTION_PHONE_STATE_CHANGED} containing the
     * disconnect code.
     */
    public static final String EXTRA_CALL_DISCONNECT_CAUSE =
            "android.telecomm.extra.CALL_DISCONNECT_CAUSE";

    /**
     * Optional extra for {@link TelephonyManager#ACTION_PHONE_STATE_CHANGED} containing the
     * disconnect message.
     */
    public static final String EXTRA_CALL_DISCONNECT_MESSAGE =
            "android.telecomm.extra.CALL_DISCONNECT_MESSAGE";

    /**
     * Optional extra for {@link TelephonyManager#ACTION_PHONE_STATE_CHANGED} containing the
     * component name of the associated connection service.
     */
    public static final String EXTRA_CONNECTION_SERVICE =
            "android.telecomm.extra.CONNECTION_SERVICE";

    /**
     * The dual tone multi-frequency signaling character sent to indicate the dialing system should
     * pause for a predefined period.
     */
    public static final char DTMF_CHARACTER_PAUSE = ',';

    /**
     * The dual-tone multi-frequency signaling character sent to indicate the dialing system should
     * wait for user confirmation before proceeding.
     */
    public static final char DTMF_CHARACTER_WAIT = ';';

    /**
     * TTY (teletypewriter) mode is off.
     *
     * @hide
     */
    public static final int TTY_MODE_OFF = 0;

    /**
     * TTY (teletypewriter) mode is on. The speaker is off and the microphone is muted. The user
     * will communicate with the remote party by sending and receiving text messages.
     *
     * @hide
     */
    public static final int TTY_MODE_FULL = 1;

    /**
     * TTY (teletypewriter) mode is in hearing carryover mode (HCO). The microphone is muted but the
     * speaker is on. The user will communicate with the remote party by sending text messages and
     * hearing an audible reply.
     *
     * @hide
     */
    public static final int TTY_MODE_HCO = 2;

    /**
     * TTY (teletypewriter) mode is in voice carryover mode (VCO). The speaker is off but the
     * microphone is still on. User will communicate with the remote party by speaking and receiving
     * text message replies.
     *
     * @hide
     */
    public static final int TTY_MODE_VCO = 3;

    /**
     * Broadcast intent action indicating that the current TTY mode has changed. An intent extra
     * provides this state as an int.
     * @see #EXTRA_CURRENT_TTY_MODE
     *
     * @hide
     */
    public static final String ACTION_CURRENT_TTY_MODE_CHANGED =
            "android.telecomm.intent.action.CURRENT_TTY_MODE_CHANGED";

    /**
     * The lookup key for an int that indicates the current TTY mode.
     * Valid modes are:
     * - {@link #TTY_MODE_OFF}
     * - {@link #TTY_MODE_FULL}
     * - {@link #TTY_MODE_HCO}
     * - {@link #TTY_MODE_VCO}
     *
     * @hide
     */
    public static final String EXTRA_CURRENT_TTY_MODE =
            "android.telecomm.intent.extra.CURRENT_TTY_MODE";

    /**
     * Broadcast intent action indicating that the TTY preferred operating mode
     * has changed. An intent extra provides the new mode as an int.
     * @see #EXTRA_TTY_PREFERRED_MODE
     *
     * @hide
     */
    public static final String ACTION_TTY_PREFERRED_MODE_CHANGED =
            "android.telecomm.intent.action.TTY_PREFERRED_MODE_CHANGED";

    /**
     * The lookup key for an int that indicates preferred TTY mode.
     * Valid modes are:
     * - {@link #TTY_MODE_OFF}
     * - {@link #TTY_MODE_FULL}
     * - {@link #TTY_MODE_HCO}
     * - {@link #TTY_MODE_VCO}
     *
     * @hide
     */
    public static final String EXTRA_TTY_PREFERRED_MODE =
            "android.telecomm.intent.extra.TTY_PREFERRED";
}
