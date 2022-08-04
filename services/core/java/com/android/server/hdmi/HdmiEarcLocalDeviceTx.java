/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.hdmi;

import android.util.IndentingPrintWriter;

/**
 * Represents a local eARC device of type TX residing in the Android system.
 * Only TV panel devices can have a local eARC TX device.
 */
public class HdmiEarcLocalDeviceTx extends HdmiEarcLocalDevice {
    private static final String TAG = "HdmiEarcLocalDeviceTx";

    /** Dump internal status of HdmiEarcLocalDeviceTx object */
    protected void dump(final IndentingPrintWriter pw) {
        pw.println("TX");
    }
}
