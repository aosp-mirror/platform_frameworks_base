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

import android.view.KeyEvent;

import libcore.util.EmptyArray;

import java.util.Arrays;

/**
 * Helper class to translate android keycode to hdmi cec keycode and vice versa.
 */
final class HdmiCecKeycode {
    public static final int UNSUPPORTED_KEYCODE = -1;
    public static final int NO_PARAM = -1;

    // =========================================================================
    // Hdmi CEC keycodes
    public static final int CEC_KEYCODE_SELECT = 0x00;
    public static final int CEC_KEYCODE_UP = 0x01;
    public static final int CEC_KEYCODE_DOWN = 0x02;
    public static final int CEC_KEYCODE_LEFT = 0x03;
    public static final int CEC_KEYCODE_RIGHT = 0x04;
    public static final int CEC_KEYCODE_RIGHT_UP = 0x05;
    public static final int CEC_KEYCODE_RIGHT_DOWN = 0x06;
    public static final int CEC_KEYCODE_LEFT_UP = 0x07;
    public static final int CEC_KEYCODE_LEFT_DOWN = 0x08;
    public static final int CEC_KEYCODE_ROOT_MENU = 0x09;
    public static final int CEC_KEYCODE_SETUP_MENU = 0x0A;
    public static final int CEC_KEYCODE_CONTENTS_MENU = 0x0B;
    public static final int CEC_KEYCODE_FAVORITE_MENU = 0x0C;
    public static final int CEC_KEYCODE_EXIT = 0x0D;
    // RESERVED = 0x0E - 0x0F
    public static final int CEC_KEYCODE_MEDIA_TOP_MENU = 0x10;
    public static final int CEC_KEYCODE_MEDIA_CONTEXT_SENSITIVE_MENU = 0x11;
    // RESERVED = 0x12 – 0x1C
    public static final int CEC_KEYCODE_NUMBER_ENTRY_MODE = 0x1D;
    public static final int CEC_KEYCODE_NUMBER_11 = 0x1E;
    public static final int CEC_KEYCODE_NUMBER_12 = 0x1F;
    public static final int CEC_KEYCODE_NUMBER_0_OR_NUMBER_10 = 0x20;
    public static final int CEC_KEYCODE_NUMBERS_1 = 0x21;
    public static final int CEC_KEYCODE_NUMBERS_2 = 0x22;
    public static final int CEC_KEYCODE_NUMBERS_3 = 0x23;
    public static final int CEC_KEYCODE_NUMBERS_4 = 0x24;
    public static final int CEC_KEYCODE_NUMBERS_5 = 0x25;
    public static final int CEC_KEYCODE_NUMBERS_6 = 0x26;
    public static final int CEC_KEYCODE_NUMBERS_7 = 0x27;
    public static final int CEC_KEYCODE_NUMBERS_8 = 0x28;
    public static final int CEC_KEYCODE_NUMBERS_9 = 0x29;
    public static final int CEC_KEYCODE_DOT = 0x2A;
    public static final int CEC_KEYCODE_ENTER = 0x2B;
    public static final int CEC_KEYCODE_CLEAR = 0x2C;
    // RESERVED = 0x2D - 0x2E
    public static final int CEC_KEYCODE_NEXT_FAVORITE = 0x2F;
    public static final int CEC_KEYCODE_CHANNEL_UP = 0x30;
    public static final int CEC_KEYCODE_CHANNEL_DOWN = 0x31;
    public static final int CEC_KEYCODE_PREVIOUS_CHANNEL = 0x32;
    public static final int CEC_KEYCODE_SOUND_SELECT = 0x33;
    public static final int CEC_KEYCODE_INPUT_SELECT = 0x34;
    public static final int CEC_KEYCODE_DISPLAY_INFORMATION = 0x35;
    public static final int CEC_KEYCODE_HELP = 0x36;
    public static final int CEC_KEYCODE_PAGE_UP = 0x37;
    public static final int CEC_KEYCODE_PAGE_DOWN = 0x38;
    // RESERVED = 0x39 - 0x3F
    public static final int CEC_KEYCODE_POWER = 0x40;
    public static final int CEC_KEYCODE_VOLUME_UP = 0x41;
    public static final int CEC_KEYCODE_VOLUME_DOWN = 0x42;
    public static final int CEC_KEYCODE_MUTE = 0x43;
    public static final int CEC_KEYCODE_PLAY = 0x44;
    public static final int CEC_KEYCODE_STOP = 0x45;
    public static final int CEC_KEYCODE_PAUSE = 0x46;
    public static final int CEC_KEYCODE_RECORD = 0x47;
    public static final int CEC_KEYCODE_REWIND = 0x48;
    public static final int CEC_KEYCODE_FAST_FORWARD = 0x49;
    public static final int CEC_KEYCODE_EJECT = 0x4A;
    public static final int CEC_KEYCODE_FORWARD = 0x4B;
    public static final int CEC_KEYCODE_BACKWARD = 0x4C;
    public static final int CEC_KEYCODE_STOP_RECORD = 0x4D;
    public static final int CEC_KEYCODE_PAUSE_RECORD = 0x4E;
    public static final int CEC_KEYCODE_RESERVED = 0x4F;
    public static final int CEC_KEYCODE_ANGLE = 0x50;
    public static final int CEC_KEYCODE_SUB_PICTURE = 0x51;
    public static final int CEC_KEYCODE_VIDEO_ON_DEMAND = 0x52;
    public static final int CEC_KEYCODE_ELECTRONIC_PROGRAM_GUIDE = 0x53;
    public static final int CEC_KEYCODE_TIMER_PROGRAMMING = 0x54;
    public static final int CEC_KEYCODE_INITIAL_CONFIGURATION = 0x55;
    public static final int CEC_KEYCODE_SELECT_BROADCAST_TYPE = 0x56;
    public static final int CEC_KEYCODE_SELECT_SOUND_PRESENTATION = 0x57;
    // RESERVED = 0x58-0x5F
    public static final int CEC_KEYCODE_PLAY_FUNCTION = 0x60;
    public static final int CEC_KEYCODE_PAUSE_PLAY_FUNCTION = 0x61;
    public static final int CEC_KEYCODE_RECORD_FUNCTION = 0x62;
    public static final int CEC_KEYCODE_PAUSE_RECORD_FUNCTION = 0x63;
    public static final int CEC_KEYCODE_STOP_FUNCTION = 0x64;
    public static final int CEC_KEYCODE_MUTE_FUNCTION = 0x65;
    public static final int CEC_KEYCODE_RESTORE_VOLUME_FUNCTION = 0x66;
    public static final int CEC_KEYCODE_TUNE_FUNCTION = 0x67;
    public static final int CEC_KEYCODE_SELECT_MEDIA_FUNCTION = 0x68;
    public static final int CEC_KEYCODE_SELECT_AV_INPUT_FUNCTION = 0x69;
    public static final int CEC_KEYCODE_SELECT_AUDIO_INPUT_FUNCTION = 0x6A;
    public static final int CEC_KEYCODE_POWER_TOGGLE_FUNCTION = 0x6B;
    public static final int CEC_KEYCODE_POWER_OFF_FUNCTION = 0x6C;
    public static final int CEC_KEYCODE_POWER_ON_FUNCTION = 0x6D;
    // RESERVED = 0x6E-0x70
    public static final int CEC_KEYCODE_F1_BLUE = 0x71;
    public static final int CEC_KEYCODE_F2_RED = 0x72;
    public static final int CEC_KEYCODE_F3_GREEN = 0x73;
    public static final int CEC_KEYCODE_F4_YELLOW = 0x74;
    public static final int CEC_KEYCODE_F5 = 0x75;
    public static final int CEC_KEYCODE_DATA = 0x76;
    // RESERVED = 0x77-0xFF

