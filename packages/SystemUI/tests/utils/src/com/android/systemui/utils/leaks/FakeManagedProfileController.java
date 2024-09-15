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

import com.android.systemui.statusbar.phone.ManagedProfileController;
import com.android.systemui.statusbar.phone.ManagedProfileController.Callback;

import java.util.ArrayList;
import java.util.List;

public class FakeManagedProfileController extends BaseLeakChecker<Callback> implements
        ManagedProfileController {

    private List<Callback> mCallbackList = new ArrayList<>();
    private boolean mIsEnabled = false;
    private boolean mHasActiveProfile = false;

    public FakeManagedProfileController(LeakCheck test) {
        super(test, "profile");
    }

    @Override
    public void addCallback(Callback cb) {
        mCallbackList.add(cb);
        cb.onManagedProfileChanged();
    }

    @Override
    public void removeCallback(Callback cb) {
        mCallbackList.remove(cb);
    }

    @Override
    public void setWorkModeEnabled(boolean enabled) {
        if (mIsEnabled != enabled) {
            mIsEnabled = enabled;
            for (Callback cb: mCallbackList) {
                cb.onManagedProfileChanged();
            }
        }

    }

    @Override
    public boolean hasActiveProfile() {
        return mHasActiveProfile;
    }

    /**
     * Triggers onManagedProfileChanged on callbacks when value flips.
     */
    public void setHasActiveProfile(boolean hasActiveProfile) {
        if (mHasActiveProfile != hasActiveProfile) {
            mHasActiveProfile = hasActiveProfile;
            for (Callback cb: mCallbackList) {
                cb.onManagedProfileChanged();
                if (!hasActiveProfile) {
                    cb.onManagedProfileRemoved();
                }
            }
        }

    }

    @Override
    public boolean isWorkModeEnabled() {
        return mIsEnabled;
    }
}
