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

import com.android.server.usb.descriptors.UsbDeviceDescriptor;
import com.android.server.usb.descriptors.report.ReportCanvas;

import java.util.ArrayList;

/**
 * @hide
 * A class to contain THE device descriptor at the root of the tree.
 */
public final class UsbDescriptorsDeviceNode extends UsbDescriptorsTreeNode {
    private static final String TAG = "UsbDescriptorsDeviceNode";

    private final UsbDeviceDescriptor mDeviceDescriptor;

    private final ArrayList<UsbDescriptorsConfigNode> mConfigNodes = new ArrayList<>();

    /**
     * Constructor.
     * @param deviceDescriptor   The Device Descriptor object wrapped by this tree node.
     */
    public UsbDescriptorsDeviceNode(UsbDeviceDescriptor deviceDescriptor) {
        mDeviceDescriptor = deviceDescriptor;
    }

    /**
     * Adds a Configuration node to the assocated device node.
     */
    public void addConfigDescriptorNode(UsbDescriptorsConfigNode configNode) {
        mConfigNodes.add(configNode);
    }

    @Override
    public void report(ReportCanvas canvas) {
        mDeviceDescriptor.report(canvas);
        for (UsbDescriptorsConfigNode node : mConfigNodes) {
            node.report(canvas);
        }
    }
}
