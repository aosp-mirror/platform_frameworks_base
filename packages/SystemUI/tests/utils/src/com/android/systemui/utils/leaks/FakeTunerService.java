/*
 * Copyright (C) 2017 The Android Open Source Project
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

import com.android.systemui.tuner.TunerService;

public class FakeTunerService extends TunerService {

    private final BaseLeakChecker<Tunable> mBaseLeakChecker;
    private boolean mEnabled;

    public FakeTunerService(LeakCheck test) {
        super(null);
        mBaseLeakChecker = new BaseLeakChecker<>(test, "tunable");
    }

    @Override
    public void addTunable(Tunable tunable, String... keys) {
        for (String key : keys) {
            tunable.onTuningChanged(key, null);
        }
        mBaseLeakChecker.addCallback(tunable);
    }

    @Override
    public void removeTunable(Tunable tunable) {
        mBaseLeakChecker.removeCallback(tunable);
    }

    @Override
    public void clearAll() {

    }

    @Override
    public void destroy() {

    }

    @Override
    public String getValue(String setting) {
        return null;
    }

    @Override
    public int getValue(String setting, int def) {
        return def;
    }

    @Override
    public String getValue(String setting, String def) {
        return def;
    }

    @Override
    public void setValue(String setting, String value) {

    }

    @Override
    public void setValue(String setting, int value) {

    }

    @Override
    public void setTunerEnabled(boolean enabled) {
        mEnabled = enabled;
    }

    @Override
    public boolean isTunerEnabled() {
        return mEnabled;
    }

    @Override
    public void showResetRequest(Runnable onDisabled) {}
}
