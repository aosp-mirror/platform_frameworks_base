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

import android.util.Log;

import com.android.server.usb.descriptors.report.ReportCanvas;
import com.android.server.usb.descriptors.report.UsbStrings;

/**
 * @hide
 * An audio class-specific Interface.
 * see audio10.pdf section 4.3.2
 */
public abstract class UsbACInterface extends UsbDescriptor {
    private static final String TAG = "UsbACInterface";

    // Audio Control Subtypes
    public static final byte ACI_UNDEFINED = 0;
    public static final byte ACI_HEADER = 1;
    public static final byte ACI_INPUT_TERMINAL = 2;
    public static final byte ACI_OUTPUT_TERMINAL = 3;
    public static final byte ACI_MIXER_UNIT = 4;
    public static final byte ACI_SELECTOR_UNIT = 5;
    public static final byte ACI_FEATURE_UNIT = 6;
    public static final byte ACI_PROCESSING_UNIT = 7;
    public static final byte ACI_EXTENSION_UNIT = 8;
    // Not handled yet
    public static final byte ACI_CLOCK_SOURCE = 0x0A;
    public static final byte ACI_CLOCK_SELECTOR = 0x0B;
    public static final byte ACI_CLOCK_MULTIPLIER = 0x0C;
    public static final byte ACI_SAMPLE_RATE_CONVERTER =  0x0D;

    // Audio Streaming Subtypes
    public static final byte ASI_UNDEFINED = 0;
    public static final byte ASI_GENERAL = 1;
    public static final byte ASI_FORMAT_TYPE = 2;
    public static final byte ASI_FORMAT_SPECIFIC = 3;

    // MIDI Streaming Subtypes
    public static final byte MSI_UNDEFINED = 0;
    public static final byte MSI_HEADER = 1;
    public static final byte MSI_IN_JACK = 2;
    public static final byte MSI_OUT_JACK = 3;
    public static final byte MSI_ELEMENT = 4;

    // Sample format IDs (encodings)
    // FORMAT_I
    public static final int FORMAT_I_UNDEFINED     = 0x0000;
    public static final int FORMAT_I_PCM           = 0x0001;
    public static final int FORMAT_I_PCM8          = 0x0002;
    public static final int FORMAT_I_IEEE_FLOAT    = 0x0003;
    public static final int FORMAT_I_ALAW          = 0x0004;
    public static final int FORMAT_I_MULAW         = 0x0005;
    // FORMAT_II
    public static final int FORMAT_II_UNDEFINED    = 0x1000;
    public static final int FORMAT_II_MPEG         = 0x1001;
    public static final int FORMAT_II_AC3          = 0x1002;
    // FORMAT_III
    public static final int FORMAT_III_UNDEFINED              = 0x2000;
    public static final int FORMAT_III_IEC1937AC3             = 0x2001;
    public static final int FORMAT_III_IEC1937_MPEG1_Layer1   = 0x2002;
    public static final int FORMAT_III_IEC1937_MPEG1_Layer2   = 0x2003;
    public static final int FORMAT_III_IEC1937_MPEG2_EXT      = 0x2004;
    public static final int FORMAT_III_IEC1937_MPEG2_Layer1LS = 0x2005;

    protected final byte mSubtype;  // 2:1 HEADER descriptor subtype
    protected final int mSubclass;  // from the mSubclass member of the
                                    // "enclosing" Interface Descriptor

    public UsbACInterface(int length, byte type, byte subtype, int subclass) {
        super(length, type);
        mSubtype = subtype;
        mSubclass = subclass;
    }

    public byte getSubtype() {
        return mSubtype;
    }

    public int getSubclass() {
        return mSubclass;
    }

