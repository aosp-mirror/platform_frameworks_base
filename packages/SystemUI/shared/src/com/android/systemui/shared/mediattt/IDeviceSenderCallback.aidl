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

package com.android.systemui.shared.mediattt;

import android.media.MediaRoute2Info;
import com.android.systemui.shared.mediattt.DeviceInfo;

/**
 * A callback interface that can be invoked to trigger media transfer events on System UI.
 *
 * This interface is for the *sender* device, which is the device currently playing media. This
 * sender device can transfer the media to a different device, called the receiver.
 *
 * System UI will implement this interface and other services will invoke it.
 */
interface IDeviceSenderCallback {
    /**
     * Invoke to notify System UI that this device (the sender) is close to a receiver device, so
     * the user can potentially *start* a cast to the receiver device if the user moves their device
     * a bit closer.
     *
     * Important notes:
     *   - When this callback triggers, the device is close enough to inform the user that
     *     transferring is an option, but the device is *not* close enough to actually initiate a
     *     transfer yet.
     *   - This callback is for *starting* a cast. It should be used when this device is currently
     *     playing media locally and the media should be transferred to be played on the receiver
     *     device instead.
     */
    oneway void closeToReceiverToStartCast(
        in MediaRoute2Info mediaInfo, in DeviceInfo otherDeviceInfo);
}
