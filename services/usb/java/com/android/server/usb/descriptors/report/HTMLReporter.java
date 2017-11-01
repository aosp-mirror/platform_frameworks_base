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

import android.hardware.usb.UsbDeviceConnection;

import com.android.server.usb.descriptors.UsbACAudioControlEndpoint;
import com.android.server.usb.descriptors.UsbACAudioStreamEndpoint;
import com.android.server.usb.descriptors.UsbACFeatureUnit;
import com.android.server.usb.descriptors.UsbACHeader;
import com.android.server.usb.descriptors.UsbACInputTerminal;
import com.android.server.usb.descriptors.UsbACInterface;
import com.android.server.usb.descriptors.UsbACMidiEndpoint;
import com.android.server.usb.descriptors.UsbACMixerUnit;
import com.android.server.usb.descriptors.UsbACOutputTerminal;
import com.android.server.usb.descriptors.UsbACSelectorUnit;
import com.android.server.usb.descriptors.UsbACTerminal;
import com.android.server.usb.descriptors.UsbASFormat;
import com.android.server.usb.descriptors.UsbASFormatI;
import com.android.server.usb.descriptors.UsbASFormatII;
import com.android.server.usb.descriptors.UsbASGeneral;
import com.android.server.usb.descriptors.UsbConfigDescriptor;
import com.android.server.usb.descriptors.UsbDescriptor;
import com.android.server.usb.descriptors.UsbDeviceDescriptor;
import com.android.server.usb.descriptors.UsbEndpointDescriptor;
import com.android.server.usb.descriptors.UsbHIDDescriptor;
import com.android.server.usb.descriptors.UsbInterfaceAssoc;
import com.android.server.usb.descriptors.UsbInterfaceDescriptor;
import com.android.server.usb.descriptors.UsbMSMidiHeader;
import com.android.server.usb.descriptors.UsbMSMidiInputJack;
import com.android.server.usb.descriptors.UsbMSMidiOutputJack;
import com.android.server.usb.descriptors.UsbUnknown;

/**
 * Implements the Reporter inteface to provide HTML reporting for UsbDescriptor subclasses.
 */
public class HTMLReporter implements Reporter {
    private final StringBuilder mStringBuilder;
    private final UsbDeviceConnection mConnection;

    public HTMLReporter(StringBuilder stringBuilder, UsbDeviceConnection connection) {
        mStringBuilder = stringBuilder;
        mConnection = connection;
    }

    /*
     * HTML Helpers
     */
    private void writeHeader(int level, String text) {
        mStringBuilder
                .append("<h").append(level).append('>')
                .append(text)
                .append("</h").append(level).append('>');
    }

    private void openParagraph() {
        mStringBuilder.append("<p>");
    }

    private void closeParagraph() {
        mStringBuilder.append("</p>");
    }

    private void writeParagraph(String text) {
        openParagraph();
        mStringBuilder.append(text);
        closeParagraph();
    }

    private void openList() {
        mStringBuilder.append("<ul>");
    }

    private void closeList() {
        mStringBuilder.append("</ul>");
    }

    private void openListItem() {
        mStringBuilder.append("<li>");
    }

    private void closeListItem() {
        mStringBuilder.append("</li>");
    }

    private void writeListItem(String text) {
        openListItem();
        mStringBuilder.append(text);
        closeListItem();
    }

    /*
     * Data Formating Helpers
     */
    private static String getHexString(byte value) {
        return "0x" + Integer.toHexString(((int) value) & 0xFF).toUpperCase();
    }

    private static String getBCDString(int value) {
        int major = value >> 8;
        int minor = (value >> 4) & 0x0F;
        int subminor = value & 0x0F;

        return "" + major + "." + minor + subminor;
    }

    private static String getHexString(int value) {
        int intValue = value & 0xFFFF;
        return "0x" + Integer.toHexString(intValue).toUpperCase();
    }

    private void dumpHexArray(byte[] rawData, StringBuilder builder) {
        if (rawData != null) {
            // Assume the type and Length and perhaps sub-type have been displayed
            openParagraph();
            for (int index = 0; index < rawData.length; index++) {
                builder.append(getHexString(rawData[index]) + " ");
            }
            closeParagraph();
        }
    }

