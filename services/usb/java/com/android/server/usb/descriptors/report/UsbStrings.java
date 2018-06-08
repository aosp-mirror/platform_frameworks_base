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
package com.android.server.usb.descriptors.report;

import com.android.server.usb.descriptors.UsbACInterface;
import com.android.server.usb.descriptors.UsbASFormat;
import com.android.server.usb.descriptors.UsbDescriptor;
import com.android.server.usb.descriptors.UsbTerminalTypes;

import java.util.HashMap;

/**
 * @hide
 * A class to provide human-readable strings for various USB constants.
 */
public final class UsbStrings {
    private static final String TAG = "UsbStrings";

    private static HashMap<Byte, String> sDescriptorNames;
    private static HashMap<Byte, String> sACControlInterfaceNames;
    private static HashMap<Byte, String> sACStreamingInterfaceNames;
    private static HashMap<Integer, String> sClassNames;
    private static HashMap<Integer, String> sAudioSubclassNames;
    private static HashMap<Integer, String> sAudioEncodingNames;
    private static HashMap<Integer, String> sTerminalNames;
    private static HashMap<Integer, String> sFormatNames;

    static {
        allocUsbStrings();
    }

    private static void initDescriptorNames() {
        sDescriptorNames = new HashMap<Byte, String>();
        sDescriptorNames.put(UsbDescriptor.DESCRIPTORTYPE_DEVICE, "Device");
        sDescriptorNames.put(UsbDescriptor.DESCRIPTORTYPE_CONFIG, "Config");
        sDescriptorNames.put(UsbDescriptor.DESCRIPTORTYPE_STRING, "String");
        sDescriptorNames.put(UsbDescriptor.DESCRIPTORTYPE_INTERFACE, "Interface");
        sDescriptorNames.put(UsbDescriptor.DESCRIPTORTYPE_ENDPOINT, "Endpoint");
        sDescriptorNames.put(UsbDescriptor.DESCRIPTORTYPE_BOS, "BOS (whatever that means)");
        sDescriptorNames.put(UsbDescriptor.DESCRIPTORTYPE_INTERFACEASSOC,
                "Interface Association");
        sDescriptorNames.put(UsbDescriptor.DESCRIPTORTYPE_CAPABILITY, "Capability");
        sDescriptorNames.put(UsbDescriptor.DESCRIPTORTYPE_HID, "HID");
        sDescriptorNames.put(UsbDescriptor.DESCRIPTORTYPE_REPORT, "Report");
        sDescriptorNames.put(UsbDescriptor.DESCRIPTORTYPE_PHYSICAL, "Physical");
        sDescriptorNames.put(UsbDescriptor.DESCRIPTORTYPE_AUDIO_INTERFACE,
                "Audio Class Interface");
        sDescriptorNames.put(UsbDescriptor.DESCRIPTORTYPE_AUDIO_ENDPOINT, "Audio Class Endpoint");
        sDescriptorNames.put(UsbDescriptor.DESCRIPTORTYPE_HUB, "Hub");
        sDescriptorNames.put(UsbDescriptor.DESCRIPTORTYPE_SUPERSPEED_HUB, "Superspeed Hub");
        sDescriptorNames.put(UsbDescriptor.DESCRIPTORTYPE_ENDPOINT_COMPANION,
                "Endpoint Companion");
    }

    private static void initACControlInterfaceNames() {
        sACControlInterfaceNames = new HashMap<Byte, String>();
        sACControlInterfaceNames.put(UsbACInterface.ACI_UNDEFINED, "Undefined");
        sACControlInterfaceNames.put(UsbACInterface.ACI_HEADER, "Header");
        sACControlInterfaceNames.put(UsbACInterface.ACI_INPUT_TERMINAL, "Input Terminal");
        sACControlInterfaceNames.put(UsbACInterface.ACI_OUTPUT_TERMINAL, "Output Terminal");
        sACControlInterfaceNames.put(UsbACInterface.ACI_MIXER_UNIT, "Mixer Unit");
        sACControlInterfaceNames.put(UsbACInterface.ACI_SELECTOR_UNIT, "Selector Unit");
        sACControlInterfaceNames.put(UsbACInterface.ACI_FEATURE_UNIT, "Feature Unit");
        sACControlInterfaceNames.put(UsbACInterface.ACI_PROCESSING_UNIT, "Processing Unit");
        sACControlInterfaceNames.put(UsbACInterface.ACI_EXTENSION_UNIT, "Extension Unit");
        sACControlInterfaceNames.put(UsbACInterface.ACI_CLOCK_SOURCE, "Clock Source");
        sACControlInterfaceNames.put(UsbACInterface.ACI_CLOCK_SELECTOR, "Clock Selector");
        sACControlInterfaceNames.put(UsbACInterface.ACI_CLOCK_MULTIPLIER, "Clock Multiplier");
        sACControlInterfaceNames.put(UsbACInterface.ACI_SAMPLE_RATE_CONVERTER,
                "Sample Rate Converter");
    }

