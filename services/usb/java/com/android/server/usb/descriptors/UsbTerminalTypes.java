/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.usb.descriptors;

/**
 * @hide
 * A class for decoding information in Terminal Descriptors.
 * see termt10.pdf
 */
public final class UsbTerminalTypes {
    private static final String TAG = "UsbTerminalTypes";

    // USB
    public static final int TERMINAL_USB_STREAMING   = 0x0101;

    // Inputs
    public static final int TERMINAL_IN_UNDEFINED    = 0x0200;
    public static final int TERMINAL_IN_MIC          = 0x0201;
    public static final int TERMINAL_IN_DESKTOP_MIC  = 0x0202;
    public static final int TERMINAL_IN_PERSONAL_MIC = 0x0203;
    public static final int TERMINAL_IN_OMNI_MIC     = 0x0204;
    public static final int TERMINAL_IN_MIC_ARRAY    = 0x0205;
    public static final int TERMINAL_IN_PROC_MIC_ARRAY = 0x0206;

    // Outputs
    public static final int TERMINAL_OUT_UNDEFINED       = 0x0300;
    public static final int TERMINAL_OUT_SPEAKER         = 0x0301;
    public static final int TERMINAL_OUT_HEADPHONES      = 0x0302;
    public static final int TERMINAL_OUT_HEADMOUNTED     = 0x0303;
    public static final int TERMINAL_OUT_DESKTOPSPEAKER  = 0x0304;
    public static final int TERMINAL_OUT_ROOMSPEAKER     = 0x0305;
    public static final int TERMINAL_OUT_COMSPEAKER      = 0x0306;
    public static final int TERMINAL_OUT_LFSPEAKER       = 0x0307;

    // Bi-directional
    public static final int TERMINAL_BIDIR_UNDEFINED    = 0x0400;
    public static final int TERMINAL_BIDIR_HANDSET      = 0x0401;
    public static final int TERMINAL_BIDIR_HEADSET      = 0x0402;
    public static final int TERMINAL_BIDIR_SKRPHONE     = 0x0403;
    public static final int TERMINAL_BIDIR_SKRPHONE_SUPRESS = 0x0404;
    public static final int TERMINAL_BIDIR_SKRPHONE_CANCEL = 0x0405;

    // Telephony
    public static final int TERMINAL_TELE_UNDEFINED     = 0x0500;
    public static final int TERMINAL_TELE_PHONELINE     = 0x0501;
    public static final int TERMINAL_TELE_PHONE         = 0x0502;
    public static final int TERMINAL_TELE_DOWNLINEPHONE = 0x0503;

    // External
    public static final int TERMINAL_EXTERN_UNDEFINED   = 0x0600;
    public static final int TERMINAL_EXTERN_ANALOG      = 0x0601;
    public static final int TERMINAL_EXTERN_DIGITAL     = 0x0602;
    public static final int TERMINAL_EXTERN_LINE        = 0x0603;
    public static final int TERMINAL_EXTERN_LEGACY      = 0x0604;
    public static final int TERMINAL_EXTERN_SPIDF       = 0x0605;
    public static final int TERMINAL_EXTERN_1394DA      = 0x0606;
    public static final int TERMINAL_EXTERN_1394DV      = 0x0607;

    public static final int TERMINAL_EMBED_UNDEFINED    = 0x0700;
    public static final int TERMINAL_EMBED_CALNOISE     = 0x0701;
    public static final int TERMINAL_EMBED_EQNOISE      = 0x0702;
    public static final int TERMINAL_EMBED_CDPLAYER     = 0x0703;
    public static final int TERMINAL_EMBED_DAT          = 0x0704;
    public static final int TERMINAL_EMBED_DCC          = 0x0705;
    public static final int TERMINAL_EMBED_MINIDISK     = 0x0706;
    public static final int TERMINAL_EMBED_ANALOGTAPE   = 0x0707;
    public static final int TERMINAL_EMBED_PHONOGRAPH   = 0x0708;
    public static final int TERMINAL_EMBED_VCRAUDIO     = 0x0709;
    public static final int TERMINAL_EMBED_VIDDISKAUDIO = 0x070A;
    public static final int TERMINAL_EMBED_DVDAUDIO     = 0x070B;
    public static final int TERMINAL_EMBED_TVAUDIO      = 0x070C;
    public static final int TERMINAL_EMBED_SATELLITEAUDIO = 0x070D;
    public static final int TERMINAL_EMBED_CABLEAUDIO   = 0x070E;
    public static final int TERMINAL_EMBED_DSSAUDIO     = 0x070F;
    public static final int TERMINAL_EMBED_RADIOAUDIO   = 0x0710;
    public static final int TERMINAL_EMBED_RADIOTRANSMITTER = 0x0711;
    public static final int TERMINAL_EMBED_MULTITRACK   = 0x0712;
    public static final int TERMINAL_EMBED_SYNTHESIZER  = 0x0713;

}
