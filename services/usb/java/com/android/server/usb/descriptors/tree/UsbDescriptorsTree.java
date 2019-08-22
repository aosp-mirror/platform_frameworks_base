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
package com.android.server.usb.descriptors.tree;

import com.android.server.usb.descriptors.UsbACInterface;
import com.android.server.usb.descriptors.UsbConfigDescriptor;
import com.android.server.usb.descriptors.UsbDescriptor;
import com.android.server.usb.descriptors.UsbDescriptorParser;
import com.android.server.usb.descriptors.UsbDeviceDescriptor;
import com.android.server.usb.descriptors.UsbEndpointDescriptor;
import com.android.server.usb.descriptors.UsbInterfaceDescriptor;
import com.android.server.usb.descriptors.report.ReportCanvas;

import java.util.ArrayList;

/*
 * The general layout of the tree looks like this, though no guarentee about
 * ordering of descriptors beyond the Device -> Config -> Interface.
 *
 * Device Descriptor
 *   +- Config Descriptor
 *       +- Interface Descriptor
 *       |   +- Audio Class Interface
 *       |   +- Audio Class Interface
 *       |   +- Audio Class Interface
 *       |   +- Endpoint Descriptor
 *       |   +- Endpoint Descriptor
 *       +- Interface Descriptor
 *           +- Endpoint Descriptor
 */
/**
 * @hide
 *
 * A class which builds a tree representation from the results of a (linear)
 * parse of USB descriptors.
 *
 * @see {@link com.android.server.usb.descriptors.UsbDescriptorsParser UsbDescriptorsParser}
 */
public final class UsbDescriptorsTree {
    private static final String TAG = "UsbDescriptorsTree";

    private UsbDescriptorsDeviceNode mDeviceNode;
    private UsbDescriptorsConfigNode mConfigNode;   // being parsed
    private UsbDescriptorsInterfaceNode mInterfaceNode; // being parsed

    /**
     * Adds THE device descriptor as the root of the tree.
     */
    private void addDeviceDescriptor(UsbDeviceDescriptor deviceDescriptor) {
        mDeviceNode = new UsbDescriptorsDeviceNode(deviceDescriptor);
    }

    /**
     * Adds A config descriptor to the tree.
     */
    private void addConfigDescriptor(UsbConfigDescriptor configDescriptor) {
        mConfigNode = new UsbDescriptorsConfigNode(configDescriptor);
        mDeviceNode.addConfigDescriptorNode(mConfigNode);
    }

    /**
     * Adds AN interface descriptor to the current configuration in the tree.
     */
    private void addInterfaceDescriptor(UsbInterfaceDescriptor interfaceDescriptor) {
        mInterfaceNode = new UsbDescriptorsInterfaceNode(interfaceDescriptor);
        mConfigNode.addInterfaceNode(mInterfaceNode);
    }

    /**
     * Adds an endpoint descriptor to the current interface in the tree.
     */
    private void addEndpointDescriptor(UsbEndpointDescriptor endpointDescriptor) {
        mInterfaceNode.addEndpointNode(new UsbDescriptorsEndpointNode(endpointDescriptor));
    }

    /**
     * Adds an audio-class interface descriptor to the current interface in the tree.
     */
    private void addACInterface(UsbACInterface acInterface) {
        mInterfaceNode.addACInterfaceNode(new UsbDescriptorsACInterfaceNode(acInterface));
    }

    /**
     * Parses the linear descriptor list contained in the parser argument, into a tree
     * representation corresponding to the logical structure of the USB descriptors.
     */
    public void parse(UsbDescriptorParser parser) {

        ArrayList<UsbDescriptor> descriptors = parser.getDescriptors();

        for (int descrIndex = 0; descrIndex < descriptors.size(); descrIndex++) {
            UsbDescriptor descriptor = descriptors.get(descrIndex);
            switch (descriptor.getType()) {
                //
                // Basic Descriptors
                //
                case UsbDescriptor.DESCRIPTORTYPE_DEVICE:
                    addDeviceDescriptor((UsbDeviceDescriptor) descriptor);
                    break;

                case UsbDescriptor.DESCRIPTORTYPE_CONFIG:
                    addConfigDescriptor((UsbConfigDescriptor) descriptor);
                    break;

                case UsbDescriptor.DESCRIPTORTYPE_INTERFACE:
                    addInterfaceDescriptor((UsbInterfaceDescriptor) descriptor);
                    break;

                case UsbDescriptor.DESCRIPTORTYPE_ENDPOINT:
                    addEndpointDescriptor((UsbEndpointDescriptor) descriptor);
                    break;

                //
                // Audio Class Descriptors
                //
                case UsbDescriptor.DESCRIPTORTYPE_CLASSSPECIFIC_INTERFACE:
                    //TODO: This needs to be parsed out to Audio/Video...
                    // addACInterface((UsbACInterface) descriptor);
                    break;

                case UsbDescriptor.DESCRIPTORTYPE_CLASSSPECIFIC_ENDPOINT:
                    //TODO: This needs to be parsed out to Audio/Video...
                    break;
            }
        }
    }

    /**
     * Generate a report of the descriptors tree.
     */
    public void report(ReportCanvas canvas) {
        mDeviceNode.report(canvas);
    }
}
