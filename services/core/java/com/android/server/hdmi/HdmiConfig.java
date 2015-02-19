/*
 * Copyright (C) 2014 The Android Open Source Project
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

/**
 * Class that holds together the constants that may require per-product configuration.
 */
final class HdmiConfig {

    // Default timeout for the incoming command to arrive in response to a request.
    static final int TIMEOUT_MS = 2000;

    // IRT(Initiator Repetition Time) in millisecond as recommended in the standard.
    // Outgoing UCP commands, when in 'Press and Hold' mode, should be this much apart
    // from the adjacent one so as not to place unnecessarily heavy load on the CEC line.
    static final int IRT_MS = 300;

    // Number of retries for polling each device in device discovery phase after TV powers on
    // or HDMI control is enabled.
    static final int DEVICE_POLLING_RETRY = 1;

    // Number of retries for polling each device in periodic check (hotplug detection).
    static final int HOTPLUG_DETECTION_RETRY = 1;

    // Number of retries for polling each device in address allocation mechanism.
    static final int ADDRESS_ALLOCATION_RETRY = 3;

    // Number of retries for sendCommand in actions related to new device discovery.
    // Number 5 comes from 10 seconds for Chromecast preparation time.
    static final int TIMEOUT_RETRY = 5;

    // CEC spec said that it should try retransmission at least once.
    // The actual number of send request for a single command will be at most
    // RETRANSMISSION_COUNT + 1. Note that it affects only to normal commands
    // and polling message for logical address allocation and device discovery
    // action. They will have their own retransmission count.
    static final int RETRANSMISSION_COUNT = 1;

    // Do not export the CEC devices connected to a legacy HDMI switch. The usage of legacy
    // (non-CEC) switches is deprecated. They stop the correct operation of many mandatory
    // CEC features. If set to true, do not pass the list of CEC devices behind the legacy
    // switch since they won't be under control from TV.
    static final boolean HIDE_DEVICES_BEHIND_LEGACY_SWITCH = true;

    private HdmiConfig() { /* cannot be instantiated */ }
}
