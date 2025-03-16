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

package com.android.systemui.statusbar.policy;

import android.media.projection.StopReason;
import com.android.systemui.Dumpable;
import com.android.systemui.statusbar.policy.CastController.Callback;

import java.util.List;

public interface CastController extends CallbackController<Callback>, Dumpable {
    void setDiscovering(boolean request);
    void setCurrentUserId(int currentUserId);
    List<CastDevice> getCastDevices();
    void startCasting(CastDevice device);
    void stopCasting(CastDevice device, @StopReason int stopReason);

    /**
     * @return whether we have a connected device.
     */
    boolean hasConnectedCastDevice();

    public interface Callback {
        void onCastDevicesChanged();
    }

}
