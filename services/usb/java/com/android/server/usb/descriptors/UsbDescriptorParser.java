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

import android.hardware.usb.UsbDevice;
import android.util.Log;

import java.util.ArrayList;

/**
 * @hide
 * Class for parsing a binary stream of USB Descriptors.
 */
public final class UsbDescriptorParser {
    private static final String TAG = "UsbDescriptorParser";
    public static final boolean DEBUG = true;

    private final String mDeviceAddr;

    // Descriptor Objects
    private static final int DESCRIPTORS_ALLOC_SIZE = 128;
    private final ArrayList<UsbDescriptor> mDescriptors;

    private UsbDeviceDescriptor mDeviceDescriptor;
    private UsbConfigDescriptor mCurConfigDescriptor;
    private UsbInterfaceDescriptor mCurInterfaceDescriptor;

    // The AudioClass spec implemented by the AudioClass Interfaces
    // This may well be different than the overall USB Spec.
    // Obtained from the first AudioClass Header descriptor.
    private int mACInterfacesSpec = UsbDeviceDescriptor.USBSPEC_1_0;

    /**
     * Connect this parser to an existing set of already parsed descriptors.
     * This is useful for reporting.
     */
    public UsbDescriptorParser(String deviceAddr, ArrayList<UsbDescriptor> descriptors) {
        mDeviceAddr = deviceAddr;
        mDescriptors = descriptors;
        //TODO some error checking here....
        mDeviceDescriptor = (UsbDeviceDescriptor) descriptors.get(0);
    }

    /**
     * Connect this parser to an byte array containing unparsed (raw) device descriptors
     * to be parsed (and parse them). Useful for parsing a stored descriptor buffer.
     */
    public UsbDescriptorParser(String deviceAddr, byte[] rawDescriptors) {
        mDeviceAddr = deviceAddr;
        mDescriptors = new ArrayList<UsbDescriptor>(DESCRIPTORS_ALLOC_SIZE);
        parseDescriptors(rawDescriptors);
    }

    public String getDeviceAddr() {
        return mDeviceAddr;
    }

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

    private class UsbDescriptorsStreamFormatException extends Exception {
        String mMessage;
        UsbDescriptorsStreamFormatException(String message) {
            mMessage = message;
        }

        public String toString() {
            return "Descriptor Stream Format Exception: " + mMessage;
        }
    }

    /**
     * The probability (as returned by getHeadsetProbability() at which we conclude
     * the peripheral is a headset.
     */
    private static final float IN_HEADSET_TRIGGER = 0.75f;
    private static final float OUT_HEADSET_TRIGGER = 0.75f;

