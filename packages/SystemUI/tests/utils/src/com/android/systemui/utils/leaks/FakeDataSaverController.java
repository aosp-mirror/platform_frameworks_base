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

import com.android.systemui.statusbar.policy.DataSaverController;
import com.android.systemui.statusbar.policy.DataSaverController.Listener;

import java.util.ArrayList;
import java.util.List;

public class FakeDataSaverController extends BaseLeakChecker<Listener> implements DataSaverController {

    private boolean mIsEnabled = false;
    private List<Listener> mListeners = new ArrayList<>();

    public FakeDataSaverController(LeakCheck test) {
        super(test, "datasaver");
    }

    @Override
    public boolean isDataSaverEnabled() {
        return mIsEnabled;
    }

    @Override
    public void setDataSaverEnabled(boolean enabled) {
        mIsEnabled = enabled;
        for (Listener listener: mListeners) {
            listener.onDataSaverChanged(enabled);
        }
    }

    @Override
    public void addCallback(Listener listener) {
        mListeners.add(listener);
    }

    @Override
    public void removeCallback(Listener listener) {
        mListeners.remove(listener);
    }
}