    /**
     * Decode ACTUAL UsbDescriptor sub classes and call type-specific report methods.
     */
    @Override
    public void report(UsbDescriptor descriptor) {
        if (descriptor instanceof UsbDeviceDescriptor) {
            tsReport((UsbDeviceDescriptor) descriptor);
        } else if (descriptor instanceof UsbConfigDescriptor) {
            tsReport((UsbConfigDescriptor) descriptor);
        } else if (descriptor instanceof UsbInterfaceDescriptor) {
            tsReport((UsbInterfaceDescriptor) descriptor);
        } else if (descriptor instanceof UsbEndpointDescriptor) {
            tsReport((UsbEndpointDescriptor) descriptor);
        } else if (descriptor instanceof UsbHIDDescriptor) {
            tsReport((UsbHIDDescriptor) descriptor);
        } else if (descriptor instanceof UsbACAudioControlEndpoint) {
            tsReport((UsbACAudioControlEndpoint) descriptor);
        } else if (descriptor instanceof UsbACAudioStreamEndpoint) {
            tsReport((UsbACAudioStreamEndpoint) descriptor);
        } else if (descriptor instanceof UsbACHeader) {
            tsReport((UsbACHeader) descriptor);
        } else if (descriptor instanceof UsbACFeatureUnit) {
            tsReport((UsbACFeatureUnit) descriptor);
        } else if (descriptor instanceof UsbACInputTerminal) {
            tsReport((UsbACInputTerminal) descriptor);
        } else if (descriptor instanceof UsbACOutputTerminal) {
            tsReport((UsbACOutputTerminal) descriptor);
        } else if (descriptor instanceof UsbACMidiEndpoint) {
            tsReport((UsbACMidiEndpoint) descriptor);
        } else if (descriptor instanceof UsbACMixerUnit) {
            tsReport((UsbACMixerUnit) descriptor);
        } else if (descriptor instanceof UsbACSelectorUnit) {
            tsReport((UsbACSelectorUnit) descriptor);
        } else if (descriptor instanceof UsbASFormatI) {
            tsReport((UsbASFormatI) descriptor);
        } else if (descriptor instanceof UsbASFormatII) {
            tsReport((UsbASFormatII) descriptor);
        } else if (descriptor instanceof UsbASFormat) {
            tsReport((UsbASFormat) descriptor);
        } else if (descriptor instanceof UsbASGeneral) {
            tsReport((UsbASGeneral) descriptor);
        } else if (descriptor instanceof UsbInterfaceAssoc) {
            tsReport((UsbInterfaceAssoc) descriptor);
        } else if (descriptor instanceof UsbMSMidiHeader) {
            tsReport((UsbMSMidiHeader) descriptor);
        } else if (descriptor instanceof UsbMSMidiInputJack) {
            tsReport((UsbMSMidiInputJack) descriptor);
        } else if (descriptor instanceof UsbMSMidiOutputJack) {
            tsReport((UsbMSMidiOutputJack) descriptor);
        } else if (descriptor instanceof UsbUnknown) {
            tsReport((UsbUnknown) descriptor);
        } else if (descriptor instanceof UsbACInterface) {
            tsReport((UsbACInterface) descriptor);
        } else if (descriptor instanceof UsbDescriptor) {
            tsReport((UsbDescriptor) descriptor);
        }
    }

    //
    // Type-specific report() implementations
    //
    private void tsReport(UsbDescriptor descriptor) {
        int length = descriptor.getLength();
        byte type = descriptor.getType();
        int status = descriptor.getStatus();

        String descTypeStr = UsbStrings.getDescriptorName(type);
        writeParagraph(descTypeStr + ":" + type + " l:" + length + " s:" + status);
    }

    private void tsReport(UsbDeviceDescriptor descriptor) {
        writeHeader(1, "Device len:" + descriptor.getLength());
        openList();

        int spec = descriptor.getSpec();
        writeListItem("spec:" + getBCDString(spec));

        byte devClass = descriptor.getDevClass();
        String classStr = UsbStrings.getClassName(devClass);
        byte devSubClass = descriptor.getDevSubClass();
        String subClasStr = UsbStrings.getClassName(devSubClass);
        writeListItem("class " + devClass + ":" + classStr + " subclass"
                + devSubClass + ":" + subClasStr);
        writeListItem("vendorID:" + descriptor.getVendorID()
                + " prodID:" + descriptor.getProductID()
                + " prodRel:" + getBCDString(descriptor.getDeviceRelease()));

        byte mfgIndex = descriptor.getMfgIndex();
        String manufacturer = UsbDescriptor.getUsbDescriptorString(mConnection, mfgIndex);
        byte productIndex = descriptor.getProductIndex();
        String product = UsbDescriptor.getUsbDescriptorString(mConnection, productIndex);

        writeListItem("mfg " + mfgIndex + ":" + manufacturer
                + " prod " + productIndex + ":" + product);
        closeList();
    }

