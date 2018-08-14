/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.utils.leaks;

import android.content.ComponentName;
import android.net.Uri;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.ZenRule;
import android.testing.LeakCheck;

import com.android.systemui.statusbar.policy.ZenModeController;
import com.android.systemui.statusbar.policy.ZenModeController.Callback;

public class FakeZenModeController extends BaseLeakChecker<Callback> implements ZenModeController {
    public FakeZenModeController(LeakCheck test) {
        super(test, "zen");
    }

    @Override
    public void setZen(int zen, Uri conditionId, String reason) {

    }

    @Override
    public int getZen() {
        return 0;
    }

    @Override
    public ZenRule getManualRule() {
        return null;
    }

    @Override
    public ZenModeConfig getConfig() {
        return null;
    }

    @Override
    public long getNextAlarm() {
        return 0;
    }

    @Override
    public boolean isZenAvailable() {
        return false;
    }

    @Override
    public ComponentName getEffectsSuppressor() {
        return null;
    }

    @Override
    public boolean isCountdownConditionSupported() {
        return false;
    }

    @Override
    public int getCurrentUser() {
        return 0;
    }

    @Override
    public boolean isVolumeRestricted() {
        return false;
    }

    @Override
    public boolean areNotificationsHiddenInShade() {
        return false;
    }
}