    private static UsbDescriptor allocAudioControlDescriptor(UsbDescriptorParser parser,
            ByteStream stream, int length, byte type, byte subtype, int subClass) {
        switch (subtype) {
            case ACI_HEADER:
            {
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> ACI_HEADER");
                }
                int acInterfaceSpec = stream.unpackUsbShort();
                parser.setACInterfaceSpec(acInterfaceSpec);
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, "  acInterfaceSpec:0x" + Integer.toHexString(acInterfaceSpec));
                }
                if (acInterfaceSpec == UsbDeviceDescriptor.USBSPEC_2_0) {
                    return new Usb20ACHeader(length, type, subtype, subClass, acInterfaceSpec);
                } else {
                    return new Usb10ACHeader(length, type, subtype, subClass, acInterfaceSpec);
                }
            }

            case ACI_INPUT_TERMINAL:
            {
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> ACI_INPUT_TERMINAL");
                }
                int acInterfaceSpec = parser.getACInterfaceSpec();
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, "  acInterfaceSpec:0x" + Integer.toHexString(acInterfaceSpec));
                }
                if (acInterfaceSpec == UsbDeviceDescriptor.USBSPEC_2_0) {
                    return new Usb20ACInputTerminal(length, type, subtype, subClass);
                } else {
                    return new Usb10ACInputTerminal(length, type, subtype, subClass);
                }
            }

            case ACI_OUTPUT_TERMINAL:
            {
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> ACI_OUTPUT_TERMINAL");
                }
                int acInterfaceSpec = parser.getACInterfaceSpec();
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, "  acInterfaceSpec:0x" + Integer.toHexString(acInterfaceSpec));
                }
                if (acInterfaceSpec == UsbDeviceDescriptor.USBSPEC_2_0) {
                    return new Usb20ACOutputTerminal(length, type, subtype, subClass);
                } else {
                    return new Usb10ACOutputTerminal(length, type, subtype, subClass);
                }
            }

            case ACI_SELECTOR_UNIT:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> ACI_SELECTOR_UNIT");
                }
                return new UsbACSelectorUnit(length, type, subtype, subClass);

            case ACI_FEATURE_UNIT:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> ACI_FEATURE_UNIT");
                }
                return new UsbACFeatureUnit(length, type, subtype, subClass);

            case ACI_MIXER_UNIT:
            {
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, " ---> ACI_MIXER_UNIT");
                }
                int acInterfaceSpec = parser.getACInterfaceSpec();
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, "  acInterfaceSpec:0x" + Integer.toHexString(acInterfaceSpec));
                }
                if (acInterfaceSpec == UsbDeviceDescriptor.USBSPEC_2_0) {
                    return new Usb20ACMixerUnit(length, type, subtype, subClass);
                } else {
                    return new Usb10ACMixerUnit(length, type, subtype, subClass);
                }
            }

            case ACI_PROCESSING_UNIT:
            case ACI_EXTENSION_UNIT:
            case ACI_UNDEFINED:
                // break; Fall through until we implement this descriptor
            default:
                Log.w(TAG, "Unknown Audio Class Interface subtype:0x"
                        + Integer.toHexString(subtype));
                return new UsbACInterfaceUnparsed(length, type, subtype, subClass);
        }
    }

    private static UsbDescriptor allocAudioStreamingDescriptor(UsbDescriptorParser parser,
            ByteStream stream, int length, byte type, byte subtype, int subClass) {
        //int spec = parser.getUsbSpec();
        int acInterfaceSpec = parser.getACInterfaceSpec();
        switch (subtype) {
            case ASI_GENERAL:
                if (acInterfaceSpec == UsbDeviceDescriptor.USBSPEC_2_0) {
                    return new Usb20ASGeneral(length, type, subtype, subClass);
                } else {
                    return new Usb10ASGeneral(length, type, subtype, subClass);
                }

            case ASI_FORMAT_TYPE:
                return UsbASFormat.allocDescriptor(parser, stream, length, type, subtype, subClass);

            case ASI_FORMAT_SPECIFIC:
            case ASI_UNDEFINED:
                // break; Fall through until we implement this descriptor
            default:
                Log.w(TAG, "Unknown Audio Streaming Interface subtype:0x"
                        + Integer.toHexString(subtype));
                return null;
        }
    }

    private static UsbDescriptor allocMidiStreamingDescriptor(int length, byte type,
            byte subtype, int subClass) {
        switch (subtype) {
            case MSI_HEADER:
                return new UsbMSMidiHeader(length, type, subtype, subClass);

            case MSI_IN_JACK:
                return new UsbMSMidiInputJack(length, type, subtype, subClass);

            case MSI_OUT_JACK:
                return new UsbMSMidiOutputJack(length, type, subtype, subClass);

            case MSI_ELEMENT:
                // break;
                // Fall through until we implement that descriptor

            case MSI_UNDEFINED:
            default:
                Log.w(TAG, "Unknown MIDI Streaming Interface subtype:0x"
                        + Integer.toHexString(subtype));
                return null;
        }
    }

    /**
     * Allocates an audio class interface subtype based on subtype and subclass.
     */
    public static UsbDescriptor allocDescriptor(UsbDescriptorParser parser, ByteStream stream,
            int length, byte type) {
        byte subtype = stream.getByte();
        UsbInterfaceDescriptor interfaceDesc = parser.getCurInterface();
        int subClass = interfaceDesc.getUsbSubclass();
        switch (subClass) {
            case AUDIO_AUDIOCONTROL:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, "  AUDIO_AUDIOCONTROL");
                }
                return allocAudioControlDescriptor(
                        parser, stream, length, type, subtype, subClass);

            case AUDIO_AUDIOSTREAMING:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, "  AUDIO_AUDIOSTREAMING");
                }
                return allocAudioStreamingDescriptor(
                        parser, stream, length, type, subtype, subClass);

            case AUDIO_MIDISTREAMING:
                if (UsbDescriptorParser.DEBUG) {
                    Log.d(TAG, "  AUDIO_MIDISTREAMING");
                }
                return allocMidiStreamingDescriptor(length, type, subtype, subClass);

            default:
                Log.w(TAG, "Unknown Audio Class Interface Subclass: 0x"
                        + Integer.toHexString(subClass));
                return null;
        }
    }

    @Override
    public void report(ReportCanvas canvas) {
        super.report(canvas);

        int subClass = getSubclass();
        String subClassName = UsbStrings.getACInterfaceSubclassName(subClass);

        byte subtype = getSubtype();
        String subTypeName = UsbStrings.getACControlInterfaceName(subtype);

        canvas.openList();
        canvas.writeListItem("Subclass: " + ReportCanvas.getHexString(subClass)
                + " " + subClassName);
        canvas.writeListItem("Subtype: " + ReportCanvas.getHexString(subtype) + " " + subTypeName);
        canvas.closeList();
    }
}
