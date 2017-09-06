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
import com.android.server.usb.descriptors.report.ReportCanvas;

/**
 * @hide
 * A tree node containing some sort-of Audio Class Descriptor.
 */
public final class UsbDescriptorsACInterfaceNode extends UsbDescriptorsTreeNode {
    private static final String TAG = "UsbDescriptorsACInterfaceNode";

    private final UsbACInterface mACInterface;

    /**
     * Constructor.
     * @param acInterface   The Audio Class Inteface object wrapped by this tree node.
     */
    public UsbDescriptorsACInterfaceNode(UsbACInterface acInterface) {
        mACInterface = acInterface;
    }

    @Override
    public void report(ReportCanvas canvas) {
        canvas.writeListItem("AC Interface type: 0x"
                + Integer.toHexString(mACInterface.getSubtype()));
        canvas.openList();
        mACInterface.report(canvas);
        canvas.closeList();
    }
}
