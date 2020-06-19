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

package com.android.server.location.util;

import static org.mockito.ArgumentMatchers.any;
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

        when(mAppOpsHelper.startLocationMonitoring(any(CallerIdentity.class))).thenReturn(true);
        when(mAppOpsHelper.startHighPowerLocationMonitoring(any(CallerIdentity.class))).thenReturn(
                true);

        mHelper = new LocationAttributionHelper(mAppOpsHelper);
    }

    @Test
    public void testLocationMonitoring() {
        CallerIdentity caller1 = CallerIdentity.forTest(1, 1, "test1", null);
        Object key1 = new Object();
        Object key2 = new Object();
        CallerIdentity caller2 = CallerIdentity.forTest(2, 2, "test2", null);
        Object key3 = new Object();
        Object key4 = new Object();

        mHelper.reportLocationStart(caller1, "gps", key1);
        verify(mAppOpsHelper).startLocationMonitoring(caller1);
        verify(mAppOpsHelper, never()).stopLocationMonitoring(caller1);

        mHelper.reportLocationStart(caller1, "gps", key2);
        verify(mAppOpsHelper).startLocationMonitoring(caller1);
        verify(mAppOpsHelper, never()).stopLocationMonitoring(caller1);

        mHelper.reportLocationStart(caller2, "gps", key3);
        verify(mAppOpsHelper).startLocationMonitoring(caller2);
        verify(mAppOpsHelper, never()).stopLocationMonitoring(caller2);

        mHelper.reportLocationStart(caller2, "gps", key4);
        verify(mAppOpsHelper).startLocationMonitoring(caller2);
        verify(mAppOpsHelper, never()).stopLocationMonitoring(caller2);

        mHelper.reportLocationStop(caller1, "gps", key2);
        verify(mAppOpsHelper, never()).stopLocationMonitoring(caller1);
        mHelper.reportLocationStop(caller1, "gps", key1);
        verify(mAppOpsHelper).stopLocationMonitoring(caller1);

        mHelper.reportLocationStop(caller2, "gps", key3);
        verify(mAppOpsHelper, never()).stopLocationMonitoring(caller2);
        mHelper.reportLocationStop(caller2, "gps", key4);
        verify(mAppOpsHelper).stopLocationMonitoring(caller2);
    }

    @Test
    public void testHighPowerLocationMonitoring() {
        CallerIdentity caller1 = CallerIdentity.forTest(1, 1, "test1", null);
        Object key1 = new Object();
        Object key2 = new Object();
        CallerIdentity caller2 = CallerIdentity.forTest(2, 2, "test2", null);
        Object key3 = new Object();
        Object key4 = new Object();

        mHelper.reportHighPowerLocationStart(caller1, "gps", key1);
        verify(mAppOpsHelper).startHighPowerLocationMonitoring(caller1);
        verify(mAppOpsHelper, never()).stopHighPowerLocationMonitoring(caller1);

        mHelper.reportHighPowerLocationStart(caller1, "gps", key2);
        verify(mAppOpsHelper).startHighPowerLocationMonitoring(caller1);
        verify(mAppOpsHelper, never()).stopHighPowerLocationMonitoring(caller1);

        mHelper.reportHighPowerLocationStart(caller2, "gps", key3);
        verify(mAppOpsHelper).startHighPowerLocationMonitoring(caller2);
        verify(mAppOpsHelper, never()).stopHighPowerLocationMonitoring(caller2);

        mHelper.reportHighPowerLocationStart(caller2, "gps", key4);
        verify(mAppOpsHelper).startHighPowerLocationMonitoring(caller2);
        verify(mAppOpsHelper, never()).stopHighPowerLocationMonitoring(caller2);

        mHelper.reportHighPowerLocationStop(caller1, "gps", key2);
        verify(mAppOpsHelper, never()).stopHighPowerLocationMonitoring(caller1);
        mHelper.reportHighPowerLocationStop(caller1, "gps", key1);
        verify(mAppOpsHelper).stopHighPowerLocationMonitoring(caller1);

        mHelper.reportHighPowerLocationStop(caller2, "gps", key3);
        verify(mAppOpsHelper, never()).stopHighPowerLocationMonitoring(caller2);
        mHelper.reportHighPowerLocationStop(caller2, "gps", key4);
        verify(mAppOpsHelper).stopHighPowerLocationMonitoring(caller2);
    }
}