    private UsbDescriptor allocDescriptor(ByteStream stream)
            throws UsbDescriptorsStreamFormatException {
        stream.resetReadCount();

        int length = stream.getUnsignedByte();
        byte type = stream.getByte();

        UsbDescriptor.logDescriptorName(type, length);

        UsbDescriptor descriptor = null;
        switch (type) {
            /*
             * Standard
             */
            case UsbDescriptor.DESCRIPTORTYPE_DEVICE:
                descriptor = mDeviceDescriptor = new UsbDeviceDescriptor(length, type);
                break;

            case UsbDescriptor.DESCRIPTORTYPE_CONFIG:
                descriptor = mCurConfigDescriptor = new UsbConfigDescriptor(length, type);
                if (mDeviceDescriptor != null) {
                    mDeviceDescriptor.addConfigDescriptor(mCurConfigDescriptor);
                } else {
                    Log.e(TAG, "Config Descriptor found with no associated Device Descriptor!");
                    throw new UsbDescriptorsStreamFormatException(
                            "Config Descriptor found with no associated Device Descriptor!");
                }
                break;

            case UsbDescriptor.DESCRIPTORTYPE_INTERFACE:
                descriptor = mCurInterfaceDescriptor = new UsbInterfaceDescriptor(length, type);
                if (mCurConfigDescriptor != null) {
                    mCurConfigDescriptor.addInterfaceDescriptor(mCurInterfaceDescriptor);
                } else {
                    Log.e(TAG, "Interface Descriptor found with no associated Config Descriptor!");
                    throw new UsbDescriptorsStreamFormatException(
                            "Interface Descriptor found with no associated Config Descriptor!");
                }
                break;

            case UsbDescriptor.DESCRIPTORTYPE_ENDPOINT:
                descriptor = new UsbEndpointDescriptor(length, type);
                if (mCurInterfaceDescriptor != null) {
                    mCurInterfaceDescriptor.addEndpointDescriptor(
                            (UsbEndpointDescriptor) descriptor);
                } else {
                    Log.e(TAG,
                            "Endpoint Descriptor found with no associated Interface Descriptor!");
                    throw new UsbDescriptorsStreamFormatException(
                            "Endpoint Descriptor found with no associated Interface Descriptor!");
                }
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
             * Various Class Specific
             */
            case UsbDescriptor.DESCRIPTORTYPE_CLASSSPECIFIC_INTERFACE:
                if (mCurInterfaceDescriptor != null) {
                    switch (mCurInterfaceDescriptor.getUsbClass()) {
                        case UsbDescriptor.CLASSID_AUDIO:
                            descriptor = UsbACInterface.allocDescriptor(this, stream, length, type);
                            break;

                        case UsbDescriptor.CLASSID_VIDEO:
                            Log.d(TAG, "  UsbDescriptor.CLASSID_VIDEO subType:0x"
                                    + Integer.toHexString(stream.getByte()));
                            descriptor = UsbVCInterface.allocDescriptor(this, stream, length, type);
                            break;

                        case UsbDescriptor.CLASSID_AUDIOVIDEO:
                            Log.d(TAG, "  UsbDescriptor.CLASSID_AUDIOVIDEO subType:0x"
                                    + Integer.toHexString(stream.getByte()));
                            break;

                        default:
                            Log.d(TAG, "  Unparsed Class-specific Interface:0x"
                                    + Integer.toHexString(mCurInterfaceDescriptor.getUsbClass()));
                            break;
                    }
                }
                break;

            case UsbDescriptor.DESCRIPTORTYPE_CLASSSPECIFIC_ENDPOINT:
                if (mCurInterfaceDescriptor != null) {
                    switch (mCurInterfaceDescriptor.getUsbClass()) {
                        case UsbDescriptor.CLASSID_AUDIO:
                            descriptor = UsbACEndpoint.allocDescriptor(this, length, type);
                            break;
                        case UsbDescriptor.CLASSID_VIDEO:
                            Log.d(TAG, "UsbDescriptor.CLASSID_VIDEO subType:0x"
                                    + Integer.toHexString(stream.getByte()));
                            descriptor = UsbVCEndpoint.allocDescriptor(this, length, type);
                            break;

                        case UsbDescriptor.CLASSID_AUDIOVIDEO:
                            Log.d(TAG, "UsbDescriptor.CLASSID_AUDIOVIDEO subType:0x"
                                    + Integer.toHexString(stream.getByte()));
                            break;
                        default:
                            Log.d(TAG, "  Unparsed Class-specific Endpoint:0x"
                                    + Integer.toHexString(mCurInterfaceDescriptor.getUsbClass()));
                            break;
                    }
                }
                break;

            default:
                break;
        }

        if (descriptor == null) {
            // Unknown Descriptor
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
        if (DEBUG) {
            Log.d(TAG, "parseDescriptors() - end " + mDescriptors.size() + " descriptors.");
        }
    }

    public byte[] getRawDescriptors() {
        return getRawDescriptors_native(mDeviceAddr);
    }

    private native byte[] getRawDescriptors_native(String deviceAddr);

    /**
     * @hide
     */
    public String getDescriptorString(int stringId) {
        return getDescriptorString_native(mDeviceAddr, stringId);
    }

    private native String getDescriptorString_native(String deviceAddr, int stringId);

    public int getParsingSpec() {
        return mDeviceDescriptor != null ? mDeviceDescriptor.getSpec() : 0;
    }

    public ArrayList<UsbDescriptor> getDescriptors() {
        return mDescriptors;
    }

    /**
     * @hide
     */
    public UsbDevice.Builder toAndroidUsbDevice() {
        if (mDeviceDescriptor == null) {
            Log.e(TAG, "toAndroidUsbDevice() ERROR - No Device Descriptor");
            return null;
        }

        UsbDevice.Builder device = mDeviceDescriptor.toAndroid(this);
        if (device == null) {
            Log.e(TAG, "toAndroidUsbDevice() ERROR Creating Device");
        }
        return device;
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
    public ArrayList<UsbDescriptor> getInterfaceDescriptorsForClass(int usbClass) {
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
    public ArrayList<UsbDescriptor> getACInterfaceDescriptors(byte subtype, int subclass) {
        ArrayList<UsbDescriptor> list = new ArrayList<UsbDescriptor>();
        for (UsbDescriptor descriptor : mDescriptors) {
            if (descriptor.getType() == UsbDescriptor.DESCRIPTORTYPE_CLASSSPECIFIC_INTERFACE) {
                // ensure that this isn't an unrecognized DESCRIPTORTYPE_CLASSSPECIFIC_INTERFACE
                if (descriptor instanceof UsbACInterface) {
                    UsbACInterface acDescriptor = (UsbACInterface) descriptor;
                    if (acDescriptor.getSubtype() == subtype
                            && acDescriptor.getSubclass() == subclass) {
                        list.add(descriptor);
                    }
                } else {
                    Log.w(TAG, "Unrecognized Audio Interface len: " + descriptor.getLength()
                            + " type:0x" + Integer.toHexString(descriptor.getType()));
                }
            }
        }
        return list;
    }

    /*
     * Attribute predicates
     */
    /**
     * @hide
     */
    public boolean hasInput() {
        if (DEBUG) {
            Log.d(TAG, "---- hasInput()");
        }
        ArrayList<UsbDescriptor> acDescriptors =
                getACInterfaceDescriptors(UsbACInterface.ACI_INPUT_TERMINAL,
                UsbACInterface.AUDIO_AUDIOCONTROL);
        boolean hasInput = false;
        for (UsbDescriptor descriptor : acDescriptors) {
            if (descriptor instanceof UsbACTerminal) {
                UsbACTerminal inDescr = (UsbACTerminal) descriptor;
                // Check for input and bi-directional terminal types
                int type = inDescr.getTerminalType();
                if (DEBUG) {
                    Log.d(TAG, "  type:0x" + Integer.toHexString(type));
                }
                int terminalCategory = type & ~0xFF;
                if (terminalCategory != UsbTerminalTypes.TERMINAL_USB_UNDEFINED
                        && terminalCategory != UsbTerminalTypes.TERMINAL_OUT_UNDEFINED) {
                    // If not explicitly a USB connection or output, it could be an input.
                    hasInput = true;
                    break;
                }
            } else {
                Log.w(TAG, "Undefined Audio Input terminal l: " + descriptor.getLength()
                        + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }

        if (DEBUG) {
            Log.d(TAG, "hasInput() = " + hasInput);
        }
        return hasInput;
    }

    /**
     * @hide
     */
    public boolean hasOutput() {
        if (DEBUG) {
            Log.d(TAG, "---- hasOutput()");
        }
        ArrayList<UsbDescriptor> acDescriptors =
                getACInterfaceDescriptors(UsbACInterface.ACI_OUTPUT_TERMINAL,
                UsbACInterface.AUDIO_AUDIOCONTROL);
        boolean hasOutput = false;
        for (UsbDescriptor descriptor : acDescriptors) {
            if (descriptor instanceof UsbACTerminal) {
                UsbACTerminal outDescr = (UsbACTerminal) descriptor;
                // Check for output and bi-directional terminal types
                int type = outDescr.getTerminalType();
                if (DEBUG) {
                    Log.d(TAG, "  type:0x" + Integer.toHexString(type));
                }
                int terminalCategory = type & ~0xFF;
                if (terminalCategory != UsbTerminalTypes.TERMINAL_USB_UNDEFINED
                        && terminalCategory != UsbTerminalTypes.TERMINAL_IN_UNDEFINED) {
                    // If not explicitly a USB connection or input, it could be an output.
                    hasOutput = true;
                    break;
                }
            } else {
                Log.w(TAG, "Undefined Audio Input terminal l: " + descriptor.getLength()
                        + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }
        if (DEBUG) {
            Log.d(TAG, "hasOutput() = " + hasOutput);
        }
        return hasOutput;
    }

    /**
     * @hide
     */
    public boolean hasMic() {
        boolean hasMic = false;

        ArrayList<UsbDescriptor> acDescriptors =
                getACInterfaceDescriptors(UsbACInterface.ACI_INPUT_TERMINAL,
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
        return hasMic;
    }

    /**
     * @hide
     */
    public boolean hasSpeaker() {
        boolean hasSpeaker = false;

        ArrayList<UsbDescriptor> acDescriptors =
                getACInterfaceDescriptors(UsbACInterface.ACI_OUTPUT_TERMINAL,
                        UsbACInterface.AUDIO_AUDIOCONTROL);
        for (UsbDescriptor descriptor : acDescriptors) {
            if (descriptor instanceof UsbACTerminal) {
                UsbACTerminal outDescr = (UsbACTerminal) descriptor;
                if (outDescr.getTerminalType() == UsbTerminalTypes.TERMINAL_OUT_SPEAKER
                        || outDescr.getTerminalType() == UsbTerminalTypes.TERMINAL_OUT_HEADPHONES
                        || outDescr.getTerminalType() == UsbTerminalTypes.TERMINAL_BIDIR_HEADSET) {
                    hasSpeaker = true;
                    break;
                }
            } else {
                Log.w(TAG, "Undefined Audio Output terminal l: " + descriptor.getLength()
                        + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }

        return hasSpeaker;
    }

    /**
     *@ hide
     */
    public boolean hasAudioInterface() {
        ArrayList<UsbDescriptor> descriptors =
                getInterfaceDescriptorsForClass(UsbDescriptor.CLASSID_AUDIO);
        return !descriptors.isEmpty();
    }

    /**
     * @hide
     */
    public boolean hasHIDInterface() {
        ArrayList<UsbDescriptor> descriptors =
                getInterfaceDescriptorsForClass(UsbDescriptor.CLASSID_HID);
        return !descriptors.isEmpty();
    }

    /**
     * @hide
     */
    public boolean hasStorageInterface() {
        ArrayList<UsbDescriptor> descriptors =
                getInterfaceDescriptorsForClass(UsbDescriptor.CLASSID_STORAGE);
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

        // Look for a microphone
        boolean hasMic = hasMic();

        // Look for a "speaker"
        boolean hasSpeaker = hasSpeaker();

        if (hasMic && hasSpeaker) {
            probability += 0.75f;
        }

        if (hasMic && hasHIDInterface()) {
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

        if (hasSpeaker && hasHIDInterface()) {
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
        return getOutputHeadsetProbability() >= OUT_HEADSET_TRIGGER;
    }

}
