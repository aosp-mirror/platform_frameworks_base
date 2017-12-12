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

import com.android.server.usb.descriptors.UsbEndpointDescriptor;
import com.android.server.usb.descriptors.report.ReportCanvas;

/**
 * @hide
 * Represents an endpoint in the descriptors tree.
 */
public final class UsbDescriptorsEndpointNode extends UsbDescriptorsTreeNode {
    private static final String TAG = "UsbDescriptorsEndpointNode";

    private final UsbEndpointDescriptor mEndpointDescriptor;

    /**
     * Constructor.
     * @param endpointDescriptor   The Device Descriptor object wrapped by this tree node.
     */
    public UsbDescriptorsEndpointNode(UsbEndpointDescriptor endpointDescriptor) {
        mEndpointDescriptor = endpointDescriptor;
    }

    @Override
    public void report(ReportCanvas canvas) {
        mEndpointDescriptor.report(canvas);
    }
}
