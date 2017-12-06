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

import com.android.server.usb.descriptors.UsbInterfaceDescriptor;
import com.android.server.usb.descriptors.report.ReportCanvas;

import java.util.ArrayList;

/**
 * @hide
 * Represents an interface in the descriptors tree.
 */
public final class UsbDescriptorsInterfaceNode extends UsbDescriptorsTreeNode {
    private static final String TAG = "UsbDescriptorsInterfaceNode";

    private final UsbInterfaceDescriptor mInterfaceDescriptor;

    private final ArrayList<UsbDescriptorsEndpointNode> mEndpointNodes = new ArrayList<>();
    private final ArrayList<UsbDescriptorsACInterfaceNode> mACInterfaceNodes = new ArrayList<>();

    /**
     * Constructor.
     * @param interfaceDescriptor   The Interface Descriptor object wrapped by this tree node.
     */
    public UsbDescriptorsInterfaceNode(UsbInterfaceDescriptor interfaceDescriptor) {
        mInterfaceDescriptor = interfaceDescriptor;
    }

    /**
     * Adds an endpoint descriptor as a child of this interface node.
     * @param endpointNode The endpoint descriptor node to add to this interface node.
     */
    public void addEndpointNode(UsbDescriptorsEndpointNode endpointNode) {
        mEndpointNodes.add(endpointNode);
    }

    /**
     * Adds an Audio-class interface descriptor as a child of this interface node.
     * @param acInterfaceNode The audio-class descriptor node to add to this interface node.
     */
    public void addACInterfaceNode(UsbDescriptorsACInterfaceNode acInterfaceNode) {
        mACInterfaceNodes.add(acInterfaceNode);
    }

    @Override
    public void report(ReportCanvas canvas) {
        mInterfaceDescriptor.report(canvas);

        // Audio Class Interfaces
        if (mACInterfaceNodes.size() > 0) {
            canvas.writeParagraph("Audio Class Interfaces", false);
            canvas.openList();
            for (UsbDescriptorsACInterfaceNode node : mACInterfaceNodes) {
                node.report(canvas);
            }
            canvas.closeList();
        }

        // Endpoints
        if (mEndpointNodes.size() > 0) {
            canvas.writeParagraph("Endpoints", false);
            canvas.openList();
            for (UsbDescriptorsEndpointNode node : mEndpointNodes) {
                node.report(canvas);
            }
            canvas.closeList();
        }
    }
}
