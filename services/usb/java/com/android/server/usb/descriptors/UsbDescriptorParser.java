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

import java.util.ArrayList;

/**
 * @hide
 * Class for parsing a binary stream of USB Descriptors.
 */
public final class UsbDescriptorParser {
    private static final String TAG = "UsbDescriptorParser";

    // Descriptor Objects
    private ArrayList<UsbDescriptor> mDescriptors = new ArrayList<UsbDescriptor>();

    private UsbDeviceDescriptor mDeviceDescriptor;
    private UsbInterfaceDescriptor mCurInterfaceDescriptor;

    // The AudioClass spec implemented by the AudioClass Interfaces
    // This may well be different than the overall USB Spec.
    // Obtained from the first AudioClass Header descriptor.
    private int mACInterfacesSpec = UsbDeviceDescriptor.USBSPEC_1_0;

    public UsbDescriptorParser() {}

    /**
     * @return the USB Spec value associated with the Device descriptor for the
     * descriptors stream being parsed.
     *
     * @throws IllegalArgumentException
     */
    public int getUsbSpec() {
        if (mDeviceDescriptor != null) {
            return mDeviceDescriptor.getSpec();
        } else {
            throw new IllegalArgumentException();
        }
    }

    public void setACInterfaceSpec(int spec) {
        mACInterfacesSpec = spec;
    }

    public int getACInterfaceSpec() {
        return mACInterfacesSpec;
    }
    /**
     * The probability (as returned by getHeadsetProbability() at which we conclude
     * the peripheral is a headset.
     */
    private static final float IN_HEADSET_TRIGGER = 0.75f;
    private static final float OUT_HEADSET_TRIGGER = 0.75f;

    private UsbDescriptor allocDescriptor(ByteStream stream) {
        stream.resetReadCount();

        int length = stream.getUnsignedByte();
        byte type = stream.getByte();

        UsbDescriptor descriptor = null;
        switch (type) {
            /*
             * Standard
             */
            case UsbDescriptor.DESCRIPTORTYPE_DEVICE:
                descriptor = mDeviceDescriptor = new UsbDeviceDescriptor(length, type);
                break;

            case UsbDescriptor.DESCRIPTORTYPE_CONFIG:
                descriptor = new UsbConfigDescriptor(length, type);
                break;

            case UsbDescriptor.DESCRIPTORTYPE_INTERFACE:
                descriptor = mCurInterfaceDescriptor = new UsbInterfaceDescriptor(length, type);
                break;

            case UsbDescriptor.DESCRIPTORTYPE_ENDPOINT:
                descriptor = new UsbEndpointDescriptor(length, type);
                break;

            /*
             * HID
             */
            case UsbDescriptor.DESCRIPTORTYPE_HID:
                descriptor = new UsbHIDDescriptor(length, type);
                break;

            /*
             * Other
             */
            case UsbDescriptor.DESCRIPTORTYPE_INTERFACEASSOC:
                descriptor = new UsbInterfaceAssoc(length, type);
                break;

            /*
             * Audio Class Specific
             */
            case UsbDescriptor.DESCRIPTORTYPE_AUDIO_INTERFACE:
                descriptor = UsbACInterface.allocDescriptor(this, stream, length, type);
                break;

            case UsbDescriptor.DESCRIPTORTYPE_AUDIO_ENDPOINT:
                descriptor = UsbACEndpoint.allocDescriptor(this, length, type);
                break;

            default:
                break;
        }

        if (descriptor == null) {
            // Unknown Descriptor
            Log.i(TAG, "Unknown Descriptor len: " + length + " type:0x"
                    + Integer.toHexString(type));
            descriptor = new UsbUnknown(length, type);
        }

        return descriptor;
    }

    public UsbDeviceDescriptor getDeviceDescriptor() {
        return mDeviceDescriptor;
    }

    public UsbInterfaceDescriptor getCurInterface() {
        return mCurInterfaceDescriptor;
    }

    /**
     * @hide
     */
    public void parseDescriptors(byte[] descriptors) {
        mDescriptors.clear();

        ByteStream stream = new ByteStream(descriptors);
        while (stream.available() > 0) {
            UsbDescriptor descriptor = null;
            try {
                descriptor = allocDescriptor(stream);
            } catch (Exception ex) {
                Log.e(TAG, "Exception allocating USB descriptor.", ex);
            }

            if (descriptor != null) {
                // Parse
                try {
                    descriptor.parseRawDescriptors(stream);

                    // Clean up
                    descriptor.postParse(stream);
                } catch (Exception ex) {
                    Log.e(TAG, "Exception parsing USB descriptors.", ex);

                    // Clean up
                    descriptor.setStatus(UsbDescriptor.STATUS_PARSE_EXCEPTION);
                } finally {
                    mDescriptors.add(descriptor);
                }
            }
        }
    }

