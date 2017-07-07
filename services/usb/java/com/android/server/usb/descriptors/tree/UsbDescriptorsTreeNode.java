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

import com.android.server.usb.descriptors.report.ReportCanvas;
import com.android.server.usb.descriptors.report.Reporting;

/**
 * @hide
 * A shared super class for UsbDescriptor tree nodes.
 */
public class UsbDescriptorsTreeNode implements Reporting {
    private static final String TAG = "UsbDescriptorsTreeNode";

    /**
     * Implements generate a comprehehensive report of descriptor.
     */
    @Override
    public void report(ReportCanvas canvas) {
    }

    /**
     * Implements generate an abreviated report of descriptor.
     */
    @Override
    public void shortReport(ReportCanvas canvas) {
    }
}
