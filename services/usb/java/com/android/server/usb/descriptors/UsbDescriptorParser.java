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
    public static final boolean DEBUG = false;

    private final String mDeviceAddr;

    private static final int MS_MIDI_1_0 = 0x0100;
    private static final int MS_MIDI_2_0 = 0x0200;

    // Descriptor Objects
    private static final int DESCRIPTORS_ALLOC_SIZE = 128;
    private final ArrayList<UsbDescriptor> mDescriptors;

    private UsbDeviceDescriptor mDeviceDescriptor;
    private UsbConfigDescriptor mCurConfigDescriptor;
    private UsbInterfaceDescriptor mCurInterfaceDescriptor;
    private UsbEndpointDescriptor mCurEndpointDescriptor;

    // The AudioClass spec implemented by the AudioClass Interfaces
    // This may well be different than the overall USB Spec.
    // Obtained from the first AudioClass Header descriptor.
    private int mACInterfacesSpec = UsbDeviceDescriptor.USBSPEC_1_0;

    // The VideoClass spec implemented by the VideoClass Interfaces
    // This may well be different than the overall USB Spec.
    // Obtained from the first VidieoClass Header descriptor.
    private int mVCInterfacesSpec = UsbDeviceDescriptor.USBSPEC_1_0;

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

    public void setVCInterfaceSpec(int spec) {
        mVCInterfacesSpec = spec;
    }

    public int getVCInterfaceSpec() {
        return mVCInterfacesSpec;
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
                descriptor = mCurEndpointDescriptor = new UsbEndpointDescriptor(length, type);
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
                            descriptor =
                                    UsbACInterface.allocDescriptor(this, stream, length, type);
                            if (descriptor instanceof UsbMSMidiHeader) {
                                mCurInterfaceDescriptor.setMidiHeaderInterfaceDescriptor(
                                        descriptor);
                            }
                            break;

                        case UsbDescriptor.CLASSID_VIDEO:
                            if (DEBUG) {
                                Log.d(TAG, "  UsbDescriptor.CLASSID_VIDEO");
                            }
                            descriptor =
                                    UsbVCInterface.allocDescriptor(this, stream, length, type);
                            break;

                        case UsbDescriptor.CLASSID_AUDIOVIDEO:
                            if (DEBUG) {
                                Log.d(TAG, "  UsbDescriptor.CLASSID_AUDIOVIDEO");
                            }
                            break;

                        default:
                            Log.w(TAG, "  Unparsed Class-specific");
                            break;
                    }
                }
                break;

            case UsbDescriptor.DESCRIPTORTYPE_CLASSSPECIFIC_ENDPOINT:
                if (mCurInterfaceDescriptor != null) {
                    int subClass = mCurInterfaceDescriptor.getUsbClass();
                    switch (subClass) {
                        case UsbDescriptor.CLASSID_AUDIO: {
                            Byte subType = stream.getByte();
                            if (DEBUG) {
                                Log.d(TAG, "UsbDescriptor.CLASSID_AUDIO type:0x"
                                        + Integer.toHexString(type));
                            }
                            descriptor = UsbACEndpoint.allocDescriptor(this, length, type,
                                    subType);
                        }
                            break;

                        case UsbDescriptor.CLASSID_VIDEO: {
                            Byte subType = stream.getByte();
                            if (DEBUG) {
                                Log.d(TAG, "UsbDescriptor.CLASSID_VIDEO type:0x"
                                        + Integer.toHexString(type));
                            }
                            descriptor = UsbVCEndpoint.allocDescriptor(this, length, type,
                                    subType);
                        }
                            break;

                        case UsbDescriptor.CLASSID_AUDIOVIDEO:
                            if (DEBUG) {
                                Log.d(TAG, "UsbDescriptor.CLASSID_AUDIOVIDEO type:0x"
                                        + Integer.toHexString(type));
                            }
                            break;

                        default:
                            Log.w(TAG, "  Unparsed Class-specific Endpoint:0x"
                                    + Integer.toHexString(subClass));
                            break;
                    }
                    if (mCurEndpointDescriptor != null && descriptor != null) {
                        mCurEndpointDescriptor.setClassSpecificEndpointDescriptor(descriptor);
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
                    // Clean up, compute error status
                    descriptor.postParse(stream);

                    // Report
                    Log.w(TAG, "Exception parsing USB descriptors. type:0x" + descriptor.getType()
                            + " status:" + descriptor.getStatus());
                    if (DEBUG) {
                        // Show full stack trace if debugging
                        Log.e(TAG, "Exception parsing USB descriptors.", ex);
                    }
                    StackTraceElement[] stackElems = ex.getStackTrace();
                    if (stackElems.length > 0) {
                        Log.i(TAG, "  class:" + stackElems[0].getClassName()
                                    + " @ " + stackElems[0].getLineNumber());
                    }
                    if (stackElems.length > 1) {
                        Log.i(TAG, "  class:" + stackElems[1].getClassName()
                                + " @ " + stackElems[1].getLineNumber());
                    }

                    // Finish up
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
    public UsbDevice.Builder toAndroidUsbDeviceBuilder() {
        if (mDeviceDescriptor == null) {
            Log.e(TAG, "toAndroidUsbDevice() ERROR - No Device Descriptor");
            return null;
        }

        UsbDevice.Builder builder = mDeviceDescriptor.toAndroid(this);
        if (builder == null) {
            Log.e(TAG, "toAndroidUsbDevice() ERROR Creating Device");
        }
        return builder;
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
        ArrayList<UsbDescriptor> acDescriptors =
                getACInterfaceDescriptors(UsbACInterface.ACI_INPUT_TERMINAL,
                UsbACInterface.AUDIO_AUDIOCONTROL);
        for (UsbDescriptor descriptor : acDescriptors) {
            if (descriptor instanceof UsbACTerminal) {
                UsbACTerminal inDescr = (UsbACTerminal) descriptor;
                if (inDescr.isInputTerminal()) {
                    return true;
                }
            } else {
                Log.w(TAG, "Undefined Audio Input terminal l: " + descriptor.getLength()
                        + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }
        return false;
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
     * Returns true only if there is a terminal whose subtype and terminal type are the same as
     * the given values.
     * @hide
     */
    public boolean hasAudioTerminal(int subType, int terminalType) {
        for (UsbDescriptor descriptor : mDescriptors) {
            if (descriptor instanceof UsbACTerminal) {
                if (((UsbACTerminal) descriptor).getSubclass() == UsbDescriptor.AUDIO_AUDIOCONTROL
                        && ((UsbACTerminal) descriptor).getSubtype() == subType
                        && ((UsbACTerminal) descriptor).getTerminalType() == terminalType) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns true only if there is an interface whose subtype is the same as the given one and
     * terminal type is different from the given one.
     * @hide
     */
    public boolean hasAudioTerminalExcludeType(int subType, int excludedTerminalType) {
        for (UsbDescriptor descriptor : mDescriptors) {
            if (descriptor instanceof UsbACTerminal) {
                if (((UsbACTerminal) descriptor).getSubclass() == UsbDescriptor.AUDIO_AUDIOCONTROL
                        && ((UsbACTerminal) descriptor).getSubtype() == subType
                        && ((UsbACTerminal) descriptor).getTerminalType() != excludedTerminalType) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @hide
     */
    public boolean hasAudioPlayback() {
        return hasAudioTerminalExcludeType(
                UsbACInterface.ACI_OUTPUT_TERMINAL, UsbTerminalTypes.TERMINAL_USB_STREAMING)
                && hasAudioTerminal(
                        UsbACInterface.ACI_INPUT_TERMINAL, UsbTerminalTypes.TERMINAL_USB_STREAMING);
    }

    /**
     * @hide
     */
    public boolean hasAudioCapture() {
        return hasAudioTerminalExcludeType(
                UsbACInterface.ACI_INPUT_TERMINAL, UsbTerminalTypes.TERMINAL_USB_STREAMING)
                && hasAudioTerminal(
                        UsbACInterface.ACI_OUTPUT_TERMINAL,
                        UsbTerminalTypes.TERMINAL_USB_STREAMING);
    }

    /**
     * @hide
     */
    public boolean hasVideoCapture() {
        for (UsbDescriptor descriptor : mDescriptors) {
            if (descriptor instanceof UsbVCInputTerminal) {
                return true;
            }
        }
        return false;
    }

    /**
     * @hide
     */
    public boolean hasVideoPlayback() {
        for (UsbDescriptor descriptor : mDescriptors) {
            if (descriptor instanceof UsbVCOutputTerminal) {
                return true;
            }
        }
        return false;
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
                UsbInterfaceDescriptor interfaceDescriptor = (UsbInterfaceDescriptor) descriptor;
                if (interfaceDescriptor.getUsbSubclass() == UsbDescriptor.AUDIO_MIDISTREAMING) {
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
    public boolean containsUniversalMidiDeviceEndpoint() {
        ArrayList<UsbInterfaceDescriptor> interfaceDescriptors =
                findUniversalMidiInterfaceDescriptors();
        return doesInterfaceContainEndpoint(interfaceDescriptors);
    }

    /**
     * @hide
     */
    public boolean containsLegacyMidiDeviceEndpoint() {
        ArrayList<UsbInterfaceDescriptor> interfaceDescriptors =
                findLegacyMidiInterfaceDescriptors();
        return doesInterfaceContainEndpoint(interfaceDescriptors);
    }

    /**
     * @hide
     */
    public boolean doesInterfaceContainEndpoint(
            ArrayList<UsbInterfaceDescriptor> interfaceDescriptors) {
        int outputCount = 0;
        int inputCount = 0;
        for (int interfaceIndex = 0; interfaceIndex < interfaceDescriptors.size();
                interfaceIndex++) {
            UsbInterfaceDescriptor interfaceDescriptor = interfaceDescriptors.get(interfaceIndex);
            for (int endpointIndex = 0; endpointIndex < interfaceDescriptor.getNumEndpoints();
                    endpointIndex++) {
                UsbEndpointDescriptor endpoint =
                        interfaceDescriptor.getEndpointDescriptor(endpointIndex);
                // 0 is output, 1 << 7 is input.
                if (endpoint.getDirection() == 0) {
                    outputCount++;
                } else {
                    inputCount++;
                }
            }
        }
        return (outputCount > 0) || (inputCount > 0);
    }

    /**
     * @hide
     */
    public ArrayList<UsbInterfaceDescriptor> findUniversalMidiInterfaceDescriptors() {
        return findMidiInterfaceDescriptors(MS_MIDI_2_0);
    }

    /**
     * @hide
     */
    public ArrayList<UsbInterfaceDescriptor> findLegacyMidiInterfaceDescriptors() {
        return findMidiInterfaceDescriptors(MS_MIDI_1_0);
    }

    /**
     * @hide
     */
    private ArrayList<UsbInterfaceDescriptor> findMidiInterfaceDescriptors(int type) {
        int count = 0;
        ArrayList<UsbDescriptor> descriptors =
                getInterfaceDescriptorsForClass(UsbDescriptor.CLASSID_AUDIO);
        ArrayList<UsbInterfaceDescriptor> midiInterfaces =
                new ArrayList<UsbInterfaceDescriptor>();

        for (UsbDescriptor descriptor : descriptors) {
            // ensure that this isn't an unrecognized interface descriptor
            if (descriptor instanceof UsbInterfaceDescriptor) {
                UsbInterfaceDescriptor interfaceDescriptor = (UsbInterfaceDescriptor) descriptor;
                if (interfaceDescriptor.getUsbSubclass() == UsbDescriptor.AUDIO_MIDISTREAMING) {
                    UsbDescriptor midiHeaderDescriptor =
                            interfaceDescriptor.getMidiHeaderInterfaceDescriptor();
                    if (midiHeaderDescriptor != null) {
                        if (midiHeaderDescriptor instanceof UsbMSMidiHeader) {
                            UsbMSMidiHeader midiHeader =
                                    (UsbMSMidiHeader) midiHeaderDescriptor;
                            if (midiHeader.getMidiStreamingClass() == type) {
                                midiInterfaces.add(interfaceDescriptor);
                            }
                        }
                    }
                }
            } else {
                Log.w(TAG, "Undefined Audio Class Interface l: " + descriptor.getLength()
                        + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }
        return midiInterfaces;
    }

    /**
     * @hide
     */
    public int calculateMidiInterfaceDescriptorsCount() {
        int count = 0;
        ArrayList<UsbDescriptor> descriptors =
                getInterfaceDescriptorsForClass(UsbDescriptor.CLASSID_AUDIO);
        for (UsbDescriptor descriptor : descriptors) {
            // ensure that this isn't an unrecognized interface descriptor
            if (descriptor instanceof UsbInterfaceDescriptor) {
                UsbInterfaceDescriptor interfaceDescriptor = (UsbInterfaceDescriptor) descriptor;
                if (interfaceDescriptor.getUsbSubclass() == UsbDescriptor.AUDIO_MIDISTREAMING) {
                    UsbDescriptor midiHeaderDescriptor =
                            interfaceDescriptor.getMidiHeaderInterfaceDescriptor();
                    if (midiHeaderDescriptor != null) {
                        if (midiHeaderDescriptor instanceof UsbMSMidiHeader) {
                            UsbMSMidiHeader midiHeader =
                                    (UsbMSMidiHeader) midiHeaderDescriptor;
                            count++;
                        }
                    }
                }
            } else {
                Log.w(TAG, "Undefined Audio Class Interface l: " + descriptor.getLength()
                        + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }
        return count;
    }

    /**
     * @hide
     */
    private int calculateNumLegacyMidiPorts(boolean isOutput) {
        // Only look at the first config.
        UsbConfigDescriptor configDescriptor = null;
        for (UsbDescriptor descriptor : mDescriptors) {
            if (descriptor.getType() == UsbDescriptor.DESCRIPTORTYPE_CONFIG) {
                if (descriptor instanceof UsbConfigDescriptor) {
                    configDescriptor = (UsbConfigDescriptor) descriptor;
                    break;
                } else {
                    Log.w(TAG, "Unrecognized Config l: " + descriptor.getLength()
                            + " t:0x" + Integer.toHexString(descriptor.getType()));
                }
            }
        }
        if (configDescriptor == null) {
            Log.w(TAG, "Config not found");
            return 0;
        }

        ArrayList<UsbInterfaceDescriptor> legacyMidiInterfaceDescriptors =
                new ArrayList<UsbInterfaceDescriptor>();
        for (UsbInterfaceDescriptor interfaceDescriptor
                : configDescriptor.getInterfaceDescriptors()) {
            if (interfaceDescriptor.getUsbClass() == UsbDescriptor.CLASSID_AUDIO) {
                if (interfaceDescriptor.getUsbSubclass() == UsbDescriptor.AUDIO_MIDISTREAMING) {
                    UsbDescriptor midiHeaderDescriptor =
                            interfaceDescriptor.getMidiHeaderInterfaceDescriptor();
                    if (midiHeaderDescriptor != null) {
                        if (midiHeaderDescriptor instanceof UsbMSMidiHeader) {
                            UsbMSMidiHeader midiHeader =
                                    (UsbMSMidiHeader) midiHeaderDescriptor;
                            if (midiHeader.getMidiStreamingClass() == MS_MIDI_1_0) {
                                legacyMidiInterfaceDescriptors.add(interfaceDescriptor);
                            }
                        }
                    }
                }
            }
        }

        int count = 0;
        for (UsbInterfaceDescriptor interfaceDescriptor : legacyMidiInterfaceDescriptors) {
            for (int i = 0; i < interfaceDescriptor.getNumEndpoints(); i++) {
                UsbEndpointDescriptor endpoint =
                        interfaceDescriptor.getEndpointDescriptor(i);
                // 0 is output, 1 << 7 is input.
                if ((endpoint.getDirection() == 0) == isOutput) {
                    UsbDescriptor classSpecificEndpointDescriptor =
                            endpoint.getClassSpecificEndpointDescriptor();
                    if (classSpecificEndpointDescriptor != null
                            && (classSpecificEndpointDescriptor instanceof UsbACMidi10Endpoint)) {
                        UsbACMidi10Endpoint midiEndpoint =
                                (UsbACMidi10Endpoint) classSpecificEndpointDescriptor;
                        count += midiEndpoint.getNumJacks();
                    }
                }
            }
        }
        return count;
    }

    /**
     * @hide
     */
    public int calculateNumLegacyMidiInputs() {
        return calculateNumLegacyMidiPorts(false /*isOutput*/);
    }

    /**
     * @hide
     */
    public int calculateNumLegacyMidiOutputs() {
        return calculateNumLegacyMidiPorts(true /*isOutput*/);
    }

    /**
     * @hide
     */
    public float getInputHeadsetProbability() {
        if (hasMIDIInterface()) {
            return 0.0f;
        }

        float probability = 0.0f;

        // Look for a "speaker"
        boolean hasSpeaker = hasSpeaker();

        if (hasMic()) {
            if (hasSpeaker) {
                probability += 0.75f;
            }
            if (hasHIDInterface()) {
                probability += 0.25f;
            }
            if (getMaximumInputChannelCount() > 1) {
                // A headset is more likely to only support mono capture.
                probability -= 0.25f;
            }
        }

        return probability;
    }

    /**
     * getInputHeadsetProbability() reports a probability of a USB Input peripheral being a
     * headset. The probability range is between 0.0f (definitely NOT a headset) and
     * 1.0f (definitely IS a headset). A probability of 0.75f seems sufficient
     * to count on the peripheral being a headset.
     * To align with the output device type, only treat the device as input headset if it is
     * an output headset.
     */
    public boolean isInputHeadset() {
        return getInputHeadsetProbability() >= IN_HEADSET_TRIGGER && isOutputHeadset();
    }

    // TODO: Up/Downmix process descriptor is not yet parsed, which may affect the result here.
    private int getMaximumChannelCount() {
        int maxChannelCount = 0;
        for (UsbDescriptor descriptor : mDescriptors) {
            if (descriptor instanceof UsbAudioChannelCluster) {
                maxChannelCount = Math.max(maxChannelCount,
                        ((UsbAudioChannelCluster) descriptor).getChannelCount());
            }
        }
        return maxChannelCount;
    }

    private int getMaximumInputChannelCount() {
        int maxChannelCount = 0;
        ArrayList<UsbDescriptor> acDescriptors =
                getACInterfaceDescriptors(UsbACInterface.ACI_INPUT_TERMINAL,
                        UsbACInterface.AUDIO_AUDIOCONTROL);
        for (UsbDescriptor descriptor : acDescriptors) {
            if (!(descriptor instanceof UsbACTerminal)) {
                continue;
            }
            UsbACTerminal inDescr = (UsbACTerminal) descriptor;
            if (!inDescr.isInputTerminal()) {
                continue;
            }
            // For an input terminal, it should at lease has 1 channel.
            // Comparing the max channel count with 1 here in case the USB device doesn't report
            // audio channel cluster.
            maxChannelCount = Math.max(maxChannelCount, 1);
            if (!(descriptor instanceof UsbAudioChannelCluster)) {
                continue;
            }
            maxChannelCount = Math.max(maxChannelCount,
                    ((UsbAudioChannelCluster) descriptor).getChannelCount());
        }
        return maxChannelCount;
    }

    /**
     * @hide
     */
    public float getOutputHeadsetLikelihood() {
        if (hasMIDIInterface()) {
            return 0.0f;
        }

        float likelihood = 0.0f;
        ArrayList<UsbDescriptor> acDescriptors;

        // Look for a "speaker"
        boolean hasSpeaker = false;
        boolean hasAssociatedInputTerminal = false;
        boolean hasHeadphoneOrHeadset = false;
        acDescriptors =
                getACInterfaceDescriptors(UsbACInterface.ACI_OUTPUT_TERMINAL,
                        UsbACInterface.AUDIO_AUDIOCONTROL);
        for (UsbDescriptor descriptor : acDescriptors) {
            if (descriptor instanceof UsbACTerminal) {
                UsbACTerminal outDescr = (UsbACTerminal) descriptor;
                if (outDescr.getTerminalType() == UsbTerminalTypes.TERMINAL_OUT_SPEAKER) {
                    hasSpeaker = true;
                    if (outDescr.getAssocTerminal() != 0x0) {
                        hasAssociatedInputTerminal = true;
                    }
                } else if (outDescr.getTerminalType() == UsbTerminalTypes.TERMINAL_OUT_HEADPHONES
                        || outDescr.getTerminalType() == UsbTerminalTypes.TERMINAL_BIDIR_HEADSET) {
                    hasHeadphoneOrHeadset = true;
                }
            } else {
                Log.w(TAG, "Undefined Audio Output terminal l: " + descriptor.getLength()
                        + " t:0x" + Integer.toHexString(descriptor.getType()));
            }
        }

        if (hasHeadphoneOrHeadset) {
            likelihood += 0.75f;
        } else if (hasSpeaker) {
            // The device only reports output terminal as speaker. Try to figure out if the device
            // is a headset or not by checking if it has associated input terminal and if multiple
            // channels are supported or not.
            likelihood += 0.5f;
            if (hasAssociatedInputTerminal) {
                likelihood += 0.25f;
            }
            if (getMaximumChannelCount() > 2) {
                // When multiple channels are supported, it is less likely to be a headset.
                likelihood -= 0.25f;
            }
        }

        if ((hasHeadphoneOrHeadset || hasSpeaker) && hasHIDInterface()) {
            likelihood += 0.25f;
        }

        return likelihood;
    }

    /**
     * getOutputHeadsetProbability() reports a probability of a USB Output peripheral being a
     * headset. The probability range is between 0.0f (definitely NOT a headset) and
     * 1.0f (definitely IS a headset). A probability of 0.75f seems sufficient
     * to count on the peripheral being a headset.
     */
    public boolean isOutputHeadset() {
        return getOutputHeadsetLikelihood() >= OUT_HEADSET_TRIGGER;
    }

    /**
     * isDock() indicates if the connected USB output peripheral is a docking station with
     * audio output.
     * A valid audio dock must declare only one audio output control terminal of type
     * TERMINAL_EXTERN_DIGITAL.
     */
    public boolean isDock() {
        if (hasMIDIInterface() || hasHIDInterface()) {
            return false;
        }

        ArrayList<UsbDescriptor> acDescriptors =
                getACInterfaceDescriptors(UsbACInterface.ACI_OUTPUT_TERMINAL,
                        UsbACInterface.AUDIO_AUDIOCONTROL);

        if (acDescriptors.size() != 1) {
            return false;
        }

        if (acDescriptors.get(0) instanceof UsbACTerminal) {
            UsbACTerminal outDescr = (UsbACTerminal) acDescriptors.get(0);
            if (outDescr.getTerminalType() == UsbTerminalTypes.TERMINAL_EXTERN_DIGITAL) {
                return true;
            }
        } else {
            Log.w(TAG, "Undefined Audio Output terminal l: " + acDescriptors.get(0).getLength()
                    + " t:0x" + Integer.toHexString(acDescriptors.get(0).getType()));
        }
        return false;
    }

}