    private void tsReport(UsbConfigDescriptor descriptor) {
        writeHeader(2, "Config #" + descriptor.getConfigValue()
                + " len:" + descriptor.getLength());

        openList();
        writeListItem(descriptor.getNumInterfaces() + " interfaces.");
        writeListItem("attribs:" + getHexString(descriptor.getAttribs()));
        closeList();
    }

    private void tsReport(UsbInterfaceDescriptor descriptor) {
        byte usbClass = descriptor.getUsbClass();
        byte usbSubclass = descriptor.getUsbSubclass();
        String descr = UsbStrings.getDescriptorName(descriptor.getType());
        String className = UsbStrings.getClassName(usbClass);
        String subclassName = "";
        if (usbClass == UsbDescriptor.CLASSID_AUDIO) {
            subclassName = UsbStrings.getAudioSubclassName(usbSubclass);
        }

        writeHeader(2, descr + " #" + descriptor.getInterfaceNumber()
                        + " len:" + descriptor.getLength());
        String descrStr =
                UsbDescriptor.getUsbDescriptorString(mConnection, descriptor.getDescrIndex());
        if (descrStr.length() > 0) {
            mStringBuilder.append("<br>" + descrStr);
        }
        openList();
        writeListItem("class " + getHexString(usbClass) + ":" + className
                + " subclass " + getHexString(usbSubclass) + ":" + subclassName);
        writeListItem(""  + descriptor.getNumEndpoints() + " endpoints");
        closeList();
    }

    private void tsReport(UsbEndpointDescriptor descriptor) {
        writeHeader(3, "Endpoint " + getHexString(descriptor.getType())
                + " len:" + descriptor.getLength());
        openList();

        byte address = descriptor.getEndpointAddress();
        writeListItem("address:"
                + getHexString(address & UsbEndpointDescriptor.MASK_ENDPOINT_ADDRESS)
                + ((address & UsbEndpointDescriptor.MASK_ENDPOINT_DIRECTION)
                        == UsbEndpointDescriptor.DIRECTION_OUTPUT ? " [out]" : " [in]"));

        byte attributes = descriptor.getAttributes();
        openListItem();
        mStringBuilder.append("attribs:" + getHexString(attributes) + " ");
        switch (attributes & UsbEndpointDescriptor.MASK_ATTRIBS_TRANSTYPE) {
            case UsbEndpointDescriptor.TRANSTYPE_CONTROL:
                mStringBuilder.append("Control");
                break;
            case UsbEndpointDescriptor.TRANSTYPE_ISO:
                mStringBuilder.append("Iso");
                break;
            case UsbEndpointDescriptor.TRANSTYPE_BULK:
                mStringBuilder.append("Bulk");
                break;
            case UsbEndpointDescriptor.TRANSTYPE_INTERRUPT:
                mStringBuilder.append("Interrupt");
                break;
        }
        closeListItem();

        // These flags are only relevant for ISO transfer type
        if ((attributes & UsbEndpointDescriptor.MASK_ATTRIBS_TRANSTYPE)
                == UsbEndpointDescriptor.TRANSTYPE_ISO) {
            openListItem();
            mStringBuilder.append("sync:");
            switch (attributes & UsbEndpointDescriptor.MASK_ATTRIBS_SYNCTYPE) {
                case UsbEndpointDescriptor.SYNCTYPE_NONE:
                    mStringBuilder.append("NONE");
                    break;
                case UsbEndpointDescriptor.SYNCTYPE_ASYNC:
                    mStringBuilder.append("ASYNC");
                    break;
                case UsbEndpointDescriptor.SYNCTYPE_ADAPTSYNC:
                    mStringBuilder.append("ADAPTIVE ASYNC");
                    break;
            }
            closeListItem();

            openListItem();
            mStringBuilder.append("useage:");
            switch (attributes & UsbEndpointDescriptor.MASK_ATTRIBS_USEAGE) {
                case UsbEndpointDescriptor.USEAGE_DATA:
                    mStringBuilder.append("DATA");
                    break;
                case UsbEndpointDescriptor.USEAGE_FEEDBACK:
                    mStringBuilder.append("FEEDBACK");
                    break;
                case UsbEndpointDescriptor.USEAGE_EXPLICIT:
                    mStringBuilder.append("EXPLICIT FEEDBACK");
                    break;
                case UsbEndpointDescriptor.USEAGE_RESERVED:
                    mStringBuilder.append("RESERVED");
                    break;
            }
            closeListItem();
        }
        writeListItem("package size:" + descriptor.getPacketSize());
        writeListItem("interval:" + descriptor.getInterval());
        closeList();
    }