    /**
     * @hide
     */
    public boolean parseDevice(String deviceAddr) {
        byte[] rawDescriptors = getRawDescriptors(deviceAddr);
        if (rawDescriptors != null) {
            parseDescriptors(rawDescriptors);
            return true;
        }
        return false;
    }

    private native byte[] getRawDescriptors(String deviceAddr);

    public int getParsingSpec() {
        return mDeviceDescriptor != null ? mDeviceDescriptor.getSpec() : 0;
    }

    public ArrayList<UsbDescriptor> getDescriptors() {
        return mDescriptors;
    }

    /**
     * @hide
     */
    public ArrayList<UsbDescriptor> getDescriptors(byte type) {
        ArrayList<UsbDescriptor> list = new ArrayList<UsbDescriptor>();
        for (UsbDescriptor descriptor : mDescriptors) {
            if (descriptor.getType() == type) {
                list.add(descriptor);
            }
        }
        return list;
    }

    /**
     * @hide
     */
    public ArrayList<UsbDescriptor> getInterfaceDescriptorsForClass(byte usbClass) {
        ArrayList<UsbDescriptor> list = new ArrayList<UsbDescriptor>();
        for (UsbDescriptor descriptor : mDescriptors) {
            // ensure that this isn't an unrecognized DESCRIPTORTYPE_INTERFACE
            if (descriptor.getType() == UsbDescriptor.DESCRIPTORTYPE_INTERFACE) {
                if (descriptor instanceof UsbInterfaceDescriptor) {
                    UsbInterfaceDescriptor intrDesc = (UsbInterfaceDescriptor) descriptor;
                    if (intrDesc.getUsbClass() == usbClass) {
                        list.add(descriptor);
                    }
                } else {
                    Log.w(TAG, "Unrecognized Interface l: " + descriptor.getLength()
                            + " t:0x" + Integer.toHexString(descriptor.getType()));
                }
            }
        }
        return list;
    }

    /**
     * @hide
     */
    public ArrayList<UsbDescriptor> getACInterfaceDescriptors(byte subtype, byte subclass) {
        ArrayList<UsbDescriptor> list = new ArrayList<UsbDescriptor>();
        for (UsbDescriptor descriptor : mDescriptors) {
            if (descriptor.getType() == UsbDescriptor.DESCRIPTORTYPE_AUDIO_INTERFACE) {
                // ensure that this isn't an unrecognized DESCRIPTORTYPE_AUDIO_INTERFACE
                if (descriptor instanceof UsbACInterface) {
                    UsbACInterface acDescriptor = (UsbACInterface) descriptor;
                    if (acDescriptor.getSubtype() == subtype
                            && acDescriptor.getSubclass() == subclass) {
                        list.add(descriptor);
                    }
                } else {
                    Log.w(TAG, "Unrecognized Audio Interface l: " + descriptor.getLength()
                            + " t:0x" + Integer.toHexString(descriptor.getType()));
                }
            }
        }
        return list;
    }

    /**
     * @hide
     */
    public boolean hasHIDDescriptor() {
        ArrayList<UsbDescriptor> descriptors =
                getInterfaceDescriptorsForClass(UsbDescriptor.CLASSID_HID);
        return !descriptors.isEmpty();
    }

