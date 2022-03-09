/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.injector;

import static android.app.AppOpsManager.OP_MONITOR_HIGH_POWER_LOCATION;
import static android.app.AppOpsManager.OP_MONITOR_LOCATION;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.location.util.identity.CallerIdentity;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class LocationAttributionHelperTest {

    @Mock private AppOpsHelper mAppOpsHelper;

    private LocationAttributionHelper mHelper;

    @Before
    public void setUp() {
        initMocks(this);

        when(mAppOpsHelper.startOpNoThrow(anyInt(), any(CallerIdentity.class))).thenReturn(true);

        mHelper = new LocationAttributionHelper(mAppOpsHelper);
    }

    @Test
    public void testLocationMonitoring() {
        CallerIdentity caller1 = CallerIdentity.forTest(1, 1, "test1", null);
        CallerIdentity caller2 = CallerIdentity.forTest(2, 2, "test2", null);

        mHelper.reportLocationStart(caller1);
        verify(mAppOpsHelper).startOpNoThrow(OP_MONITOR_LOCATION,
                CallerIdentity.forAggregation(caller1));
        verify(mAppOpsHelper, never()).finishOp(OP_MONITOR_LOCATION,
                CallerIdentity.forAggregation(caller1));

        mHelper.reportLocationStart(caller1);
        verify(mAppOpsHelper).startOpNoThrow(OP_MONITOR_LOCATION,
                CallerIdentity.forAggregation(caller1));
        verify(mAppOpsHelper, never()).finishOp(OP_MONITOR_LOCATION,
                CallerIdentity.forAggregation(caller1));

        mHelper.reportLocationStart(caller2);
        verify(mAppOpsHelper).startOpNoThrow(OP_MONITOR_LOCATION,
                CallerIdentity.forAggregation(caller2));
        verify(mAppOpsHelper, never()).finishOp(OP_MONITOR_LOCATION,
                CallerIdentity.forAggregation(caller2));

        mHelper.reportLocationStart(caller2);
        verify(mAppOpsHelper).startOpNoThrow(OP_MONITOR_LOCATION,
                CallerIdentity.forAggregation(caller2));
        verify(mAppOpsHelper, never()).finishOp(OP_MONITOR_LOCATION,
                CallerIdentity.forAggregation(caller2));

        mHelper.reportLocationStop(caller1);
        verify(mAppOpsHelper, never()).finishOp(OP_MONITOR_LOCATION,
                CallerIdentity.forAggregation(caller1));
        mHelper.reportLocationStop(caller1);
        verify(mAppOpsHelper).finishOp(OP_MONITOR_LOCATION, CallerIdentity.forAggregation(caller1));

        mHelper.reportLocationStop(caller2);
        verify(mAppOpsHelper, never()).finishOp(OP_MONITOR_LOCATION,
                CallerIdentity.forAggregation(caller2));
        mHelper.reportLocationStop(caller2);
        verify(mAppOpsHelper).finishOp(OP_MONITOR_LOCATION, CallerIdentity.forAggregation(caller2));
    }

    @Test
    public void testHighPowerLocationMonitoring() {
        CallerIdentity caller1 = CallerIdentity.forTest(1, 1, "test1", null);
        CallerIdentity caller2 = CallerIdentity.forTest(2, 2, "test2", null);

        mHelper.reportHighPowerLocationStart(caller1);
        verify(mAppOpsHelper).startOpNoThrow(OP_MONITOR_HIGH_POWER_LOCATION,
                CallerIdentity.forAggregation(caller1));
        verify(mAppOpsHelper, never()).finishOp(OP_MONITOR_HIGH_POWER_LOCATION,
                CallerIdentity.forAggregation(caller1));

        mHelper.reportHighPowerLocationStart(caller1);
        verify(mAppOpsHelper).startOpNoThrow(OP_MONITOR_HIGH_POWER_LOCATION,
                CallerIdentity.forAggregation(caller1));
        verify(mAppOpsHelper, never()).finishOp(OP_MONITOR_HIGH_POWER_LOCATION,
                CallerIdentity.forAggregation(caller1));

        mHelper.reportHighPowerLocationStart(caller2);
        verify(mAppOpsHelper).startOpNoThrow(OP_MONITOR_HIGH_POWER_LOCATION,
                CallerIdentity.forAggregation(caller2));
        verify(mAppOpsHelper, never()).finishOp(OP_MONITOR_HIGH_POWER_LOCATION,
                CallerIdentity.forAggregation(caller2));

        mHelper.reportHighPowerLocationStart(caller2);
        verify(mAppOpsHelper).startOpNoThrow(OP_MONITOR_HIGH_POWER_LOCATION,
                CallerIdentity.forAggregation(caller2));
        verify(mAppOpsHelper, never()).finishOp(OP_MONITOR_HIGH_POWER_LOCATION,
                CallerIdentity.forAggregation(caller2));

        mHelper.reportHighPowerLocationStop(caller1);
        verify(mAppOpsHelper, never()).finishOp(OP_MONITOR_HIGH_POWER_LOCATION,
                CallerIdentity.forAggregation(caller1));
        mHelper.reportHighPowerLocationStop(caller1);
        verify(mAppOpsHelper).finishOp(OP_MONITOR_HIGH_POWER_LOCATION,
                CallerIdentity.forAggregation(caller1));

        mHelper.reportHighPowerLocationStop(caller2);
        verify(mAppOpsHelper, never()).finishOp(OP_MONITOR_HIGH_POWER_LOCATION,
                CallerIdentity.forAggregation(caller2));
        mHelper.reportHighPowerLocationStop(caller2);
        verify(mAppOpsHelper).finishOp(OP_MONITOR_HIGH_POWER_LOCATION,
                CallerIdentity.forAggregation(caller2));
    }
}