    private void tsReport(UsbHIDDescriptor descriptor) {
        String descr = UsbStrings.getDescriptorName(descriptor.getType());
        writeHeader(2, descr + " len:" + descriptor.getLength());
        openList();
        writeListItem("spec:" + getBCDString(descriptor.getRelease()));
        writeListItem("type:" + getBCDString(descriptor.getDescriptorType()));
        writeListItem("descriptor.getNumDescriptors()  descriptors len:"
                + descriptor.getDescriptorLen());
        closeList();
    }

    private void tsReport(UsbACAudioControlEndpoint descriptor) {
        writeHeader(3, "AC Audio Control Endpoint:" + getHexString(descriptor.getType())
                + " length:" + descriptor.getLength());
    }

    private void tsReport(UsbACAudioStreamEndpoint descriptor) {
        writeHeader(3, "AC Audio Streaming Endpoint:"
                + getHexString(descriptor.getType())
                + " length:" + descriptor.getLength());
    }

    private void tsReport(UsbACHeader descriptor) {
        tsReport((UsbACInterface) descriptor);

        openList();
        writeListItem("spec:" + getBCDString(descriptor.getADCRelease()));
        int numInterfaces = descriptor.getNumInterfaces();
        writeListItem("" + numInterfaces + " interfaces");
        if (numInterfaces > 0) {
            openListItem();
            mStringBuilder.append("[");
            byte[] interfaceNums = descriptor.getInterfaceNums();
            if (numInterfaces != 0 && interfaceNums != null) {
                for (int index = 0; index < numInterfaces; index++) {
                    mStringBuilder.append("" + interfaceNums[index]);
                    if (index < numInterfaces - 1) {
                        mStringBuilder.append(" ");
                    }
                }
            }
            mStringBuilder.append("]");
            closeListItem();
        }
        writeListItem("controls:" + getHexString(descriptor.getControls()));
        closeList();
    }

    private void tsReport(UsbACFeatureUnit descriptor) {
        tsReport((UsbACInterface) descriptor);
    }

    private void tsReport(UsbACInterface descriptor) {
        String subClassName =
                descriptor.getSubclass() == UsbDescriptor.AUDIO_AUDIOCONTROL
                        ? "AC Control"
                        : "AC Streaming";
        byte subtype = descriptor.getSubtype();
        String subTypeStr = UsbStrings.getACControlInterfaceName(subtype);
        writeHeader(4, subClassName + " - " + getHexString(subtype)
                + ":" + subTypeStr + " len:" + descriptor.getLength());
    }

    private void tsReport(UsbACTerminal descriptor) {
        tsReport((UsbACInterface) descriptor);
    }

    private void tsReport(UsbACInputTerminal descriptor) {
        tsReport((UsbACTerminal) descriptor);

        openList();
        writeListItem("ID:" + getHexString(descriptor.getTerminalID()));
        int terminalType = descriptor.getTerminalType();
        writeListItem("Type:<b>" + getHexString(terminalType) + ":"
                + UsbStrings.getTerminalName(terminalType) + "</b>");
        writeListItem("AssocTerminal:" + getHexString(descriptor.getAssocTerminal()));
        writeListItem("" + descriptor.getNrChannels() + " chans. config:"
                + getHexString(descriptor.getChannelConfig()));
        closeList();
    }

    private void tsReport(UsbACOutputTerminal descriptor) {
        tsReport((UsbACTerminal) descriptor);

        openList();
        writeListItem("ID:" + getHexString(descriptor.getTerminalID()));
        int terminalType = descriptor.getTerminalType();
        writeListItem("Type:<b>" + getHexString(terminalType) + ":"
                + UsbStrings.getTerminalName(terminalType) + "</b>");
        writeListItem("AssocTerminal:" + getHexString(descriptor.getAssocTerminal()));
        writeListItem("Source:" + getHexString(descriptor.getSourceID()));
        closeList();
    }

    private void tsReport(UsbACMidiEndpoint descriptor) {
        writeHeader(3, "AC Midi Endpoint:" + getHexString(descriptor.getType())
                + " length:" + descriptor.getLength());
        openList();
        writeListItem("" + descriptor.getNumJacks() + " jacks.");
        closeList();
    }