    // =========================================================================
    // UI Broadcast Type
    public static final int UI_BROADCAST_TOGGLE_ALL = 0x00;
    public static final int UI_BROADCAST_TOGGLE_ANALOGUE_DIGITAL = 0x01;
    public static final int UI_BROADCAST_ANALOGUE = 0x10;
    public static final int UI_BROADCAST_ANALOGUE_TERRESTRIAL = 0x20;
    public static final int UI_BROADCAST_ANALOGUE_CABLE = 0x30;
    public static final int UI_BROADCAST_ANALOGUE_SATELLITE = 0x40;
    public static final int UI_BROADCAST_DIGITAL = 0x50;
    public static final int UI_BROADCAST_DIGITAL_TERRESTRIAL = 0x60;
    public static final int UI_BROADCAST_DIGITAL_CABLE = 0x70;
    public static final int UI_BROADCAST_DIGITAL_SATELLITE = 0x80;
    public static final int UI_BROADCAST_DIGITAL_COMMNICATIONS_SATELLITE = 0x90;
    public static final int UI_BROADCAST_DIGITAL_COMMNICATIONS_SATELLITE_2 = 0x91;
    public static final int UI_BROADCAST_IP = 0xA0;

    // =========================================================================
    // UI Sound Presentation Control
    public static final int UI_SOUND_PRESENTATION_SOUND_MIX_DUAL_MONO = 0x20;
    public static final int UI_SOUND_PRESENTATION_SOUND_MIX_KARAOKE = 0x30;
    public static final int UI_SOUND_PRESENTATION_SELECT_AUDIO_DOWN_MIX = 0x80;
    public static final int UI_SOUND_PRESENTATION_SELECT_AUDIO_AUTO_REVERBERATION = 0x90;
    public static final int UI_SOUND_PRESENTATION_SELECT_AUDIO_AUTO_EQUALIZER = 0xA0;
    public static final int UI_SOUND_PRESENTATION_BASS_STEP_PLUS = 0xB1;
    public static final int UI_SOUND_PRESENTATION_BASS_NEUTRAL = 0xB2;
    public static final int UI_SOUND_PRESENTATION_BASS_STEP_MINUS = 0xB3;
    public static final int UI_SOUND_PRESENTATION_TREBLE_STEP_PLUS = 0xC1;
    public static final int UI_SOUND_PRESENTATION_TREBLE_NEUTRAL = 0xC2;
    public static final int UI_SOUND_PRESENTATION_TREBLE_STEP_MINUS = 0xC3;

