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

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;

import android.os.BatteryStats;
import android.os.Parcel;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import junit.framework.Assert;
import junit.framework.TestCase;

import com.android.internal.os.BatteryStatsImpl;

import org.mockito.Mockito;

/**
 * Mocks a BatteryStatsImpl object.
 */
public class MockBatteryStatsImpl extends BatteryStatsImpl {
    public BatteryStatsImpl.Clocks clocks;

    MockBatteryStatsImpl() {
        super(new MockClocks());
        this.clocks = mClocks;
    }

    public TimeBase getOnBatteryTimeBase() {
        return mOnBatteryTimeBase;
    }

}