    private void tsReport(UsbACMixerUnit descriptor) {
        tsReport((UsbACInterface) descriptor);
        openList();

        writeListItem("Unit ID:" + getHexString(descriptor.getUnitID()));
        byte numInputs = descriptor.getNumInputs();
        byte[] inputIDs = descriptor.getInputIDs();
        openListItem();
        mStringBuilder.append("Num Inputs:" + numInputs + " [");
        for (int input = 0; input < numInputs; input++) {
            mStringBuilder.append("" + getHexString(inputIDs[input]));
            if (input < numInputs - 1) {
                mStringBuilder.append(" ");
            }
        }
        mStringBuilder.append("]");
        closeListItem();

        writeListItem("Num Outputs:" + descriptor.getNumOutputs());
        writeListItem("Chan Config:" + getHexString(descriptor.getChannelConfig()));

        byte[] controls = descriptor.getControls();
        openListItem();
        mStringBuilder.append("controls:" + controls.length + " [");
        for (int ctrl = 0; ctrl < controls.length; ctrl++) {
            mStringBuilder.append("" + controls[ctrl]);
            if (ctrl < controls.length - 1) {
                mStringBuilder.append(" ");
            }
        }
        mStringBuilder.append("]");
        closeListItem();
        closeList();
        // byte mChanNameID; // First channel name string descriptor ID
        // byte mNameID;       // string descriptor ID of mixer name
    }

    private void tsReport(UsbACSelectorUnit descriptor) {
        tsReport((UsbACInterface) descriptor);
    }

    private void tsReport(UsbASFormat descriptor) {
        writeHeader(4, "AC Streaming Format "
                + (descriptor.getFormatType() ==  UsbASFormat.FORMAT_TYPE_I  ? "I" : "II")
                + " - " + getHexString(descriptor.getSubtype()) + ":"
                + " len:" + descriptor.getLength());
    }

    private void tsReport(UsbASFormatI descriptor) {
        tsReport((UsbASFormat) descriptor);
        openList();
        writeListItem("chans:" + descriptor.getNumChannels());
        writeListItem("subframe size:" + descriptor.getSubframeSize());
        writeListItem("bit resolution:" + descriptor.getBitResolution());
        byte sampleFreqType = descriptor.getSampleFreqType();
        int[] sampleRates = descriptor.getSampleRates();
        writeListItem("sample freq type:" + sampleFreqType);
        if (sampleFreqType == 0) {
            openList();
            writeListItem("min:" + sampleRates[0]);
            writeListItem("max:" + sampleRates[1]);
            closeList();
        } else {
            openList();
            for (int index = 0; index < sampleFreqType; index++) {
                writeListItem("" + sampleRates[index]);
            }
            closeList();
        }
        closeList();
    }

    private void tsReport(UsbASFormatII descriptor) {
        tsReport((UsbASFormat) descriptor);
        openList();
        writeListItem("max bit rate:" + descriptor.getMaxBitRate());
        writeListItem("samples per frame:" + descriptor.getMaxBitRate());
        byte sampleFreqType = descriptor.getSamFreqType();
        int[] sampleRates = descriptor.getSampleRates();
        writeListItem("sample freq type:" + sampleFreqType);
        if (sampleFreqType == 0) {
            openList();
            writeListItem("min:" + sampleRates[0]);
            writeListItem("max:" + sampleRates[1]);
            closeList();
        } else {
            openList();
            for (int index = 0; index < sampleFreqType; index++) {
                writeListItem("" + sampleRates[index]);
            }
            closeList();
        }

        closeList();
    }

    private void tsReport(UsbASGeneral descriptor) {
        tsReport((UsbACInterface) descriptor);
        openList();
        int formatTag = descriptor.getFormatTag();
        writeListItem("fmt:" + UsbStrings.getAudioFormatName(formatTag) + " - "
                + getHexString(formatTag));
        closeList();
    }

    private void tsReport(UsbInterfaceAssoc descriptor) {
        tsReport((UsbDescriptor) descriptor);
    }

    private void tsReport(UsbMSMidiHeader descriptor) {
        writeHeader(3, "MS Midi Header:" + getHexString(descriptor.getType())
                + " subType:" + getHexString(descriptor.getSubclass())
                + " length:" + descriptor.getSubclass());
    }

    private void tsReport(UsbMSMidiInputJack descriptor) {
        writeHeader(3, "MS Midi Input Jack:" + getHexString(descriptor.getType())
                + " subType:" + getHexString(descriptor.getSubclass())
                + " length:" + descriptor.getSubclass());
    }

    private void tsReport(UsbMSMidiOutputJack descriptor) {
        writeHeader(3, "MS Midi Output Jack:" + getHexString(descriptor.getType())
                + " subType:" + getHexString(descriptor.getSubclass())
                + " length:" + descriptor.getSubclass());
    }

    private void tsReport(UsbUnknown descriptor) {
        writeParagraph("<i><b>Unknown Descriptor " + getHexString(descriptor.getType())
                + " len:" + descriptor.getLength() + "</b></i>");
        dumpHexArray(descriptor.getRawData(), mStringBuilder);
    }
}