    private HdmiCecKeycode() {
    }

    /**
     * A mapping between Android and CEC keycode.
     * <p>
     * Normal implementation of this looks like
     *
     * <pre>
     * new KeycodeEntry(KeyEvent.KEYCODE_DPAD_CENTER, CEC_KEYCODE_SELECT);
     * </pre>
     * <p>
     * However, some keys in CEC requires additional parameter. In order to use parameterized cec
     * key, add unique android keycode (existing or custom) corresponding to a pair of cec keycode
     * and and its param.
     *
     * <pre>
     * new KeycodeEntry(CUSTOME_ANDORID_KEY_1, CEC_KEYCODE_SELECT_BROADCAST_TYPE,
     *         UI_BROADCAST_TOGGLE_ALL);
     * new KeycodeEntry(CUSTOME_ANDORID_KEY_2, CEC_KEYCODE_SELECT_BROADCAST_TYPE,
     *         UI_BROADCAST_ANALOGUE);
     * </pre>
     */
    private static class KeycodeEntry {
        private final int mAndroidKeycode;
        private final boolean mIsRepeatable;
        private final byte[] mCecKeycodeAndParams;

        private KeycodeEntry(int androidKeycode, int cecKeycode, boolean isRepeatable,
                byte[] cecParams) {
            mAndroidKeycode = androidKeycode;
            mIsRepeatable = isRepeatable;
            mCecKeycodeAndParams = new byte[cecParams.length + 1];
            System.arraycopy(cecParams, 0, mCecKeycodeAndParams, 1, cecParams.length);
            mCecKeycodeAndParams[0] = (byte) (cecKeycode & 0xFF);
        }

        private KeycodeEntry(int androidKeycode, int cecKeycode, boolean isRepeatable) {
            this(androidKeycode, cecKeycode, isRepeatable, EmptyArray.BYTE);
        }

        private KeycodeEntry(int androidKeycode, int cecKeycode, byte[] cecParams) {
            this(androidKeycode, cecKeycode, true, cecParams);
        }

        private KeycodeEntry(int androidKeycode, int cecKeycode) {
            this(androidKeycode, cecKeycode, true, EmptyArray.BYTE);
        }

        private byte[] toCecKeycodeAndParamIfMatched(int androidKeycode) {
            if (mAndroidKeycode == androidKeycode) {
                return mCecKeycodeAndParams;
            } else {
                return null;
            }
        }

        private int toAndroidKeycodeIfMatched(byte[] cecKeycodeAndParams) {
            if (cecKeycodeAndParams.length < mCecKeycodeAndParams.length) {
                return UNSUPPORTED_KEYCODE;
            }
            byte[] trimmedCecKeycodeAndParams = new byte[mCecKeycodeAndParams.length];
            System.arraycopy(cecKeycodeAndParams, 0, trimmedCecKeycodeAndParams, 0,
                    mCecKeycodeAndParams.length);
            if (Arrays.equals(mCecKeycodeAndParams, trimmedCecKeycodeAndParams)) {
                return mAndroidKeycode;
            } else {
                return UNSUPPORTED_KEYCODE;
            }
        }

        private Boolean isRepeatableIfMatched(int androidKeycode) {
            if (mAndroidKeycode == androidKeycode) {
                return mIsRepeatable;
            } else {
                return null;
            }
        }
    }

    private static byte[] intToSingleByteArray(int value) {
        return new byte[] {
                (byte) (value & 0xFF) };
    }

