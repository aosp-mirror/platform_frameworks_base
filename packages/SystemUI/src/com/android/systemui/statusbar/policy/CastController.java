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

import com.android.systemui.Dumpable;
import com.android.systemui.statusbar.policy.CastController.Callback;

import java.util.Set;

public interface CastController extends CallbackController<Callback>, Dumpable {
    void setDiscovering(boolean request);
    void setCurrentUserId(int currentUserId);
    Set<CastDevice> getCastDevices();
    void startCasting(CastDevice device);
    void stopCasting(CastDevice device);

    public interface Callback {
        void onCastDevicesChanged();
    }

    public static final class CastDevice {
        public static final int STATE_DISCONNECTED = 0;
        public static final int STATE_CONNECTING = 1;
        public static final int STATE_CONNECTED = 2;

        public String id;
        public String name;
        public String description;
        public int state = STATE_DISCONNECTED;
        public Object tag;
    }
}