    private static void initACStreamingInterfaceNames() {
        sACStreamingInterfaceNames = new HashMap<Byte, String>();
        sACStreamingInterfaceNames.put(UsbACInterface.ASI_UNDEFINED, "Undefined");
        sACStreamingInterfaceNames.put(UsbACInterface.ASI_GENERAL, "General");
        sACStreamingInterfaceNames.put(UsbACInterface.ASI_FORMAT_TYPE, "Format Type");
        sACStreamingInterfaceNames.put(UsbACInterface.ASI_FORMAT_SPECIFIC, "Format Specific");
    }

    private static void initClassNames() {
        sClassNames = new HashMap<Integer, String>();
        sClassNames.put(UsbDescriptor.CLASSID_DEVICE, "Device");
        sClassNames.put(UsbDescriptor.CLASSID_AUDIO, "Audio");
        sClassNames.put(UsbDescriptor.CLASSID_COM, "Communications");
        sClassNames.put(UsbDescriptor.CLASSID_HID, "HID");
        sClassNames.put(UsbDescriptor.CLASSID_PHYSICAL, "Physical");
        sClassNames.put(UsbDescriptor.CLASSID_IMAGE, "Image");
        sClassNames.put(UsbDescriptor.CLASSID_PRINTER, "Printer");
        sClassNames.put(UsbDescriptor.CLASSID_STORAGE, "Storage");
        sClassNames.put(UsbDescriptor.CLASSID_HUB, "Hub");
        sClassNames.put(UsbDescriptor.CLASSID_CDC_CONTROL, "CDC Control");
        sClassNames.put(UsbDescriptor.CLASSID_SMART_CARD, "Smart Card");
        sClassNames.put(UsbDescriptor.CLASSID_SECURITY, "Security");
        sClassNames.put(UsbDescriptor.CLASSID_VIDEO, "Video");
        sClassNames.put(UsbDescriptor.CLASSID_HEALTHCARE, "Healthcare");
        sClassNames.put(UsbDescriptor.CLASSID_AUDIOVIDEO, "Audio/Video");
        sClassNames.put(UsbDescriptor.CLASSID_BILLBOARD, "Billboard");
        sClassNames.put(UsbDescriptor.CLASSID_TYPECBRIDGE, "Type C Bridge");
        sClassNames.put(UsbDescriptor.CLASSID_DIAGNOSTIC, "Diagnostic");
        sClassNames.put(UsbDescriptor.CLASSID_WIRELESS, "Wireless");
        sClassNames.put(UsbDescriptor.CLASSID_MISC, "Misc");
        sClassNames.put(UsbDescriptor.CLASSID_APPSPECIFIC, "Application Specific");
        sClassNames.put(UsbDescriptor.CLASSID_VENDSPECIFIC, "Vendor Specific");
    }

    private static void initAudioSubclassNames() {
        sAudioSubclassNames = new HashMap<Integer, String>();
        sAudioSubclassNames.put(UsbDescriptor.AUDIO_SUBCLASS_UNDEFINED, "Undefinded");
        sAudioSubclassNames.put(UsbDescriptor.AUDIO_AUDIOCONTROL, "Audio Control");
        sAudioSubclassNames.put(UsbDescriptor.AUDIO_AUDIOSTREAMING, "Audio Streaming");
        sAudioSubclassNames.put(UsbDescriptor.AUDIO_MIDISTREAMING, "MIDI Streaming");
    }

