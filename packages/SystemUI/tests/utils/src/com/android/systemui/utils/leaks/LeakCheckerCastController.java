/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.utils.leaks;

import android.media.projection.StopReason;
import android.testing.LeakCheck;

import com.android.systemui.statusbar.policy.CastController;
import com.android.systemui.statusbar.policy.CastController.Callback;
import com.android.systemui.statusbar.policy.CastDevice;

import java.util.ArrayList;
import java.util.List;

public class LeakCheckerCastController extends BaseLeakChecker<Callback> implements CastController {
    public LeakCheckerCastController(LeakCheck test) {
        super(test, "cast");
    }

    @Override
    public void setDiscovering(boolean request) {

    }

    @Override
    public void setCurrentUserId(int currentUserId) {

    }

    @Override
    public List<CastDevice> getCastDevices() {
        return new ArrayList<>();
    }

    @Override
    public void startCasting(CastDevice device) {

    }

    @Override
    public void stopCasting(CastDevice device, @StopReason int stopReason) {

    }

    @Override
    public boolean hasConnectedCastDevice() {
        return false;
    }
}