    /**
     * @hide
     */
    public boolean hasMIDIInterface() {
        ArrayList<UsbDescriptor> descriptors =
                getInterfaceDescriptorsForClass(UsbDescriptor.CLASSID_AUDIO);
        for (UsbDescriptor descriptor : descriptors) {
            // enusure that this isn't an unrecognized interface descriptor
            if (descriptor instanceof UsbInterfaceDescriptor) {
                UsbInterfaceDescriptor interfaceDescr = (UsbInterfaceDescriptor) descriptor;
                if (interfaceDescr.getUsbSubclass() == UsbDescriptor.AUDIO_MIDISTREAMING) {
                    return true;
                }
            } else {
                Log.w(TAG, "Undefined Audio Class Interface l: " + descriptor.getLength()
                        + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }
        return false;
    }

    /**
     * @hide
     */
    public float getInputHeadsetProbability() {
        if (hasMIDIInterface()) {
            return 0.0f;
        }

        float probability = 0.0f;
        ArrayList<UsbDescriptor> acDescriptors;

        // Look for a microphone
        boolean hasMic = false;
        acDescriptors = getACInterfaceDescriptors(UsbACInterface.ACI_INPUT_TERMINAL,
                UsbACInterface.AUDIO_AUDIOCONTROL);
        for (UsbDescriptor descriptor : acDescriptors) {
            if (descriptor instanceof UsbACTerminal) {
                UsbACTerminal inDescr = (UsbACTerminal) descriptor;
                if (inDescr.getTerminalType() == UsbTerminalTypes.TERMINAL_IN_MIC
                        || inDescr.getTerminalType() == UsbTerminalTypes.TERMINAL_BIDIR_HEADSET
                        || inDescr.getTerminalType() == UsbTerminalTypes.TERMINAL_BIDIR_UNDEFINED
                        || inDescr.getTerminalType() == UsbTerminalTypes.TERMINAL_EXTERN_LINE) {
                    hasMic = true;
                    break;
                }
            } else {
                Log.w(TAG, "Undefined Audio Input terminal l: " + descriptor.getLength()
                        + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }

        // Look for a "speaker"
        boolean hasSpeaker = false;
        acDescriptors =
                getACInterfaceDescriptors(UsbACInterface.ACI_OUTPUT_TERMINAL,
                        UsbACInterface.AUDIO_AUDIOCONTROL);
        for (UsbDescriptor descriptor : acDescriptors) {
            if (descriptor instanceof UsbACTerminal) {
                UsbACTerminal outDescr = (UsbACTerminal) descriptor;
                if (outDescr.getTerminalType() == UsbTerminalTypes.TERMINAL_OUT_SPEAKER
                        || outDescr.getTerminalType()
                            == UsbTerminalTypes.TERMINAL_OUT_HEADPHONES
                        || outDescr.getTerminalType() == UsbTerminalTypes.TERMINAL_BIDIR_HEADSET) {
                    hasSpeaker = true;
                    break;
                }
            } else {
                Log.w(TAG, "Undefined Audio Output terminal l: " + descriptor.getLength()
                        + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }

        if (hasMic && hasSpeaker) {
            probability += 0.75f;
        }

        if (hasMic && hasHIDDescriptor()) {
            probability += 0.25f;
        }

        return probability;
    }

    /**
     * getInputHeadsetProbability() reports a probability of a USB Input peripheral being a
     * headset. The probability range is between 0.0f (definitely NOT a headset) and
     * 1.0f (definitely IS a headset). A probability of 0.75f seems sufficient
     * to count on the peripheral being a headset.
     */
    public boolean isInputHeadset() {
        // TEMP
        Log.i(TAG, "---- isInputHeadset() prob:" + (getInputHeadsetProbability() * 100f) + "%");
        return getInputHeadsetProbability() >= IN_HEADSET_TRIGGER;
    }

    /**
     * @hide
     */
    public float getOutputHeadsetProbability() {
        if (hasMIDIInterface()) {
            return 0.0f;
        }

        float probability = 0.0f;
        ArrayList<UsbDescriptor> acDescriptors;

        // Look for a "speaker"
        boolean hasSpeaker = false;
        acDescriptors =
                getACInterfaceDescriptors(UsbACInterface.ACI_OUTPUT_TERMINAL,
                        UsbACInterface.AUDIO_AUDIOCONTROL);
        for (UsbDescriptor descriptor : acDescriptors) {
            if (descriptor instanceof UsbACTerminal) {
                UsbACTerminal outDescr = (UsbACTerminal) descriptor;
                if (outDescr.getTerminalType() == UsbTerminalTypes.TERMINAL_OUT_SPEAKER
                        || outDescr.getTerminalType()
                            == UsbTerminalTypes.TERMINAL_OUT_HEADPHONES
                        || outDescr.getTerminalType() == UsbTerminalTypes.TERMINAL_BIDIR_HEADSET) {
                    hasSpeaker = true;
                    break;
                }
            } else {
                Log.w(TAG, "Undefined Audio Output terminal l: " + descriptor.getLength()
                        + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }

        if (hasSpeaker) {
            probability += 0.75f;
        }

        if (hasSpeaker && hasHIDDescriptor()) {
            probability += 0.25f;
        }

        return probability;
    }

    /**
     * getOutputHeadsetProbability() reports a probability of a USB Output peripheral being a
     * headset. The probability range is between 0.0f (definitely NOT a headset) and
     * 1.0f (definitely IS a headset). A probability of 0.75f seems sufficient
     * to count on the peripheral being a headset.
     */
    public boolean isOutputHeadset() {
        // TEMP
        Log.i(TAG, "---- isOutputHeadset() prob:" + (getOutputHeadsetProbability() * 100f) + "%");
        return getOutputHeadsetProbability() >= OUT_HEADSET_TRIGGER;
    }

}