    private static void initAudioEncodingNames() {
        sAudioEncodingNames = new HashMap<Integer, String>();
        sAudioEncodingNames.put(UsbACInterface.FORMAT_I_UNDEFINED, "Format I Undefined");
        sAudioEncodingNames.put(UsbACInterface.FORMAT_I_PCM, "Format I PCM");
        sAudioEncodingNames.put(UsbACInterface.FORMAT_I_PCM8, "Format I PCM8");
        sAudioEncodingNames.put(UsbACInterface.FORMAT_I_IEEE_FLOAT, "Format I FLOAT");
        sAudioEncodingNames.put(UsbACInterface.FORMAT_I_ALAW, "Format I ALAW");
        sAudioEncodingNames.put(UsbACInterface.FORMAT_I_MULAW, "Format I MuLAW");
        sAudioEncodingNames.put(UsbACInterface.FORMAT_II_UNDEFINED, "FORMAT_II Undefined");
        sAudioEncodingNames.put(UsbACInterface.FORMAT_II_MPEG, "FORMAT_II MPEG");
        sAudioEncodingNames.put(UsbACInterface.FORMAT_II_AC3, "FORMAT_II AC3");
        sAudioEncodingNames.put(UsbACInterface.FORMAT_III_UNDEFINED, "FORMAT_III Undefined");
        sAudioEncodingNames.put(UsbACInterface.FORMAT_III_IEC1937AC3, "FORMAT_III IEC1937 AC3");
        sAudioEncodingNames.put(UsbACInterface.FORMAT_III_IEC1937_MPEG1_Layer1,
                "FORMAT_III MPEG1 Layer 1");
        sAudioEncodingNames.put(UsbACInterface.FORMAT_III_IEC1937_MPEG1_Layer2,
                "FORMAT_III MPEG1 Layer 2");
        sAudioEncodingNames.put(UsbACInterface.FORMAT_III_IEC1937_MPEG2_EXT,
                "FORMAT_III MPEG2 EXT");
        sAudioEncodingNames.put(UsbACInterface.FORMAT_III_IEC1937_MPEG2_Layer1LS,
                "FORMAT_III MPEG2 Layer1LS");
    }

    private static void initTerminalNames() {
        sTerminalNames = new HashMap<Integer, String>();
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_USB_STREAMING, "USB Streaming");