    // Keycode entry container for all mappings.
    // Note that order of entry is the same as above cec keycode definition.
    private static final KeycodeEntry[] KEYCODE_ENTRIES = new KeycodeEntry[] {
            new KeycodeEntry(KeyEvent.KEYCODE_DPAD_CENTER, CEC_KEYCODE_SELECT),
            new KeycodeEntry(KeyEvent.KEYCODE_DPAD_UP, CEC_KEYCODE_UP),
            new KeycodeEntry(KeyEvent.KEYCODE_DPAD_DOWN, CEC_KEYCODE_DOWN),
            new KeycodeEntry(KeyEvent.KEYCODE_DPAD_LEFT, CEC_KEYCODE_LEFT),
            new KeycodeEntry(KeyEvent.KEYCODE_DPAD_RIGHT, CEC_KEYCODE_RIGHT),
            // No Android keycode defined for CEC_KEYCODE_RIGHT_UP
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_RIGHT_UP),
            // No Android keycode defined for CEC_KEYCODE_RIGHT_DOWN
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_RIGHT_DOWN),
            // No Android keycode defined for CEC_KEYCODE_LEFT_UP
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_LEFT_UP),
            // No Android keycode defined for CEC_KEYCODE_LEFT_DOWN
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_LEFT_DOWN),
            // Both KEYCODE_HOME and KEYCODE_MENU keys are sent as CEC_KEYCODE_ROOT_MENU
            // NOTE that the HOME key is not usually forwarded.
            // When CEC_KEYCODE_ROOT_MENU is received, it is translated to KEYCODE_HOME
            new KeycodeEntry(KeyEvent.KEYCODE_HOME, CEC_KEYCODE_ROOT_MENU),
            new KeycodeEntry(KeyEvent.KEYCODE_MENU, CEC_KEYCODE_ROOT_MENU),
            new KeycodeEntry(KeyEvent.KEYCODE_SETTINGS, CEC_KEYCODE_SETUP_MENU),
            new KeycodeEntry(KeyEvent.KEYCODE_TV_CONTENTS_MENU, CEC_KEYCODE_CONTENTS_MENU, false),
            // No Android keycode defined for CEC_KEYCODE_FAVORITE_MENU
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_FAVORITE_MENU),
            // Note that both BACK and ESCAPE are mapped to EXIT of CEC keycode.
            // This would be problematic when translates CEC keycode to Android keycode.
            // In current implementation, we pick BACK as mapping of EXIT key.
            // If you'd like to map CEC EXIT to Android EXIT key, change order of
            // the following two definition.
            new KeycodeEntry(KeyEvent.KEYCODE_BACK, CEC_KEYCODE_EXIT),
            new KeycodeEntry(KeyEvent.KEYCODE_ESCAPE, CEC_KEYCODE_EXIT),
            // RESERVED
            new KeycodeEntry(KeyEvent.KEYCODE_MEDIA_TOP_MENU, CEC_KEYCODE_MEDIA_TOP_MENU),
            new KeycodeEntry(KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU,
                    CEC_KEYCODE_MEDIA_CONTEXT_SENSITIVE_MENU),
            // RESERVED
            // No Android keycode defined for CEC_KEYCODE_NUMBER_ENTRY_MODE
            new KeycodeEntry(KeyEvent.KEYCODE_TV_NUMBER_ENTRY, CEC_KEYCODE_NUMBER_ENTRY_MODE),
            new KeycodeEntry(KeyEvent.KEYCODE_11, CEC_KEYCODE_NUMBER_11),
            new KeycodeEntry(KeyEvent.KEYCODE_12, CEC_KEYCODE_NUMBER_12),
            new KeycodeEntry(KeyEvent.KEYCODE_0, CEC_KEYCODE_NUMBER_0_OR_NUMBER_10),
            new KeycodeEntry(KeyEvent.KEYCODE_1, CEC_KEYCODE_NUMBERS_1),
            new KeycodeEntry(KeyEvent.KEYCODE_2, CEC_KEYCODE_NUMBERS_2),
            new KeycodeEntry(KeyEvent.KEYCODE_3, CEC_KEYCODE_NUMBERS_3),
            new KeycodeEntry(KeyEvent.KEYCODE_4, CEC_KEYCODE_NUMBERS_4),
            new KeycodeEntry(KeyEvent.KEYCODE_5, CEC_KEYCODE_NUMBERS_5),
            new KeycodeEntry(KeyEvent.KEYCODE_6, CEC_KEYCODE_NUMBERS_6),
            new KeycodeEntry(KeyEvent.KEYCODE_7, CEC_KEYCODE_NUMBERS_7),
            new KeycodeEntry(KeyEvent.KEYCODE_8, CEC_KEYCODE_NUMBERS_8),
            new KeycodeEntry(KeyEvent.KEYCODE_9, CEC_KEYCODE_NUMBERS_9),
            new KeycodeEntry(KeyEvent.KEYCODE_PERIOD, CEC_KEYCODE_DOT),
            new KeycodeEntry(KeyEvent.KEYCODE_NUMPAD_ENTER, CEC_KEYCODE_ENTER),
            new KeycodeEntry(KeyEvent.KEYCODE_CLEAR, CEC_KEYCODE_CLEAR),
            // RESERVED
            // No Android keycode defined for CEC_KEYCODE_NEXT_FAVORITE
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_NEXT_FAVORITE),
            new KeycodeEntry(KeyEvent.KEYCODE_CHANNEL_UP, CEC_KEYCODE_CHANNEL_UP),
            new KeycodeEntry(KeyEvent.KEYCODE_CHANNEL_DOWN, CEC_KEYCODE_CHANNEL_DOWN),
            new KeycodeEntry(KeyEvent.KEYCODE_LAST_CHANNEL, CEC_KEYCODE_PREVIOUS_CHANNEL),
            new KeycodeEntry(KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK, CEC_KEYCODE_SOUND_SELECT),
            new KeycodeEntry(KeyEvent.KEYCODE_TV_INPUT, CEC_KEYCODE_INPUT_SELECT),
            new KeycodeEntry(KeyEvent.KEYCODE_INFO, CEC_KEYCODE_DISPLAY_INFORMATION),
            // No Android keycode defined for CEC_KEYCODE_HELP
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_HELP),
            new KeycodeEntry(KeyEvent.KEYCODE_PAGE_UP, CEC_KEYCODE_PAGE_UP),
            new KeycodeEntry(KeyEvent.KEYCODE_PAGE_DOWN, CEC_KEYCODE_PAGE_DOWN),
            // RESERVED
            new KeycodeEntry(KeyEvent.KEYCODE_POWER, CEC_KEYCODE_POWER, false),
            new KeycodeEntry(KeyEvent.KEYCODE_VOLUME_UP, CEC_KEYCODE_VOLUME_UP),
            new KeycodeEntry(KeyEvent.KEYCODE_VOLUME_DOWN, CEC_KEYCODE_VOLUME_DOWN),
            new KeycodeEntry(KeyEvent.KEYCODE_VOLUME_MUTE, CEC_KEYCODE_MUTE, false),
            new KeycodeEntry(KeyEvent.KEYCODE_MEDIA_PLAY, CEC_KEYCODE_PLAY),
            new KeycodeEntry(KeyEvent.KEYCODE_MEDIA_STOP, CEC_KEYCODE_STOP),
            // Note that we map both MEDIA_PAUSE and MEDIA_PLAY_PAUSE to CEC PAUSE key.
            // When it translates CEC PAUSE key, it picks Android MEDIA_PAUSE key as a mapping of
            // it. If you'd like to choose MEDIA_PLAY_PAUSE, please change order of the following
            // two lines.
            new KeycodeEntry(KeyEvent.KEYCODE_MEDIA_PAUSE, CEC_KEYCODE_PAUSE),
            new KeycodeEntry(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, CEC_KEYCODE_PAUSE),
            new KeycodeEntry(KeyEvent.KEYCODE_MEDIA_RECORD, CEC_KEYCODE_RECORD),
            new KeycodeEntry(KeyEvent.KEYCODE_MEDIA_REWIND, CEC_KEYCODE_REWIND),
            new KeycodeEntry(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, CEC_KEYCODE_FAST_FORWARD),
            new KeycodeEntry(KeyEvent.KEYCODE_MEDIA_EJECT, CEC_KEYCODE_EJECT),
            new KeycodeEntry(KeyEvent.KEYCODE_MEDIA_NEXT, CEC_KEYCODE_FORWARD),
            new KeycodeEntry(KeyEvent.KEYCODE_MEDIA_PREVIOUS, CEC_KEYCODE_BACKWARD),
            // No Android keycode defined for CEC_KEYCODE_STOP_RECORD
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_STOP_RECORD),
            // No Android keycode defined for CEC_KEYCODE_PAUSE_RECORD
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_PAUSE_RECORD),
            // No Android keycode defined for CEC_KEYCODE_RESERVED
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_RESERVED),
            // No Android keycode defined for CEC_KEYCODE_ANGLE
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_ANGLE),
            new KeycodeEntry(KeyEvent.KEYCODE_CAPTIONS, CEC_KEYCODE_SUB_PICTURE),
            // No Android keycode defined for CEC_KEYCODE_VIDEO_ON_DEMAND
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_VIDEO_ON_DEMAND),
            new KeycodeEntry(KeyEvent.KEYCODE_GUIDE, CEC_KEYCODE_ELECTRONIC_PROGRAM_GUIDE),
            new KeycodeEntry(KeyEvent.KEYCODE_TV_TIMER_PROGRAMMING, CEC_KEYCODE_TIMER_PROGRAMMING),
            // No Android keycode defined for CEC_KEYCODE_INITIAL_CONFIGURATION
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_INITIAL_CONFIGURATION),
            // No Android keycode defined for CEC_KEYCODE_SELECT_BROADCAST_TYPE
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_SELECT_BROADCAST_TYPE),
            new KeycodeEntry(KeyEvent.KEYCODE_TV_TERRESTRIAL_ANALOG,
                    CEC_KEYCODE_SELECT_BROADCAST_TYPE, true,
                    intToSingleByteArray(UI_BROADCAST_ANALOGUE)),
            new KeycodeEntry(KeyEvent.KEYCODE_TV_TERRESTRIAL_DIGITAL,
                    CEC_KEYCODE_SELECT_BROADCAST_TYPE, true,
                    intToSingleByteArray(UI_BROADCAST_DIGITAL_TERRESTRIAL)),
            new KeycodeEntry(KeyEvent.KEYCODE_TV_SATELLITE_BS,
                    CEC_KEYCODE_SELECT_BROADCAST_TYPE, true,
                    intToSingleByteArray(UI_BROADCAST_DIGITAL_SATELLITE)),
            new KeycodeEntry(KeyEvent.KEYCODE_TV_SATELLITE_CS,
                    CEC_KEYCODE_SELECT_BROADCAST_TYPE, true,
                    intToSingleByteArray(UI_BROADCAST_DIGITAL_COMMNICATIONS_SATELLITE)),
            new KeycodeEntry(KeyEvent.KEYCODE_TV_NETWORK,
                    CEC_KEYCODE_SELECT_BROADCAST_TYPE, true,
                    intToSingleByteArray(UI_BROADCAST_TOGGLE_ANALOGUE_DIGITAL)),
            // No Android keycode defined for CEC_KEYCODE_SELECT_SOUND_PRESENTATION
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_SELECT_SOUND_PRESENTATION),
            // RESERVED
            // The following deterministic key definitions do not need key mapping
            // since they are supposed to be generated programmatically only.
            // No Android keycode defined for CEC_KEYCODE_PLAY_FUNCTION
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_PLAY_FUNCTION, false),
            // No Android keycode defined for CEC_KEYCODE_PAUSE_PLAY_FUNCTION
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_PAUSE_PLAY_FUNCTION, false),
            // No Android keycode defined for CEC_KEYCODE_RECORD_FUNCTION
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_RECORD_FUNCTION, false),
            // No Android keycode defined for CEC_KEYCODE_PAUSE_RECORD_FUNCTION
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_PAUSE_RECORD_FUNCTION, false),
            // No Android keycode defined for CEC_KEYCODE_STOP_FUNCTION
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_STOP_FUNCTION, false),
            // No Android keycode defined for CEC_KEYCODE_MUTE_FUNCTION
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_MUTE_FUNCTION, false),
            // No Android keycode defined for CEC_KEYCODE_RESTORE_VOLUME_FUNCTION
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_RESTORE_VOLUME_FUNCTION, false),
            // No Android keycode defined for CEC_KEYCODE_TUNE_FUNCTION
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_TUNE_FUNCTION, false),
            // No Android keycode defined for CEC_KEYCODE_SELECT_MEDIA_FUNCTION
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_SELECT_MEDIA_FUNCTION, false),
            // No Android keycode defined for CEC_KEYCODE_SELECT_AV_INPUT_FUNCTION
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_SELECT_AV_INPUT_FUNCTION, false),
            // No Android keycode defined for CEC_KEYCODE_SELECT_AUDIO_INPUT_FUNCTION
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_SELECT_AUDIO_INPUT_FUNCTION, false),
            // No Android keycode defined for CEC_KEYCODE_POWER_TOGGLE_FUNCTION
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_POWER_TOGGLE_FUNCTION, false),
            // No Android keycode defined for CEC_KEYCODE_POWER_OFF_FUNCTION
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_POWER_OFF_FUNCTION, false),
            // No Android keycode defined for CEC_KEYCODE_POWER_ON_FUNCTION
            new KeycodeEntry(UNSUPPORTED_KEYCODE, CEC_KEYCODE_POWER_ON_FUNCTION, false),
            // RESERVED
            new KeycodeEntry(KeyEvent.KEYCODE_PROG_BLUE, CEC_KEYCODE_F1_BLUE),
            new KeycodeEntry(KeyEvent.KEYCODE_PROG_RED, CEC_KEYCODE_F2_RED),
            new KeycodeEntry(KeyEvent.KEYCODE_PROG_GREEN, CEC_KEYCODE_F3_GREEN),
            new KeycodeEntry(KeyEvent.KEYCODE_PROG_YELLOW, CEC_KEYCODE_F4_YELLOW),
            new KeycodeEntry(KeyEvent.KEYCODE_F5, CEC_KEYCODE_F5),
            new KeycodeEntry(KeyEvent.KEYCODE_TV_DATA_SERVICE, CEC_KEYCODE_DATA),
            // RESERVED
            // Add a new key mapping here if new keycode is introduced.
    };

    /**
     * Translate Android keycode to Hdmi Cec keycode and params.
     *
     * @param keycode Android keycode. For details, refer {@link KeyEvent}
     * @return byte array of CEC keycode and params if matched. Otherwise, return null.
     */
    static byte[] androidKeyToCecKey(int keycode) {
        for (int i = 0; i < KEYCODE_ENTRIES.length; ++i) {
            byte[] cecKeycodeAndParams = KEYCODE_ENTRIES[i].toCecKeycodeAndParamIfMatched(keycode);
            if (cecKeycodeAndParams != null) {
                return cecKeycodeAndParams;
            }
        }
        return null;
    }

    /**
     * Translate Hdmi CEC keycode with params to Android keycode.
     *
     * @param cecKeycodeAndParams CEC keycode and params
     * @return cec keycode corresponding to the given android keycode. If finds no matched keycode,
     *         return {@link #UNSUPPORTED_KEYCODE}
     */
    static int cecKeycodeAndParamsToAndroidKey(byte[] cecKeycodeAndParams) {
        for (int i = 0; i < KEYCODE_ENTRIES.length; ++i) {
            int androidKey = KEYCODE_ENTRIES[i].toAndroidKeycodeIfMatched(cecKeycodeAndParams);
            if (androidKey != UNSUPPORTED_KEYCODE) {
                return androidKey;
            }
        }
        return UNSUPPORTED_KEYCODE;
    }

    /**
     * Whether the given {@code androidKeycode} is repeatable key or not.
     *
     * @param androidKeycode keycode of android
     * @return false if the given {@code androidKeycode} is not supported key code
     */
    static boolean isRepeatableKey(int androidKeycode) {
        for (int i = 0; i < KEYCODE_ENTRIES.length; ++i) {
            Boolean isRepeatable = KEYCODE_ENTRIES[i].isRepeatableIfMatched(androidKeycode);
            if (isRepeatable != null) {
                return isRepeatable;
            }
        }
        return false;
    }

    /**
     * Returns {@code true} if given Android keycode is supported, otherwise {@code false}.
     */
    static boolean isSupportedKeycode(int androidKeycode) {
        return HdmiCecKeycode.androidKeyToCecKey(androidKeycode) != null;
    }

    /**
     * Returns {@code true} if given Android keycode is volume control related,
     * otherwise {@code false}.
     */
    static boolean isVolumeKeycode(int androidKeycode) {
        int cecKeyCode = HdmiCecKeycode.androidKeyToCecKey(androidKeycode)[0];
        return isSupportedKeycode(androidKeycode)
            && (cecKeyCode == CEC_KEYCODE_VOLUME_UP
                || cecKeyCode == CEC_KEYCODE_VOLUME_DOWN
                || cecKeyCode == CEC_KEYCODE_MUTE
                || cecKeyCode == CEC_KEYCODE_MUTE_FUNCTION
                || cecKeyCode == CEC_KEYCODE_RESTORE_VOLUME_FUNCTION);
    }

    /**
     * Returns CEC keycode to control audio mute status.
     *
     * @param muting {@code true} if audio is being muted
     */
    public static int getMuteKey(boolean muting) {
        // CEC_KEYCODE_MUTE_FUNCTION, CEC_KEYCODE_RESTORE_VOLUME_FUNCTION are deterministic
        // commands that ensures the status changes to what we want, while CEC_KEYCODE_MUTE
        // simply toggles the status.
        // The former is a better choice in this regard, but there are compatibility issues
        // observed - many audio receivers don't recognize the commands. We fall back on
        // CEC_KEYCODE_MUTE for now.
        // return muting ? CEC_KEYCODE_MUTE_FUNCTION : CEC_KEYCODE_RESTORE_VOLUME_FUNCTION;
        return CEC_KEYCODE_MUTE;
    }

    public static String getKeycodeType(byte keycode) {
        switch (keycode) {
            case CEC_KEYCODE_UP:
            case CEC_KEYCODE_DOWN:
            case CEC_KEYCODE_LEFT:
            case CEC_KEYCODE_RIGHT:
            case CEC_KEYCODE_RIGHT_UP:
            case CEC_KEYCODE_RIGHT_DOWN:
            case CEC_KEYCODE_LEFT_UP:
            case CEC_KEYCODE_LEFT_DOWN:
            case CEC_KEYCODE_PAGE_UP:
            case CEC_KEYCODE_PAGE_DOWN:
            case CEC_KEYCODE_EXIT:
                return "Navigation";
            case CEC_KEYCODE_ROOT_MENU:
            case CEC_KEYCODE_SETUP_MENU:
            case CEC_KEYCODE_CONTENTS_MENU:
            case CEC_KEYCODE_FAVORITE_MENU:
            case CEC_KEYCODE_MEDIA_TOP_MENU:
            case CEC_KEYCODE_MEDIA_CONTEXT_SENSITIVE_MENU:
                return "Menu";
            case CEC_KEYCODE_VOLUME_UP:
                return "Volume up";
            case CEC_KEYCODE_VOLUME_DOWN:
                return "Volume down";
            case CEC_KEYCODE_MUTE:
                return "Volume mute";
            case CEC_KEYCODE_POWER:
                return "Power";
            case CEC_KEYCODE_POWER_TOGGLE_FUNCTION:
                return "Power toggle";
            case CEC_KEYCODE_POWER_OFF_FUNCTION:
                return "Power off";
            case CEC_KEYCODE_POWER_ON_FUNCTION:
                return "Power on";
            case CEC_KEYCODE_F1_BLUE:
            case CEC_KEYCODE_F2_RED:
            case CEC_KEYCODE_F3_GREEN:
            case CEC_KEYCODE_F4_YELLOW:
            case CEC_KEYCODE_F5:
                return "Function key";
            case CEC_KEYCODE_NEXT_FAVORITE:
            case CEC_KEYCODE_CHANNEL_UP:
            case CEC_KEYCODE_CHANNEL_DOWN:
            case CEC_KEYCODE_PREVIOUS_CHANNEL:
                return "Channel";
            case CEC_KEYCODE_NUMBER_11:
            case CEC_KEYCODE_NUMBER_12:
            case CEC_KEYCODE_NUMBER_0_OR_NUMBER_10:
            case CEC_KEYCODE_NUMBERS_1:
            case CEC_KEYCODE_NUMBERS_2:
            case CEC_KEYCODE_NUMBERS_3:
            case CEC_KEYCODE_NUMBERS_4:
            case CEC_KEYCODE_NUMBERS_5:
            case CEC_KEYCODE_NUMBERS_6:
            case CEC_KEYCODE_NUMBERS_7:
            case CEC_KEYCODE_NUMBERS_8:
            case CEC_KEYCODE_NUMBERS_9:
                return "Number";
            case CEC_KEYCODE_PLAY:
            case CEC_KEYCODE_STOP:
            case CEC_KEYCODE_PAUSE:
            case CEC_KEYCODE_RECORD:
            case CEC_KEYCODE_REWIND:
            case CEC_KEYCODE_FAST_FORWARD:
            case CEC_KEYCODE_EJECT:
            case CEC_KEYCODE_FORWARD:
            case CEC_KEYCODE_BACKWARD:
            case CEC_KEYCODE_STOP_RECORD:
            case CEC_KEYCODE_PAUSE_RECORD:
            case CEC_KEYCODE_ANGLE:
            case CEC_KEYCODE_SUB_PICTURE:
            case CEC_KEYCODE_VIDEO_ON_DEMAND:
                return "Media";
            case CEC_KEYCODE_PLAY_FUNCTION:
            case CEC_KEYCODE_PAUSE_PLAY_FUNCTION:
            case CEC_KEYCODE_RECORD_FUNCTION:
            case CEC_KEYCODE_PAUSE_RECORD_FUNCTION:
            case CEC_KEYCODE_STOP_FUNCTION:
            case CEC_KEYCODE_MUTE_FUNCTION:
            case CEC_KEYCODE_RESTORE_VOLUME_FUNCTION:
            case CEC_KEYCODE_TUNE_FUNCTION:
            case CEC_KEYCODE_SELECT_MEDIA_FUNCTION:
            case CEC_KEYCODE_SELECT_AV_INPUT_FUNCTION:
            case CEC_KEYCODE_SELECT_AUDIO_INPUT_FUNCTION:
                return "Functional";
            case CEC_KEYCODE_ELECTRONIC_PROGRAM_GUIDE:
            case CEC_KEYCODE_TIMER_PROGRAMMING:
                return "Timer";
            case CEC_KEYCODE_SOUND_SELECT:
            case CEC_KEYCODE_SELECT_SOUND_PRESENTATION:
            case CEC_KEYCODE_SELECT_BROADCAST_TYPE:
            case CEC_KEYCODE_INPUT_SELECT:
            case CEC_KEYCODE_SELECT:
                return "Select";
            case CEC_KEYCODE_NUMBER_ENTRY_MODE:
            case CEC_KEYCODE_DOT:
            case CEC_KEYCODE_CLEAR:
            case CEC_KEYCODE_ENTER:
            case CEC_KEYCODE_DISPLAY_INFORMATION:
            case CEC_KEYCODE_HELP:
            case CEC_KEYCODE_DATA:
            case CEC_KEYCODE_INITIAL_CONFIGURATION:
                return "General";
            default:
                return "Unknown";
        }
    }
}
