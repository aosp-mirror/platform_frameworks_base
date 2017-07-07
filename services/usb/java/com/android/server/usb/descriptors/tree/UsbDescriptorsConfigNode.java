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

import com.android.server.usb.descriptors.UsbConfigDescriptor;
import com.android.server.usb.descriptors.report.ReportCanvas;

import java.util.ArrayList;

/**
 * @hide
 * Represents a configuration in the descriptors tree.
 */
public final class UsbDescriptorsConfigNode extends UsbDescriptorsTreeNode {
    private static final String TAG = "UsbDescriptorsConfigNode";

    private final UsbConfigDescriptor mConfigDescriptor;

    private final ArrayList<UsbDescriptorsInterfaceNode> mInterfaceNodes = new ArrayList<>();

    /**
     * Constructor.
     * @param configDescriptor   The Config Descriptor object wrapped by this tree node.
     */
    public UsbDescriptorsConfigNode(UsbConfigDescriptor configDescriptor) {
        mConfigDescriptor = configDescriptor;
    }

    /**
     * Adds the inteface node logical contained in this configuration.
     * @param interfaceNode The inteface treenode to assocate with this configuration.
     */
    public void addInterfaceNode(UsbDescriptorsInterfaceNode interfaceNode) {
        mInterfaceNodes.add(interfaceNode);
    }

    @Override
    public void report(ReportCanvas canvas) {
        mConfigDescriptor.report(canvas);

        canvas.openList();

        // Interfaces
        for (UsbDescriptorsInterfaceNode node : mInterfaceNodes) {
            node.report(canvas);
        }

        canvas.closeList();
    }
}