        sTerminalNames.put(UsbTerminalTypes.TERMINAL_IN_UNDEFINED, "Undefined");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_IN_MIC, "Microphone");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_IN_DESKTOP_MIC, "Desktop Microphone");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_IN_PERSONAL_MIC,
                "Personal (headset) Microphone");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_IN_OMNI_MIC, "Omni Microphone");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_IN_MIC_ARRAY, "Microphone Array");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_IN_PROC_MIC_ARRAY,
                "Proecessing Microphone Array");

        sTerminalNames.put(UsbTerminalTypes.TERMINAL_OUT_UNDEFINED, "Undefined");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_OUT_SPEAKER, "Speaker");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_OUT_HEADPHONES, "Headphones");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_OUT_HEADMOUNTED, "Head Mounted Speaker");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_OUT_DESKTOPSPEAKER, "Desktop Speaker");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_OUT_ROOMSPEAKER, "Room Speaker");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_OUT_COMSPEAKER, "Communications Speaker");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_OUT_LFSPEAKER, "Low Frequency Speaker");

        sTerminalNames.put(UsbTerminalTypes.TERMINAL_BIDIR_UNDEFINED, "Undefined");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_BIDIR_HANDSET, "Handset");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_BIDIR_HEADSET, "Headset");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_BIDIR_SKRPHONE, "Speaker Phone");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_BIDIR_SKRPHONE_SUPRESS,
                "Speaker Phone (echo supressing)");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_BIDIR_SKRPHONE_CANCEL,
                "Speaker Phone (echo canceling)");

        sTerminalNames.put(UsbTerminalTypes.TERMINAL_TELE_UNDEFINED, "Undefined");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_TELE_PHONELINE, "Phone Line");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_TELE_PHONE, "Telephone");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_TELE_DOWNLINEPHONE, "Down Line Phone");

        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EXTERN_UNDEFINED, "Undefined");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EXTERN_ANALOG, "Analog Connector");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EXTERN_DIGITAL, "Digital Connector");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EXTERN_LINE, "Line Connector");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EXTERN_LEGACY, "Legacy Audio Connector");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EXTERN_SPIDF, "S/PIDF Interface");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EXTERN_1394DA, "1394 Audio");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EXTERN_1394DV, "1394 Audio/Video");

        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_UNDEFINED, "Undefined");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_CALNOISE, "Calibration Nose");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_EQNOISE, "EQ Noise");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_CDPLAYER, "CD Player");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_DAT, "DAT");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_DCC, "DCC");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_MINIDISK, "Mini Disk");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_ANALOGTAPE, "Analog Tap");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_PHONOGRAPH, "Phonograph");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_VCRAUDIO, "VCR Audio");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_VIDDISKAUDIO, "Video Disk Audio");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_DVDAUDIO, "DVD Audio");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_TVAUDIO, "TV Audio");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_SATELLITEAUDIO, "Satellite Audio");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_CABLEAUDIO, "Cable Tuner Audio");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_DSSAUDIO, "DSS Audio");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_RADIOTRANSMITTER, "Radio Transmitter");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_MULTITRACK, "Multitrack Recorder");
        sTerminalNames.put(UsbTerminalTypes.TERMINAL_EMBED_SYNTHESIZER, "Synthesizer");
    }

    /**
     * Retrieves the terminal name for the specified terminal type ID.
     */
    public static String getTerminalName(int terminalType) {
        String name = sTerminalNames.get(terminalType);
        return name != null
                ? name
                : "Unknown Terminal Type 0x" + Integer.toHexString(terminalType);
    }

    private static void initFormatNames() {
        sFormatNames = new HashMap<Integer, String>();

        sFormatNames.put((int) UsbASFormat.FORMAT_TYPE_I, "FORMAT_TYPE_I");
        sFormatNames.put((int) UsbASFormat.FORMAT_TYPE_II, "FORMAT_TYPE_II");
        sFormatNames.put((int) UsbASFormat.FORMAT_TYPE_III, "FORMAT_TYPE_III");
        sFormatNames.put((int) UsbASFormat.FORMAT_TYPE_IV, "FORMAT_TYPE_IV");
        sFormatNames.put((int) UsbASFormat.EXT_FORMAT_TYPE_I, "EXT_FORMAT_TYPE_I");
        sFormatNames.put((int) UsbASFormat.EXT_FORMAT_TYPE_II, "EXT_FORMAT_TYPE_II");
        sFormatNames.put((int) UsbASFormat.EXT_FORMAT_TYPE_III, "EXT_FORMAT_TYPE_III");
    }

    /**
     * Retrieves the name for the specified format (encoding) type ID.
     */
    public static String getFormatName(int format) {
        String name = sFormatNames.get(format);
        return name != null
                ? name
                : "Unknown Format Type 0x" + Integer.toHexString(format);
    }

    /**
     * Initializes string tables.
     */
    private static void allocUsbStrings() {
        initDescriptorNames();
        initACControlInterfaceNames();
        initACStreamingInterfaceNames();
        initClassNames();
        initAudioSubclassNames();
        initAudioEncodingNames();
        initTerminalNames();
        initFormatNames();
    }

    /**
     * Retrieves the name for the specified descriptor ID.
     */
    public static String getDescriptorName(byte descriptorID) {
        String name = sDescriptorNames.get(descriptorID);
        int iDescriptorID = descriptorID & 0xFF;
        return name != null
            ? name
            : "Unknown Descriptor [0x" + Integer.toHexString(iDescriptorID)
                + ":" + iDescriptorID + "]";
    }

    /**
     * Retrieves the audio-class control interface name for the specified audio-class subtype.
     */
    public static String getACControlInterfaceName(byte subtype) {
        String name = sACControlInterfaceNames.get(subtype);
        int iSubType = subtype & 0xFF;
        return name != null
                ? name
                : "Unknown subtype [0x" + Integer.toHexString(iSubType)
                    + ":" + iSubType + "]";
    }

    /**
     * Retrieves the audio-class streaming interface name for the specified audio-class subtype.
     */
    public static String getACStreamingInterfaceName(byte subtype) {
        String name = sACStreamingInterfaceNames.get(subtype);
        int iSubType = subtype & 0xFF;
        return name != null
                ? name
                : "Unknown Subtype [0x" + Integer.toHexString(iSubType) + ":"
                    + iSubType + "]";
    }

    /**
     * Retrieves the name for the specified USB class ID.
     */
    public static String getClassName(int classID) {
        String name = sClassNames.get(classID);
        int iClassID = classID & 0xFF;
        return name != null
                ? name
                : "Unknown Class ID [0x" + Integer.toHexString(iClassID) + ":"
                    + iClassID + "]";
    }

    /**
     * Retrieves the name for the specified USB audio subclass ID.
     */
    public static String getAudioSubclassName(int subClassID) {
        String name = sAudioSubclassNames.get(subClassID);
        int iSubclassID = subClassID & 0xFF;
        return name != null
                ? name
                : "Unknown Audio Subclass [0x" + Integer.toHexString(iSubclassID) + ":"
                    + iSubclassID + "]";
    }

    /**
     * Retrieves the name for the specified USB audio format ID.
     */
    public static String getAudioFormatName(int formatID) {
        String name = sAudioEncodingNames.get(formatID);
        return name != null
                ? name
                : "Unknown Format (encoding) ID [0x" + Integer.toHexString(formatID) + ":"
                    + formatID + "]";
    }

    /**
     * Retrieves the name for the specified USB audio interface subclass ID.
     */
    public static String getACInterfaceSubclassName(int subClassID) {
        return subClassID == UsbDescriptor.AUDIO_AUDIOCONTROL ? "AC Control" : "AC Streaming";
    }
}
