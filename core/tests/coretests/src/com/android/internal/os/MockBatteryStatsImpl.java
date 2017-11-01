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

package com.android.internal.os;

/**
 * Mocks a BatteryStatsImpl object.
 */
public class MockBatteryStatsImpl extends BatteryStatsImpl {
    public BatteryStatsImpl.Clocks clocks;
    public boolean mForceOnBattery;

    MockBatteryStatsImpl(Clocks clocks) {
        super(clocks);
        this.clocks = mClocks;
        mBluetoothScanTimer = new StopwatchTimer(mClocks, null, -14, null, mOnBatteryTimeBase);
    }

    MockBatteryStatsImpl() {
        this(new MockClocks());
    }

    public TimeBase getOnBatteryTimeBase() {
        return mOnBatteryTimeBase;
    }

    public boolean isOnBattery() {
        return mForceOnBattery ? true : super.isOnBattery();
    }

    public TimeBase getOnBatteryBackgroundTimeBase(int uid) {
        return getUidStatsLocked(uid).mOnBatteryBackgroundTimeBase;
    }

    public TimeBase getOnBatteryScreenOffBackgroundTimeBase(int uid) {
        return getUidStatsLocked(uid).mOnBatteryScreenOffBackgroundTimeBase;
    }
}

