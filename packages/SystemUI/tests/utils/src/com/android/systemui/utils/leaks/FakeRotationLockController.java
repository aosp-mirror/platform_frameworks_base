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

import android.testing.LeakCheck;

import com.android.systemui.statusbar.policy.RotationLockController;
import com.android.systemui.statusbar.policy.RotationLockController.RotationLockControllerCallback;

public class FakeRotationLockController extends BaseLeakChecker<RotationLockControllerCallback>
        implements RotationLockController {
    public FakeRotationLockController(LeakCheck test) {
        super(test, "rotation");
    }

    @Override
    public void setListening(boolean listening) {

    }

    @Override
    public int getRotationLockOrientation() {
        return 0;
    }

    @Override
    public boolean isRotationLockAffordanceVisible() {
        return false;
    }

    @Override
    public boolean isRotationLocked() {
        return false;
    }

    @Override
    public void setRotationLocked(boolean locked, String caller) {

    }

    @Override
    public boolean isCameraRotationEnabled() {
        return false;
    }

    @Override
    public void setRotationLockedAtAngle(boolean locked, int rotation, String caller) {

    }
}
